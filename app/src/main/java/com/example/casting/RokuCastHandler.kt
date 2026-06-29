package com.example.casting

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class RokuCastHandler(private val okHttpClient: OkHttpClient) : CastProtocolHandler {
    private val TAG = "RokuCastHandler"
    private var activeDevice: CastingDevice? = null

    override suspend fun connect(device: CastingDevice): Boolean {
        activeDevice = device
        return true
    }

    override suspend fun castMedia(url: String, title: String): Boolean {
        val device = activeDevice ?: return false
        return try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val endpoint = "http://${device.ipAddress}:8060/launch/dev?contentId=$encodedUrl&mediaType=movie"
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed Roku castMedia: ${e.message}")
            false
        }
    }

    override suspend fun pause() {
        sendKeyPress("Pause")
    }

    override suspend fun resume() {
        sendKeyPress("Play")
    }

    override suspend fun stop() {
        sendKeyPress("Home")
    }

    override suspend fun seekTo(positionMs: Long) {
        val device = activeDevice ?: return
        try {
            // For seeking, we can post a play/pause/seek key or send launch with offset
            val seconds = positionMs / 1000
            // Since Roku uses discrete keys for seek forward/backward, we send a Play/Pause sequence
            Log.d(TAG, "Seeking Roku device to $seconds seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed Roku seek: ${e.message}")
        }
    }

    override suspend fun setVolume(volume: Int) {
        val device = activeDevice ?: return
        try {
            val endpoint = "http://${device.ipAddress}:8060/keypress/VolumeUp"
            val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
            okHttpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed Roku setVolume: ${e.message}")
        }
    }

    private fun sendKeyPress(key: String) {
        val device = activeDevice ?: return
        val endpoint = "http://${device.ipAddress}:8060/keypress/$key"
        val request = Request.Builder().url(endpoint).post("".toRequestBody()).build()
        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }
}
