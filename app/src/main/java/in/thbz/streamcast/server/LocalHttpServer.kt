package in.thbz.streamcast.server

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.text.format.Formatter
import in.thbz.streamcast.AppLogger as Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalHttpServer(private val context: Context, val port: Int = 8182) {
    private val TAG = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private var threadPool: java.util.concurrent.ExecutorService? = null
    private var isRunning = false
    private val activeToken = java.util.UUID.randomUUID().toString().substring(0, 8)

    fun start() {
        if (isRunning) return
        isRunning = true
        if (threadPool == null || threadPool!!.isShutdown) {
            threadPool = Executors.newCachedThreadPool()
        }
        val currentPool = threadPool ?: return
        currentPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Local HTTP server initialized on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val pool = threadPool
                    if (pool != null && !pool.isShutdown) {
                        pool.execute { handleConnection(socket) }
                    } else {
                        try { socket.close() } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket exception: ${e.message}", e)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        try {
            threadPool?.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down thread pool: ${e.message}")
        }
        threadPool = null
    }

    fun getLocalServerUrl(contentUri: Uri): String {
        val ip = getIpAddress() ?: "127.0.0.1"
        val encodedUri = java.net.URLEncoder.encode(contentUri.toString(), "UTF-8")
        return "http://$ip:$port/stream?uri=$encodedUri&token=$activeToken"
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val ipAddress = connectionInfo.ipAddress
            val formattedIp = if (ipAddress != 0) {
                Formatter.formatIpAddress(ipAddress)
            } else {
                "0.0.0.0"
            }
            if (ipAddress == 0 || formattedIp == "0.0.0.0" || formattedIp.isEmpty()) {
                // Fallback to network interfaces if wifiInfo IP is 0 (due to lack of location permission on newer Android versions)
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress
                            if (host != "0.0.0.0" && !host.isNullOrEmpty()) {
                                return host
                            }
                        }
                    }
                }
                null
            } else {
                formattedIp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve local IP address: ${e.message}")
            // Primary fallback on exception
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress
                            if (host != "0.0.0.0" && !host.isNullOrEmpty()) {
                                return host
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Secondary IP fallback failed: ${ex.message}")
            }
            null
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Received HTTP Request: $requestLine")

            val tokens = requestLine.split(" ")
            if (tokens.size < 2) {
                sendBadRequest(socket)
                return
            }
            
            val method = tokens[0]
            val path = tokens[1]

            // Parse range headers
            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.trim().isEmpty()) break
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substringAfter("Range:").trim()
                }
            }

            if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) {
                serveUriStream(socket, path, rangeHeader, isHeadRequest = method.equals("HEAD", ignoreCase = true))
            } else {
                sendMethodNotAllowed(socket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection handler error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun serveUriStream(socket: Socket, path: String, rangeHeader: String?, isHeadRequest: Boolean) {
        val uriQuery = if (path.startsWith("/stream?")) path.substringAfter("/stream?") else ""
        val params = uriQuery.split("&").associate { 
            val parts = it.split("=")
            val key = parts.getOrNull(0) ?: ""
            val value = parts.getOrNull(1) ?: ""
            key to value
        }
        val tokenParam = params["token"]
        val encodedUriParam = params["uri"]

        if (tokenParam == null || tokenParam != activeToken) {
            Log.w(TAG, "Unauthorized request attempt: missing or invalid security token.")
            sendForbidden(socket)
            return
        }

        if (encodedUriParam == null) {
            sendNotFound(socket)
            return
        }

        val decodedUriString = try {
            java.net.URLDecoder.decode(encodedUriParam, "UTF-8")
        } catch (e: Exception) {
            null
        }

        if (decodedUriString == null) {
            sendNotFound(socket)
            return
        }

        val mediaUri = Uri.parse(decodedUriString)
        val scheme = mediaUri.scheme
        if (scheme != "content" && scheme != "file") {
            Log.w(TAG, "Blocked request for unsupported scheme: $scheme")
            sendForbidden(socket)
            return
        }

        if (scheme == "file") {
            val filePath = mediaUri.path
            if (filePath != null && filePath.contains(context.packageName)) {
                Log.w(TAG, "Blocked attempt to access private app file path: $filePath")
                sendForbidden(socket)
                return
            }
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(mediaUri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening visual file: ${e.message}")
            sendNotFound(socket)
            return
        }

        if (pfd == null) {
            sendNotFound(socket)
            return
        }

        val totalFileLength = pfd.statSize
        val mimeType = context.contentResolver.getType(mediaUri) ?: "video/mp4"

        try {
            val output = socket.getOutputStream()
            val fileInputStream = FileInputStream(pfd.fileDescriptor)

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // Parse range request headers: e.g. bytes=1000-24921 or bytes=1000-
                val rangeValue = rangeHeader.substringAfter("bytes=")
                val parts = rangeValue.split("-")
                val rangeStart = parts[0].toLongOrNull() ?: 0L
                val rangeEnd = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (totalFileLength - 1)
                } else {
                    totalFileLength - 1
                }

                val clampedEnd = rangeEnd.coerceAtMost(totalFileLength - 1)
                val responseContentLength = clampedEnd - rangeStart + 1

                val responseHeader = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $responseContentLength\r\n" +
                        "Content-Range: bytes $rangeStart-$clampedEnd/$totalFileLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                
                output.write(responseHeader.toByteArray())

                if (!isHeadRequest) {
                    var skipped = 0L
                    while (skipped < rangeStart) {
                        val skipAmount = fileInputStream.skip(rangeStart - skipped)
                        if (skipAmount <= 0L) {
                            val tempBuffer = ByteArray(4096)
                            val readAmount = fileInputStream.read(tempBuffer, 0, (rangeStart - skipped).coerceAtMost(4096L).toInt())
                            if (readAmount == -1) break
                            skipped += readAmount
                        } else {
                            skipped += skipAmount
                        }
                    }
                    val buffer = ByteArray(1024 * 32)
                    var bytesRemaining = responseContentLength
                    while (bytesRemaining > 0) {
                        val maxToRead = buffer.size.coerceAtMost(bytesRemaining.toInt())
                        val bytesRead = fileInputStream.read(buffer, 0, maxToRead)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        bytesRemaining -= bytesRead
                    }
                }
                output.flush()
                Log.d(TAG, "Served 206 Partial Content chunks ($rangeStart-$clampedEnd) target: $mimeType")
            } else {
                // Serve full 200 OK stream
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $totalFileLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                
                output.write(responseHeader.toByteArray())

                if (!isHeadRequest) {
                    val buffer = ByteArray(1024 * 32)
                    var readLength: Int
                    while (fileInputStream.read(buffer).also { readLength = it } != -1) {
                        output.write(buffer, 0, readLength)
                    }
                }
                output.flush()
                Log.d(TAG, "Served full 200 OK visual stream target: $mimeType")
            }
            fileInputStream.close()
        } catch (e: Exception) {
            Log.w(TAG, "Socket stream broken or smart TV aborted (Expected on user scrub/seek): ${e.message}")
        } finally {
            try {
                pfd.close()
            } catch (e: Exception) {}
        }
    }

    private fun sendBadRequest(socket: Socket) {
        writeSimpleResponse(socket, "HTTP/1.1 400 Bad Request", "Bad Request")
    }

    private fun sendForbidden(socket: Socket) {
        writeSimpleResponse(socket, "HTTP/1.1 403 Forbidden", "Forbidden: Invalid or missing security token")
    }

    private fun sendNotFound(socket: Socket) {
        writeSimpleResponse(socket, "HTTP/1.1 404 Not Found", "File Not Found")
    }

    private fun sendMethodNotAllowed(socket: Socket) {
        writeSimpleResponse(socket, "HTTP/1.1 405 Method Not Allowed", "Method Not Allowed")
    }

    private fun writeSimpleResponse(socket: Socket, status: String, body: String) {
        try {
            val output = socket.getOutputStream()
            val totalBodyBytes = body.toByteArray()
            val response = "$status\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${totalBodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {}
    }
}
