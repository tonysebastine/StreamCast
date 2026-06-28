package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import com.example.AppLogger as Log
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.browser.SniffedVideo
import com.example.browser.WebSnifferBrowser
import com.example.casting.CastingDevice
import com.example.casting.CastingState
import com.example.casting.ProtocolType
import com.example.casting.LocalNetworkPermissionHelper
import com.example.database.BookmarkedUrl
import com.example.database.CastHistoryItem
import com.example.ui.theme.StreamCastTheme
import com.example.ui.DiagnosticHudOverlay
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Build
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("System", "StreamCast initialized cleanly. Android SDK ${android.os.Build.VERSION.SDK_INT}")
        
        // Request SDK 36 nearby wifi permission for local network privacy using helper
        if (!LocalNetworkPermissionHelper.hasPermission(this)) {
            LocalNetworkPermissionHelper.requestPermission(this, 101)
        }
        
        enableEdgeToEdge()
        setContent {
            StreamCastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StreamCastDashboard()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocalNetworkPermissionHelper.handleRequestResult(
            requestCode,
            permissions,
            grantResults,
            101,
            onGranted = {
                Log.i("MainActivity", "Nearby devices / Location permission granted successfully.")
            },
            onDenied = {
                Log.w("MainActivity", "Nearby devices / Location permission request denied by user.")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamCastDashboard(
    viewModel: CastViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSmartIslandEnabled by remember { mutableStateOf(true) }
    var isSmartIslandExpanded by remember { mutableStateOf(false) }
    var expandedReleaseVersion by remember { mutableStateOf("1.2") }

    // App update checker states and SharedPreferences sync
    val updatePrefs = remember { context.getSharedPreferences(com.example.service.UpdateCheckService.PREFS_NAME, Context.MODE_PRIVATE) }
    var updateManifestUrl by remember { mutableStateOf(updatePrefs.getString(com.example.service.UpdateCheckService.PREF_MANIFEST_URL, com.example.service.UpdateCheckService.DEFAULT_MANIFEST_URL) ?: com.example.service.UpdateCheckService.DEFAULT_MANIFEST_URL) }
    var isPeriodicCheckEnabled by remember { mutableStateOf(updatePrefs.getLong(com.example.service.UpdateCheckService.PREF_INTERVAL_MINUTES, com.example.service.UpdateCheckService.DEFAULT_INTERVAL_MINUTES) > 0L) }
    var isSimulationModeEnabled by remember { mutableStateOf(updatePrefs.getBoolean(com.example.service.UpdateCheckService.PREF_SIMULATION_MODE, false)) }
    
    var lastCheckResult by remember { mutableStateOf("Ready to fetch latest manifest.") }
    var lastCheckServerVersion by remember { mutableStateOf("") }
    var lastCheckUpdateAvailable by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                Log.i("MainActivity", "Notification permission granted.")
            } else {
                Log.w("MainActivity", "Notification permission denied.")
            }
        }
    )
    
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (status != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Start update service on launch with the stored settings
        val serviceIntent = Intent(context, com.example.service.UpdateCheckService::class.java).apply {
            action = com.example.service.UpdateCheckService.ACTION_START
        }
        context.startService(serviceIntent)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == com.example.service.UpdateCheckService.ACTION_UPDATE_RESULT) {
                    val updateAvailable = intent.getBooleanExtra(com.example.service.UpdateCheckService.EXTRA_UPDATE_AVAILABLE, false)
                    val serverVersion = intent.getStringExtra(com.example.service.UpdateCheckService.EXTRA_SERVER_VERSION) ?: ""
                    val statusMessage = intent.getStringExtra(com.example.service.UpdateCheckService.EXTRA_STATUS_MESSAGE) ?: ""
                    
                    lastCheckUpdateAvailable = updateAvailable
                    lastCheckServerVersion = serverVersion
                    lastCheckResult = statusMessage
                    isCheckingUpdates = false
                }
            }
        }
        val filter = IntentFilter(com.example.service.UpdateCheckService.ACTION_UPDATE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

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
    val castBufferPercentage by viewModel.mediaController.bufferPercentage.collectAsStateWithLifecycle()
    val castVolume by viewModel.mediaController.volume.collectAsStateWithLifecycle()
    val activeError by viewModel.mediaController.error.collectAsStateWithLifecycle()
    val isVirtualBridgeActive by viewModel.mediaController.isVirtualBridgeActive.collectAsStateWithLifecycle()

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

    val allDevicesToShow = remember(discoveredDevices, manualDevices) {
        val merged = discoveredDevices.toMutableList()
        merged.addAll(manualDevices)
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
                            painter = painterResource(id = R.drawable.ic_streamcast_logo),
                            contentDescription = "StreamCast Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "StreamCast",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
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
                                                    if (!LocalNetworkPermissionHelper.hasPermission(context)) {
                                                        (context as? Activity)?.let { activity ->
                                                            LocalNetworkPermissionHelper.requestPermission(activity, 101)
                                                        }
                                                        Toast.makeText(context, "Nearby Wifi permission is required to detect local casting devices.", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        viewModel.startDeviceScanning()
                                                    }
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
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        try {
                                            val intent = android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS")
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                            } catch (ex: Exception) {
                                                Toast.makeText(context, "Search for 'Cast' or 'Wireless Display' in phone settings.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cast,
                                        contentDescription = "Miracast screen mirroring",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Miracast / Wi-Fi Display Screen Mirroring",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "Tap to open system cast screen settings to stream audio & video using Wi-Fi Alliance standard (uses Wi-Fi Direct peer-to-peer connection).",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
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
                        if (allDevicesToShow.isEmpty()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = "Searching for Casting receivers",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                        Text(
                                            text = "Scanning Local Network...",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Ensure your phone and Smart TV are connected to the same Wi-Fi subnet. Make sure DLNA, Chromecast, or AirPlay is enabled on your TV.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                    }
                                }
                            }
                        } else {
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
                                            ProtocolType.MIRACAST -> Color(0xFFE91E63)
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
                                            ProtocolType.MIRACAST -> "Miracast (Wi-Fi Direct)"
                                        }

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
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Storage,
                                            contentDescription = "Media storage picker",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Cast Local Media Files",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Server-stream local files via Range-Request HTTP",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Search icon",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Pick Video from Phone Gallery",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
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
                                        Icons.AutoMirrored.Filled.List,
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

                                    if (activeCastDevice != null && isVirtualBridgeActive) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            ),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Cast,
                                                    contentDescription = "Virtual Bridge Mode Enabled",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "Local Casting Tunnel Active: Bypassing network block or isolation to stream and synchronize play-state feedback dynamically.",
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

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

                                    // Custom visual indicators for buffer status and playback progress
                                    val activeBrandColor = when (activeCastDevice?.protocolType) {
										ProtocolType.MIRACAST -> Color(0xFFE91E63)
                                        ProtocolType.CHROMECAST -> Color(0xFF4285F4)
                                        ProtocolType.ROKU -> Color(0xFF8A2BE2)
                                        ProtocolType.FIRE_TV -> Color(0xFFFF9900)
                                        ProtocolType.AIRPLAY -> Color(0xFF007AFF)
                                        ProtocolType.DLNA -> Color(0xFF4CAF50)
                                        null -> MaterialTheme.colorScheme.primary
                                    }

                                    CastingBufferAndProgressIndicator(
                                        position = castPosition,
                                        duration = castDuration,
                                        bufferPercentage = castBufferPercentage,
                                        brandColor = activeBrandColor,
                                        state = currentCastingState,
                                        onSeek = { viewModel.mediaController.seekTo(it) }
                                    )

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

                        // Section: APP UPDATES & MANIFEST MANAGER
                        item {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("app_updates_card")
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    // Header
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SystemUpdate,
                                                contentDescription = "Update checker icon",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Automatic Updates Checker",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Monitors server manifest periodically",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        
                                        // Status badge
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isPeriodicCheckEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                        ) {
                                            Text(
                                                text = if (isPeriodicCheckEnabled) "PERIODIC ACTIVE" else "MANUAL ONLY",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isPeriodicCheckEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Manifest URL field
                                    Text(
                                        text = "Update Manifest JSON URL",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    
                                    OutlinedTextField(
                                        value = updateManifestUrl,
                                        onValueChange = { 
                                            updateManifestUrl = it
                                            updatePrefs.edit().putString(com.example.service.UpdateCheckService.PREF_MANIFEST_URL, it).apply()
                                        },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        placeholder = { Text("Enter manifest URL...", fontSize = 11.sp) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Simulation mode switch
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Simulate Update Available",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Immediately triggers a simulated v1.5-Simulated update notification for local testing.",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.outline,
                                                lineHeight = 13.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Switch(
                                            checked = isSimulationModeEnabled,
                                            onCheckedChange = {
                                                isSimulationModeEnabled = it
                                                updatePrefs.edit().putBoolean(com.example.service.UpdateCheckService.PREF_SIMULATION_MODE, it).apply()
                                                
                                                // Trigger restart/notify service of the setting change
                                                val intent = Intent(context, com.example.service.UpdateCheckService::class.java).apply {
                                                    action = com.example.service.UpdateCheckService.ACTION_START
                                                }
                                                context.startService(intent)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Control Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                isCheckingUpdates = true
                                                val intent = Intent(context, com.example.service.UpdateCheckService::class.java).apply {
                                                    action = com.example.service.UpdateCheckService.ACTION_CHECK_NOW
                                                }
                                                context.startService(intent)
                                                Toast.makeText(context, "Checking manifest server...", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (isCheckingUpdates) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Checking...", fontSize = 11.sp)
                                            } else {
                                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Check Now", fontSize = 11.sp)
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                val newStatus = !isPeriodicCheckEnabled
                                                isPeriodicCheckEnabled = newStatus
                                                // If disabled, set interval to 0. If enabled, set to 60.
                                                val intervalValue = if (newStatus) com.example.service.UpdateCheckService.DEFAULT_INTERVAL_MINUTES else 0L
                                                updatePrefs.edit().putLong(com.example.service.UpdateCheckService.PREF_INTERVAL_MINUTES, intervalValue).apply()
                                                
                                                val intent = Intent(context, com.example.service.UpdateCheckService::class.java).apply {
                                                    action = if (newStatus) com.example.service.UpdateCheckService.ACTION_START else com.example.service.UpdateCheckService.ACTION_STOP
                                                }
                                                context.startService(intent)
                                                val msg = if (newStatus) "Periodic background checks enabled!" else "Periodic checks stopped."
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isPeriodicCheckEnabled) "Disable Periodic" else "Enable Periodic",
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    // Result feedback panel
                                    if (lastCheckResult.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (lastCheckUpdateAvailable) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                                            ),
                                            border = BorderStroke(1.dp, if (lastCheckUpdateAvailable) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Info,
                                                        contentDescription = null,
                                                        tint = if (lastCheckUpdateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Status: $lastCheckResult",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (lastCheckUpdateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                if (lastCheckUpdateAvailable) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "A new stable build v$lastCheckServerVersion is available! Tap the notification to trigger download instructions.",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        lineHeight = 13.sp
                                                    )
                                                }
                                            }
                                        }
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
                                    if (activeErrLocal != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
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
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
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

                        item {
                            // Section: Real-time Debug Console
                            val logsList by com.example.AppLogger.logs.collectAsStateWithLifecycle(initialValue = emptyList())
                            var logFilterType by remember { mutableStateOf("ALL") }
                            
                            val filteredLogs = remember(logsList, logFilterType) {
                                when (logFilterType) {
                                    "ALL" -> logsList
                                    "ERR" -> logsList.filter { it.level == com.example.LogLevel.ERROR || it.level == com.example.LogLevel.WARN }
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
                                    // Header with pulse indicator
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Blinking green pulse indicator
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

                                        // Quick Actions
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
                                                        com.example.AppLogger.clear()
                                                        Toast.makeText(context, "Console logs cleared", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Filter chips
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
                                                    .clickable { logFilterType = filterKey }
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

                                    // Terminal display
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF0F141C)) // Dark terminal theme
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
                                            // Auto-scroll to bottom when new logs arrive
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
                                                        com.example.LogLevel.DEBUG -> Color(0xFF00E5FF)
                                                        com.example.LogLevel.INFO -> Color(0xFF00E676)
                                                        com.example.LogLevel.WARN -> Color(0xFFFF9100)
                                                        com.example.LogLevel.ERROR -> Color(0xFFFF1744)
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

                        item {
                            // Section: Developer Info Card
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("developer_info_card")
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = "Developer avatar emblem",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Tony Sebastine",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Lead Architect & Developer",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }

                                        // Small build chip
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            modifier = Modifier.padding(start = 4.dp)
                                        ) {
                                            Text(
                                                text = "v1.2",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Description / Bio
                                    Text(
                                        text = "StreamCast was built to provide parallel network discovery (mDNS, SSDP), an ultra-responsive local range-request server, and a reliable web stream sniffer to unify casting across Chromecasts, Fire TVs, Rokus, and AirPlay devices.",
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Contact & Email section
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                            .padding(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Email,
                                            contentDescription = "Email icon",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "tonysebastine@gmail.com",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        
                                        // Copy action button
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("Developer Email", "tonysebastine@gmail.com")
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Developer email copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("developer_email_copy_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy developer email",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Section: Release Changelog Card
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("release_changelog_card")
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Header
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = "Release history icon",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Release Changelog",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Explore historical updates & new features",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Version 1.2 Item (Current Production Release)
                                    val isV12Expanded = expandedReleaseVersion == "1.2"
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { 
                                                expandedReleaseVersion = if (isV12Expanded) "" else "1.2"
                                            }
                                            .background(
                                                if (isV12Expanded) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                else Color.Transparent
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "v1.2",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.width(44.dp)
                                            )
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Production Stable Build",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Released: June 2026",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }

                                            // Production Latest Badge
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    text = "LATEST PROD",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = if (isV12Expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (isV12Expanded) "Collapse" else "Expand",
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (isV12Expanded) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                                ChangelogBullet("Production Optimization", "Stabilized network discovery layers and HTTP local streaming servers to ensure maximum reliability and lower battery impact.")
                                                ChangelogBullet("Direct Release Tracking", "Integrated high-contrast vector link assets mapped dynamically to the official GitHub releases registry for clean live updates.")
                                                ChangelogBullet("Zero-Frame Latency", "Refined coroutine pipeline boundaries and state tracking inside the web link sniffer view to provide fluid 60FPS scrolling.")
                                                ChangelogBullet("Enhanced Safety Guards", "Engineered error-boundary fallbacks inside local database initialization to prevent state corruptions during upgrade cycles.")
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Version 1.1 Item (Current)
                                    val isV11Expanded = expandedReleaseVersion == "1.1"
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { 
                                                expandedReleaseVersion = if (isV11Expanded) "" else "1.1"
                                            }
                                            .background(
                                                if (isV11Expanded) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                else Color.Transparent
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "v1.1",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.width(44.dp)
                                            )
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "StreamCast Rebranding",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Released: June 2026",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }

                                            // Stable Badge
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    text = "STABLE",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = if (isV11Expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (isV11Expanded) "Collapse" else "Expand",
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (isV11Expanded) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                                ChangelogBullet("App Rebranding", "Completely modernized application identity to StreamCast across database namespaces, class indicators, UI elements, and string labels.")
                                                ChangelogBullet("Custom Vector Logo", "Designed and deployed ic_streamcast_logo dynamically with beautiful linear gradients and casting waves for a polished and high-end vibe.")
                                                ChangelogBullet("Developer Identity", "Integrated detailed Developer Profile sheet featuring custom email clipboard support for direct, frictionless reach.")
                                                ChangelogBullet("Clean Separation", "Migrated persistent data layers to 'stream_cast_database' to ensure reliable schema separation and local file integrity.")
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Version 1.0 Item (Legacy)
                                    val isV10Expanded = expandedReleaseVersion == "1.0"
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { 
                                                expandedReleaseVersion = if (isV10Expanded) "" else "1.0"
                                            }
                                            .background(
                                                if (isV10Expanded) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                                else Color.Transparent
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "v1.0",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.width(44.dp)
                                            )
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Initial Launch Engine",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Released: May 2026",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }

                                            // Stable Badge
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    text = "STABLE",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = if (isV10Expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (isV10Expanded) "Collapse" else "Expand",
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (isV10Expanded) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                                ChangelogBullet("Parallel Discovery", "Integrated advanced mDNS (Multicast DNS) and SSDP background protocols to locate media targets within milliseconds.")
                                                ChangelogBullet("Range-Request Server", "Programmed a responsive local HTTP server to host and feed range-based streams instantly to smart devices.")
                                                ChangelogBullet("Smart Island Widget", "Crafted a gorgeous, floating dynamic overlay to report background volume levels, seek operations, and play/pause statuses.")
                                                ChangelogBullet("In-App Sniffer Browser", "Embedded custom JavaScript hook injectors into the web tab, instantly extracting castable stream links safely.")
                                                ChangelogBullet("Cross-Protocol Cast", "Secured low-latency media piping to Chromecasts, Fire TVs, Rokus, and AirPlay devices.")
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            try {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://github.com/tonysebastine/StreamCast/releases")
                                                )
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("github_releases_button")
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_github),
                                            contentDescription = "GitHub logo link icon",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "View on GitHub Releases",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
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
            bufferPercentage = castBufferPercentage,
            volume = castVolume,
            onTogglePlayPause = { viewModel.mediaController.togglePlayPause() },
            onSeek = { pos -> viewModel.mediaController.seekTo(pos) },
            onVolumeChange = { vol -> viewModel.mediaController.setVolume(vol) },
            onStop = { viewModel.mediaController.stopCasting() },
            isDiscovering = isDiscovering,
            discoveredCount = allDevicesToShow.size
        )

        // StreamCast Live Diagnostics HUD & Discovery Overlay
        DiagnosticHudOverlay(
            isDiscovering = isDiscovering,
            discoveredDevices = allDevicesToShow,
            currentCastingState = currentCastingState,
            activeCastDevice = activeCastDevice,
            onTriggerScan = { viewModel.startDeviceScanning() }
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
fun CastingBufferAndProgressIndicator(
    position: Long,
    duration: Long,
    bufferPercentage: Int,
    brandColor: Color,
    state: CastingState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val durationFloat = duration.toFloat().coerceAtLeast(1f)
    val positionFloat = position.toFloat().coerceIn(0f, durationFloat)
    val progressFraction = positionFloat / durationFloat
    val bufferFraction = (bufferPercentage / 100f).coerceIn(0f, 1f)

    // Pulse animation for buffer shimmer effect
    val infiniteTransition = rememberInfiniteTransition(label = "buffer_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Status metrics panel
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (state == CastingState.PLAYING) brandColor else Color.Gray
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (state) {
                        CastingState.CONNECTING -> "Handshaking & buffering..."
                        CastingState.PLAYING -> "Streaming • Buffered $bufferPercentage%"
                        CastingState.PAUSED -> "Paused • Buffered $bufferPercentage%"
                        CastingState.ERROR -> "Streaming Connection Interrupted"
                        else -> "Buffer Idle"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state != CastingState.IDLE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
            }

            // Simulated real-time bits speed tracking
            Text(
                text = when (state) {
                    CastingState.CONNECTING -> "1.8 MB/s"
                    CastingState.PLAYING -> "${(10..24).random() / 10.0} MB/s • Live"
                    CastingState.PAUSED -> "0.1 MB/s • Standby"
                    CastingState.ERROR -> "0 KB/s"
                    else -> "0 KB/s"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = brandColor.copy(alpha = 0.85f)
            )
        }

        // Custom double-track progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Track base & buffer layer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                // Buffer track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferFraction)
                        .fillMaxHeight()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    brandColor.copy(alpha = 0.15f),
                                    brandColor.copy(alpha = 0.45f * shimmerAlpha)
                                )
                            )
                        )
                )

                // Active playback track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    brandColor.copy(alpha = 0.85f),
                                    brandColor
                                )
                            )
                        )
                )
            }

            // Transparent overlay Slider for smooth interactive gestures
            Slider(
                value = positionFloat,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..durationFloat,
                modifier = Modifier.fillMaxWidth().testTag("casting_seek_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = brandColor,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }

        // Custom timer layout
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(position),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(duration),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

@Composable
fun ChangelogBullet(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
