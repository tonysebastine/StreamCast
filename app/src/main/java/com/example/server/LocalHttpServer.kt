package com.example.server

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
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
    private val threadPool = Executors.newCachedThreadPool()
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Local HTTP server initialized on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    threadPool.execute { handleConnection(socket) }
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
    }

    fun getLocalServerUrl(contentUri: Uri): String {
        val ip = getIpAddress() ?: "127.0.0.1"
        val encodedUri = java.net.URLEncoder.encode(contentUri.toString(), "UTF-8")
        return "http://$ip:$port/stream?uri=$encodedUri"
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val ipAddress = connectionInfo.ipAddress
            if (ipAddress == 0) {
                null
            } else {
                Formatter.formatIpAddress(ipAddress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve local IP address: ${e.message}")
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
        // Query param parsing: Expect /stream?uri=content%3A%2F%2F...
        val decodedUriString = if (path.startsWith("/stream?uri=")) {
            try {
                java.net.URLDecoder.decode(path.substringAfter("/stream?uri="), "UTF-8")
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        if (decodedUriString == null) {
            sendNotFound(socket)
            return
        }

        val mediaUri = Uri.parse(decodedUriString)
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
                    fileInputStream.skip(rangeStart)
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
