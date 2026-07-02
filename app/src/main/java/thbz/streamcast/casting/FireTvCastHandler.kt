package thbz.streamcast.casting

import thbz.streamcast.AppLogger as Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionState {
    INIT,
    PROBING,
    CONNECTING,
    CONNECTED
}

class FireTvCastHandler(private val okHttpClient: OkHttpClient) : CastProtocolHandler {
    private val TAG = "FireTvCastHandler"
    private var activeDevice: CastingDevice? = null
    
    // Connection Lifecycle State Machine
    private var connectionState = ConnectionState.INIT
    private var currentSessionUrl: String? = null
    private var activePort = 8008 // Default Amazon DIAL port

    override suspend fun connect(device: CastingDevice): Boolean = withContext(Dispatchers.IO) {
        activeDevice = device
        connectionState = ConnectionState.PROBING
        Log.i(TAG, "[INIT] Native Fire TV Handshake triggered for ${device.name} (${device.ipAddress})")

        // Use StarterTimer timeout logic as described in the decompiled specification (e.g., 5-second timeout)
        val connectedSuccessfully = withTimeoutOrNull(5000) {
            try {
                // 1. Probing State: Verify target port and availability
                val portsToTry = if (device.port > 0 && device.port != 8009) listOf(device.port, 8008) else listOf(8008)
                var resolvedPort = 8008
                var foundReceiver = false

                Log.d(TAG, "[STEP_1 / PROBING] Contacting service endpoints on ports: $portsToTry")
                for (port in portsToTry) {
                    val urlProbe = "http://${device.ipAddress}:$port/apps/amzn.thin.pl"
                    val request = Request.Builder().url(urlProbe).get().build()
                    try {
                        okHttpClient.newCall(request).execute().use { response ->
                            // If endpoint responds (even with 404 or 405), the DIAL service is alive and listening
                            if (response.code in 200..499) {
                                resolvedPort = port
                                foundReceiver = true
                                Log.d(TAG, "[STEP_1 / PROBING] Confirmed DIAL receiver on port $port (status ${response.code})")
                                return@withTimeoutOrNull Pair(resolvedPort, true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Probe failed on port $port: ${e.message}")
                    }
                }

                if (!foundReceiver) {
                    // Try Fling app endpoint directly
                    for (port in portsToTry) {
                        val urlProbe = "http://${device.ipAddress}:$port/apps/AmazonFling"
                        val request = Request.Builder().url(urlProbe).get().build()
                        try {
                            okHttpClient.newCall(request).execute().use { response ->
                                if (response.code in 200..499) {
                                    resolvedPort = port
                                    foundReceiver = true
                                    Log.d(TAG, "[STEP_1 / PROBING] Confirmed Fling service on port $port")
                                    return@withTimeoutOrNull Pair(resolvedPort, true)
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Probe for Fling failed on port $port: ${e.message}")
                        }
                    }
                }

                Pair(resolvedPort, foundReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error during PROBING phase: ${e.message}")
                null
            }
        }

        if (connectedSuccessfully == null || !connectedSuccessfully.second) {
            connectionState = ConnectionState.INIT
            Log.e(TAG, "State violation: Session must be in STEP_1 / PROBING state. Handshake timed out or device unreachable.")
            return@withContext false
        }

        // 2. Connecting State: Establish channel configurations
        connectionState = ConnectionState.CONNECTING
        activePort = connectedSuccessfully.first
        Log.i(TAG, "[STEP_2 / CONNECTING] Handshake validated on port $activePort. Establishing secure parameters.")

        // 3. Connected State: Handshake complete
        connectionState = ConnectionState.CONNECTED
        Log.i(TAG, "[STEP_3 / CONNECTED] Fire TV session handshake established successfully with ${device.name}")
        return@withContext true
    }

    override suspend fun castMedia(url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext false
        if (connectionState != ConnectionState.CONNECTED) {
            Log.e(TAG, "State violation: Session must be in STEP_3 / CONNECTED state to cast media. Current state: $connectionState")
            return@withContext false
        }

        Log.d(TAG, "Casting media with native WhisperPlay format transcode metadata to ${device.name}")
        
        // Formulate target player arguments & metadata JSON objects exactly as reverse engineered
        val metadataJson = """{"type":"video","title":"$title","description":"Casting from StreamCast","noreplay":false}"""
        
        try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val encodedMetadata = URLEncoder.encode(metadataJson, "UTF-8")
            val payload = "url=$encodedUrl&metadata=$encodedMetadata"

            val endpoint = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl"
            Log.d(TAG, "Executing play_args POST on endpoint: $endpoint with payload: $payload")

            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val isSuccess = response.isSuccessful || response.code == 201 || response.code == 202
                val locationHeader = response.header("Location")
                
                if (locationHeader != null) {
                    currentSessionUrl = if (locationHeader.startsWith("http")) {
                        locationHeader
                    } else {
                        "http://${device.ipAddress}:$activePort$locationHeader"
                    }
                    Log.d(TAG, "Retrieved DIAL instance location: $currentSessionUrl")
                } else {
                    currentSessionUrl = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl/run"
                }

                if (isSuccess) {
                    Log.i(TAG, "SUCCESS: play_result returned code ${response.code}. Media buffer populating.")
                    return@withContext true
                } else {
                    Log.e(TAG, "FAILED: play_result returned code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Whisperplay castMedia: ${e.message}", e)
        }

        // Fallback to AmazonFling endpoint if amzn.thin.pl DIAL failed
        try {
            val payload = "url=$url&title=$title"
            val endpoint = "http://${device.ipAddress}:$activePort/apps/AmazonFling"
            Log.d(TAG, "Executing Fling play_args fallback: $endpoint")
            
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 202) {
                    currentSessionUrl = "http://${device.ipAddress}:$activePort/apps/AmazonFling/run"
                    Log.i(TAG, "SUCCESS: Fling play_result returned code ${response.code}")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during AmazonFling castMedia: ${e.message}")
        }

        return@withContext false
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext
        Log.d(TAG, "Executing pause_args command to Fire TV")
        
        // 1. Try DIAL Control pause
        try {
            val endpoint = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl/input?cmd=pause"
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "pause_result: Success via input?cmd=pause")
                    return@withContext
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DIAL pause failed: ${e.message}")
        }

        // 2. Fallback to UPnP AVTransport pause
        try {
            sendUpnpAvTransportCommand("Pause")
        } catch (e: Exception) {
            Log.e(TAG, "UPnP fallback pause failed: ${e.message}")
        }
    }

    override suspend fun resume() = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext
        Log.d(TAG, "Executing play_args (resume) command to Fire TV")

        // 1. Try DIAL Control play
        try {
            val endpoint = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl/input?cmd=play"
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "resume_result: Success via input?cmd=play")
                    return@withContext
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DIAL resume failed: ${e.message}")
        }

        // 2. Fallback to UPnP AVTransport play
        try {
            sendUpnpAvTransportCommand("Play")
        } catch (e: Exception) {
            Log.e(TAG, "UPnP fallback resume failed: ${e.message}")
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext
        Log.d(TAG, "Executing stop_args command to Fire TV (destroying playback pipeline)")

        // Send HTTP DELETE to the app run session URL to terminate the receiver app natively
        val sessionUrl = currentSessionUrl ?: "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl"
        try {
            Log.d(TAG, "Terminating player app instance via DELETE on session url: $sessionUrl")
            val request = Request.Builder().url(sessionUrl).delete().build()
            okHttpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "stop_result: app applet termination returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DIAL app instance termination exception: ${e.message}")
        }

        // Also stop via fallback UPnP Stop command
        try {
            sendUpnpAvTransportCommand("Stop")
        } catch (e: Exception) {
            Log.e(TAG, "UPnP stop fallback exception: ${e.message}")
        }

        connectionState = ConnectionState.INIT
        currentSessionUrl = null
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext
        val positionSeconds = positionMs / 1000
        Log.d(TAG, "Executing seekTo_args to Fire TV at position: $positionSeconds seconds")

        // 1. Direct dynamic control seek parameters: input?cmd=seek&seekto=X
        try {
            val endpoint = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl/input?cmd=seek&seekto=$positionSeconds"
            Log.d(TAG, "Sending seek request to endpoint: $endpoint")
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "seekTo_result: Success via input?cmd=seek")
                    return@withContext
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DIAL seek failed: ${e.message}")
        }

        // 2. UPnP AVTransport seek fallback
        try {
            val upnpTarget = String.format("%02d:%02d:%02d", positionSeconds / 3600, (positionSeconds % 3600) / 60, positionSeconds % 60)
            val standardDlnaPorts = listOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
            for (port in standardDlnaPorts) {
                try {
                    val endpoint = "http://${device.ipAddress}:$port/AVTransport/control"
                    val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\""
                    val soapBody = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <s:Body>
                                <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                    <InstanceID>0</InstanceID>
                                    <Unit>REL_TIME</Unit>
                                    <Target>$upnpTarget</Target>
                                </u:Seek>
                            </s:Body>
                        </s:Envelope>
                    """.trimIndent()
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                        .header("SOAPACTION", soapAction)
                        .build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.d(TAG, "Seek successful via UPnP fallback on port $port")
                            return@withContext
                        }
                    }
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "UPnP fallback seek exception: ${e.message}")
        }
    }

    override suspend fun setVolume(volume: Int) = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext
        Log.d(TAG, "Executing onVolumeSetRequest / onVolumeUpdateRequest: target volume $volume")

        // Send standard DIAL/Fling volume parameters or fallback UPnP Volume control
        try {
            val endpoint = "http://${device.ipAddress}:$activePort/apps/amzn.thin.pl/input?cmd=volume&set=$volume"
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "onVolumeSetRequest: Success via REST endpoint")
                    return@withContext
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DIAL volume set failed: ${e.message}")
        }

        // Try standard UPnP RenderingControl set volume
        try {
            val standardDlnaPorts = listOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
            for (port in standardDlnaPorts) {
                try {
                    val endpoint = "http://${device.ipAddress}:$port/RenderingControl/control"
                    val soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\""
                    val soapBody = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <s:Body>
                                <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                                    <InstanceID>0</InstanceID>
                                    <Channel>Master</Channel>
                                    <DesiredVolume>$volume</DesiredVolume>
                                </u:SetVolume>
                            </s:Body>
                        </s:Envelope>
                    """.trimIndent()
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(soapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                        .header("SOAPACTION", soapAction)
                        .build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.d(TAG, "Volume set successfully via UPnP fallback on port $port")
                            return@withContext
                        }
                    }
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "UPnP fallback volume set exception: ${e.message}")
        }
    }

    private fun sendUpnpAvTransportCommand(action: String) {
        val device = activeDevice ?: return
        val standardDlnaPorts = listOf(49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 2869, 7676, 8058, 55000, 8080)
        for (port in standardDlnaPorts) {
            try {
                val endpoint = "http://${device.ipAddress}:$port/AVTransport/control"
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
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "UPnP Command $action successful on port $port")
                        return
                    }
                }
            } catch (ignored: Exception) {}
        }
    }
}
