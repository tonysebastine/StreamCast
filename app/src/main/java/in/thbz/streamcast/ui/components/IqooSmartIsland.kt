package in.thbz.streamcast.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import in.thbz.streamcast.casting.CastingDevice
import in.thbz.streamcast.casting.CastingState
import in.thbz.streamcast.casting.ProtocolType
import in.thbz.streamcast.formatDuration

@Composable
fun IqooSmartIsland(
    isEnabled: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    state: CastingState,
    device: CastingDevice?,
    title: String,
    position: Long,
    duration: Long,
    bufferPercentage: Int,
    volume: Int,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onStop: () -> Unit,
    isDiscovering: Boolean,
    discoveredCount: Int
) {
    if (!isEnabled) return

    val brandColor = when (device?.protocolType) {
        ProtocolType.MIRACAST -> Color(0xFFE91E63)
        ProtocolType.CHROMECAST -> Color(0xFF4285F4)
        ProtocolType.ROKU -> Color(0xFF8A2BE2)
        ProtocolType.FIRE_TV -> Color(0xFFFF9900)
        ProtocolType.AIRPLAY -> Color(0xFF007AFF)
        ProtocolType.DLNA -> Color(0xFF4CAF50)
        null -> MaterialTheme.colorScheme.primary
    }

    val isCasting = state != CastingState.IDLE
    val isPlaying = state == CastingState.PLAYING

    // Pulse animation for scanner
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Layout configuration with bouncy springs
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .statusBarsPadding(), // Ensures it positions gracefully relative to status bar cutouts
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = if (isExpanded) 360.dp else 260.dp)
                .fillMaxWidth(if (isExpanded) 0.92f else 0.65f)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
                .border(
                    width = 1.5.dp,
                    color = if (isExpanded) brandColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(if (isExpanded) 24.dp else 22.dp)
                )
                .clip(RoundedCornerShape(if (isExpanded) 24.dp else 22.dp))
                .clickable { onToggleExpand() },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F0F12) // Glossy premium iQOO Black
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            if (isExpanded) {
                // Expanded Controller layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(brandColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (device?.protocolType) {
                                        ProtocolType.CHROMECAST -> Icons.Default.Cast
                                        ProtocolType.AIRPLAY -> Icons.Default.Airplay
                                        ProtocolType.DLNA -> Icons.Default.Router
                                        ProtocolType.MIRACAST -> Icons.Default.Cast
                                        else -> Icons.Default.Tv
                                    },
                                    contentDescription = "Active target icon",
                                    tint = brandColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OriginOS Smart Island",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        // Close button to minimize
                        IconButton(
                            onClick = { onToggleExpand() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Minimize island",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isCasting) {
                        // Cast details
                        Text(
                            text = title.ifEmpty { "Active Media Stream" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = device?.name ?: "Unknown Receiver",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = brandColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Progress slider / Timeline (Enhanced double-layer buffer bar)
                        val durationFloat = duration.toFloat().coerceAtLeast(1f)
                        val positionFloat = position.toFloat().coerceIn(0f, durationFloat)
                        val progressFraction = positionFloat / durationFloat
                        val bufferFraction = (bufferPercentage / 100f).coerceIn(0f, 1f)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDuration(position),
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                // Background base track
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.12f))
                                ) {
                                    // Buffer track
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(bufferFraction)
                                            .fillMaxHeight()
                                            .background(brandColor.copy(alpha = 0.35f))
                                    )
                                    // Active playback track
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progressFraction)
                                            .fillMaxHeight()
                                            .background(brandColor)
                                    )
                                }

                                Slider(
                                    value = positionFloat,
                                    onValueChange = { onSeek(it.toLong()) },
                                    valueRange = 0f..durationFloat,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = brandColor,
                                        activeTrackColor = Color.Transparent,
                                        inactiveTrackColor = Color.Transparent,
                                        activeTickColor = Color.Transparent,
                                        inactiveTickColor = Color.Transparent
                                    )
                                )
                            }

                            Text(
                                text = formatDuration(duration),
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Compact Volume Control Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Island Volume",
                                tint = brandColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Slider(
                                value = volume.toFloat(),
                                onValueChange = { onVolumeChange(it.toInt()) },
                                valueRange = 0f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(18.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = brandColor,
                                    activeTrackColor = brandColor,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                            Text(
                                text = "$volume%",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Equalizer in expanded card
                            CasterEqualizer(isPlaying = isPlaying, accentColor = brandColor)

                            // Play / Pause button
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(brandColor, shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Toggle play pause",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Disconnect Button
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.1f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop stream",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        // Not casting state (Search status or idle)
                        Text(
                            text = if (isDiscovering) "Casting Network Active" else "Casting System Idle",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = if (isDiscovering) "Scanning for Chromecast, AirPlay, DLNA..." else "Tapped to search for surrounding smart displays",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isDiscovering) Color(0xFF00FFCC) else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$discoveredCount receivers discovered",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { onToggleExpand() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Minimize",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            } else {
                // Compact Capsule Pill layout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCasting) {
                        // Left: Icon + Label
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = when (device?.protocolType) {
                                    ProtocolType.CHROMECAST -> Icons.Default.Cast
                                    ProtocolType.AIRPLAY -> Icons.Default.Airplay
                                    ProtocolType.DLNA -> Icons.Default.Router
                                    ProtocolType.MIRACAST -> Icons.Default.Cast
                                    else -> Icons.Default.Tv
                                },
                                contentDescription = null,
                                tint = brandColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isPlaying) "Playing..." else "Paused",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right: Visualizer Equalizer
                        CasterEqualizer(isPlaying = isPlaying, accentColor = brandColor)
                    } else {
                        // Scanning / Discovery Compact Mode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isDiscovering) Color(0xFF00FFCC).copy(alpha = pulseAlpha)
                                        else Color.White.copy(alpha = 0.3f)
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isDiscovering) "Searching Displays ($discoveredCount)..." else "Casting System Idle",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CasterEqualizer(isPlaying: Boolean, accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val heightFactor1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val heightFactor2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val heightFactor3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val heightFactor4 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 18.dp, height = 14.dp)) {
        val barWidth = 3.dp.toPx()
        val spacing = 2.dp.toPx()
        val maxHeight = size.height

        val h1 = maxHeight * (if (isPlaying) heightFactor1 else 0.3f)
        drawRoundRect(
            color = accentColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, maxHeight - h1),
            size = androidx.compose.ui.geometry.Size(barWidth, h1),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )

        val h2 = maxHeight * (if (isPlaying) heightFactor2 else 0.4f)
        drawRoundRect(
            color = accentColor,
            topLeft = androidx.compose.ui.geometry.Offset(barWidth + spacing, maxHeight - h2),
            size = androidx.compose.ui.geometry.Size(barWidth, h2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )

        val h3 = maxHeight * (if (isPlaying) heightFactor3 else 0.2f)
        drawRoundRect(
            color = accentColor,
            topLeft = androidx.compose.ui.geometry.Offset((barWidth + spacing) * 2, maxHeight - h3),
            size = androidx.compose.ui.geometry.Size(barWidth, h3),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )

        val h4 = maxHeight * (if (isPlaying) heightFactor4 else 0.3f)
        drawRoundRect(
            color = accentColor,
            topLeft = androidx.compose.ui.geometry.Offset((barWidth + spacing) * 3, maxHeight - h4),
            size = androidx.compose.ui.geometry.Size(barWidth, h4),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )
    }
}

@Composable
fun IqooSmartIslandConfigCard(
    isSmartIslandEnabled: Boolean,
    onSmartIslandEnabledChange: (Boolean) -> Unit,
    isSmartIslandExpanded: Boolean,
    onSmartIslandExpandedChange: (Boolean) -> Unit,
    onTriggerFlashTest: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F0F12)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Airplay,
                            contentDescription = "iQOO Island",
                            tint = Color(0xFF00FFCC),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "iQOO Smart Island",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Interactive bouncy capsule controller",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Switch(
                    checked = isSmartIslandEnabled,
                    onCheckedChange = onSmartIslandEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FFCC),
                        checkedTrackColor = Color(0xFF00FFCC).copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Inspired by OriginOS / FuntouchOS, this floating capsule rests at the top-center of the screen. It expands dynamically when clicked or during active cast sessions.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isSmartIslandEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSmartIslandExpandedChange(!isSmartIslandExpanded) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0F0F12),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isSmartIslandExpanded) "Minimize Island" else "Expand Island",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onTriggerFlashTest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = "Flash 3s Test",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
