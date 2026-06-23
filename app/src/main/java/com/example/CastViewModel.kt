package com.example

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiSupportEngine
import com.example.browser.SniffedVideo
import com.example.casting.CastingDevice
import com.example.casting.DiscoveryEngine
import com.example.casting.ProtocolType
import com.example.casting.UniversalMediaController
import com.example.database.AppDatabase
import com.example.database.BookmarkedUrl
import com.example.database.CastHistoryItem
import com.example.database.CastRepository
import com.example.server.LocalHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CastViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "CastViewModel"

    private val db = AppDatabase.getDatabase(application)
    private val repository = CastRepository(db.castDao())

    // Engine Instances
    val discoveryEngine = DiscoveryEngine(application)
    val httpServer = LocalHttpServer(application)
    val mediaController = UniversalMediaController()
    private val geminiEngine = GeminiSupportEngine()

    // UI Reactive States
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _selectedLocalMediaUri = MutableStateFlow<Uri?>(null)
    val selectedLocalMediaUri: StateFlow<Uri?> = _selectedLocalMediaUri.asStateFlow()

    private val _localMediaName = MutableStateFlow("")
    val localMediaName: StateFlow<String> = _localMediaName.asStateFlow()

    private val _isAiRunning = MutableStateFlow(false)
    val isAiRunning: StateFlow<Boolean> = _isAiRunning.asStateFlow()

    private val _geminiAnalysis = MutableStateFlow("")
    val geminiAnalysis: StateFlow<String> = _geminiAnalysis.asStateFlow()

    // Room Database Observables
    val bookmarks: StateFlow<List<BookmarkedUrl>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val castHistory: StateFlow<List<CastHistoryItem>> = repository.castHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Automatically start local HTTP server in the background
        try {
            httpServer.start()
            Log.d(TAG, "Local HTTP server initialized successfully on start.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local HTTP server on VM init: ${e.message}")
        }
    }

    fun startDeviceScanning() {
        _isDiscovering.value = true
        discoveryEngine.startDiscovery()
    }

    fun stopDeviceScanning() {
        _isDiscovering.value = false
        discoveryEngine.stopDiscovery()
    }

    fun selectLocalFile(uri: Uri, name: String) {
        _selectedLocalMediaUri.value = uri
        _localMediaName.value = name
    }

    fun castLocalFile(device: CastingDevice) {
        val uri = _selectedLocalMediaUri.value ?: return
        val name = _localMediaName.value.ifEmpty { "Local Gallery Streaming Video" }
        
        // Generate the streamable range-supporting local URL
        val streamingUrl = httpServer.getLocalServerUrl(uri)
        Log.d(TAG, "Local file stream requested from address: $streamingUrl")

        // Store casting action into room history
        viewModelScope.launch {
            repository.insertHistoryItem(
                CastHistoryItem(title = name, url = streamingUrl)
            )
        }

        mediaController.castMedia(device, streamingUrl, name)
    }

    fun castWebVideo(device: CastingDevice, video: SniffedVideo) {
        // Store casting action into room history
        viewModelScope.launch {
            repository.insertHistoryItem(
                CastHistoryItem(title = video.title, url = video.url)
            )
        }
        mediaController.castMedia(device, video.url, video.title)
    }

    fun bookmarkWebPage(url: String, title: String) {
        viewModelScope.launch {
            repository.insertBookmark(
                BookmarkedUrl(url = url, title = title)
            )
        }
    }

    fun removeBookmark(bookmark: BookmarkedUrl) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun removeHistoryItem(id: Int) {
        viewModelScope.launch {
            repository.deleteHistoryById(id)
        }
    }

    fun runAiTroubleshooter(userMessage: String) {
        _isAiRunning.value = true
        _geminiAnalysis.value = "Analyzing local Wi-Fi logs and evaluating AP isolation levels. Model is compiling troubleshooting response..."

        val activeError = mediaController.error.value
        val diagnosticStr = buildString {
            appendLine("--- CASTING DIAGNOSTIC TRACE ---")
            appendLine("Local Active WiFi IP: ${httpServer.getLocalServerUrl(Uri.EMPTY).substringAfter("http://").substringBefore(":")}")
            appendLine("Casting State: ${mediaController.state.value}")
            appendLine("Connected Caster Target: ${mediaController.activeDevice.value?.name ?: "None"}")
            appendLine("Connected Protocol Type: ${mediaController.activeDevice.value?.protocolType ?: "N/A"}")
            appendLine("Streaming Video URL: ${mediaController.currentUrl.value}")
            appendLine("Current Scan Discovery Count: ${discoveryEngine.devices.value.size}")
            if (activeError != null) {
                appendLine("Active Caster Error Title: ${activeError.title}")
                appendLine("Error Codec Description: ${activeError.message}")
                appendLine("System Stacktrace Logs: ${activeError.debugLogs}")
            } else {
                appendLine("Active Error: No hardware connection errors reported in past minutes.")
            }
        }

        viewModelScope.launch {
            try {
                val adviceResult = geminiEngine.getTroubleshootingGuide(userMessage, diagnosticStr)
                _geminiAnalysis.value = adviceResult
            } catch (e: Exception) {
                _geminiAnalysis.value = "Failed to compile AI troubleshooting advice: ${e.localizedMessage}"
            } finally {
                _isAiRunning.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            httpServer.stop()
            discoveryEngine.stopDiscovery()
            mediaController.stopCasting()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up server on VM cleared: ${e.message}")
        }
    }
}
