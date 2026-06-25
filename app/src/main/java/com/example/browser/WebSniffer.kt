package com.example.browser

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        val snippetName = if (title.trim().isEmpty() || title == "undefined") "Discovered Video Stream" else title
        onVideoFound(SniffedVideo(url = url, title = snippetName))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSnifferBrowser(
    onVideoSelectedForCasting: (SniffedVideo) -> Unit,
    initialUrl: String = "https://archive.org/details/classic_cartoons",
    onBookmarkAdded: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var currentWebUrl by remember { mutableStateOf(initialUrl) }
    
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotEmpty() && initialUrl != currentWebUrl) {
            currentWebUrl = initialUrl
            urlText = initialUrl
        }
    }

    val sniffedVideos = remember { mutableStateListOf<SniffedVideo>() }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
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
                        var destinationUrl = urlText.trim()
                        if (!destinationUrl.startsWith("http://") && !destinationUrl.startsWith("https://")) {
                            destinationUrl = "https://$destinationUrl"
                        }
                        currentWebUrl = destinationUrl
                        urlText = destinationUrl
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
                    var destinationUrl = urlText.trim()
                    if (!destinationUrl.startsWith("http://") && !destinationUrl.startsWith("https://")) {
                        destinationUrl = "https://$destinationUrl"
                    }
                    currentWebUrl = destinationUrl
                    urlText = destinationUrl
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Go")
                }

                if (onBookmarkAdded != null) {
                    IconButton(onClick = {
                        val currentUrl = webViewInstance?.url ?: currentWebUrl
                        val currentTitle = webViewInstance?.title ?: "Web Page"
                        onBookmarkAdded(currentUrl, currentTitle)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Bookmark this web page",
                            tint = Color(0xFFFFD700)
                        )
                    }
                }
            }
        }

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
                    .heightIn(max = 140.dp)
                    .padding(8.dp)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sniffedVideos) { video ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onVideoSelectedForCasting(video) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Tv,
                                contentDescription = "Cast stream",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = video.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = video.url,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onVideoSelectedForCasting(video) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Cast", fontSize = 11.sp)
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
                    }
                    
                    addJavascriptInterface(
                        CastWebBridge { video ->
                            // Enforce unique links, avoid duplicate logs on polling
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
                                val lowerUrl = reqUrl.lowercase()
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
                            }
                            // Inject early
                            view?.evaluateJavascript(snifferJs, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Inject again on load completeness to hit delayed elements
                            view?.evaluateJavascript(snifferJs, null)
                        }
                    }
                    loadUrl(currentWebUrl)
                    webViewInstance = this
                }
            },
            update = { view ->
                // Check if target URL changed externally
                if (view.url != currentWebUrl) {
                    view.loadUrl(currentWebUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
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
