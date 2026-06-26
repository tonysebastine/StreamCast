package com.example.casting

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

enum class ProtocolType {
    CHROMECAST,
    ROKU,
    FIRE_TV,
    AIRPLAY,
    DLNA,
    MIRACAST
}

data class CastingDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val protocolType: ProtocolType,
    val location: String? = null
)

class DiscoveryEngine(private val context: Context) {
    private val TAG = "DiscoveryEngine"
    
    private val _devices = MutableStateFlow<List<CastingDevice>>(emptyList())
    val devices: StateFlow<List<CastingDevice>> = _devices.asStateFlow()

    private val activeDevicesMap = ConcurrentHashMap<String, CastingDevice>()

    private var nsdManager: NsdManager? = null
    private val activeListeners = ConcurrentHashMap<String, NsdManager.DiscoveryListener>()
    private val resolveMutex = Mutex()
    
    private var discoveryScope: CoroutineScope? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdpSocket: DatagramSocket? = null

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    @Synchronized
    fun startDiscovery() {
        if (discoveryScope != null) return // Already scanning
        discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Acquire Multicast Lock to receive SSDP response packets on Android
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("XCastDiscoveryLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Acquired multicast lock")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }

        activeDevicesMap.clear()
        _devices.value = emptyList()

        // 1. Start mDNS scan for multiple casting services
        val mDnsServicesToScan = listOf(
            "_googlecast._tcp",
            "_airplay._tcp",
            "_raop._tcp",
            "_roku-epc._tcp",
            "_upnp-mediarenderer._tcp",
            "_amzn-wplay._tcp",
            "_dial._tcp"
        )
        for (service in mDnsServicesToScan) {
            startMdnsDiscovery(service)
        }

        // 2. Start SSDP parallel network worker
        discoveryScope?.launch {
            while (isActive) {
                sendSsdpDiscoveryPackets()
                delay(8000) // Re-scan every 8 seconds
            }
        }
        
        // 3. Start listening for incoming SSDP UDP unicast responses
        discoveryScope?.launch {
            listenForSsdpResponses()
        }

