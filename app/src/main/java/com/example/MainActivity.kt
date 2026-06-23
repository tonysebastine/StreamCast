package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Support
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.browser.SniffedVideo
import com.example.browser.WebSnifferBrowser
import com.example.casting.CastingDevice
import com.example.casting.CastingState
import com.example.casting.ProtocolType
import com.example.database.BookmarkedUrl
import com.example.database.CastHistoryItem
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UniversalCastDashboard()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalCastDashboard(
    viewModel: CastViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSmartIslandEnabled by remember { mutableStateOf(true) }
    var isSmartIslandExpanded by remember { mutableStateOf(false) }

    // Bind VM outputs
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveryEngine.devices.collectAsStateWithLifecycle()
    val selectedLocalMediaUri by viewModel.selectedLocalMediaUri.collectAsStateWithLifecycle()
    val localMediaName by viewModel.localMediaName.collectAsStateWithLifecycle()
    
    val currentCastingState by viewModel.mediaController.state.collectAsStateWithLifecycle()
    val activeCastDevice by viewModel.mediaController.activeDevice.collectAsStateWithLifecycle()
    val castTitle by viewModel.mediaController.currentTitle.collectAsStateWithLifecycle()
    val castUrl by viewModel.mediaController.currentUrl.collectAsStateWithLifecycle()
    val castPosition by viewModel.mediaController.currentPosition.collectAsStateWithLifecycle()
    val castDuration by viewModel.mediaController.totalDuration.collectAsStateWithLifecycle()
    val castVolume by viewModel.mediaController.volume.collectAsStateWithLifecycle()
    val activeError by viewModel.mediaController.error.collectAsStateWithLifecycle()

    LaunchedEffect(currentCastingState) {
        if (currentCastingState != CastingState.IDLE) {
            isSmartIslandExpanded = true
            kotlinx.coroutines.delay(3500)
            isSmartIslandExpanded = false
        }
    }
    
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val castHistory by viewModel.castHistory.collectAsStateWithLifecycle()
    
    var activeBrowserUrl by remember { mutableStateOf("https://archive.org/details/classic_cartoons") }
    var searchQuery by remember { mutableStateOf("") }
    val manualDevices = remember { mutableStateListOf<CastingDevice>() }
    
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val diagnosticAnalysis by viewModel.diagnosticAnalysis.collectAsStateWithLifecycle()

    // Add virtual/mock devices to the lists to allow user exploration when smart TVs are absent
    val simulatedRokuDemo = remember {
        CastingDevice(
            id = "demo_roku_virtual",
            name = "Living Room Roku TV (Demo)",
            ipAddress = "192.168.1.42",
            port = 8060,
            protocolType = ProtocolType.ROKU,
            location = "http://192.168.1.42:8060/"
        )
    }

    val simulatedChromecastDemo = remember {
        CastingDevice(
            id = "demo_chromecast_virtual",
            name = "All-Floor Chromecast (Demo)",
            ipAddress = "192.168.1.88",
            port = 8009,
            protocolType = ProtocolType.CHROMECAST
        )
    }

    val allDevicesToShow = remember(discoveredDevices, manualDevices) {
        val merged = discoveredDevices.toMutableList()
        merged.addAll(manualDevices)
        // inject simulated entries for rich local experience
        if (merged.none { it.id == "demo_roku_virtual" }) merged.add(simulatedRokuDemo)
        if (merged.none { it.id == "demo_chromecast_virtual" }) merged.add(simulatedChromecastDemo)
        merged
    }

    var selectedCastingTarget: CastingDevice? by remember { mutableStateOf(null) }

    // Visual media picker contract launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                val name = getFileName(context, uri) ?: "Local Stream Asset.mp4"
                viewModel.selectLocalFile(uri, name)
                Toast.makeText(context, "Local video loaded: $name", Toast.LENGTH_SHORT).show()
                // Auto route to play dialog or suggest casting
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Universal Caster Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Universal Cast",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                },
                actions = {
                    // Quick casting health chip indicator
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentCastingState != CastingState.IDLE) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (currentCastingState) {
                                            CastingState.IDLE -> MaterialTheme.colorScheme.outline
                                            CastingState.CONNECTING -> Color(0xFFFFB300)
                                            CastingState.PLAYING -> Color(0xFF00C853)
                                            CastingState.PAUSED -> Color(0xFF2979FF)
                                            CastingState.ERROR -> MaterialTheme.colorScheme.error
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentCastingState.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Screen switching tab selector
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("app_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = "Devices tab") },
                    label = { Text("Scanner", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Language, contentDescription = "Browser tab") },
                    label = { Text("Web Video", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Remote & Help Tab") },
                    label = { Text("Remote & Help", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .widthIn(max = 600.dp) // Responsive fluid centering constraints
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: Active devices scanning and local media select
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .testTag("scroller_container"),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            // Section: Network Scanner Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        "SCANNING SUBNET",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.sp,
                                                        color = MaterialTheme.colorScheme.primary
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
                                                    viewModel.startDeviceScanning()
                                                }
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isDiscovering) 
                                                    MaterialTheme.colorScheme.error 
                                                else 
                                                    MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            if (isDiscovering) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        color = Color.White,
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Stop", fontSize = 12.sp)
                                                }
                                            } else {
                                                Text("Start Scan", fontSize = 12.sp)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (isDiscovering) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Text(
                                            text = "Scanner inactive. Start scan to discover smart TVs automatically.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            // Section Header: Devices
                            Text(
                                text = "Discovered Receivers (${allDevicesToShow.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        // Devices list items
                        items(allDevicesToShow) { device ->
                            val isSelected = selectedCastingTarget?.id == device.id
                            
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline
                                        ),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable {
                                        selectedCastingTarget = device
                                        Toast.makeText(context, "${device.name} selected as caster destination.", Toast.LENGTH_SHORT).show()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) 
                                        MaterialTheme.colorScheme.tertiary 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val brandColor = when (device.protocolType) {
                                        ProtocolType.CHROMECAST -> Color(0xFF4285F4)
                                        ProtocolType.ROKU -> Color(0xFF8A2BE2)
                                        ProtocolType.FIRE_TV -> Color(0xFFFF9900)
                                        ProtocolType.AIRPLAY -> Color(0xFF007AFF)
                                        ProtocolType.DLNA -> Color(0xFF4CAF50)
                                    }
                                    
                                    val brandLabel = when (device.protocolType) {
                                        ProtocolType.CHROMECAST -> "Google Cast"
                                        ProtocolType.ROKU -> "Roku EPC"
                                        ProtocolType.FIRE_TV -> "Fire TV"
                                        ProtocolType.AIRPLAY -> "Apple AirPlay"
                                        ProtocolType.DLNA -> "DLNA Renderer"
                                    }

                                    Icon(
                                        imageVector = when (device.protocolType) {
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

                        item {
                            var showManualForm by remember { mutableStateOf(false) }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showManualForm = !showManualForm },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Router,
                                                contentDescription = "Manual IP target configuration",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                "Cannot find TV? Add Device Manually",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Icon(
                                            if (showManualForm) Icons.Default.Close else Icons.Default.Search,
                                            contentDescription = "Toggle Manual Form",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    if (showManualForm) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        var customName by remember { mutableStateOf("") }
                                        var customIp by remember { mutableStateOf("") }
                                        var customPortStr by remember { mutableStateOf("8009") }
                                        var selectedProtocol by remember { mutableStateOf(ProtocolType.CHROMECAST) }

                                        OutlinedTextField(
                                            value = customName,
                                            onValueChange = { customName = it },
                                            label = { Text("Display Name (e.g. My Bedroom TV)", fontSize = 11.sp) },
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                            singleLine = true
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = customIp,
                                                onValueChange = { customIp = it },
                                                label = { Text("IP Address (e.g. 192.168.1.15)", fontSize = 11.sp) },
                                                modifier = Modifier.weight(1.5f),
                                                textStyle = MaterialTheme.typography.bodyMedium,
                                                singleLine = true
                                            )

                                            OutlinedTextField(
                                                value = customPortStr,
                                                onValueChange = { customPortStr = it },
                                                label = { Text("Port", fontSize = 11.sp) },
                                                modifier = Modifier.weight(0.8f),
                                                textStyle = MaterialTheme.typography.bodyMedium,
                                                singleLine = true
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text("Protocol Type:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            ProtocolType.entries.forEach { protocol ->
                                                val isSelected = selectedProtocol == protocol
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                                        .clickable { selectedProtocol = protocol }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = protocol.name,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Button(
                                            onClick = {
                                                if (customIp.trim().isEmpty()) {
                                                    Toast.makeText(context, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                val portNum = customPortStr.toIntOrNull() ?: 8009
                                                val nameStr = customName.ifEmpty { "Manual Target (${customIp.trim()})" }
                                                val customDev = CastingDevice(
                                                    id = "manual_" + System.currentTimeMillis(),
                                                    name = nameStr,
                                                    ipAddress = customIp.trim(),
                                                    port = portNum,
                                                    protocolType = selectedProtocol,
                                                    location = "http://${customIp.trim()}:$portNum/"
                                                )
                                                manualDevices.add(customDev)
                                                selectedCastingTarget = customDev
                                                customName = ""
                                                customIp = ""
                                                showManualForm = false
                                                Toast.makeText(context, "Added and selected manual casting target!", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Save & Connect Device", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            // Section Picker: Local visual files
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Default.Storage,
                                            contentDescription = "Media directory scanner folder",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                "Cast Local Media Files",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                "Server stream via range-request HTTP",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = {
                                            videoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                            )
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = "Open file manager")
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Pick Video from Phone Gallery")
                                    }

                                    if (selectedLocalMediaUri != null) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Video file verified",
                                                tint = Color(0xFF00C853),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = localMediaName,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Ready to server-stream over port 8182",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            
                                            Button(
                                                onClick = {
                                                    val target = selectedCastingTarget
                                                    if (target == null) {
                                                        Toast.makeText(context, "Please click to select a discovered TV receiver first!", Toast.LENGTH_SHORT).show()
                                                        return@Button
                                                    }
                                                    viewModel.castLocalFile(target)
                                                    selectedTab = 2 // Auto route to Controller remote screen
                                                    Toast.makeText(context, "Casting request emitted...", Toast.LENGTH_SHORT).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                            ) {
                                                Text("Cast Now", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Cast History lists
                        if (castHistory.isNotEmpty()) {
                            item {
                                Text(
                                    "Historical Streams Casted",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            items(castHistory.take(4)) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.List,
                                        contentDescription = "stream log history",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            item.url,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeHistoryItem(item.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Delete item",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: Web Video Sniffer browser component
                    Box(modifier = Modifier.fillMaxSize()) {
                        WebSnifferBrowser(
                            initialUrl = activeBrowserUrl,
                            onBookmarkAdded = { url, title ->
                                viewModel.bookmarkWebPage(url, title)
                                Toast.makeText(context, "Added Bookmark: $title", Toast.LENGTH_SHORT).show()
                            },
                            onVideoSelectedForCasting = { sniffedVideo ->
                                val target = selectedCastingTarget
                                if (target == null) {
                                    Toast.makeText(context, "Please select an active TV in the Scanner tab first!", Toast.LENGTH_LONG).show()
                                    selectedTab = 0
                                    return@WebSnifferBrowser
                                }
                                viewModel.castWebVideo(target, sniffedVideo)
                                selectedTab = 2 // Auto route to remote control player
                                Toast.makeText(context, "Initiating stream to ${target.name}...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                2 -> {
                    // TAB 2: Controller & AI Diagnostics Support Panel
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Bookmarks or History", tint = MaterialTheme.colorScheme.primary) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search query")
                                        }
                                    }
                                },
                                placeholder = { Text("Filter bookmarks...", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                )
                            )
                        }

                        item {
                            val filteredBms = remember(bookmarks, searchQuery) {
                                if (searchQuery.isBlank()) bookmarks
                                else bookmarks.filter { it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true) }
                            }

                            if (filteredBms.isNotEmpty() || bookmarks.isNotEmpty()) {
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "My Bookmarks star",
                                                tint = Color(0xFFFFD700),
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Saved Web Bookmarks (${filteredBms.size})",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    text = "Tapping loads URL in browser sniffer tab",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        if (filteredBms.isEmpty()) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("No bookmarks matching query", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            }
                                        } else {
                                            filteredBms.forEach { bm ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surface)
                                                        .clickable {
                                                            activeBrowserUrl = bm.url
                                                            selectedTab = 1
                                                            Toast.makeText(context, "Loading: ${bm.title} in sniffer...", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Language,
                                                            contentDescription = "Web link logo",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column {
                                                            Text(
                                                                text = bm.title,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = bm.url,
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.outline,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = { viewModel.removeBookmark(bm) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Delete bookmark",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section: REMOTE CONTROL PANEL
                        item {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("remote_controller_panel")
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (activeCastDevice != null) "CLIENT CONTROLLER: ACTIVE" else "NO DEVICE CURRENTLY STREAMING",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp,
                                        color = if (activeCastDevice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Caster device name
                                    Text(
                                        text = activeCastDevice?.name ?: "Idle / Not Connected",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        modifier = Modifier.testTag("device_caster_name")
                                    )

                                    if (activeCastDevice != null) {
                                        Text(
                                            text = "Playing: $castTitle",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                        Text(
                                            text = castUrl,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Play Pause trigger buttons
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.mediaController.stopCasting() },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface,
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Stop stream target key",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(24.dp))

                                        // Play Pause Action Circle
                                        Box(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                                .clickable { viewModel.mediaController.togglePlayPause() }
                                                .testTag("play_pause_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (currentCastingState == CastingState.PLAYING) 
                                                    Icons.Default.Pause 
                                                else 
                                                    Icons.Default.PlayArrow,
                                                contentDescription = "Play or Pause media stream key",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(24.dp))

                                        IconButton(
                                            onClick = { viewModel.mediaController.seekTo(0) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surface,
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Restart stream",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    // Timeline Seek Slider
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = formatDuration(castPosition),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            Text(
                                                text = formatDuration(castDuration),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        Slider(
                                            value = castPosition.toFloat(),
                                            onValueChange = { viewModel.mediaController.seekTo(it.toLong()) },
                                            valueRange = 0f..(castDuration.toFloat().coerceAtLeast(1f)),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Volume level Slider
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Default.Tv,
                                            contentDescription = "Volume speaker emblem",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Slider(
                                            value = castVolume.toFloat(),
                                            onValueChange = { viewModel.mediaController.setVolume(it.toInt()) },
                                            valueRange = 0f..100f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "$castVolume%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Section: iQOO SMART ISLAND CONFIGURATION
                        item {
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
                                            onCheckedChange = { isSmartIslandEnabled = it },
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
                                                onClick = { isSmartIslandExpanded = !isSmartIslandExpanded },
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
                                                onClick = {
                                                    isSmartIslandExpanded = true
                                                    // Auto-dismiss in 3 seconds
                                                    scope.launch {
                                                        kotlinx.coroutines.delay(3000)
                                                        isSmartIslandExpanded = false
                                                    }
                                                },
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

                        // Section: ALERTS / NETWORK INTERACTIVE TROUBLESHOOTING
                        item {
                            val activeErrLocal = activeError
                            
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (activeErrLocal != null) 
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = if (activeErrLocal != null) 
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
                                                    if (activeErrLocal != null) 
                                                        MaterialTheme.colorScheme.error 
                                                    else 
                                                        MaterialTheme.colorScheme.primary
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (activeErrLocal != null) 
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
                                                text = if (activeErrLocal != null) 
                                                    activeErrLocal.title 
                                                else 
                                                    "Casting & Network Diagnostic Assistant",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = if (activeErrLocal != null) 
                                                    MaterialTheme.colorScheme.onErrorContainer 
                                                else 
                                                    MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = if (activeErrLocal != null) 
                                                    "Diagnostic Exception flagged" 
                                                else 
                                                    "Continuous background Wi-Fi health monitoring",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Error Jargon-Free Prompt Explanation
                                    Text(
                                        text = if (activeErrLocal != null) 
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
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // OFFLINE DIAGNOSTIC INTERACTIVE SECTION
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

                                    // Display the Offline Diagnostics results
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
                    }
                }
            }
        }

        // Floating dynamic iQOO Smart Island Overlay
        IqooSmartIsland(
            isEnabled = isSmartIslandEnabled,
            isExpanded = isSmartIslandExpanded,
            onToggleExpand = { isSmartIslandExpanded = !isSmartIslandExpanded },
            state = currentCastingState,
            device = activeCastDevice,
            title = castTitle,
            position = castPosition,
            duration = castDuration,
            volume = castVolume,
            onTogglePlayPause = { viewModel.mediaController.togglePlayPause() },
            onSeek = { pos -> viewModel.mediaController.seekTo(pos) },
            onVolumeChange = { vol -> viewModel.mediaController.setVolume(vol) },
            onStop = { viewModel.mediaController.stopCasting() },
            isDiscovering = isDiscovering,
            discoveredCount = allDevicesToShow.size
        )
    }
}
}

// Visual layout helper to grab visual items inside Android Photo/Video scopes
fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
    }
    if (result == null) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            if (cut != -1) {
                result = path.substring(cut + 1)
            }
        }
    }
    return result
}

// Format milliseconds as visual timing track (HH:MM:SS / MM:SS)
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

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
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
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

                        // Progress slider / Timeline
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
                            
                            // Custom high-contrast slider
                            Slider(
                                value = position.toFloat(),
                                onValueChange = { onSeek(it.toLong()) },
                                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(18.dp)
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = brandColor,
                                    activeTrackColor = brandColor,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                )
                            )

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
            animation = tween(450, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val heightFactor2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val heightFactor3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val heightFactor4 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = androidx.compose.animation.core.LinearEasing),
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
