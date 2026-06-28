package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppLogger
import com.example.LogEntry
import com.example.LogLevel
import com.example.casting.CastingDevice
import com.example.casting.CastingState
import com.example.casting.ProtocolType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DiagnosticHudOverlay(
    isDiscovering: Boolean,
    discoveredDevices: List<CastingDevice>,
    currentCastingState: CastingState,
    activeCastDevice: CastingDevice?,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    // Glower animation for the trigger FAB
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val floatTranslation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floating"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // FLOATING ACTION TRIGGER BUTTON (Glows active when scanning)
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp, end = 16.dp)
                .offset(y = floatTranslation.dp)
        ) {
            Box {
                // Pulse background
                if (isDiscovering || currentCastingState == CastingState.CONNECTING) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(
                                if (currentCastingState == CastingState.CONNECTING) 
                                    Color(0xFFFFB300).copy(alpha = pulseAlpha)
                                else 
                                    MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                            )
                    )
                }
                
                LargeFloatingActionButton(
                    onClick = { isExpanded = true },
                    shape = CircleShape,
                    containerColor = when (currentCastingState) {
                        CastingState.CONNECTING -> Color(0xFFFFB300)
                        CastingState.PLAYING -> Color(0xFF00C853)
                        CastingState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = when (currentCastingState) {
                        CastingState.CONNECTING, CastingState.PLAYING, CastingState.ERROR -> Color.White
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .align(Alignment.Center)
                        .testTag("diagnostic_hud_trigger")
                ) {
                    Icon(
                        imageVector = if (currentCastingState == CastingState.ERROR) 
                            Icons.Default.ReportProblem 
                        else 
                            Icons.Default.Troubleshoot,
                        contentDescription = "Open Stream Diagnostic HUD",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // FULL SCREEN OVERLAY PANEL
        AnimatedVisibility(
            visible = isExpanded,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {} // Keep clicks contained when expanded
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 24.dp)
                ) {
                    // Header Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Troubleshoot,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stream Diagnostic HUD",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Real-time multicast discovery & pairing analysis",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                            )
                        }
                        
                        IconButton(
                            onClick = { isExpanded = false },
                            modifier = Modifier.testTag("diagnostic_hud_close")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close HUD",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Horizontal split or column-based scrollable modules
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                    ) {
                        // SECTION 1: SYSTEM STATE & DISCOVERY MATRIX
                        DiscoveryStateVizBlock(
                            isDiscovering = isDiscovering,
                            discoveredDevices = discoveredDevices,
                            currentCastingState = currentCastingState,
                            activeCastDevice = activeCastDevice,
                            onTriggerScan = onTriggerScan
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // SECTION 2: CONNECTION HANDSHAKING EXPLORER (Auto Troubleshooting Wizard)
                        HandshakeTroubleshooterBlock()

                        Spacer(modifier = Modifier.height(16.dp))

                        // SECTION 3: REAL-TIME CONSOLE LOGGER TERMINAL
                        RealTimeTerminalBlock()

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveryStateVizBlock(
    isDiscovering: Boolean,
    discoveredDevices: List<CastingDevice>,
    currentCastingState: CastingState,
    activeCastDevice: CastingDevice?,
    onTriggerScan: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Live Multicast & Socket Discovery State",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Protocols Scanned Progress / Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProtocolBadge(name = "mDNS", active = isDiscovering, description = "Google Cast / AirPlay")
                ProtocolBadge(name = "SSDP", active = isDiscovering, description = "Roku / DLNA")
                ProtocolBadge(name = "Subnet", active = isDiscovering, description = "Local IP Ping Sweep")
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subnet Scan IP Range feedback
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NetworkWifi,
                    contentDescription = null,
                    tint = if (isDiscovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Subnet Scan Segment",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isDiscovering) "Polling subnets 10.211.154.* dynamically..." else "IDLE • Subnet caches active",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Devices categorization list
            Text(
                text = "Discovered Media Renderers (${discoveredDevices.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            if (discoveredDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No devices found yet. Tap scan to trigger network broadcast.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discoveredDevices.forEach { dev ->
                        val isCurrent = activeCastDevice?.id == dev.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = when (dev.protocolType) {
                                    ProtocolType.CHROMECAST -> Icons.Default.Cast
                                    ProtocolType.ROKU -> Icons.Default.Tv
                                    ProtocolType.FIRE_TV -> Icons.Default.ConnectedTv
                                    ProtocolType.DLNA -> Icons.Default.SettingsEthernet
                                    else -> Icons.Default.DevicesOther
                                },
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dev.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${dev.ipAddress}:${dev.port} • Protocol: ${dev.protocolType.name}",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (isCurrent) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = currentCastingState.name,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProtocolBadge(name: String, active: Boolean, description: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp, 
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = alpha) 
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        ),
        modifier = Modifier.width(100.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun HandshakeTroubleshooterBlock() {
    val logs by AppLogger.logs.collectAsState()
    
    // Look for connection failure patterns inside the current session logs
    val errorLog = remember(logs) {
        logs.findLast { it.level == LogLevel.ERROR && (it.message.contains("failed", ignoreCase = true) || it.message.contains("Exception", ignoreCase = true) || it.message.contains("Not Found", ignoreCase = true) || it.message.contains("refused", ignoreCase = true)) }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connection Handshake Analysis",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (errorLog == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF00C853),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "No pairing failures detected in active session. Casting handshakes are clean.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column {
                    // Highlight the failure
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "LATEST EXCEPTION ENCOUNTERED:",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorLog.message,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Parse the error dynamically to recommend solutions
                    val recommendations = remember(errorLog) {
                        val msg = errorLog.message.lowercase()
                        when {
                            msg.contains("8009") || msg.contains("eofexception") -> {
                                listOf(
                                    "Port 8009 is a secure Chromecast TLS socket. Attempting HTTP on it drops connections instantly.",
                                    "Fix: Bypass 8009 HTTP calls. StreamCast automatically re-routes traffic cleanly to standard DIAL launcher port 8008.",
                                    "For legacy Chromecasts/Rokus, make sure that Google Play Services has not limited local network scans."
                                )
                            }
                            msg.contains("timeout") || msg.contains("connection refused") -> {
                                listOf(
                                    "The target device closed or refused connection handshakes.",
                                    "AP ISOLATION: Confirm your Wi-Fi router does not have Peer-to-Peer access disabled.",
                                    "SSID Split: Ensure both the TV and your phone are on the exact same Wi-Fi frequency (e.g. both on 2.4Ghz)."
                                )
                            }
                            msg.contains("codec") || msg.contains("not supported") -> {
                                listOf(
                                    "Selected media stream is incompatible with the receiver's hardware decoder.",
                                    "Fix: Use the 'Web Sniffer' browser to select an alternative MP4 (H.264) or HLS stream.",
                                    "Tip: Rokus and Fire TVs struggle with MKV or raw high-bitrate WebM codecs. Prefer MP4."
                                )
                            }
                            else -> {
                                listOf(
                                    "Verify that the TV's cast/DLNA discovery settings are set to 'Always Allow'.",
                                    "Turn the TV off and on again (unplug power cord for 10 seconds to flush its SSDP cache).",
                                    "Disable active VPNs or dynamic proxy profiles on your phone, which intercept local loopbacks."
                                )
                            }
                        }
                    }

                    Text(
                        text = "Suggested Troubleshooting Remedies:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    recommendations.forEach { recommendation ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = "• ",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = recommendation,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Row: Quick simulations
            Text(
                text = "Simulation Sandbox Actions",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        AppLogger.e(
                            "UniversalMediaController", 
                            "Failed to cast to Family Room TV: Port 8009 cleartext protocol rejected (java.io.EOFException: \\n not found in Stream)."
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Simulate 8009 EOF Error", fontSize = 9.sp)
                }

                OutlinedButton(
                    onClick = {
                        AppLogger.e(
                            "UniversalMediaController", 
                            "Connection timeout: Failed to hand-shake with Roku Stick (10.211.154.98:8060). Network is unreachable."
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Simulate Timeout", fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
fun RealTimeTerminalBlock() {
    val logs by AppLogger.logs.collectAsState()
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Filtered logs
    val filteredLogs = remember(logs, selectedFilter, searchQuery) {
        logs.filter { entry ->
            val matchesLevel = selectedFilter == null || entry.level == selectedFilter
            val matchesSearch = searchQuery.isEmpty() || 
                entry.message.contains(searchQuery, ignoreCase = true) || 
                entry.tag.contains(searchQuery, ignoreCase = true)
            matchesLevel && matchesSearch
        }
    }

    // Auto scroll to bottom when logs size change
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            lazyListState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A) // Sleek slate console background
        ),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .fillMaxWidth()
            .height(350.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Log panel header controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8), // Cyber Cyan
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PAIRING & DISCOVERY SHELL",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                
                // Clear button
                IconButton(
                    onClick = { AppLogger.clear() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear logs",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Filter Tabs & Search
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LogLevelFilterBadge(label = "ALL", active = selectedFilter == null, activeColor = Color(0xFF38BDF8)) {
                    selectedFilter = null
                }
                LogLevelFilterBadge(label = "DEBUG", active = selectedFilter == LogLevel.DEBUG, activeColor = Color.LightGray) {
                    selectedFilter = LogLevel.DEBUG
                }
                LogLevelFilterBadge(label = "INFO", active = selectedFilter == LogLevel.INFO, activeColor = Color(0xFF4ADE80)) {
                    selectedFilter = LogLevel.INFO
                }
                LogLevelFilterBadge(label = "WARN", active = selectedFilter == LogLevel.WARN, activeColor = Color(0xFFFBBF24)) {
                    selectedFilter = LogLevel.WARN
                }
                LogLevelFilterBadge(label = "ERR", active = selectedFilter == LogLevel.ERROR, activeColor = Color(0xFFF87171)) {
                    selectedFilter = LogLevel.ERROR
                }
            }

            // Quick live filter text search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontFamily = FontFamily.Monospace),
                singleLine = true,
                placeholder = { Text("Filter logs by tag or word...", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    focusedBorderColor = Color(0xFF334155),
                    unfocusedBorderColor = Color(0xFF1E293B)
                )
            )

            // Dynamic Terminal List
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    item {
                        Text(
                            text = "No logs match current filters.",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(filteredLogs) { log ->
                        LogLineItem(log)
                    }
                }
            }
        }
    }
}

@Composable
fun LogLevelFilterBadge(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) activeColor.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (active) activeColor else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeColor else Color.LightGray,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun LogLineItem(log: LogEntry) {
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF94A3B8) // Slate Gray
        LogLevel.INFO -> Color(0xFF38BDF8)  // Cyber Cyan
        LogLevel.WARN -> Color(0xFFFBBF24)  // Amber Orange
        LogLevel.ERROR -> Color(0xFFF87171) // Critical Red
    }

    val levelString = when (log.level) {
        LogLevel.DEBUG -> "DEBUG"
        LogLevel.INFO -> "INFO "
        LogLevel.WARN -> "WARN "
        LogLevel.ERROR -> "ERROR"
    }

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "[${log.timestamp}]",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = "[$levelString]",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = "[${log.tag}]",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFA5B4FC), // Lavender tag
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = log.message,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = if (log.level == LogLevel.ERROR) Color(0xFFFECDD3) else Color(0xFFE2E8F0),
            modifier = Modifier.weight(1f)
        )
    }
}