        // 4. Start concurrent Subnet Scan for DIAL / ECP devices (ensures robust Gen1+ Fire TV Stick discovery)
        discoveryScope?.launch {
            scanSubnetForCastingDevices()
        }
    }

    @Synchronized
    fun stopDiscovery() {
        // Stop all active mDNS listeners
        for ((serviceType, listener) in activeListeners) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
                Log.d(TAG, "Stopped mDNS listener for $serviceType")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mDNS listener for $serviceType: ${e.message}")
            }
        }
        activeListeners.clear()
        
        // Stop background coroutines
        discoveryScope?.cancel()
        discoveryScope = null

        // Release SSDP socket
        try {
            ssdpSocket?.close()
            ssdpSocket = null
            Log.d(TAG, "Closed SSDP socket")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SSDP socket: ${e.message}")
        }

        // Release Multicast Lock
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null
            Log.d(TAG, "Released multicast lock")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing license lock: ${e.message}")
        }
    }

    private fun startMdnsDiscovery(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "mDNS Start Failed for $serviceType, Error Code: $errorCode")
                activeListeners.remove(serviceType ?: "")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "mDNS Stop Failed for $serviceType, Error Code: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "mDNS discovery stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                Log.d(TAG, "mDNS service found: ${serviceInfo.serviceName} of type ${serviceInfo.serviceType}")
                
                discoveryScope?.launch {
                    resolveMutex.withLock {
                        val resolved = resolveServiceSuspended(serviceInfo)
                        if (resolved != null) {
                            processResolvedMdnsService(resolved)
                        }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                Log.d(TAG, "mDNS Service Lost: ${serviceInfo.serviceName}")
                val match = activeDevicesMap.values.find { 
                    it.name.contains(serviceInfo.serviceName, ignoreCase = true) || 
                    it.id.contains(serviceInfo.serviceName, ignoreCase = true) 
                }
                if (match != null) {
                    activeDevicesMap.remove(match.id)
                    updateDevicesFlow()
                }
            }
        }

        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            activeListeners[serviceType] = listener
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NsdManager for $serviceType: ${e.message}")
        }
    }

    private suspend fun resolveServiceSuspended(serviceInfo: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "mDNS Resolve Failed for ${serviceInfo?.serviceName}, code: $errorCode")
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo?) {
                Log.d(TAG, "mDNS Resolve Success for ${resolvedServiceInfo?.serviceName}")
                if (continuation.isActive) continuation.resume(resolvedServiceInfo)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager?.resolveService(serviceInfo, Executor { it.run() }, listener)
            } else {
                nsdManager?.resolveService(serviceInfo, listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call resolveService: ${e.message}")
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun processResolvedMdnsService(resolved: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolved.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            resolved.host?.hostAddress
        } ?: return
        val port = resolved.port
        val rawName = resolved.serviceName
        val serviceType = resolved.serviceType ?: ""

        val protocolType = when {
            rawName.contains("anycast", ignoreCase = true) || rawName.contains("ezcast", ignoreCase = true) || serviceType.contains("anycast", ignoreCase = true) || rawName.contains("miracast", ignoreCase = true) -> ProtocolType.MIRACAST
            serviceType.contains("googlecast", ignoreCase = true) -> ProtocolType.CHROMECAST
            serviceType.contains("airplay", ignoreCase = true) || serviceType.contains("raop", ignoreCase = true) -> ProtocolType.AIRPLAY
            serviceType.contains("roku", ignoreCase = true) -> ProtocolType.ROKU
            serviceType.contains("upnp", ignoreCase = true) || serviceType.contains("dlna", ignoreCase = true) -> ProtocolType.DLNA
            serviceType.contains("amzn", ignoreCase = true) || serviceType.contains("dial", ignoreCase = true) -> ProtocolType.FIRE_TV
            else -> ProtocolType.CHROMECAST
        }

        val friendlyName = when (protocolType) {
            ProtocolType.MIRACAST -> {
                "Miracast DLNA Receiver ($host)"
            }
            ProtocolType.CHROMECAST -> {
                getTxtRecord(resolved, "fn") ?: rawName.substringBefore(".tcp").replace("_", " ").trim()
            }
            ProtocolType.AIRPLAY -> {
                rawName.replace("@", " ").replace("_airplay", "").replace("_tcp", "").replace("_", " ").trim()
            }
            ProtocolType.ROKU -> {
                "Roku Caster Receiver ($host)"
            }
            ProtocolType.FIRE_TV -> {
                "Amazon Fire TV ($host)"
            }
            ProtocolType.DLNA -> {
                rawName.replace("_", " ").trim()
            }
            else -> {
                rawName.replace("_", " ").trim()
            }
        }

        val deviceId = "${protocolType.name.lowercase()}_$host"
        val device = CastingDevice(
            id = deviceId,
            name = friendlyName,
            ipAddress = host,
            port = port,
            protocolType = protocolType
        )
        addOrUpdateDevice(device)
    }

    private fun getTxtRecord(serviceInfo: NsdServiceInfo, key: String): String? {
        return try {
            val attributes = serviceInfo.attributes
            val valueBytes = attributes[key]
            if (valueBytes != null) {
                String(valueBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendSsdpDiscoveryPackets() {
        val ssdpAddress = InetAddress.getByName("239.255.255.250")
        val ssdpPort = 1900

        // Create M-SEARCH for Roku
        val queryRoku = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: roku:ecp\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create M-SEARCH for Amazon Fire TV / DIAL
        val queryFireTV = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create secondary M-SEARCH for Fire TV direct protocols (amzn.thin.pl)
        val queryFireTVThin = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: amzn.thin.pl\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create M-SEARCH for DLNA MediaRenderer
        val queryDlna = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create secondary M-SEARCH for DLNA AVTransport
        val queryDlnaTransport = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create query for generic UPnP RootDevice
        val queryRootDevice = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: upnp:rootdevice\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create universal query for ssdp:all
        val queryAll = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        // Create query for UPnP basic device
        val queryBasic = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:basic:1\r\n" +
                "USER-AGENT: Android/XCast\r\n\r\n"

        val queries = listOf(queryRoku, queryFireTV, queryFireTVThin, queryDlna, queryDlnaTransport, queryRootDevice, queryAll, queryBasic)

        try {
            val socket = ssdpSocket
            if (socket != null && !socket.isClosed) {
                socket.broadcast = true
                for (query in queries) {
                    val bytes = query.toByteArray()
                    val packet = DatagramPacket(bytes, bytes.size, ssdpAddress, ssdpPort)
                    socket.send(packet)
                    Log.d(TAG, "Sent SSDP M-SEARCH broadcast via active listening socket...")
                }
            } else {
                Log.d(TAG, "Active SSDP socket is null or closed, broadcasting on temporary socket")
                DatagramSocket().use { tempSocket ->
                    tempSocket.broadcast = true
                    for (query in queries) {
                        val bytes = query.toByteArray()
                        val packet = DatagramPacket(bytes, bytes.size, ssdpAddress, ssdpPort)
                        tempSocket.send(packet)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting SSDP query: ${e.message}")
        }
    }

    private fun CoroutineScope.listenForSsdpResponses() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 0 // block wait
            }
            ssdpSocket = socket
            
            val buffer = ByteArray(2048)
            Log.d(TAG, "SSDP UDP Response Listener active on port ${socket.localPort}")

            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val responseStr = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress ?: continue
                    parseSsdpResponse(responseStr, senderIp)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.d(TAG, "SSDP socket exception: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP response receiver crashes: ${e.message}")
        } finally {
            socket?.close()
            if (ssdpSocket == socket) {
                ssdpSocket = null
            }
        }
    }

    private fun parseSsdpResponse(response: String, ip: String) {
        val lines = response.split("\r\n", "\n")
        var isRoku = false
        var isFireTV = false
        var isDlna = false
        var isMiracast = false
        var locationUrl: String? = null
        var friendlyName = "Unknown Lan Device"

        for (line in lines) {
            val upperLine = line.uppercase()
            if (upperLine.startsWith("LOCATION:")) {
                locationUrl = line.substringAfter("LOCATION:").trim()
            }
            if (upperLine.contains("ROKU:ECP")) {
                isRoku = true
            }
            if (upperLine.contains("DIAL") || upperLine.contains("AMZN:THIN") || upperLine.contains("FIRETV") || upperLine.contains("WPLAY")) {
                isFireTV = true
            }
            if (upperLine.contains("MEDIARENDERER") || upperLine.contains("AVTRANSPORT") || upperLine.contains("UPNP")) {
                isDlna = true
            }
            if (upperLine.contains("ANYCAST") || upperLine.contains("EZCAST") || upperLine.contains("MIRACAST")) {
                isMiracast = true
            }
        }

        // Standard heuristics for SSDP devices
        val protocol = when {
            isMiracast -> ProtocolType.MIRACAST
            isRoku -> ProtocolType.ROKU
            isFireTV -> ProtocolType.FIRE_TV
            isDlna -> ProtocolType.DLNA
            else -> {
                // Heuristic inspection of typical ports / paths
                if (locationUrl?.contains("anycast", ignoreCase = true) == true || locationUrl?.contains("ezcast", ignoreCase = true) == true || locationUrl?.contains("miracast", ignoreCase = true) == true) {
                    isMiracast = true
                    ProtocolType.MIRACAST
                } else if (locationUrl?.contains(":8060") == true) {
                    isRoku = true
                    ProtocolType.ROKU
                } else if (locationUrl?.contains(":8008") == true || locationUrl?.contains("dial") == true) {
                    isFireTV = true
                    ProtocolType.FIRE_TV
                } else if (locationUrl?.contains("description.xml") == true || locationUrl?.contains("xml") == true || locationUrl?.contains(":49152") == true) {
                    isDlna = true
                    ProtocolType.DLNA
                } else {
                    return // Unrecognized
                }
            }
        }

        var parsedPort: Int? = null
        locationUrl?.let { url ->
            try {
                val uri = java.net.URI(url)
                if (uri.port != -1) {
                    parsedPort = uri.port
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val devicePort = parsedPort ?: when (protocol) {
            ProtocolType.ROKU -> 8060
            ProtocolType.FIRE_TV -> 8008
            ProtocolType.CHROMECAST -> 8009
            ProtocolType.AIRPLAY -> 7000
            ProtocolType.DLNA -> 49152
            ProtocolType.MIRACAST -> 49152
        }

        friendlyName = when (protocol) {
            ProtocolType.MIRACAST -> "Miracast Screen Adapter ($ip)"
            ProtocolType.ROKU -> "Roku Caster Receiver ($ip)"
            ProtocolType.FIRE_TV -> "Amazon Fire TV ($ip)"
            ProtocolType.DLNA -> "DLNA Smart TV / Renderer ($ip)"
            else -> "LAN Cast Receiver ($ip)"
        }

        val deviceId = "${protocol.name.lowercase()}_$ip"
        val device = CastingDevice(
            id = deviceId,
            name = friendlyName,
            ipAddress = ip,
            port = devicePort,
            protocolType = protocol,
            location = locationUrl
        )

        addOrUpdateDevice(device)
    }

    private fun addOrUpdateDevice(device: CastingDevice) {
        activeDevicesMap[device.id] = device
        updateDevicesFlow()
    }

    private fun updateDevicesFlow() {
        _devices.value = activeDevicesMap.values.toList().sortedBy { it.name }
    }

    private fun getSubnetPrefix(): String? {
        val ip = getLocalIpAddress() ?: return null
        val lastDotIndex = ip.lastIndexOf('.')
        return if (lastDotIndex != -1) {
            ip.substring(0, lastDotIndex + 1)
        } else {
            null
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val ipAddress = connectionInfo.ipAddress
            if (ipAddress == 0) {
                // Fallback to network interfaces
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } else {
                @Suppress("DEPRECATION")
                android.text.format.Formatter.formatIpAddress(ipAddress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
            null
        }
    }

    private fun scanSubnetForCastingDevices() {
        val prefix = getSubnetPrefix() ?: return
        Log.d(TAG, "Starting subnet scan on prefix: $prefix")
        
        discoveryScope?.launch(Dispatchers.IO) {
            // We run 254 parallel coroutines to check ports 8008 (Fire TV / DIAL / Chromecast) and 8060 (Roku)
            val jobs = (1..254).map { hostId ->
                launch {
                    val ip = "$prefix$hostId"
                    if (ip == getLocalIpAddress()) return@launch // Skip self
                    
                    // 1. Check Port 8008
                    checkPortAndIdentifyDevice(ip, 8008)
                    
                    // 2. Check Port 8060
                    checkPortAndIdentifyDevice(ip, 8060)
                }
            }
            jobs.joinAll()
            Log.d(TAG, "Subnet scan completed")
        }
    }

    private suspend fun checkPortAndIdentifyDevice(ip: String, port: Int) {
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 600) // 600ms timeout
                Log.d(TAG, "Found active port $port on $ip")
                
                if (port == 8008) {
                    identifyDialDevice(ip)
                } else if (port == 8060) {
                    val deviceId = "roku_$ip"
                    val device = CastingDevice(
                        id = deviceId,
                        name = "Roku Caster Receiver ($ip)",
                        ipAddress = ip,
                        port = 8060,
                        protocolType = ProtocolType.ROKU
                    )
                    addOrUpdateDevice(device)
                }
            } catch (e: Exception) {
                // Port closed or host unreachable, ignore
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {}
            }
        }
    }

    private fun identifyDialDevice(ip: String) {
        try {
            val url = URL("http://$ip:8008/ssdp/device-desc.xml")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val stream = connection.inputStream
                val reader = BufferedReader(InputStreamReader(stream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                stream.close()
                
                val xml = response.toString()
                val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>", "").trim()
                val modelName = xml.substringAfter("<modelName>", "").substringBefore("</modelName>", "").trim()
                val manufacturer = xml.substringAfter("<manufacturer>", "").substringBefore("</manufacturer>", "").trim()
                
                val isAmazon = manufacturer.contains("Amazon", ignoreCase = true) || 
                               modelName.contains("Fire", ignoreCase = true) || 
                               xml.contains("Amazon", ignoreCase = true)
                
                val finalName = if (friendlyName.isNotEmpty()) {
                    if (isAmazon) "$friendlyName (Fire TV)" else "$friendlyName (DIAL Device)"
                } else {
                    if (isAmazon) "Amazon Fire TV ($ip)" else "Chromecast / DIAL Device ($ip)"
                }
                
                val protocol = if (isAmazon) ProtocolType.FIRE_TV else ProtocolType.CHROMECAST
                val deviceId = "${protocol.name.lowercase()}_$ip"
                
                val device = CastingDevice(
                    id = deviceId,
                    name = finalName,
                    ipAddress = ip,
                    port = 8008,
                    protocolType = protocol,
                    location = "http://$ip:8008/ssdp/device-desc.xml"
                )
                addOrUpdateDevice(device)
            } else {
                val deviceId = "fire_tv_$ip"
                val device = CastingDevice(
                    id = deviceId,
                    name = "Amazon Fire TV ($ip)",
                    ipAddress = ip,
                    port = 8008,
                    protocolType = ProtocolType.FIRE_TV
                )
                addOrUpdateDevice(device)
            }
            connection.disconnect()
        } catch (e: Exception) {
            val deviceId = "fire_tv_$ip"
            val device = CastingDevice(
                id = deviceId,
                name = "Amazon Fire TV ($ip)",
                ipAddress = ip,
                port = 8008,
                protocolType = ProtocolType.FIRE_TV
            )
            addOrUpdateDevice(device)
        }
    }
}
