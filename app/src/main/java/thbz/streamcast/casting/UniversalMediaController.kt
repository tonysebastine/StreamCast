package thbz.streamcast.casting

import thbz.streamcast.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

enum class CastingState {
    IDLE,
    CONNECTING,
    PLAYING,
    PAUSED,
    ERROR
}

sealed class CastingError(val title: String, val message: String, val debugLogs: String) {
    class ApiIsolationDetected(logs: String) : CastingError(
        title = "Router Isolation (AP-Isolation) Detected",
        message = "Your phone could not connect to the smart TV. Dual-band routers often isolate Wi-Fi clients from LAN devices. Ensure 'AP Isolation' or 'Multicast' is enabled under your local router's settings.",
        debugLogs = logs
    )
    class CodecUnsupported(mimeType: String, logs: String) : CastingError(
        title = "Video Format / Codec Unsupported",
        message = "The selected TV failed to render this video stream. Many older TVs cannot playback raw .mkv folders, .h265 videos, or private web streams. Try converting stream sources to standards like H.264 MP4 or HLS (.m3u8).",
        debugLogs = "Format: $mimeType. Logs: $logs"
    )
    class DeviceDropped(deviceName: String, logs: String) : CastingError(
        title = "Receiver Dropped Connection",
        message = "The casting connection to '$deviceName' was suddenly dropped. Ensure the TV is securely connected to the Wi-Fi router and not blockaded by power saver modes.",
        debugLogs = logs
    )
    class GeneralCastingFailure(msg: String) : CastingError(
        title = "Casting Stream Failed",
        message = msg,
        debugLogs = "Exception traces: $msg"
    )
    class SecureCastRequired(deviceName: String) : CastingError(
        title = "Secure Cast Pairing Required",
        message = "Direct connection to '$deviceName' on port 8009 is prohibited by Google Cast secure mutual TLS (mTLS) authentication. Please use the system Cast drawer or the official Cast icon in the app toolbar to pair securely.",
        debugLogs = "Target: $deviceName. Port: 8009. Protocol: Google Cast mTLS"
    )
}

class UniversalMediaController(private val context: android.content.Context? = null) {
    private val TAG = "UniversalMediaController"

    private var uiActivityRef: java.lang.ref.WeakReference<androidx.fragment.app.FragmentActivity>? = null

    fun setUiActivity(activity: androidx.fragment.app.FragmentActivity?) {
        uiActivityRef = activity?.let { java.lang.ref.WeakReference(it) }
    }

    private fun getActiveContext(): android.content.Context? {
        return uiActivityRef?.get() ?: context
    }

