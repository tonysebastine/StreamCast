package in.thbz.streamcast.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Support
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import in.thbz.streamcast.AppLogger
import in.thbz.streamcast.CastViewModel
import in.thbz.streamcast.LogEntry
import in.thbz.streamcast.LogLevel
import in.thbz.streamcast.casting.CastingError

@Composable
fun NetworkDiagnosticAssistantCard(
    context: Context,
    activeError: CastingError?,
    isAnalyzing: Boolean,
    diagnosticAnalysis: String,
    viewModel: CastViewModel
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
fun RealTimeDebugConsoleCard(
    context: Context,
    logsList: List<LogEntry>,
    logFilterType: String,
    onLogFilterTypeChange: (String) -> Unit
) {
    val filteredLogs = remember(logsList, logFilterType) {
        when (logFilterType) {
            "ALL" -> logsList
            "ERR" -> logsList.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }
            "NET" -> logsList.filter { it.tag.contains("Discovery", ignoreCase = true) || it.tag.contains("Server", ignoreCase = true) }
            "CAST" -> logsList.filter { it.tag.contains("Caster", ignoreCase = true) || it.tag.contains("Controller", ignoreCase = true) || it.tag.contains("UniversalMedia", ignoreCase = true) }
            else -> logsList
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

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F141C))
                    .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No logs matching current filter.\nTrigger actions like scan or cast to stream records.",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF6B7A99),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(filteredLogs.size) {
                        if (filteredLogs.isNotEmpty()) {
                            listState.animateScrollToItem(filteredLogs.size - 1)
                        }
                    }
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredLogs) { log ->
                            val color = when (log.level) {
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
                                    color = color,
                                    modifier = Modifier.widthIn(max = 100.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = log.message,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
