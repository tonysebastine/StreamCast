package in.thbz.streamcast.browser

import android.annotation.SuppressLint
import android.content.Context
import in.thbz.streamcast.AppLogger as Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class SniffedVideo(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CastWebBridge(private val onVideoFound: (SniffedVideo) -> Unit) {
    @JavascriptInterface
    fun onVideoSniffed(url: String, title: String) {
        val lowerUrl = url.lowercase().trim()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return // ignore non-web formats like file:// and content://
        }
        val snippetName = if (title.trim().isEmpty() || title == "undefined") "Discovered Video Stream" else title
        onVideoFound(SniffedVideo(url = url, title = snippetName))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSnifferBrowser(
    onVideoSelectedForCasting: (SniffedVideo) -> Unit,
    initialUrl: String = "about:home",
    onBookmarkAdded: ((String, String) -> Unit)? = null,
    onUrlChanged: ((String) -> Unit)? = null,
    bookmarks: List<in.thbz.streamcast.database.BookmarkedUrl> = emptyList(),
    onDeleteBookmark: ((in.thbz.streamcast.database.BookmarkedUrl) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val browserPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    var homepageUrl by remember { mutableStateOf(browserPrefs.getString("homepage_url", "about:home") ?: "about:home") }
    var searchEngine by remember { mutableStateOf(browserPrefs.getString("search_engine", "https://www.google.com/search?q=") ?: "https://www.google.com/search?q=") }
    
    var showSettingsDialog by remember { mutableStateOf(false) }

    var urlText by remember { mutableStateOf(if (initialUrl == "about:home") "" else initialUrl) }
    var currentWebUrl by remember { mutableStateOf(initialUrl) }
    
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotEmpty() && initialUrl != currentWebUrl) {
            currentWebUrl = initialUrl
            urlText = if (initialUrl == "about:home") "" else initialUrl
        }
    }

    val sniffedVideos = remember { mutableStateListOf<SniffedVideo>() }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(currentWebUrl) {
        if (currentWebUrl != "about:home") {
            webViewInstance?.let { webView ->
                if (webView.url != null && webView.url != currentWebUrl) {
                    webView.loadUrl(currentWebUrl)
                }
            }
        }
    }

    if (showSettingsDialog) {
        BrowserSettingsDialog(
            currentHomepage = homepageUrl,
            onHomepageChanged = { newHome ->
                homepageUrl = newHome
                browserPrefs.edit().putString("homepage_url", newHome).apply()
                Toast.makeText(context, "Homepage updated!", Toast.LENGTH_SHORT).show()
            },
            currentSearchEngine = searchEngine,
            onSearchEngineChanged = { newEngine ->
                searchEngine = newEngine
                browserPrefs.edit().putString("search_engine", newEngine).apply()
                Toast.makeText(context, "Search engine updated!", Toast.LENGTH_SHORT).show()
            },
            onClearCache = {
                try {
                    webViewInstance?.clearCache(true)
                    webViewInstance?.clearHistory()
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.removeAllCookies { success ->
                        Log.d("WebSniffer", "Cookies cleared: $success")
                    }
                    cookieManager.flush()
                    Toast.makeText(context, "Browser Cache & Cookies Cleared!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("WebSniffer", "Error clearing cache: ${e.message}")
                }
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    val keyboardController = LocalSoftwareKeyboardController.current

    val quickStations = remember {
        listOf(
            Pair("Archive Cartoons", "https://archive.org/details/classic_cartoons"),
            Pair("Mux HLS Test", "https://test-streams.mux.dev/"),
            Pair("Pexels Free Videos", "https://www.pexels.com/videos/"),
            Pair("NASA Archive Space", "https://archive.org/details/nasaaudiovideo"),
            Pair("Big Buck Bunny HLS", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        )
    }

    val snifferJs = """
        (function() {
            if (window.XCastSnifferActive) return;
            window.XCastSnifferActive = true;
            console.log('XCast Sniffer Active');

            // Override document.createElement to intercept video components compiled at runtime
            const originalCreateElement = document.createElement;
            document.createElement = function(tagName) {
                const element = originalCreateElement.apply(this, arguments);
                if (tagName.toLowerCase() === 'video') {
                    observeVideoElement(element);
                }
                return element;
            };

            const detectedUrls = new Set();
            function triggerNativeCallback(rawUrl, pageTitle) {
                if (!rawUrl || typeof rawUrl !== 'string') return;
                let absoluteUrl = rawUrl;
                try {
                    absoluteUrl = new URL(rawUrl, document.baseURI).href;
                } catch(e) {}
                
                if (detectedUrls.has(absoluteUrl)) return;

                // Match typical targets like mp4, mkv, HLS m3u8 playlists or general static video paths
                const isMedia = absoluteUrl.match(/\.(mp4|mkv|m3u8|webm|ogv|mp3|ogg|ircast|iracst|anycast)(\?|$)/i) ||
                                absoluteUrl.includes('.m3u8') ||
                                absoluteUrl.includes('.ircast') ||
                                absoluteUrl.includes('.iracst') ||
                                absoluteUrl.includes('.anycast') ||
                                absoluteUrl.includes('googlevideo.com/videoplayback') ||
                                absoluteUrl.includes('manifest.m3u8') ||
                                absoluteUrl.includes('master.m3u8');
                
                if (isMedia) {
                    detectedUrls.add(absoluteUrl);
                    if (window.AndroidCastBridge) {
                        window.AndroidCastBridge.onVideoSniffed(absoluteUrl, pageTitle || document.title);
                    }
                }
            }

            function observeVideoElement(video) {
                // Monitor modifications to direct src attributes
                const srcObserver = new MutationObserver((mutations) => {
                    mutations.forEach((m) => {
                        if (m.type === 'attributes' && m.attributeName === 'src') {
                            triggerNativeCallback(video.src, document.title);
                        }
                    });
                });
                srcObserver.observe(video, { attributes: true, attributeFilter: ['src'] });

                // Proxy setters for video.src = '...'
                const prototype = HTMLMediaElement.prototype;
                const descriptor = Object.getOwnPropertyDescriptor(prototype, 'src');
                if (descriptor) {
                    Object.defineProperty(video, 'src', {
                        get: descriptor.get,
                        set: function(val) {
                            descriptor.set.call(this, val);
                            triggerNativeCallback(val, document.title);
                        }
                    });
                }

                // Monitor nested source tag additions
                const sourceObserver = new MutationObserver(() => {
                    const sources = video.getElementsByTagName('source');
                    for (let i = 0; i < sources.length; i++) {
                        if (sources[i].src) {
                            triggerNativeCallback(sources[i].src, document.title);
                        }
                    }
                });
                sourceObserver.observe(video, { childList: true, subtree: true });
                
                // Read immediate values if loaded
                if (video.src) triggerNativeCallback(video.src, document.title);
                const sourceTags = video.getElementsByTagName('source');
                for (let i = 0; i < sourceTags.length; i++) {
                    if (sourceTags[i].src) triggerNativeCallback(sourceTags[i].src, document.title);
                }
            }

            // Continuous polling scan for dynamic DOM pages
            function scanEntireDOM() {
                const videoTags = document.getElementsByTagName('video');
                for (let v of videoTags) {
                    observeVideoElement(v);
                }
                const anchorTags = document.getElementsByTagName('a');
                for (let a of anchorTags) {
                    if (a.href) {
                        triggerNativeCallback(a.href, a.innerText || "Link Stream");
                    }
                }
            }

            setInterval(scanEntireDOM, 3500);
            scanEntireDOM();
        })();
    """.trimIndent()

    Column(modifier = modifier.fillMaxSize()) {
        // Browser navigation row
        Card(
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                IconButton(onClick = {
                    if (webViewInstance?.canGoBack() == true) {
                        webViewInstance?.goBack()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }

                IconButton(onClick = {
                    sniffedVideos.clear() // Clear list of links for new page
                    webViewInstance?.reload()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload")
                }

                IconButton(onClick = {
                    sniffedVideos.clear()
                    currentWebUrl = homepageUrl
                    urlText = if (homepageUrl == "about:home") "" else homepageUrl
                    if (homepageUrl != "about:home") {
                        webViewInstance?.loadUrl(homepageUrl)
                    }
                }) {
                    Icon(Icons.Default.Home, contentDescription = "Home", tint = MaterialTheme.colorScheme.primary)
                }

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        val destinationUrl = formatSearchOrUrlWithEngine(urlText.trim(), searchEngine)
                        currentWebUrl = destinationUrl
                        urlText = if (destinationUrl == "about:home") "" else destinationUrl
                        if (destinationUrl != "about:home") {
                            webViewInstance?.loadUrl(destinationUrl)
                        }
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = true,
                    placeholder = { Text("Search or input URL", fontSize = 12.sp) }
                )

                IconButton(onClick = {
                    keyboardController?.hide()
                    val destinationUrl = formatSearchOrUrlWithEngine(urlText.trim(), searchEngine)
                    currentWebUrl = destinationUrl
                    urlText = if (destinationUrl == "about:home") "" else destinationUrl
                    if (destinationUrl != "about:home") {
                        webViewInstance?.loadUrl(destinationUrl)
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Go")
                }

                if (onBookmarkAdded != null) {
                    IconButton(onClick = {
                        val currentUrl = webViewInstance?.url ?: currentWebUrl
                        val currentTitle = webViewInstance?.title ?: "Web Page"
                        if (currentUrl != "about:home") {
                            onBookmarkAdded(currentUrl, currentTitle)
                        } else {
                            Toast.makeText(context, "Cannot bookmark homepage dashboard", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Bookmark this web page",
                            tint = Color(0xFFFFD700)
                        )
                    }
                }

                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Browser Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (currentWebUrl == "about:home") {
            BrowserHomepageDashboard(
                bookmarks = bookmarks,
                onSelectUrl = { url ->
                    currentWebUrl = url
                    urlText = url
                    webViewInstance?.loadUrl(url)
                },
                onDeleteBookmark = onDeleteBookmark,
                onOpenSettings = { showSettingsDialog = true },
                quickStations = quickStations,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(vertical = 6.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Wifi, 
                        contentDescription = "Sniff icon", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Sniffed video media URLs: ${sniffedVideos.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (sniffedVideos.isNotEmpty()) {
                    Text(
                        text = "Clear Links",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { sniffedVideos.clear() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Live Sniffed Video Drawer / Ribbon
            if (sniffedVideos.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(8.dp)
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sniffedVideos) { video ->
                            OutlinedCard(
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val format = getFormatLabel(video.url)
                                            FormatBadge(format)
                                            Text(
                                                text = video.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = video.url,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = { onVideoSelectedForCasting(video) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        modifier = Modifier.height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Tv,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Cast", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .padding(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Text(
                        text = "Navigate & play video to sniff streams automatically, or use a test station:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 12.dp)
                    ) {
                        items(quickStations) { (name, url) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable {
                                        currentWebUrl = url
                                        urlText = url
                                        webViewInstance?.loadUrl(url)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Webview embedder
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            allowFileAccess = false
                            allowContentAccess = false
                        }
                        
                        addJavascriptInterface(
                            CastWebBridge { video ->
                                post {
                                    if (sniffedVideos.none { it.url == video.url }) {
                                        sniffedVideos.add(video)
                                    }
                                }
                            },
                            "AndroidCastBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false // Open inside the same in-app Web view
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                request?.url?.toString()?.let { reqUrl ->
                                    val lowerUrl = reqUrl.lowercase().trim()
                                    if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    val isStream = lowerUrl.contains(".m3u8") || 
                                                   lowerUrl.contains(".mp4") || 
                                                   lowerUrl.contains(".mpd") || 
                                                   lowerUrl.contains(".webm") || 
                                                   lowerUrl.contains(".mkv") || 
                                                   lowerUrl.contains(".ircast") || 
                                                   lowerUrl.contains(".iracst") || 
                                                   lowerUrl.contains(".anycast") || 
                                                   lowerUrl.contains("googlevideo.com/videoplayback") ||
                                                   lowerUrl.contains("/stream-media/")

                                    if (isStream) {
                                        view?.post {
                                            val currentTitle = view.title?.ifEmpty { null } ?: "Network Video Stream"
                                            if (sniffedVideos.none { it.url == reqUrl }) {
                                                sniffedVideos.add(
                                                    SniffedVideo(
                                                        url = reqUrl,
                                                        title = "$currentTitle (${getFormatLabel(reqUrl)})"
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                url?.let {
                                    urlText = it
                                    currentWebUrl = it
                                    onUrlChanged?.invoke(it)
                                }
                                view?.evaluateJavascript(snifferJs, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(snifferJs, null)
                            }
                        }
                        loadUrl(currentWebUrl)
                        webViewInstance = this
                    }
                },
                update = { view ->
                    // Handled reactively by LaunchedEffect(currentWebUrl)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private fun getFormatLabel(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> "HLS .m3u8"
        lower.contains(".ircast") -> "IRCast .ircast"
        lower.contains(".iracst") -> "IRCast .iracst"
        lower.contains(".anycast") -> "AnyCast Stream"
        lower.contains(".mp4") -> "MP4"
        lower.contains(".mpd") -> "DASH .mpd"
        lower.contains(".webm") -> "WebM"
        lower.contains(".mkv") -> "MKV"
        lower.contains("googlevideo") -> "Video Playback"
        else -> "Stream"
    }
}

@Composable
fun FormatBadge(format: String) {
    val (bgColor, textColor) = when {
        format.contains("HLS", ignoreCase = true) || format.contains("m3u8", ignoreCase = true) -> Pair(Color(0xFFE3F2FD), Color(0xFF1E88E5)) // Blue
        format.contains("MP4", ignoreCase = true) -> Pair(Color(0xFFE8F5E9), Color(0xFF43A047)) // Green
        format.contains("MP3", ignoreCase = true) || format.contains("audio", ignoreCase = true) -> Pair(Color(0xFFFFF3E0), Color(0xFFFB8C00)) // Orange
        format.contains("DASH", ignoreCase = true) || format.contains("mpd", ignoreCase = true) -> Pair(Color(0xFFF3E5F5), Color(0xFF8E24AA)) // Purple
        else -> Pair(Color(0xFFECEFF1), Color(0xFF546E7A)) // Grey
    }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = format.replace(" .m3u8", "").replace(" .mpd", "").uppercase(),
            color = textColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BrowserHomepageDashboard(
    bookmarks: List<in.thbz.streamcast.database.BookmarkedUrl>,
    onSelectUrl: (String) -> Unit,
    onDeleteBookmark: ((in.thbz.streamcast.database.BookmarkedUrl) -> Unit)?,
    onOpenSettings: () -> Unit,
    quickStations: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "StreamCast Web Video",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Surf any webpage & stream movies to your TV",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Search Input
        var searchInput by remember { mutableStateOf("") }
        val keyboardController = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            placeholder = { Text("Search or type web URL...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                if (searchInput.isNotEmpty()) {
                    IconButton(onClick = {
                        val query = searchInput.trim()
                        if (query.isNotEmpty()) {
                            keyboardController?.hide()
                            onSelectUrl(formatSearchOrUrl(query))
                        }
                    }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Go", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = {
                val query = searchInput.trim()
                if (query.isNotEmpty()) {
                    keyboardController?.hide()
                    onSelectUrl(formatSearchOrUrl(query))
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Saved Bookmarks Dashboard Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "My Bookmarked Stations",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Browser Settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved bookmarks yet.\nStar pages while browsing to pin them here!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bookmarks.forEach { bm ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                    .clickable { onSelectUrl(bm.url) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = bm.title.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = bm.url,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (onDeleteBookmark != null) {
                                    IconButton(
                                        onClick = { onDeleteBookmark(bm) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete bookmark",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Stations
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Recommended Stations",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                quickStations.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (name, url) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { onSelectUrl(url) }
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = url.replace("https://", "").replace("http://", "").split("/").firstOrNull() ?: "",
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.outline,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun BrowserSettingsDialog(
    currentHomepage: String,
    onHomepageChanged: (String) -> Unit,
    currentSearchEngine: String,
    onSearchEngineChanged: (String) -> Unit,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    var homepageInput by remember { mutableStateOf(currentHomepage) }
    var expandedDropdown by remember { mutableStateOf(false) }
    val searchEngineOptions = listOf(
        Pair("Google", "https://www.google.com/search?q="),
        Pair("Bing", "https://www.bing.com/search?q="),
        Pair("DuckDuckGo", "https://duckduckgo.com/?q="),
        Pair("Yahoo", "https://search.yahoo.com/search?p=")
    )
    val selectedEngineName = searchEngineOptions.firstOrNull { it.second == currentSearchEngine }?.first ?: "Google"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Browser Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Homepage URL",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = homepageInput,
                        onValueChange = { homepageInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. about:home or website URL") }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set to 'about:home' for the custom Compose Dashboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = { homepageInput = "about:home" },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Use Dashboard", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = { homepageInput = "https://archive.org/details/classic_cartoons" },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Use Archive.org", fontSize = 11.sp)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column {
                    Text(
                        text = "Default Search Engine",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Box {
                        OutlinedButton(
                            onClick = { expandedDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedEngineName, color = MaterialTheme.colorScheme.onSurface)
                                Text("▼", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        ) {
                            searchEngineOptions.forEach { (name, url) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onSearchEngineChanged(url)
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column {
                    Text(
                        text = "Maintenance",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Button(
                        onClick = {
                            onClearCache()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Cache & Cookies")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalHomepage = homepageInput.trim()
                    onHomepageChanged(if (finalHomepage.isEmpty()) "about:home" else finalHomepage)
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatSearchOrUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "https://$trimmed"
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed
    }
    return try {
        "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
    } catch (e: Exception) {
        "https://www.google.com/search?q=$trimmed"
    }
}

private fun formatSearchOrUrlWithEngine(input: String, engine: String): String {
    val trimmed = input.trim()
    if (trimmed == "about:home") return "about:home"
    if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "https://$trimmed"
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed
    }
    return try {
        engine + URLEncoder.encode(trimmed, "UTF-8")
    } catch (e: Exception) {
        engine + trimmed
    }
}
