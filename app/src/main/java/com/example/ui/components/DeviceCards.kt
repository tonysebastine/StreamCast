package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CastViewModel
import com.example.casting.CastingDevice
import com.example.casting.LocalNetworkPermissionHelper
import com.example.casting.ProtocolType

import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor

@Composable
fun ScanningRadarAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarScan")
    val progress1 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius1"
    )
    val progress2 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius2"
    )
    val progress3 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius3"
    )

    Box(
        modifier = modifier.size(150.dp),
        contentAlignment = Alignment.Center
    ) {
        // Radar waves
        listOf(progress1, progress2, progress3).forEach { progressState ->
            val progress = progressState.value
            Box(
                modifier = Modifier
                    .size((150 * progress).dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = (1f - progress) * 0.15f)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = (1f - progress) * 0.4f),
                        shape = CircleShape
                    )
            )
        }
        
        // Center icon
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                        )
                    )
                )
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = "Searching",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun DeviceRowItem(
    device: CastingDevice,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val brandColor = when (device.protocolType) {
        ProtocolType.MIRACAST -> ComposeColor(0xFFE91E63)
        ProtocolType.CHROMECAST -> ComposeColor(0xFF4285F4)
        ProtocolType.ROKU -> ComposeColor(0xFF8A2BE2)
        ProtocolType.FIRE_TV -> ComposeColor(0xFFFF9900)
        ProtocolType.AIRPLAY -> ComposeColor(0xFF007AFF)
        ProtocolType.DLNA -> ComposeColor(0xFF4CAF50)
    }
    
    val brandLabel = when (device.protocolType) {
        ProtocolType.CHROMECAST -> "Google Cast"
        ProtocolType.ROKU -> "Roku EPC"
        ProtocolType.FIRE_TV -> "Fire TV"
        ProtocolType.AIRPLAY -> "Apple AirPlay"
        ProtocolType.DLNA -> "DLNA Renderer"
        ProtocolType.MIRACAST -> "Miracast (Wi-Fi Direct)"
    }

    val animatedBorderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val animatedBorderWidth = if (isSelected) 2.dp else 1.dp

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    width = animatedBorderWidth,
                    color = animatedBorderColor
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable {
                onSelect()
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (device.protocolType) {
                    ProtocolType.MIRACAST -> Icons.Default.Cast
                    ProtocolType.CHROMECAST -> Icons.Default.Cast
                    ProtocolType.ROKU -> Icons.Default.Tv
                    ProtocolType.FIRE_TV -> Icons.Default.Tv
                    ProtocolType.AIRPLAY -> Icons.Default.Airplay
                    ProtocolType.DLNA -> Icons.Default.Router
                },
                contentDescription = "Receiver device emblem",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else brandColor,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = brandLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                    Text(
                        text = "${device.ipAddress}:${device.port}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (device.protocolType == ProtocolType.FIRE_TV) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "💡 Tip: If connection fails, launch free 'AirScreen' or 'Cast to TV' app on Fire TV.",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = brandColor
                    )
                }
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected Caster target indicator",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun DeviceDiscoveryEngineCard(
    context: Context,
    viewModel: CastViewModel,
    isDiscovering: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Device Discovery Engine",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isDiscovering) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00C853).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "SCANNING SUBNET",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Color(0xFF00C853)
                            )
                        }
                    } else {
                        Text(
                            "Scanner offline. Search inactive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                Button(
                    onClick = {
                        if (isDiscovering) {
                            viewModel.stopDeviceScanning()
                        } else {
                            if (!LocalNetworkPermissionHelper.hasPermission(context)) {
                                (context as? Activity)?.let { activity ->
                                    LocalNetworkPermissionHelper.requestPermission(activity, 101)
                                }
                            } else {
                                viewModel.startDeviceScanning()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDiscovering) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("scan_toggle_button")
                ) {
                    Text(
                        text = if (isDiscovering) "Stop Scan" else "Search TVs",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDiscovering) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "StreamCast simultaneously interrogates mDNS (Chromecast / AirPlay standard multicast packages) and SSDP (Roku, DLNA simple service protocols) to map any living room, kitchen, or office network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun MiracastMirroringCard(
    context: Context
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = "Miracast screen mirroring",
                        tint = Color(0xFFE91E63),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Miracast / Wi-Fi Display",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Low latency direct screen sharing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS")
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, "Direct Miracast Settings not supported on this device version.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE91E63)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("miracast_settings_button")
                ) {
                    Text("Mirror", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ManualDeviceCard(
    context: Context,
    manualDevices: androidx.compose.runtime.snapshots.SnapshotStateList<CastingDevice>,
    selectedCastingTarget: CastingDevice?,
    onSelectDevice: (CastingDevice) -> Unit
) {
    var ipInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf(ProtocolType.CHROMECAST) }
    var isProtocolDropdownExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Add TV Receiver Manually",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("IP Address (e.g., 192.168.1.100)", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Custom Name (e.g., Living Room Roku)", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Button(
                        onClick = { isProtocolDropdownExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Protocol: ${selectedProtocol.name}", fontSize = 10.sp)
                    }
                    DropdownMenu(
                        expanded = isProtocolDropdownExpanded,
                        onDismissRequest = { isProtocolDropdownExpanded = false }
                    ) {
                        ProtocolType.values().forEach { protocol ->
                            DropdownMenuItem(
                                text = { Text(protocol.name) },
                                onClick = {
                                    selectedProtocol = protocol
                                    isProtocolDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        val ip = ipInput.trim()
                        val name = nameInput.trim().ifEmpty { "Manual Target (${selectedProtocol.name})" }
                        if (ip.isNotEmpty()) {
                            val newDevice = CastingDevice(
                                id = "manual_${System.currentTimeMillis()}",
                                name = name,
                                ipAddress = ip,
                                port = when (selectedProtocol) {
                                    ProtocolType.CHROMECAST -> 8009
                                    ProtocolType.ROKU -> 8060
                                    ProtocolType.AIRPLAY -> 7000
                                    ProtocolType.DLNA -> 49152
                                    ProtocolType.MIRACAST -> 7236
                                    ProtocolType.FIRE_TV -> 8009
                                },
                                protocolType = selectedProtocol
                            )
                            manualDevices.add(newDevice)
                            onSelectDevice(newDevice)
                            ipInput = ""
                            nameInput = ""
                            Toast.makeText(context, "Added $name successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "IP Address cannot be blank.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("add_manual_device_button")
                ) {
                    Text("Add Receiver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
