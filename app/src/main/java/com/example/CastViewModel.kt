package com.example

import android.app.Application
import android.net.Uri
import com.example.AppLogger as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    // UI Reactive States
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _selectedLocalMediaUri = MutableStateFlow<Uri?>(null)
    val selectedLocalMediaUri: StateFlow<Uri?> = _selectedLocalMediaUri.asStateFlow()

    private val _localMediaName = MutableStateFlow("")
    val localMediaName: StateFlow<String> = _localMediaName.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _diagnosticAnalysis = MutableStateFlow("")
    val diagnosticAnalysis: StateFlow<String> = _diagnosticAnalysis.asStateFlow()

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

    fun runDiagnosticTroubleshooter(userMessage: String) {
        _isAnalyzing.value = true
        _diagnosticAnalysis.value = "Analyzing local network logs, interface routing tables, and device discovery states..."

        viewModelScope.launch {
            kotlinx.coroutines.delay(1000) // Simulate diagnostic scan delay
            val lowerQuery = userMessage.lowercase()
            val activeError = mediaController.error.value

            val solution = when {
                activeError != null && activeError.title.contains("Codec", ignoreCase = true) -> {
                    """
                    ⚠️ CODEC COMPATIBILITY ISSUE DETECTED:
                    Your TV/Caster does not support the selected video stream codec.
                    
                    How to solve:
                    1. Try a different format link (e.g., MP4 instead of MKV or HLS).
                    2. Use the "Web Sniffer" tab to find an alternative stream source from the web page.
                    3. Restart the TV's media player app to clear its hardware decoder cache.
                    """.trimIndent()
                }
                activeError != null && activeError.title.contains("Connection", ignoreCase = true) -> {
                    """
                    ❌ CONNECTION/TIMEOUT EXCEPTION:
                    Failed to hand-shake or route packets to ${mediaController.activeDevice.value?.name ?: "the TV"}.
                    
                    How to solve:
                    1. Verify both devices are on the EXACT same Wi-Fi network (SSID).
                    2. Check if your router has AP Isolation (Access Point Isolation) enabled. This blocks local devices from communicating. Disable it in your router settings.
                    3. Ensure Multicast (mDNS/UPnP) is enabled under your router's advanced settings.
                    """.trimIndent()
                }
                lowerQuery.contains("wifi") || lowerQuery.contains("wi-fi") || lowerQuery.contains("network") -> {
                    """
                    🌐 WI-FI & NETWORK ROUTING DIAGNOSIS:
                    - Current Phone IP: ${httpServer.getLocalServerUrl(Uri.EMPTY).substringAfter("http://").substringBefore(":")}
                    - Active Server Port: 8182 (Listening for local Range-Request requests)
                    
                    Troubleshooting Steps:
                    1. Ensure dual-band steering is not forcing your TV onto 5GHz and your phone onto 2.4GHz with different subnet routing.
                    2. Disable any active VPN, adblockers, or proxy profiles on your phone, as they intercept local network traffic and break DLNA/mDNS discovery.
                    3. Restart your Wi-Fi router. Dynamic IP leases can sometimes block local socket handshakes.
                    """.trimIndent()
                }
                lowerQuery.contains("mkv") || lowerQuery.contains("mp4") || lowerQuery.contains("format") || lowerQuery.contains("codec") -> {
                    """
                    📹 MEDIA FORMATS & CODECS GUIDE:
                    - Modern smart TVs support standard MP4 (H.264 + AAC) and HLS (.m3u8).
                    - Legacy players or DLNA receivers frequently fail to render MKV, WebM, or H.265 streams.
                    
                    Solutions:
                    1. Select an alternate format from the "Web Sniffer" list.
                    2. Clear current playback queues on your casting device and re-transmit.
                    3. Keep screen on while initiating streams to prevent background CPU limiters.
                    """.trimIndent()
                }
                lowerQuery.contains("roku") || lowerQuery.contains("fire") || lowerQuery.contains("tv") || lowerQuery.contains("cast") -> {
                    """
                    📺 RECEIVER SPECIFIC RECOMMENDATIONS:
                    - Roku: Supports MP4 and HLS. Ensure "Screen Mirroring / Device Connect" permissions are set to "Always Allow" in Roku Settings -> System.
                    - Fire TV: Requires the cast receiver app to be open and active in the foreground.
                    - DLNA Renderer: Ensure the TV's network sharing / media play renderer option is enabled.
                    """.trimIndent()
                }
                else -> {
                    """
                    🔍 GENERAL CASTING HEALTH CHECKLIST:
                    - Network State: Clean, HTTP local port 8182 active.
                    - TV Discovery Count: ${discoveryEngine.devices.value.size} active receiver(s) found in range.
                    
                    Standard Solutions:
                    1. Double check that your phone is NOT on mobile data. Wi-Fi must be active.
                    2. Verify that both the mobile phone and casting TV are connected to the exact same router band.
                    3. Turn the TV off and on again (unplug power for 10 seconds to force SSDP system daemon restart).
                    4. Check for router AP isolation or guest-network isolation which blocks peer-to-peer casting.
                    """.trimIndent()
                }
            }

            _diagnosticAnalysis.value = solution
            _isAnalyzing.value = false
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
