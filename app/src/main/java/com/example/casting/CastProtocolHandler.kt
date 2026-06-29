package com.example.casting

interface CastProtocolHandler {
    suspend fun connect(device: CastingDevice): Boolean
    suspend fun castMedia(url: String, title: String): Boolean
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(volume: Int)
}
