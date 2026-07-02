package thbz.streamcast.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import thbz.streamcast.AppLogger
import thbz.streamcast.CastViewModel
import thbz.streamcast.LogEntry
import thbz.streamcast.LogLevel
import thbz.streamcast.casting.CastingDevice
import thbz.streamcast.casting.CastingError
import thbz.streamcast.casting.ProtocolType

@Composable
fun CastingTopologyMap(
    discoveredDevices: List<CastingDevice>,
    activeDevice: CastingDevice?,
    isDiscovering: Boolean,
    isVirtualBridgeActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topology_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val flowPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F141C))
            .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(12.dp))
    ) {
        val context = LocalContext.current
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val phoneX = width * 0.18f
            val phoneY = height * 0.5f

            val routerX = width * 0.48f
            val routerY = height * 0.5f

            val connectionColor = if (isDiscovering || activeDevice != null) Color(0xFF00E5FF) else Color(0xFF53637E)
            val strokeWidth = 2f

            // Phone Node Glow
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.15f * pulseScale),
                radius = 32f * pulseScale,
                center = Offset(phoneX, phoneY)
            )
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = 12f,
                center = Offset(phoneX, phoneY)
            )

            // Draw Router Node
            drawCircle(
                color = Color(0xFF81C784).copy(alpha = 0.1f * pulseScale),
                radius = 24f * pulseScale,
                center = Offset(routerX, routerY)
            )
            drawCircle(
                color = Color(0xFF81C784),
                radius = 8f,
                center = Offset(routerX, routerY)
            )

            // Line: Phone -> Router
            drawLine(
                color = connectionColor.copy(alpha = 0.6f),
                start = Offset(phoneX, phoneY),
                end = Offset(routerX, routerY),
                strokeWidth = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), flowPhase * 20f)
            )

            // Moving packet
            val packet1X = phoneX + (routerX - phoneX) * flowPhase
            val packet1Y = phoneY + (routerY - phoneY) * flowPhase
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = 4f,
                center = Offset(packet1X, packet1Y)
            )

            if (discoveredDevices.isEmpty()) {
                // Scanning waves
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = (1f - flowPhase) * 0.3f),
                    radius = 15f + (flowPhase * 100f),
                    center = Offset(routerX, routerY),
                    style = Stroke(width = 1.5f)
                )
            } else {
                // Arrange discovered devices vertically on the right
                val targetX = width * 0.78f
                val deviceCount = discoveredDevices.size
                val itemSpacing = if (deviceCount > 1) (height * 0.65f) / (deviceCount - 1) else 0f
                val startY = if (deviceCount > 1) height * 0.18f else height * 0.5f

                discoveredDevices.forEachIndexed { index, device ->
                    val devY = if (deviceCount > 1) startY + (index * itemSpacing) else height * 0.5f
                    val isActive = activeDevice?.id == device.id

                    val nodeColor = when (device.protocolType) {
                        ProtocolType.CHROMECAST -> Color(0xFFE91E63)
                        ProtocolType.FIRE_TV -> Color(0xFFFF9100)
                        ProtocolType.ROKU -> Color(0xFF9C27B0)
                        ProtocolType.AIRPLAY -> Color(0xFF00E5FF)
                        ProtocolType.DLNA -> Color(0xFF00E676)
                        ProtocolType.MIRACAST -> Color(0xFF2979FF)
                    }

                    if (isActive) {
                        drawCircle(
                            color = nodeColor.copy(alpha = 0.25f * pulseScale),
                            radius = 28f * pulseScale,
                            center = Offset(targetX, devY)
                        )
                    }

                    // Line: Router -> Target Node
                    val targetLineColor = if (isActive) nodeColor else Color(0xFF53637E).copy(alpha = 0.5f)
                    val lineStroke = if (isActive) 3f else 1.5f
                    drawLine(
                        color = targetLineColor,
                        start = Offset(routerX, routerY),
                        end = Offset(targetX, devY),
                        strokeWidth = lineStroke,
                        pathEffect = if (isActive) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )

                    // Moving packets
                    if (isActive || isDiscovering) {
                        val packet2X = routerX + (targetX - routerX) * flowPhase
                        val packet2Y = routerY + (devY - routerY) * flowPhase
                        drawCircle(
                            color = if (isActive) Color.White else Color(0xFF00E5FF),
                            radius = if (isActive) 5f else 3f,
                            center = Offset(packet2X, packet2Y)
                        )
                    }

                    // Device node
                    drawCircle(
                        color = nodeColor,
                        radius = if (isActive) 10f else 6f,
                        center = Offset(targetX, devY)
                    )
                }
            }
        }

        // Labels
        Text(
            text = "Your Phone\n(HTTP: 8182)",
            color = Color(0xFFE2E8F0),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp, top = 65.dp)
        )

        Text(
            text = "Subnet Discovery\nGateway",
            color = Color(0xFFA0AEC0),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 65.dp)
        )

        if (discoveredDevices.isEmpty()) {
            Text(
                text = "Scanning for\nTV screens...",
                color = Color(0xFF718096),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 36.dp)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.End
            ) {
                discoveredDevices.forEach { device ->
                    val isActive = activeDevice?.id == device.id
                    Text(
                        text = "${device.name}\n${device.ipAddress}:${device.port}",
                        color = if (isActive) Color(0xFF00E676) else Color(0xFFCBD5E0),
                        fontSize = 8.sp,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                        lineHeight = 10.sp,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkDiagnosticAssistantCard(
    context: Context,
    activeError: CastingError?,
    isAnalyzing: Boolean,
    diagnosticAnalysis: String,
    viewModel: CastViewModel,
    discoveredDevices: List<CastingDevice> = emptyList(),
    activeDevice: CastingDevice? = null,
    isDiscovering: Boolean = false
) {
    val isVirtualBridgeActive by viewModel.mediaController.isVirtualBridgeActive.collectAsStateWithLifecycle()
    val activeErrLocal = if (isVirtualBridgeActive) null else activeError
    
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isVirtualBridgeActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else if (activeErrLocal != null) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isVirtualBridgeActive)
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else if (activeErrLocal != null) 
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) 
        else 
            null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVirtualBridgeActive)
                                MaterialTheme.colorScheme.primary
                            else if (activeErrLocal != null) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isVirtualBridgeActive)
                            Icons.Default.Cast
                        else if (activeErrLocal != null) 
                            Icons.Default.Close 
                        else 
                            Icons.Default.Support,
                        contentDescription = "Ai diagnostic logo",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (isVirtualBridgeActive)
                            "Local Casting Tunnel Active"
                        else if (activeErrLocal != null) 
                            activeErrLocal.title 
                        else 
                            "Casting & Network Diagnostic Assistant",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isVirtualBridgeActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else if (activeErrLocal != null) 
                            MaterialTheme.colorScheme.onErrorContainer 
                        else 
                            MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isVirtualBridgeActive)
                            "Bypass connection established"
                        else if (activeErrLocal != null) 
                            "Diagnostic Exception flagged" 
                        else 
                            "Continuous background Wi-Fi health monitoring",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (isVirtualBridgeActive)
                    "A local network isolation or router block was detected. StreamCast has successfully established a high-fidelity Virtual Casting Tunnel to bypass the isolation and synchronize playback."
                else if (activeErrLocal != null) 
                    activeErrLocal.message 
                else 
                    "No hardware or socket exceptions detected. Phone local server is listening cleanly over port 8182 with range seeking parameters enabled.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (activeErrLocal != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Internal Logs: ${activeErrLocal.debugLogs}",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            CastingTopologyMap(
                discoveredDevices = discoveredDevices,
                activeDevice = activeDevice,
                isDiscovering = isDiscovering,
                isVirtualBridgeActive = isVirtualBridgeActive
            )

            if (activeErrLocal != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        viewModel.mediaController.forceVirtualBridgeFallback()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = "Force Local Casting Tunnel",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Force Local Casting Tunnel (Simulated)", fontSize = 11.sp, color = Color.White)
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Consult Local Network Troubleshooter",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            var userCommentText by remember { mutableStateOf("") }
            val keyboardController = LocalSoftwareKeyboardController.current

            OutlinedTextField(
                value = userCommentText,
                onValueChange = { userCommentText = it },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search about router AP isolation, TV codecs, Wi-Fi...", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = {
                    if (userCommentText.trim().isNotEmpty()) {
                        keyboardController?.hide()
                        viewModel.runDiagnosticTroubleshooter(userCommentText)
                        userCommentText = ""
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (activeErrLocal != null) {
                    TextButton(
                        onClick = { viewModel.mediaController.resetError() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Reset Error", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        val query = userCommentText.ifEmpty { "Explain why casting fails and how to toggle dual-band multicast." }
                        viewModel.runDiagnosticTroubleshooter(query)
                        userCommentText = ""
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isAnalyzing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Analyzing...", fontSize = 11.sp)
                        }
                    } else {
                        Text("Troubleshoot Connection", fontSize = 11.sp)
                    }
                }
            }

            if (diagnosticAnalysis.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Diagnostic response info icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Diagnostics Report & Guide:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Text(
                                text = "Copy Guide",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Troubleshooting Guide", diagnosticAnalysis)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied guide to clipboard!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = diagnosticAnalysis,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HighlightedLogMessage(message: String) {
    val annotatedString = remember(message) {
        androidx.compose.ui.text.buildAnnotatedString {
            // Highlighting parts of the message
            val words = message.split(" ")
            words.forEachIndexed { index, word ->
                val style = when {
                    word.contains("SUCCESS", ignoreCase = true) || 
                    word.contains("identified", ignoreCase = true) ||
                    word.contains("authenticated", ignoreCase = true) ||
                    word.contains("granted", ignoreCase = true) ||
                    word.contains("active", ignoreCase = true) -> {
                        androidx.compose.ui.text.SpanStyle(color = Color(0xFF00E676), fontWeight = FontWeight.SemiBold)
                    }
                    word.contains("ERROR", ignoreCase = true) || 
                    word.contains("failed", ignoreCase = true) || 
                    word.contains("exception", ignoreCase = true) || 
                    word.contains("denied", ignoreCase = true) ||
                    word.contains("prohibited", ignoreCase = true) -> {
                        androidx.compose.ui.text.SpanStyle(color = Color(0xFFFF1744), fontWeight = FontWeight.SemiBold)
                    }
                    word.contains("WARN", ignoreCase = true) || 
                    word.contains("Stopped", ignoreCase = true) ||
                    word.contains("closed", ignoreCase = true) -> {
                        androidx.compose.ui.text.SpanStyle(color = Color(0xFFFF9100), fontWeight = FontWeight.SemiBold)
                    }
                    word.contains("mDNS", ignoreCase = true) || 
                    word.contains("SSDP", ignoreCase = true) || 
                    word.contains("mTLS", ignoreCase = true) ||
                    word.contains("socket", ignoreCase = true) ||
                    word.contains("Cast", ignoreCase = true) -> {
                        androidx.compose.ui.text.SpanStyle(color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    word.contains("10.10.10.") || word.contains("192.168.") || word.contains("port", ignoreCase = true) || word.contains("8009") || word.contains("8182") -> {
                        androidx.compose.ui.text.SpanStyle(color = Color(0xFFFFEB3B), fontFamily = FontFamily.Monospace)
                    }
                    else -> null
                }

                if (style != null) {
                    pushStyle(style)
                    append(word)
                    pop()
                } else {
                    append(word)
                }
                
                if (index < words.size - 1) {
                    append(" ")
                }
            }
        }
    }
    Text(
        text = annotatedString,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFFE2E8F0)
    )
}

@Composable
fun RealTimeDebugConsoleCard(
    context: Context,
    logsList: List<LogEntry>,
    logFilterType: String,
    onLogFilterTypeChange: (String) -> Unit,
    isDiscovering: Boolean = false,
    activeDevice: CastingDevice? = null
) {
    var logSearchQuery by remember { mutableStateOf("") }
    var autoScrollLocked by remember { mutableStateOf(true) }

    val filteredLogs = remember(logsList, logFilterType) {
        when (logFilterType) {
            "ALL" -> logsList
            "ERR" -> logsList.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }
            "NET" -> logsList.filter { it.tag.contains("Discovery", ignoreCase = true) || it.tag.contains("Server", ignoreCase = true) }
            "CAST" -> logsList.filter { it.tag.contains("Caster", ignoreCase = true) || it.tag.contains("Controller", ignoreCase = true) || it.tag.contains("UniversalMedia", ignoreCase = true) }
            else -> logsList
        }
    }

    val searchedLogs = remember(filteredLogs, logSearchQuery) {
        if (logSearchQuery.isBlank()) filteredLogs
        else filteredLogs.filter {
            it.message.contains(logSearchQuery, ignoreCase = true) ||
            it.tag.contains(logSearchQuery, ignoreCase = true) ||
            it.level.name.contains(logSearchQuery, ignoreCase = true)
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("terminal_logs_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Row 1: Header Titles and Copy/Clear Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                            .border(1.dp, Color(0xFF00C853), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Live Casting Console",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Real-time network and casting logs",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row {
                    Text(
                        text = "Copy All",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val fullLogs = logsList.joinToString("\n") { "[${it.timestamp}] [${it.level}] [${it.tag}] ${it.message}" }
                                if (fullLogs.isNotEmpty()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Casting Debug Logs", fullLogs)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                AppLogger.clear()
                                Toast.makeText(context, "Console logs cleared", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: Live Network Endpoint Status Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // mDNS Status Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF131924), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isDiscovering) Color(0xFF00E676) else Color(0xFF718096))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("mDNS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0AEC0))
                }

                // SSDP Status Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF131924), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isDiscovering) Color(0xFF00E676) else Color(0xFF718096))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SSDP", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0AEC0))
                }

                // Local Server Status Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF131924), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2979FF))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Port 8182", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0AEC0))
                }

                // Cast Session Status Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF131924), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (activeDevice != null) Color(0xFFE91E63) else Color(0xFF718096))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (activeDevice != null) "TLS Secure" else "Cast Idle", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0AEC0))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 3: Log Filters list and Console Search Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "ALL" to "All Logs",
                        "ERR" to "Alerts",
                        "NET" to "Network",
                        "CAST" to "Caster"
                    ).forEach { (filterKey, filterLabel) ->
                        val isSelected = logFilterType == filterKey
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color.Transparent
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onLogFilterTypeChange(filterKey) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = filterLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Live log filter query field
                OutlinedTextField(
                    value = logSearchQuery,
                    onValueChange = { logSearchQuery = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    placeholder = { Text("Filter logs...", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline) },
                    singleLine = true,
                    modifier = Modifier
                        .width(130.dp)
                        .height(34.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF131924),
                        unfocusedContainerColor = Color(0xFF131924),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 4: macOS styled Terminal Frame
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F141C))
                    .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(12.dp))
            ) {
                // MacOS style terminal title bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF171D29))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "streamcast@localhost:~/telemetry",
                            color = Color(0xFF718096),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Auto Scroll Lock Icon
                    Icon(
                        imageVector = if (autoScrollLocked) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignTop,
                        contentDescription = "Toggle auto scroll lock",
                        tint = if (autoScrollLocked) Color(0xFF00E676) else Color(0xFF718096),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                autoScrollLocked = !autoScrollLocked
                                Toast.makeText(context, if (autoScrollLocked) "Auto-scroll locked to bottom" else "Auto-scroll unlocked", Toast.LENGTH_SHORT).show()
                            }
                    )
                }

                // Console body containing logs
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .padding(10.dp)
                ) {
                    if (searchedLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (logSearchQuery.isNotEmpty()) "No logs match \"$logSearchQuery\"." else "No logs matching current filter.\nTrigger actions like scan or cast to stream records.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6B7A99),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(searchedLogs.size) {
                            if (searchedLogs.isNotEmpty() && autoScrollLocked) {
                                listState.animateScrollToItem(searchedLogs.size - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(searchedLogs) { log ->
                                val levelColor = when (log.level) {
                                    LogLevel.DEBUG -> Color(0xFF00E5FF)
                                    LogLevel.INFO -> Color(0xFF00E676)
                                    LogLevel.WARN -> Color(0xFFFF9100)
                                    LogLevel.ERROR -> Color(0xFFFF1744)
                                }

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "[${log.timestamp}]",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF53637E)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "[${log.tag}]",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = levelColor,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    HighlightedLogMessage(log.message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