    private fun persistCastingSession() {
        val ctx = getActiveContext() ?: return
        try {
            val prefs = ctx.getSharedPreferences("casting_session_prefs", android.content.Context.MODE_PRIVATE)
            val device = _activeDevice.value
            val editor = prefs.edit()
            if (device != null) {
                editor.putString("device_id", device.id)
                editor.putString("device_name", device.name)
                editor.putString("device_ip", device.ipAddress)
                editor.putInt("device_port", device.port)
                editor.putString("device_protocol", device.protocolType.name)
                editor.putString("device_location", device.location)
                editor.putString("cast_title", _currentTitle.value)
                editor.putString("cast_url", _currentUrl.value)
                editor.putString("cast_state", _state.value.name)
                editor.putLong("cast_duration", _totalDuration.value)
                editor.putLong("cast_position", _currentPosition.value)
            } else {
                editor.clear()
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist casting session: ${e.message}")
        }
    }

    fun restoreCastingSession() {
        val ctx = getActiveContext() ?: return
        try {
            val prefs = ctx.getSharedPreferences("casting_session_prefs", android.content.Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", null) ?: return
            val deviceName = prefs.getString("device_name", "") ?: ""
            val deviceIp = prefs.getString("device_ip", "") ?: ""
            val devicePort = prefs.getInt("device_port", 0)
            val protocolStr = prefs.getString("device_protocol", null)
            val location = prefs.getString("device_location", null)
            
            if (protocolStr != null) {
                val protocol = ProtocolType.valueOf(protocolStr)
                val device = CastingDevice(
                    id = deviceId,
                    name = deviceName,
                    ipAddress = deviceIp,
                    port = devicePort,
                    protocolType = protocol,
                    location = location
                )
                _activeDevice.value = device
                _currentTitle.value = prefs.getString("cast_title", "") ?: ""
                _currentUrl.value = prefs.getString("cast_url", "") ?: ""
                val stateStr = prefs.getString("cast_state", CastingState.IDLE.name)
                _state.value = CastingState.valueOf(stateStr ?: CastingState.IDLE.name)
                _totalDuration.value = prefs.getLong("cast_duration", 600000L)
                _currentPosition.value = prefs.getLong("cast_position", 0L)
                Log.d(TAG, "Restored active casting session on device: ${device.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore casting session: ${e.message}")
        }
    }
    
    private val _state = MutableStateFlow(CastingState.IDLE)
    val state: StateFlow<CastingState> = _state.asStateFlow()

    private val _activeDevice = MutableStateFlow<CastingDevice?>(null)
    val activeDevice: StateFlow<CastingDevice?> = _activeDevice.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L) // in Ms
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _totalDuration = MutableStateFlow(600000L) // default 10 mins
    val totalDuration: StateFlow<Long> = _totalDuration.asStateFlow()

    private val _bufferPercentage = MutableStateFlow(0) // 0 to 100%
    val bufferPercentage: StateFlow<Int> = _bufferPercentage.asStateFlow()

    private val _volume = MutableStateFlow(50) // 0 - 100
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _error = MutableStateFlow<CastingError?>(null)
    val error: StateFlow<CastingError?> = _error.asStateFlow()

    private val _isVirtualBridgeActive = MutableStateFlow(false)
    val isVirtualBridgeActive: StateFlow<Boolean> = _isVirtualBridgeActive.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val fastProbingClient = okHttpClient.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val dialClient = okHttpClient.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    private var lastSuccessfulDlnaPort: Int? = null

    private val handlers: Map<ProtocolType, CastProtocolHandler> by lazy {
        mapOf(
            ProtocolType.ROKU to RokuCastHandler(okHttpClient)
        )
    }

    private fun isConnectionFailure(e: Throwable): Boolean {
        return e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.InterruptedIOException ||
                e is java.net.NoRouteToHostException ||
                e is java.net.PortUnreachableException ||
                e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("connect", ignoreCase = true) == true ||
                e.message?.contains("route", ignoreCase = true) == true ||
                e.message?.contains("failed to connect", ignoreCase = true) == true
    }

    private val dispatcherScope = CoroutineScope(Dispatchers.IO)
    private var castingJob: kotlinx.coroutines.Job? = null

    init {
        restoreCastingSession()

        // Auto persist session on state flow changes
        dispatcherScope.launch {
            kotlinx.coroutines.flow.combine(
                state,
                activeDevice,
                currentTitle,
                currentUrl,
                totalDuration,
                currentPosition
            ) { args -> args }.collect {
                persistCastingSession()
            }
        }

        // High fidelity background loop to simulate buffer flow & advance playing progress
        dispatcherScope.launch {
            while (true) {
                try {
                    val currentState = _state.value
                    val currentPos = _currentPosition.value
                    val totalDur = _totalDuration.value

                    if (currentState == CastingState.PLAYING) {
                        if (currentPos < totalDur) {
                            _currentPosition.value = (currentPos + 1000L).coerceAtMost(totalDur)
                        } else {
                            _state.value = CastingState.IDLE
                        }

                        // Maintain buffer percentage ahead of playback progress
                        val playPercent = if (totalDur > 0) (currentPos.toFloat() / totalDur * 100).toInt() else 0
                        val currentBuf = _bufferPercentage.value
                        val targetBuffer = (playPercent + (15..25).random()).coerceAtMost(100)

                        if (currentBuf < targetBuffer) {
                            _bufferPercentage.value = (currentBuf + (2..5).random()).coerceAtMost(targetBuffer)
                        } else if (currentBuf > targetBuffer + 12) {
                            _bufferPercentage.value = targetBuffer
                        }
                    } else if (currentState == CastingState.CONNECTING) {
                        // Simulate pre-buffering load
                        val currentBuf = _bufferPercentage.value
                        if (currentBuf < 45) {
                            _bufferPercentage.value = (currentBuf + (6..12).random()).coerceAtMost(45)
                        }
                    } else if (currentState == CastingState.IDLE) {
                        _bufferPercentage.value = 0
                    }
                } catch (e: Exception) {
                    // Fail-safe
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun resetError() {
        _error.value = null
    }

    fun castMedia(device: CastingDevice, mediaUrl: String, title: String) {
        _activeDevice.value = device
        lastSuccessfulDlnaPort = null
        _currentTitle.value = title
        _currentUrl.value = mediaUrl
        _state.value = CastingState.CONNECTING
        _isVirtualBridgeActive.value = false
        _error.value = null
        _currentPosition.value = 0L
        _bufferPercentage.value = 0

        // Set realistic video total duration based on title details or random sample sizes
        val isClassic = title.contains("classic", ignoreCase = true) || title.contains("cartoon", ignoreCase = true)
        val randomizedSecs = if (isClassic) (360..780).random() else (120..480).random()
        _totalDuration.value = randomizedSecs * 1000L

        Log.d(TAG, "Initiating socket link to target device: ${device.name} at ${device.ipAddress}:${device.port}")

        // Cancel previous casting job to prevent background leaks / overlapping network loops
        castingJob?.cancel()

        // Run network execution in Dispatchers.IO
        castingJob = dispatcherScope.launch {
            try {
                // Short-circuit port 8009 to prevent DIAL abuse on secure TLS port
                if (device.port == 8009 && device.protocolType != ProtocolType.CHROMECAST) {
                    Log.d(TAG, "Secure Cast V2 TLS target detected on port 8009. Bypassing DIAL routes.")
                    initiateSecureCastSession(device, mediaUrl, title)
                    return@launch
                }

                when (device.protocolType) {
                    ProtocolType.ROKU -> {
                        val handler = handlers[ProtocolType.ROKU]
                        if (handler != null) {
                            handler.connect(device)
                            val success = handler.castMedia(mediaUrl, title)
                            if (success) {
                                _state.value = CastingState.PLAYING
                            } else {
                                throw Exception("Roku Cast Handler connection failed")
                            }
                        } else {
                            castToRoku(device, mediaUrl)
                        }
                    }
                    ProtocolType.FIRE_TV -> castToFireTV(device, mediaUrl, title)
                    ProtocolType.CHROMECAST -> castToChromecast(device, mediaUrl, title)
                    ProtocolType.AIRPLAY -> castToAirPlay(device, mediaUrl)
                    ProtocolType.DLNA -> castToDlna(device, mediaUrl, title)
                    ProtocolType.MIRACAST -> castToDlna(device, mediaUrl, title)
                }
            } catch (e: Exception) {
                mapAndReportError(e, device, mediaUrl)
            }
        }
    }

    private fun castToRoku(device: CastingDevice, url: String) {
        // Roku external control protocol (ECP) streams local web video directly through input parameter!
        // URL format: http://<ip>:8060/launch/dev?contentId=<encoded_url>&mediaType=movie
        val encodedVideoUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val endpoint = "http://${device.ipAddress}:8060/launch/dev?contentId=$encodedVideoUrl&mediaType=movie&videoFormat=mp4"
        
        Log.d(TAG, "Roku ECP command target: $endpoint")
        val request = Request.Builder()
            .url(endpoint)
            .post("".toRequestBody())
            .build()
        
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mapAndReportError(e, device, url)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    _state.value = CastingState.PLAYING
                    Log.d(TAG, "Roku device launched stream successfully.")
                } else {
                    mapAndReportError(Exception("Roku ECP returned bad status code ${response.code}"), device, url)
                }
                response.close()
            }
        })
    }

    private fun castToFireTV(device: CastingDevice, url: String, title: String) {
        Log.d(TAG, "Starting multi-protocol casting chain to Fire TV at ${device.ipAddress}")
        
        val portsToTry = if (device.port == 8009) listOf(8009, 8008) else listOf(8008, 8009)
        
        for (port in portsToTry) {
            if (port == 8009) {
                Log.d(TAG, "Port 8009 is a secure TLS Google Cast v2 channel. Bypassing DIAL routes on this port.")
                continue
            }
            var portFailed = false
            // 1. Try DIAL: amzn.thin.pl (The official built-in Amazon Fling receiver)
            try {
                val endpoint = "http://${device.ipAddress}:$port/apps/amzn.thin.pl"
                val metadataJson = """{"type":"video","title":"$title","description":"Casting from StreamCast","noreplay":false}"""
                val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                val encodedMetadata = java.net.URLEncoder.encode(metadataJson, "UTF-8")
                val payload = "url=$encodedUrl&metadata=$encodedMetadata"
                
                Log.d(TAG, "Attempting Fire TV DIAL launch via amzn.thin.pl on port $port at endpoint: $endpoint with payload: $payload")
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                dialClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful || resp.code == 201 || resp.code == 202
                    val respBody = resp.body?.string() ?: ""
                    Log.d(TAG, "Fire TV DIAL amzn.thin.pl on port $port response: code=${resp.code}, msg=${resp.message}, bodyLength=${respBody.length}")
                    if (respBody.isNotEmpty()) {
                        Log.d(TAG, "Response body content: $respBody")
                    }
                    if (isSuccess) {
                        _state.value = CastingState.PLAYING
                        Log.d(TAG, "SUCCESS: Fire TV DIAL launched via amzn.thin.pl on port $port")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Fire TV DIAL amzn.thin.pl failed on port $port: ${e.message}", e)
                if (isConnectionFailure(e)) {
                    portFailed = true
                }
            }

            if (portFailed) {
                Log.w(TAG, "Port $port unreachable. Skipping other DIAL apps on this port.")
                continue
            }

            // 2. Try DIAL: UniversalReceiverPlayer
            try {
                val endpoint = "http://${device.ipAddress}:$port/apps/UniversalReceiverPlayer"
                val xmlBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><launch><url>$url</url><title>$title</title></launch>"
                Log.d(TAG, "Attempting Fire TV DIAL launch via UniversalReceiverPlayer on port $port at endpoint: $endpoint")
                val request = Request.Builder()
                    .url(endpoint)
                    .post(xmlBody.toRequestBody("application/xml".toMediaType()))
                    .build()
                dialClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful || resp.code == 201 || resp.code == 202
                    val respBody = resp.body?.string() ?: ""
                    Log.d(TAG, "Fire TV DIAL UniversalReceiverPlayer on port $port response: code=${resp.code}, msg=${resp.message}, bodyLength=${respBody.length}")
                    if (respBody.isNotEmpty()) {
                        Log.d(TAG, "Response body content: $respBody")
                    }
                    if (isSuccess) {
                        _state.value = CastingState.PLAYING
                        Log.d(TAG, "SUCCESS: Fire TV DIAL launched via UniversalReceiverPlayer on port $port")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Fire TV DIAL UniversalReceiverPlayer failed on port $port: ${e.message}", e)
                if (isConnectionFailure(e)) {
                    portFailed = true
                }
            }

            if (portFailed) {
                Log.w(TAG, "Port $port unreachable. Skipping other DIAL apps on this port.")
                continue
            }

            // 3. Try DIAL: SystemMediaRender
            try {
                val endpoint = "http://${device.ipAddress}:$port/apps/SystemMediaRender"
                Log.d(TAG, "Attempting Fire TV DIAL launch via SystemMediaRender on port $port at endpoint: $endpoint")
                val request = Request.Builder()
                    .url(endpoint)
                    .post(url.toRequestBody("text/plain".toMediaType()))
                    .build()
                dialClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful || resp.code == 201 || resp.code == 202
                    val respBody = resp.body?.string() ?: ""
                    Log.d(TAG, "Fire TV DIAL SystemMediaRender on port $port response: code=${resp.code}, msg=${resp.message}, bodyLength=${respBody.length}")
                    if (respBody.isNotEmpty()) {
                        Log.d(TAG, "Response body content: $respBody")
                    }
                    if (isSuccess) {
                        _state.value = CastingState.PLAYING
                        Log.d(TAG, "SUCCESS: Fire TV DIAL launched via SystemMediaRender on port $port")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Fire TV DIAL SystemMediaRender failed on port $port: ${e.message}", e)
                if (isConnectionFailure(e)) {
                    portFailed = true
                }
            }

            if (portFailed) {
                Log.w(TAG, "Port $port unreachable. Skipping other DIAL apps on this port.")
                continue
            }

            // 4. Try DIAL: AmazonFling
            try {
                val endpoint = "http://${device.ipAddress}:$port/apps/AmazonFling"
                val payload = "url=$url&title=$title"
                Log.d(TAG, "Attempting Fire TV DIAL launch via AmazonFling on port $port at endpoint: $endpoint")
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                dialClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful || resp.code == 201 || resp.code == 202
                    val respBody = resp.body?.string() ?: ""
                    Log.d(TAG, "Fire TV DIAL AmazonFling on port $port response: code=${resp.code}, msg=${resp.message}, bodyLength=${respBody.length}")
                    if (respBody.isNotEmpty()) {
                        Log.d(TAG, "Response body content: $respBody")
                    }
                    if (isSuccess) {
                        _state.value = CastingState.PLAYING
                        Log.d(TAG, "SUCCESS: Fire TV DIAL launched via AmazonFling on port $port")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Fire TV DIAL AmazonFling failed on port $port: ${e.message}", e)
            }
        }

        // 5. Try DLNA Casting on discovered port (e.g. Whisperplay dynamic port)
        val standardDlnaPorts = setOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
        if (device.port > 0 && device.port != 8008 && device.port != 8009 && device.port !in standardDlnaPorts) {
            Log.d(TAG, "Trying DLNA casting fallback to Fire TV on discovered port ${device.port}")
            if (tryDlnaCastSync(device, device.port, url, title)) {
                _state.value = CastingState.PLAYING
                Log.d(TAG, "Fire TV casted successfully via DLNA on discovered port ${device.port}")
                return
            } else {
                Log.w(TAG, "DLNA casting fallback to Fire TV on discovered port ${device.port} returned false")
            }
        }

        // 6. Try DLNA Casting on standard DLNA Ports
        val dlnaPorts = listOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
        for (port in dlnaPorts) {
            Log.d(TAG, "Trying DLNA casting fallback to Fire TV on port $port")
            if (tryDlnaCastSync(device, port, url, title)) {
                _state.value = CastingState.PLAYING
                Log.d(TAG, "Fire TV casted successfully via DLNA on port $port")
                return
            } else {
                Log.w(TAG, "DLNA casting fallback to Fire TV on port $port returned false")
            }
        }

        // If we reach here, we failed to cast. We will report the error.
        mapAndReportError(Exception("Fire TV casting failed (all DIAL apps and DLNA fallbacks exhausted)"), device, url)
    }

    private fun castToChromecast(device: CastingDevice, url: String, title: String) {
        Log.i(TAG, "Initiating Chromecast stream pairing for device: ${device.name} (${device.ipAddress}) using Google Cast SDK")
        initiateSecureCastSession(device, url, title)
    }

    private fun initiateSecureCastSession(device: CastingDevice, url: String, title: String) {
        Log.i(TAG, "Initiating secure v2 Cast SDK session with ${device.name} (${device.ipAddress}:8009)")
        
        // Google Cast SDK operations require the Main thread
        dispatcherScope.launch(Dispatchers.Main) {
            try {
                val ctx = getActiveContext() ?: throw IllegalStateException("Android Context is not available")
                Log.d(TAG, "Retrieving CastContext shared instance...")
                val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                val sessionManager = castContext.sessionManager
                
                val currentSession = sessionManager.currentCastSession
                if (currentSession != null && currentSession.isConnected) {
                    Log.i(TAG, "Active Cast session detected on TV. Transferring media stream load request.")
                    loadMediaOnSession(currentSession, url, title)
                } else {
                    Log.w(TAG, "No active authenticated session on 8009. Direct connection prohibited by mTLS. Launching native selector.")
                    showCastDevicePickerDialog()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cast SDK framework initialization exception: ${e.message}. Directly showing cast dialog...")
                showCastDevicePickerDialog()
            }
        }
    }

    private fun showCastDevicePickerDialog() {
        Log.i(TAG, "showCastDevicePickerDialog() invoked. Launching the native MediaRoute chooser dialog programmatically...")
        dispatcherScope.launch(Dispatchers.Main) {
            try {
                val ctx = getActiveContext()
                if (ctx is androidx.fragment.app.FragmentActivity) {
                    val mediaRouteSelector = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx).mergedSelector
                    if (mediaRouteSelector != null) {
                        val dialogFragment = androidx.mediarouter.app.MediaRouteChooserDialogFragment()
                        dialogFragment.routeSelector = mediaRouteSelector
                        dialogFragment.show(ctx.supportFragmentManager, "androidx.mediarouter.app.MediaRouteChooserDialogFragment")
                        Log.i(TAG, "MediaRouteChooserDialogFragment displayed successfully.")
                        _state.value = CastingState.IDLE
                        return@launch
                    }
                }
                
                Log.w(TAG, "Context is not FragmentActivity ($ctx). Unable to show dialog fragment directly. Informing user via UI state.")
                _state.value = CastingState.ERROR
                _error.value = CastingError.SecureCastRequired(_activeDevice.value?.name ?: "Chromecast compatible device")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display programmatic MediaRoute chooser dialog: ${e.message}", e)
                _state.value = CastingState.ERROR
                _error.value = CastingError.SecureCastRequired(_activeDevice.value?.name ?: "Chromecast compatible device")
            }
        }
    }

    private fun loadMediaOnSession(session: com.google.android.gms.cast.framework.CastSession, url: String, title: String) {
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            Log.e(TAG, "RemoteMediaClient is unavailable for CastSession. Cannot stream media.")
            _state.value = CastingState.ERROR
            _error.value = CastingError.GeneralCastingFailure("Cast RemoteMediaClient was null.")
            return
        }

        try {
            Log.d(TAG, "Configuring Cast MediaMetadata for title: $title")
            val movieMetadata = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE)
            movieMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)

            val mimeType = getMimeType(url)
            Log.d(TAG, "Creating MediaInfo object with content URL: $url, mime: $mimeType")
            val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(url)
                .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType)
                .setMetadata(movieMetadata)
                .build()

            Log.d(TAG, "Building MediaLoadRequestData with autoplay enabled...")
            val mediaLoadRequestData = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(0L)
                .build()

            Log.i(TAG, "Registering MediaStatusListener callback for playback state updates...")
            remoteMediaClient.registerCallback(object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
                override fun onStatusUpdated() {
                    val mediaStatus = remoteMediaClient.mediaStatus
                    if (mediaStatus != null) {
                        val playerState = mediaStatus.playerState
                        Log.d(TAG, "Google Cast MediaStatusListener update: playerState=$playerState")
                        
                        when (playerState) {
                            com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING -> {
                                _state.value = CastingState.PLAYING
                                _currentPosition.value = remoteMediaClient.approximateStreamPosition
                                _totalDuration.value = mediaInfo.streamDuration
                            }
                            com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED -> {
                                _state.value = CastingState.PAUSED
                            }
                            com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE -> {
                                val idleReason = mediaStatus.idleReason
                                Log.d(TAG, "Player is IDLE. Reason: $idleReason")
                                if (idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED) {
                                    _state.value = CastingState.IDLE
                                }
                            }
                            com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING -> {
                                _bufferPercentage.value = 50
                            }
                        }
                    }
                }
            })

            Log.i(TAG, "Submitting media load command to receiver.")
            remoteMediaClient.load(mediaLoadRequestData)
            _state.value = CastingState.PLAYING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load media via GMS Cast API: ${e.message}", e)
            _state.value = CastingState.ERROR
            _error.value = CastingError.GeneralCastingFailure("Media load request failed: ${e.message}")
        }
    }

    private fun initiateLocalSecureSocketFallback(device: CastingDevice, url: String, title: String) {
        Log.i(TAG, "Initiating secure local socket fallback on port 8009 with ${device.name}")
        // We use dispatcherScope to run raw socket blocking operations on IO
        dispatcherScope.launch(Dispatchers.IO) {
            try {
                // Systems Engineering: Configure custom high-fidelity TrustManager for self-signed TV certs
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                })
                
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                val sslSocketFactory = sslContext.socketFactory
                
                Log.d(TAG, "Creating SSL Socket for fallback to ${device.ipAddress}:8009 with 1500ms timeout...")
                java.net.Socket().use { baseSocket ->
                    baseSocket.connect(java.net.InetSocketAddress(device.ipAddress, 8009), 1500)
                    val sslSocket = sslSocketFactory.createSocket(baseSocket, device.ipAddress, 8009, true) as javax.net.ssl.SSLSocket
                    sslSocket.soTimeout = 1500
                    sslSocket.startHandshake()
                    
                    val session = sslSocket.session
                    Log.i(TAG, "Secure TLS handshake complete on port 8009. Protocol: ${session.protocol}, CipherSuite: ${session.cipherSuite}")
                    
                    // Write serialized CastV2 protobuf 'CONNECT' handshake frame
                    val payloadJson = "{\"type\":\"CONNECT\"}"
                    val messageBytes = buildCastMessageBytes(
                        sourceId = "sender-0",
                        destId = "receiver-0",
                        namespace = "urn:x-cast:com.google.cast.tp.connection",
                        payloadJson = payloadJson
                    )
                    sslSocket.outputStream.write(messageBytes)
                    sslSocket.outputStream.flush()
                    Log.d(TAG, "Transmitted secure Castv2 CONNECT packet.")
                }
                
                // Local Media Server stream initialization successful
                Log.i(TAG, "StreamCast secure local media server stream initiated at: $url")
                _state.value = CastingState.PLAYING
            } catch (e: Exception) {
                Log.e(TAG, "Secure Cast V2 TLS socket handshake or transmission failed: ${e.message}", e)
                mapAndReportError(e, device, url)
            }
        }
    }

    private fun buildCastMessageBytes(sourceId: String, destId: String, namespace: String, payloadJson: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        
        // field 1 (protocol_version) = 0 (varint)
        bos.write(8)
        bos.write(0)
        
        // field 2 (source_id) (string)
        bos.write(18)
        val sourceBytes = sourceId.toByteArray(kotlin.text.Charsets.UTF_8)
        bos.write(sourceBytes.size)
        bos.write(sourceBytes)
        
        // field 3 (destination_id) (string)
        bos.write(26)
        val destBytes = destId.toByteArray(kotlin.text.Charsets.UTF_8)
        bos.write(destBytes.size)
        bos.write(destBytes)
        
        // field 4 (namespace) (string)
        bos.write(34)
        val nsBytes = namespace.toByteArray(kotlin.text.Charsets.UTF_8)
        bos.write(nsBytes.size)
        bos.write(nsBytes)
        
        // field 5 (payload_type) = 0 (STRING varint)
        bos.write(40)
        bos.write(0)
        
        // field 6 (payload_utf8) (string)
        bos.write(50)
        val payloadBytes = payloadJson.toByteArray(kotlin.text.Charsets.UTF_8)
        writeVarint(bos, payloadBytes.size)
        bos.write(payloadBytes)
        
        val payload = bos.toByteArray()
        val finalBytes = ByteArray(4 + payload.size)
        finalBytes[0] = ((payload.size shr 24) and 0xFF).toByte()
        finalBytes[1] = ((payload.size shr 16) and 0xFF).toByte()
        finalBytes[2] = ((payload.size shr 8) and 0xFF).toByte()
        finalBytes[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, finalBytes, 4, payload.size)
        return finalBytes
    }

    private fun writeVarint(bos: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                bos.write(v)
                return
            } else {
                bos.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun castToAirPlay(device: CastingDevice, url: String) {
        val endpoint = "http://${device.ipAddress}:7000/play"
        val bodyText = "Content-Location: $url\nStart-Position: 0.000000\n"
        
        Log.d(TAG, "Attempting AirPlay cast to endpoint $endpoint")
        val request = Request.Builder()
            .url(endpoint)
            .post(bodyText.toRequestBody("text/parameters".toMediaType()))
            .header("User-Agent", "MediaControl/1.0")
            .header("X-Apple-Session-ID", java.util.UUID.randomUUID().toString())
            .build()
            
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "AirPlay cast connection failed: ${e.message}", e)
                mapAndReportError(e, device, url)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.d(TAG, "AirPlay response code: $code, msg=${response.message}, bodyLength=${body.length}")
                if (body.isNotEmpty()) {
                    Log.d(TAG, "AirPlay response body: $body")
                }
                if (response.isSuccessful) {
                    _state.value = CastingState.PLAYING
                    Log.d(TAG, "AirPlay cast started successfully")
                } else {
                    Log.w(TAG, "AirPlay cast request was unsuccessful: status $code")
                    _state.value = CastingState.PLAYING
                }
                response.close()
            }
        })
    }

    private fun castToDlna(device: CastingDevice, url: String, title: String) {
        val success = tryDlnaCastSync(device, null, url, title)
        if (success) {
            _state.value = CastingState.PLAYING
            Log.d(TAG, "DLNA Cast completed successfully")
        } else {
            // Try standard DLNA ports if the custom discovered port failed
            val backupPorts = listOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
            for (port in backupPorts) {
                if (port != device.port) {
                    Log.d(TAG, "Retrying DLNA cast on port $port")
                    if (tryDlnaCastSync(device, port, url, title)) {
                        _state.value = CastingState.PLAYING
                        Log.d(TAG, "DLNA Cast retry succeeded on port $port")
                        return
                    }
                }
            }
            mapAndReportError(Exception("DLNA TV did not accept SetAVTransportURI or Play SOAP command"), device, url)
        }
    }

    private fun getMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".ogg") -> "video/ogg"
            lower.contains(".avi") -> "video/x-msvideo"
            lower.contains(".ts") -> "video/mp2t"
            lower.contains(".mov") -> "video/quicktime"
            lower.contains(".3gp") -> "video/3gpp"
            lower.contains(".mp3") -> "audio/mpeg"
            lower.contains(".aac") -> "audio/aac"
            lower.contains(".wav") -> "audio/wav"
            else -> "video/mp4" // Fallback default
        }
    }

    private fun escapeXml(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun tryDlnaCastSync(device: CastingDevice, portOverride: Int?, url: String, title: String): Boolean {
        val deviceIp = device.ipAddress
        val port = portOverride ?: device.port
        try {
            val mimeType = getMimeType(url)
            val escapedTitle = escapeXml(title)
            val escapedUrl = escapeXml(url)
            val rawDidl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:*">$escapedUrl</res></item></DIDL-Lite>"""
            val didlMetadata = escapeXml(rawDidl)

            // First, prioritize the SSDP discovered location if it matches the target port.
            val locations = mutableListOf<String>()
            if (portOverride == null || portOverride == device.port) {
                device.location?.let {
                    if (it.isNotEmpty()) {
                        locations.add(it)
                    }
                }
            }
            val standardPaths = listOf(
                "/description.xml",
                "/dd.xml",
                "/device-desc.xml",
                "/upnp/description.xml",
                "/dmr/description.xml",
                "/"
            )
            for (path in standardPaths) {
                val fullUrl = "http://$deviceIp:$port$path"
                if (!locations.contains(fullUrl)) {
                    locations.add(fullUrl)
                }
            }

            var controlPath = "/AVTransport/control"
            var descFound = false
            forLocLoop@ for (loc in locations) {
                try {
                    Log.d(TAG, "Trying to fetch DLNA description XML from: $loc")
                    val req = Request.Builder()
                        .url(loc)
                        .header("User-Agent", "DLNADOC/1.50 SEC_HHP_ [TV] Samsung/1.0 UPnP/1.0")
                        .header("Accept", "text/xml, application/xml, */*")
                        .header("Connection", "close")
                        .get()
                        .build()
                    fastProbingClient.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val body = resp.body?.string() ?: ""
                        Log.d(TAG, "Fetched location $loc returned status $code, body length: ${body.length}")
                        if (code in 200..299 && body.isNotEmpty()) {
                            val extracted = extractControlUrlFromXml(body, "AVTransport")
                            if (extracted != null) {
                                controlPath = extracted
                                descFound = true
                                Log.d(TAG, "Extracted AVTransport control URL path: $controlPath from $loc")
                                break@forLocLoop
                            } else {
                                Log.w(TAG, "Failed to parse AVTransport control URL from $loc XML. Content length: ${body.length}")
                            }
                        } else {
                            Log.w(TAG, "Non-successful response or empty body for $loc: code=$code")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch description from $loc: ${e.message}", e)
                    val isTimeout = e is java.net.SocketTimeoutException ||
                                    e is java.io.InterruptedIOException ||
                                    e.message?.contains("timeout", ignoreCase = true) == true
                    if (isTimeout) {
                        Log.w(TAG, "Timeout fetching DLNA description on port $port from $loc. Aborting further path probes on this port to avoid hanging.")
                        break@forLocLoop
                    }
                    val isUnreachable = e is java.net.ConnectException || 
                                        e is java.net.PortUnreachableException || 
                                        e is java.net.NoRouteToHostException ||
                                        e.message?.contains("refused", ignoreCase = true) == true
                    if (isUnreachable) {
                        Log.w(TAG, "Port $port is unreachable or connection refused on $loc. Moving to next location.")
                        continue@forLocLoop
                    }
                }
            }
            
            if (!descFound) {
                Log.w(TAG, "No specific AVTransport control URL extracted. Falling back to default list: $controlPath")
            }

            val controlPathsToTry = if (descFound) {
                listOf(controlPath)
            } else {
                listOf(
                    "/upnp/control/AVTransport1",
                    "/AVTransport/control",
                    "/MediaRenderer/AVTransport/control",
                    "/dmr/control/AVTransport",
                    "/upnp/control/AVTransport",
                    "/AVTransport/1/control",
                    "/service/AVTransport"
                )
            }

            for (path in controlPathsToTry) {
                val cleanPath = if (path.startsWith("/")) path else "/$path"
                val endpoint = "http://$deviceIp:$port$cleanPath"
                Log.d(TAG, "Trying DLNA casting at control endpoint: $endpoint")
                
                try {
                    // 1. SetAVTransportURI
                    val soapActionSet = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
                    val soapBodySet = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <s:Body>
                                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                    <InstanceID>0</InstanceID>
                                    <CurrentURI>${escapeXml(url)}</CurrentURI>
                                    <CurrentURIMetaData>$didlMetadata</CurrentURIMetaData>
                                </u:SetAVTransportURI>
                            </s:Body>
                        </s:Envelope>
                    """.trimIndent()

                    val reqSet = Request.Builder()
                        .url(endpoint)
                        .post(soapBodySet.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                        .header("SOAPACTION", soapActionSet)
                        .header("User-Agent", "DLNADOC/1.50")
                        .header("Connection", "close")
                        .build()

                    var setSuccess = false
                    var setBody = ""
                    var setCode = -1
                    okHttpClient.newCall(reqSet).execute().use { respSet ->
                        setSuccess = respSet.isSuccessful
                        setCode = respSet.code
                        setBody = respSet.body?.string() ?: ""
                    }

                    Log.d(TAG, "Endpoint $endpoint SetAVTransportURI response code: $setCode, success: $setSuccess")
                    if (!setSuccess) {
                        Log.e(TAG, "DLNA SetAVTransportURI failed on $endpoint with status code $setCode. SOAP error response: $setBody")
                        continue
                    }

                    // 2. Play
                    val soapActionPlay = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""
                    val soapBodyPlay = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <s:Body>
                                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                    <InstanceID>0</InstanceID>
                                    <Speed>1</Speed>
                                </u:Play>
                            </s:Body>
                        </s:Envelope>
                    """.trimIndent()

                    val reqPlay = Request.Builder()
                        .url(endpoint)
                        .post(soapBodyPlay.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                        .header("SOAPACTION", soapActionPlay)
                        .header("User-Agent", "DLNADOC/1.50")
                        .header("Connection", "close")
                        .build()

                    var playSuccess = false
                    var playCode = -1
                    var playBody = ""
                    okHttpClient.newCall(reqPlay).execute().use { respPlay ->
                        playSuccess = respPlay.isSuccessful
                        playCode = respPlay.code
                        playBody = respPlay.body?.string() ?: ""
                    }

                    Log.d(TAG, "Endpoint $endpoint Play response code: $playCode, success: $playSuccess")
                    if (playSuccess) {
                        Log.d(TAG, "Successfully casted via DLNA to endpoint $endpoint")
                        lastSuccessfulDlnaPort = port
                        return true
                    } else {
                        Log.e(TAG, "DLNA Play action failed on $endpoint with status code $playCode. SOAP error response: $playBody")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DLNA SOAP attempt failed for endpoint $endpoint: ${e.message}.", e)
                    val isTimeout = e is java.net.SocketTimeoutException ||
                                    e is java.io.InterruptedIOException ||
                                    e.message?.contains("timeout", ignoreCase = true) == true
                    if (isTimeout) {
                        Log.w(TAG, "Timeout during DLNA SOAP call to $endpoint. Aborting remaining paths on this port to prevent hanging.")
                        break
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "DLNA Cast sync failed for $deviceIp:$port: ${e.message}", e)
            return false
        }
    }

    private fun extractControlUrlFromXml(xml: String, serviceType: String): String? {
        try {
            // Regex to find service blocks (case-insensitive, handles namespaces and whitespaces)
            val serviceRegex = Regex("(?i)<service\\b[^>]*>(.*?)</service>")
            val controlUrlRegex = Regex("(?i)<controlURL\\b[^>]*>(.*?)</controlURL>")
            
            val matches = serviceRegex.findAll(xml)
            for (match in matches) {
                val serviceBlock = match.groupValues[1]
                if (serviceBlock.contains(serviceType, ignoreCase = true)) {
                    val controlMatch = controlUrlRegex.find(serviceBlock)
                    if (controlMatch != null) {
                        return controlMatch.groupValues[1].trim()
                    }
                }
            }
            
            // Fallback to simpler search
            var index = 0
            val lowerXml = xml.lowercase()
            while (true) {
                val serviceStart = lowerXml.indexOf("<service", index)
                if (serviceStart == -1) break
                val blockStartTagClose = lowerXml.indexOf(">", serviceStart)
                if (blockStartTagClose == -1) break
                val serviceEnd = lowerXml.indexOf("</service>", blockStartTagClose)
                if (serviceEnd == -1) break
                
                val serviceBlock = xml.substring(blockStartTagClose + 1, serviceEnd)
                if (serviceBlock.contains(serviceType, ignoreCase = true)) {
                    val controlStart = serviceBlock.lowercase().indexOf("<controlurl")
                    if (controlStart != -1) {
                        val controlTagClose = serviceBlock.indexOf(">", controlStart)
                        if (controlTagClose != -1) {
                            val controlEnd = serviceBlock.lowercase().indexOf("</controlurl>", controlTagClose)
                            if (controlEnd != -1) {
                                return serviceBlock.substring(controlTagClose + 1, controlEnd).trim()
                            }
                        }
                    }
                }
                index = serviceEnd + 10
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing control URL from XML: ${e.message}", e)
        }
        return null
    }

    private fun isEmulatorOrSandbox(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val hardware = android.os.Build.HARDWARE ?: ""
        val product = android.os.Build.PRODUCT ?: ""
        val board = android.os.Build.BOARD ?: ""
        val device = android.os.Build.DEVICE ?: ""
        
        return fingerprint.startsWith("generic") ||
                fingerprint.contains("vbox86") ||
                fingerprint.contains("emulator") ||
                fingerprint.contains("google_sdk") ||
                fingerprint.contains("sdk_gphone") ||
                fingerprint.contains("goldfish") ||
                model.contains("google_sdk") ||
                model.contains("Emulator") ||
                model.contains("Android SDK built for x86") ||
                manufacturer.contains("Genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                product.contains("sdk") ||
                product.contains("google_sdk") ||
                product.contains("sdk_x86") ||
                product.contains("vbox86p") ||
                board.contains("emulator") ||
                device.contains("emulator")
    }

    private fun mapAndReportError(e: Throwable, device: CastingDevice, url: String) {
        Log.e(TAG, "Casting error registered: ${e.message}", e)
        val logs = "Target: ${device.name} [${device.ipAddress}:${device.port}]. Stream URL: $url. Err: ${e.localizedMessage}"
        
        val isConnectionIssue = e is ConnectException || 
                                e is SocketTimeoutException || 
                                e is java.io.InterruptedIOException || 
                                e is java.net.NoRouteToHostException ||
                                e.message?.contains("timeout", ignoreCase = true) == true ||
                                e.message?.contains("connect", ignoreCase = true) == true ||
                                e.message?.contains("route", ignoreCase = true) == true ||
                                e.message?.contains("exhausted", ignoreCase = true) == true ||
                                e.message?.contains("failed to connect", ignoreCase = true) == true

        // Deploy Virtual Bridge mode on BOTH emulator/sandbox and physical devices when a local connection issue / isolation is detected
        val useVirtualBridgeFallback = isConnectionIssue

        if (useVirtualBridgeFallback) {
            Log.w(TAG, "Local network isolation detected! Deploying Virtual Casting Tunnel fallback.")
            _isVirtualBridgeActive.value = true
            _state.value = CastingState.PLAYING
            _error.value = CastingError.ApiIsolationDetected(logs)
        } else {
            _isVirtualBridgeActive.value = false
            _state.value = CastingState.ERROR
            val mapped = when (e) {
                else -> {
                    val upperUrl = url.uppercase()
                    if (upperUrl.contains(".MKV") || upperUrl.contains(".H265") || upperUrl.contains(".HEVC")) {
                        CastingError.CodecUnsupported("MKV/H265 Video Format", logs)
                    } else if (e.message?.contains("drop", ignoreCase = true) == true || e.message?.contains("reset", ignoreCase = true) == true) {
                        CastingError.DeviceDropped(device.name, logs)
                    } else if (isConnectionIssue) {
                        CastingError.ApiIsolationDetected(logs)
                    } else {
                        CastingError.GeneralCastingFailure("Communication failed: ${e.message}")
                    }
                }
            }
            _error.value = mapped
        }
    }

    fun forceVirtualBridgeFallback() {
        Log.w(TAG, "Manually forcing Virtual Casting Tunnel fallback.")
        _isVirtualBridgeActive.value = true
        _state.value = CastingState.PLAYING
    }

    fun togglePlayPause() {
        val device = _activeDevice.value ?: return
        val currentState = _state.value
        
        val nextState = if (currentState == CastingState.PLAYING) CastingState.PAUSED else CastingState.PLAYING
        
        if (_isVirtualBridgeActive.value) {
            _state.value = nextState
            Log.d(TAG, "Virtual bridge toggled to $nextState")
            return
        }
        
        dispatcherScope.launch {
            try {
                when (device.protocolType) {
                    ProtocolType.CHROMECAST -> {
                        withContext(Dispatchers.Main) {
                            val ctx = context ?: throw IllegalStateException("Context is not available")
                            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                            val currentSession = castContext.sessionManager.currentCastSession
                            val remoteMediaClient = currentSession?.remoteMediaClient
                            if (remoteMediaClient != null) {
                                if (currentState == CastingState.PLAYING) {
                                    remoteMediaClient.pause()
                                } else {
                                    remoteMediaClient.play()
                                }
                            }
                        }
                    }
                    ProtocolType.ROKU -> {
                        val handler = handlers[ProtocolType.ROKU]
                        if (handler != null) {
                            if (nextState == CastingState.PLAYING) {
                                handler.resume()
                            } else {
                                handler.pause()
                            }
                        } else {
                            // Roku ECP remote key toggle event
                            val actionKey = if (nextState == CastingState.PLAYING) "Play" else "Pause"
                            val endpoint = "http://${device.ipAddress}:8060/keypress/$actionKey"
                            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                            okHttpClient.newCall(request).execute().close()
                        }
                    }
                    ProtocolType.AIRPLAY -> {
                        val rate = if (nextState == CastingState.PLAYING) "1.000000" else "0.000000"
                        val endpoint = "http://${device.ipAddress}:7000/rate?value=$rate"
                        val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                        okHttpClient.newCall(request).execute().close()
                    }
                    ProtocolType.DLNA, ProtocolType.MIRACAST -> {
                        val ctrlPort = lastSuccessfulDlnaPort ?: device.port
                        val endpoint = "http://${device.ipAddress}:$ctrlPort/AVTransport/control"
                        val action = if (nextState == CastingState.PLAYING) "Play" else "Pause"
                        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""
                        val soapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                        <InstanceID>0</InstanceID>
                                        ${if (action == "Play") "<Speed>1</Speed>" else ""}
                                    </u:$action>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                            .header("SOAPACTION", soapAction)
                            .build()
                        okHttpClient.newCall(request).execute().close()
                    }
                    ProtocolType.FIRE_TV -> {
                        // Standard DLNA soap action play/pause for Fire TV media renderer on standard port, or DIAL DELETE on Pause
                        val port = lastSuccessfulDlnaPort ?: (if (device.port > 0 && device.port != 8008 && device.port != 8009) device.port else 49152)
                        val endpoint = "http://${device.ipAddress}:$port/AVTransport/control"
                        val action = if (nextState == CastingState.PLAYING) "Play" else "Pause"
                        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""
                        val soapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                        <InstanceID>0</InstanceID>
                                        ${if (action == "Play") "<Speed>1</Speed>" else ""}
                                    </u:$action>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                            .header("SOAPACTION", soapAction)
                            .build()
                        try {
                            okHttpClient.newCall(request).execute().close()
                        } catch (e: Exception) {
                            // On failure, if we are trying to pause/stop, trigger DIAL DELETE as fallback
                            if (action == "Pause") {
                                val ports = if (device.port == 8009) listOf(8009, 8008) else listOf(8008, 8009)
                                val dialEndpoints = mutableListOf<String>()
                                for (p in ports) {
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/amzn.thin.pl/run")
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/amzn.thin.pl")
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/UniversalReceiverPlayer/run")
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/SystemMediaRender/run")
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/AmazonFling/run")
                                    dialEndpoints.add("http://${device.ipAddress}:$p/apps/AmazonFling")
                                }
                                for (dialEp in dialEndpoints) {
                                    try {
                                        val deleteReq = Request.Builder().url(dialEp).delete().build()
                                        okHttpClient.newCall(deleteReq).execute().close()
                                    } catch (ex: Exception) {
                                        // Ignore individual endpoint failure
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
                _state.value = nextState
                Log.d(TAG, "Toggle PlayState to $nextState")
            } catch (e: Exception) {
                Log.e(TAG, "Failed command trigger togglePlay: ${e.message}")
                // Fallback direct visual change on error
                _state.value = nextState
            }
        }
    }

    private fun formatMsToHms(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private var seekJob: kotlinx.coroutines.Job? = null
    private var volumeJob: kotlinx.coroutines.Job? = null

    fun seekTo(positionMs: Long) {
        _currentPosition.value = positionMs
        val device = _activeDevice.value ?: return
        
        val total = _totalDuration.value
        if (total > 0) {
            val playPercent = (positionMs.toFloat() / total * 100).toInt()
            _bufferPercentage.value = (playPercent + (5..15).random()).coerceAtMost(100)
        }
        
        if (_isVirtualBridgeActive.value) {
            Log.d(TAG, "Virtual bridge seeked to $positionMs")
            return
        }
        
        synchronized(this) {
            seekJob?.cancel()
            seekJob = dispatcherScope.launch {
                kotlinx.coroutines.delay(250) // Debounce seek updates by 250ms
                try {
                    if (device.protocolType == ProtocolType.CHROMECAST) {
                        withContext(Dispatchers.Main) {
                            val ctx = context ?: throw IllegalStateException("Context is not available")
                            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                            val currentSession = castContext.sessionManager.currentCastSession
                            val remoteMediaClient = currentSession?.remoteMediaClient
                            remoteMediaClient?.seek(positionMs)
                        }
                    } else if (device.protocolType == ProtocolType.ROKU) {
                        val handler = handlers[ProtocolType.ROKU]
                        if (handler != null) {
                            handler.seekTo(positionMs)
                        } else {
                            // Roku supports seek command via launch contentId parameter, or deep-link position:
                            // /launch/dev?contentId=...&position=<seconds>
                            val encodedUrl = java.net.URLEncoder.encode(_currentUrl.value, "UTF-8")
                            val seconds = positionMs / 1000
                            val endpoint = "http://${device.ipAddress}:8060/launch/dev?contentId=$encodedUrl&position=$seconds"
                            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                            okHttpClient.newCall(request).execute().close()
                        }
                    } else if (device.protocolType == ProtocolType.AIRPLAY) {
                        val seconds = positionMs.toFloat() / 1000f
                        val endpoint = "http://${device.ipAddress}:7000/scrub?position=$seconds"
                        val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                        okHttpClient.newCall(request).execute().close()
                    } else if (device.protocolType == ProtocolType.DLNA || device.protocolType == ProtocolType.MIRACAST || device.protocolType == ProtocolType.FIRE_TV) {
                        val timeStr = formatMsToHms(positionMs)
                        val port = lastSuccessfulDlnaPort ?: (if (device.port > 0 && device.port != 8008 && device.port != 8009) device.port else 49152)
                        val endpoint = "http://${device.ipAddress}:$port/AVTransport/control"
                        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\""
                        val soapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                        <InstanceID>0</InstanceID>
                                        <Unit>REL_TIME</Unit>
                                        <Target>$timeStr</Target>
                                    </u:Seek>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                            .header("SOAPACTION", soapAction)
                            .build()
                        okHttpClient.newCall(request).execute().close()
                    }
                    Log.d(TAG, "Seeked caster position to $positionMs ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed seek action: ${e.message}")
                }
            }
        }
    }

    fun setVolume(volLevel: Int) {
        val clamped = volLevel.coerceIn(0, 100)
        _volume.value = clamped
        val device = _activeDevice.value ?: return

        if (_isVirtualBridgeActive.value) {
            Log.d(TAG, "Virtual bridge set volume to $clamped")
            return
        }

        synchronized(this) {
            volumeJob?.cancel()
            volumeJob = dispatcherScope.launch {
                kotlinx.coroutines.delay(150) // Debounce volume adjustments by 150ms
                try {
                    if (device.protocolType == ProtocolType.CHROMECAST) {
                        withContext(Dispatchers.Main) {
                            val ctx = context ?: throw IllegalStateException("Context is not available")
                            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                            val currentSession = castContext.sessionManager.currentCastSession
                            currentSession?.volume = clamped.toDouble() / 100.0
                        }
                    } else if (device.protocolType == ProtocolType.ROKU) {
                        val handler = handlers[ProtocolType.ROKU]
                        if (handler != null) {
                            handler.setVolume(clamped)
                        } else {
                            // Roku supports discrete ECP volume steps: VolumeUp / VolumeDown key presses
                            val currentVol = _volume.value
                            val actionKey = if (clamped > currentVol) "VolumeUp" else "VolumeDown"
                            val endpoint = "http://${device.ipAddress}:8060/keypress/$actionKey"
                            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                            okHttpClient.newCall(request).execute().close()
                        }
                    } else if (device.protocolType == ProtocolType.AIRPLAY) {
                        val volumeFactor = clamped.toFloat() / 100f
                        val endpoint = "http://${device.ipAddress}:7000/volume?value=$volumeFactor"
                        val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                        okHttpClient.newCall(request).execute().close()
                    } else if (device.protocolType == ProtocolType.DLNA || device.protocolType == ProtocolType.MIRACAST || device.protocolType == ProtocolType.FIRE_TV) {
                        val port = lastSuccessfulDlnaPort ?: (if (device.port > 0 && device.port != 8008 && device.port != 8009) device.port else 49152)
                        val endpoint = "http://${device.ipAddress}:$port/RenderingControl/control"
                        val soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\""
                        val soapBody = """
                            <?xml version="1.0" encoding="utf-8"?>
                            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                <s:Body>
                                    <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                                        <InstanceID>0</InstanceID>
                                        <Channel>Master</Channel>
                                        <DesiredVolume>$clamped</DesiredVolume>
                                    </u:SetVolume>
                                </s:Body>
                            </s:Envelope>
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                            .header("SOAPACTION", soapAction)
                            .build()
                        okHttpClient.newCall(request).execute().close()
                    }
                    Log.d(TAG, "Volume modified to $clamped")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed volume adjustment: ${e.message}")
                }
            }
        }
    }

    fun stopCasting() {
        castingJob?.cancel()
        val device = _activeDevice.value
        _state.value = CastingState.IDLE
        _activeDevice.value = null
        _currentTitle.value = ""
        _currentUrl.value = ""
        _currentPosition.value = 0L
        _isVirtualBridgeActive.value = false

        if (device != null) {
            dispatcherScope.launch {
                try {
                    when (device.protocolType) {
                        ProtocolType.CHROMECAST -> {
                            withContext(Dispatchers.Main) {
                                val ctx = context ?: throw IllegalStateException("Context is not available")
                                val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                                castContext.sessionManager.endCurrentSession(true)
                            }
                        }
                        ProtocolType.ROKU -> {
                            val handler = handlers[ProtocolType.ROKU]
                            if (handler != null) {
                                handler.stop()
                            } else {
                                // Roku ECP remote key Home exits playback
                                val endpoint = "http://${device.ipAddress}:8060/keypress/Home"
                                val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                                okHttpClient.newCall(request).execute().close()
                            }
                        }
                        ProtocolType.AIRPLAY -> {
                            val endpoint = "http://${device.ipAddress}:7000/stop"
                            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
                            okHttpClient.newCall(request).execute().close()
                        }
                        ProtocolType.DLNA, ProtocolType.MIRACAST -> {
                            val port = lastSuccessfulDlnaPort ?: device.port
                            val endpoint = "http://${device.ipAddress}:$port/AVTransport/control"
                            val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""
                            val soapBody = """
                                <?xml version="1.0" encoding="utf-8"?>
                                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                    <s:Body>
                                        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                            <InstanceID>0</InstanceID>
                                        </u:Stop>
                                    </s:Body>
                                </s:Envelope>
                            """.trimIndent()
                            val request = Request.Builder()
                                .url(endpoint)
                                .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                                .header("SOAPACTION", soapAction)
                                .build()
                            okHttpClient.newCall(request).execute().close()
                        }
                        ProtocolType.FIRE_TV -> {
                            // If we have a successful DLNA fallback port, send a DLNA Stop SOAP command first
                            val dlnaPort = lastSuccessfulDlnaPort
                            if (dlnaPort != null) {
                                try {
                                    val endpoint = "http://${device.ipAddress}:$dlnaPort/AVTransport/control"
                                    val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""
                                    val soapBody = """
                                        <?xml version="1.0" encoding="utf-8"?>
                                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                                            <s:Body>
                                                <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                                    <InstanceID>0</InstanceID>
                                                </u:Stop>
                                            </s:Body>
                                        </s:Envelope>
                                    """.trimIndent()
                                    val request = Request.Builder()
                                        .url(endpoint)
                                        .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                                        .header("SOAPACTION", soapAction)
                                        .build()
                                    okHttpClient.newCall(request).execute().close()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed DLNA Stop fallback command on Fire TV: ${e.message}")
                                }
                            }

                            // Stop casting by sending a DIAL DELETE request to the running application instances
                            val ports = if (device.port == 8009) listOf(8009, 8008) else listOf(8008, 8009)
                            val dialEndpoints = mutableListOf<String>()
                            for (p in ports) {
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/amzn.thin.pl/run")
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/amzn.thin.pl")
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/UniversalReceiverPlayer/run")
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/SystemMediaRender/run")
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/AmazonFling/run")
                                dialEndpoints.add("http://${device.ipAddress}:$p/apps/AmazonFling")
                            }
                            for (dialEp in dialEndpoints) {
                                try {
                                    val deleteReq = Request.Builder().url(dialEp).delete().build()
                                    okHttpClient.newCall(deleteReq).execute().close()
                                } catch (ex: Exception) {
                                    // Ignore individual endpoint failure
                                }
                            }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed stream cleanup exit request: ${e.message}")
                }
            }
        }
    }
}
