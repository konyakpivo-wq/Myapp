package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Random

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private var scanResultsState = mutableStateListOf<ScanResult>()
    private var isScanningState = mutableStateOf(false)
    
    // Local-only Hotspot state
    var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    var hotspotSSID = mutableStateOf("")
    var hotspotPassword = mutableStateOf("")
    var isHotspotActive = mutableStateOf(false)
    var hotspotError = mutableStateOf("")

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            updateScanResults()
            isScanningState.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        setContent {
            MyApplicationTheme {
                MainScreen(
                    wifiManager = wifiManager,
                    scanResults = scanResultsState,
                    isScanning = isScanningState.value,
                    onScanRequested = { startWifiScan() },
                    hotspotSSID = hotspotSSID.value,
                    hotspotPassword = hotspotPassword.value,
                    isHotspotActive = isHotspotActive.value,
                    hotspotError = hotspotError.value,
                    onToggleHotspot = { enable ->
                        if (enable) {
                            startLocalHotspot()
                        } else {
                            stopLocalHotspot()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for WiFi scans
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
        updateScanResults()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun startWifiScan() {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Включите Wi-Fi на устройстве", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (e: Exception) {
                // Ignore
            }
            return
        }

        val permissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            isScanningState.value = true
            val success = wifiManager.startScan()
            if (!success) {
                // Scan failed or throttled, read cached results
                updateScanResults()
                isScanningState.value = false
                Toast.makeText(this, "Поиск временно ограничен системой (throttling)", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Разрешите доступ к геопозиции для поиска сетей", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateScanResults() {
        try {
            val permissionGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                val results = wifiManager.scanResults
                scanResultsState.clear()
                if (results != null) {
                    scanResultsState.addAll(results)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startLocalHotspot() {
        hotspotError.value = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(reservation)
                        hotspotReservation = reservation
                        val config = reservation.wifiConfiguration
                        hotspotSSID.value = config?.SSID ?: "GRT_Local_Hotspot"
                        hotspotPassword.value = config?.preSharedKey ?: ""
                        isHotspotActive.value = true
                        Toast.makeText(this@MainActivity, "Точка доступа GRT запущена!", Toast.LENGTH_SHORT).show()
                    }

                    override fun onStopped() {
                        super.onStopped()
                        cleanHotspotState()
                        Toast.makeText(this@MainActivity, "Точка доступа остановлена", Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        val errorMsg = when (reason) {
                            1 -> "Нет свободных каналов (каналы заняты)"
                            2 -> "Критическая ошибка Wi-Fi модуля"
                            3 -> "Точка доступа уже используется другой службой"
                            4 -> "Режим модема запрещен оператором или политикой"
                            else -> "Не удалось запустить точку доступа (код: $reason)"
                        }
                        hotspotError.value = errorMsg
                        cleanHotspotState()
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                hotspotError.value = "Ошибка запуска: ${e.localizedMessage}"
                cleanHotspotState()
            }
        } else {
            hotspotError.value = "Запуск точки доступа программно доступен только на Android 8.0+"
            Toast.makeText(this, "Ваша версия Android не поддерживает LocalOnlyHotspot API", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocalHotspot() {
        hotspotReservation?.close()
        cleanHotspotState()
        Toast.makeText(this, "Точка доступа выключена", Toast.LENGTH_SHORT).show()
    }

    private fun cleanHotspotState() {
        hotspotReservation = null
        hotspotSSID.value = ""
        hotspotPassword.value = ""
        isHotspotActive.value = false
    }
}

@Composable
fun MainScreen(
    wifiManager: WifiManager,
    scanResults: List<ScanResult>,
    isScanning: Boolean,
    onScanRequested: () -> Unit,
    hotspotSSID: String,
    hotspotPassword: String,
    isHotspotActive: Boolean,
    hotspotError: String,
    onToggleHotspot: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Permissions launcher
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    var permissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            onScanRequested()
        } else {
            Toast.makeText(context, "Необходимы разрешения для работы с Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0F172A),
                                Color(0xFF1E293B)
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(vertical = 16.dp, horizontal = 20.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = "GRT WiFi",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Управление сетями & Точка доступа",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        IconButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFF334155)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Системные настройки",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 8.dp,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Сканирование") },
                    label = { Text("Сканирование") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF06B6D4),
                        selectedTextColor = Color(0xFF06B6D4),
                        indicatorColor = Color(0xFF1E293B),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Share, contentDescription = "Точка доступа") },
                    label = { Text("Точка доступа") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF06B6D4),
                        selectedTextColor = Color(0xFF06B6D4),
                        indicatorColor = Color(0xFF1E293B),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
            }
        },
        containerColor = Color(0xFF0F172A)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                // WiFi Scans View
                WifiScanView(
                    scanResults = scanResults,
                    isScanning = isScanning,
                    onScan = {
                        if (permissionsGranted) {
                            onScanRequested()
                        } else {
                            launcher.launch(permissionsToRequest)
                        }
                    },
                    permissionsGranted = permissionsGranted
                )
            } else {
                // Hotspot View
                HotspotView(
                    hotspotSSID = hotspotSSID,
                    hotspotPassword = hotspotPassword,
                    isHotspotActive = isHotspotActive,
                    hotspotError = hotspotError,
                    onToggleHotspot = { active ->
                        if (permissionsGranted) {
                            onToggleHotspot(active)
                        } else {
                            launcher.launch(permissionsToRequest)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun WifiRadarScanner(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val radiusMultiplier by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radius"
    )
    val alphaValue by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val center = size / 2f
            val maxRadius = size.minDimension / 2f

            // Draw radar grid lines
            drawCircle(
                color = Color(0xFF1E293B),
                radius = maxRadius,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF1E293B),
                radius = maxRadius * 0.66f,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF1E293B),
                radius = maxRadius * 0.33f,
                style = Stroke(width = 1.dp.toPx())
            )

            // Dynamic scan wave
            if (isScanning) {
                drawCircle(
                    color = Color(0xFF06B6D4).copy(alpha = alphaValue),
                    radius = maxRadius * radiusMultiplier,
                    style = Stroke(width = 4.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF06B6D4).copy(alpha = (alphaValue - 0.3f).coerceAtLeast(0f)),
                    radius = maxRadius * (radiusMultiplier - 0.25f).coerceAtLeast(0f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Target central glowing beacon
            drawCircle(
                color = if (isScanning) Color(0xFF22C55E) else Color(0xFF06B6D4),
                radius = 7.dp.toPx()
            )
        }
        
        Text(
            text = if (isScanning) "Активный радар..." else "Радар готов к поиску",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (isScanning) Color(0xFF06B6D4) else Color(0xFF94A3B8),
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun WifiScanView(
    scanResults: List<ScanResult>,
    isScanning: Boolean,
    onScan: () -> Unit,
    permissionsGranted: Boolean
) {
    val context = LocalContext.current
    
    // Group networks to filter duplicates and sort them
    val processedResults = remember(scanResults) {
        scanResults.filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { group -> group.value.maxByOrNull { it.level }!! }
            .sortedByDescending { it.level }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        WifiRadarScanner(isScanning = isScanning)
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Доступные сети",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Найдено: ${processedResults.size}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
            
            Button(
                onClick = onScan,
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF06B6D4),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF94A3B8)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Поиск")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!permissionsGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Разрешение",
                        tint = Color(0xFFE11D48),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Требуется доступ к местоположению",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "В Android поиск Wi-Fi сетей технически привязан к службам геолокации. Пожалуйста, предоставьте разрешение.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onScan,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
                    ) {
                        Text("Предоставить доступ")
                    }
                }
            }
        } else if (processedResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Нет сетей",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Сети не найдены",
                        color = Color(0xFF94A3B8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Убедитесь, что Wi-Fi включен, и нажмите кнопку 'Поиск'",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(processedResults) { result ->
                    WifiNetworkCard(result = result) {
                        connectToWifi(context, result.SSID, result.BSSID)
                    }
                }
            }
        }
    }
}

@Composable
fun WifiNetworkCard(result: ScanResult, onConnectClicked: () -> Unit) {
    val signalPercentage = remember(result.level) {
        // Estimate quality from -100 to -40 dBm
        val quality = 2 * (result.level + 100)
        quality.coerceIn(0, 100)
    }

    val wifiIconTint = when {
        signalPercentage >= 75 -> Color(0xFF22C55E) // Green
        signalPercentage >= 40 -> Color(0xFFEAB308) // Yellow
        else -> Color(0xFFEF4444) // Red
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                WifiSignalIcon(
                    signalPercentage = signalPercentage,
                    color = wifiIconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.SSID,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Сигнал: $signalPercentage%",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "•",
                        color = Color(0xFF475569),
                        fontSize = 12.sp
                    )
                    Text(
                        text = getSecurityLabel(result.capabilities),
                        color = Color(0xFF06B6D4),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onConnectClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF334155),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Вход", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HotspotView(
    hotspotSSID: String,
    hotspotPassword: String,
    isHotspotActive: Boolean,
    hotspotError: String,
    onToggleHotspot: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val localIp = remember(isHotspotActive) { getLocalIpAddress() ?: "192.168.43.1" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            // Explanatory Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Информация",
                            tint = Color(0xFF06B6D4),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Локальная сеть (Wi-Fi Hotspot)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Запустите локальную точку доступа на этом телефоне. Другое устройство с этим же приложением сможет найти ее в списке сканирования, отсканировать QR-код и подключиться к вашей персональной сети!",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        item {
            // Main control card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isHotspotActive) Color(0xFF0F2D37) else Color(0xFF1E293B)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (isHotspotActive) Color(0xFF06B6D4) else Color(0xFF334155),
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                color = if (isHotspotActive) Color(0xFF06B6D4).copy(alpha = 0.2f) else Color(0xFF0F172A),
                                shape = RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHotspotActive) Icons.Default.Share else Icons.Default.Lock,
                            contentDescription = "Status",
                            tint = if (isHotspotActive) Color(0xFF06B6D4) else Color(0xFF64748B),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isHotspotActive) "Локальная точка активна" else "Точка доступа выключена",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isHotspotActive) "Устройства могут подключаться к вам" else "Запустите трансляцию для создания сети",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onToggleHotspot(!isHotspotActive) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHotspotActive) Color(0xFFEF4444) else Color(0xFF06B6D4),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isHotspotActive) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = "Toggle"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHotspotActive) "Остановить точку" else "Запустить локальную точку",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (hotspotError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = hotspotError,
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (isHotspotActive) {
            item {
                // Hotspot credentials card
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Параметры подключения",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // SSID row
                        CredentialRow(
                            label = "Название сети (SSID)",
                            value = hotspotSSID,
                            onCopy = {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("SSID", hotspotSSID))
                                Toast.makeText(context, "Имя сети скопировано!", Toast.LENGTH_SHORT).show()
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Password row
                        CredentialRow(
                            label = "Пароль (Passphrase)",
                            value = hotspotPassword,
                            onCopy = {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("Password", hotspotPassword))
                                Toast.makeText(context, "Пароль скопирован!", Toast.LENGTH_SHORT).show()
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Local IP row
                        CredentialRow(
                            label = "Ваш Локальный IP",
                            value = localIp,
                            onCopy = {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("IP", localIp))
                                Toast.makeText(context, "IP скопирован!", Toast.LENGTH_SHORT).show()
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Connection QR code visual
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Быстрое подключение через QR",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Отсканируйте камерой другого телефона для мгновенного входа",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            GeneratedQrCodeRepresentation(ssid = hotspotSSID, key = hotspotPassword)
                        }
                    }
                }
            }
        }

        item {
            // Custom Name (grt) / System Hotspot Help Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Хотите назвать сеть \"grt\"?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "По соображениям безопасности современные версии Android генерируют случайное имя для локальной сети приложений. Чтобы установить своё собственное имя (например, \"grt\") и использовать телефон как полноценный роутер, настройте системную точку доступа в меню Android:",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent()
                                intent.action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                } catch (ex: Exception) {
                                    Toast.makeText(context, "Не удалось открыть настройки точек доступа", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Настройка",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Изменить системное имя на \"grt\"", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Column {
        Text(text = label, color = Color(0xFF64748B), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            CopyIconButton(onClick = onCopy)
        }
    }
}

@Composable
fun GeneratedQrCodeRepresentation(ssid: String, key: String) {
    val hash = (ssid + key).hashCode()
    val size = 15
    val random = Random(hash.toLong())
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .size(160.dp)
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (r in 0 until size) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (c in 0 until size) {
                        // Place QR positioning corner blocks at top-left, top-right, bottom-left
                        val isMarker = (r < 4 && c < 4) || (r < 4 && c >= size - 4) || (r >= size - 4 && c < 4)
                        val isMarkerBorder = isMarker && (r == 0 || r == 3 || c == 0 || c == 3 || (c == size - 1 || c == size - 4) || (r == size - 1 || r == size - 4))
                        val isMarkerCenter = isMarker && (r == 1.coerceAtLeast(0) && c == 1.coerceAtLeast(0)) // small center block
                        
                        val isBlack = if (isMarker) {
                            if (r < 4 && c < 4) {
                                r == 0 || r == 3 || c == 0 || c == 3 || (r == 1 && c == 1) || (r == 1 && c == 2) || (r == 2 && c == 1) || (r == 2 && c == 2)
                            } else if (r < 4 && c >= size - 4) {
                                r == 0 || r == 3 || c == size - 1 || c == size - 4 || (r == 1 && c == size - 2) || (r == 1 && c == size - 3) || (r == 2 && c == size - 2) || (r == 2 && c == size - 3)
                            } else {
                                r == size - 1 || r == size - 4 || c == 0 || c == 3 || (r == size - 2 && c == 1) || (r == size - 2 && c == 2) || (r == size - 3 && c == 1) || (r == size - 3 && c == 2)
                            }
                        } else {
                            random.nextBoolean()
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    color = if (isBlack) Color.Black else Color.White,
                                    shape = RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

fun getSecurityLabel(capabilities: String): String {
    return when {
        capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> "Открытая"
    }
}

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress
                    if (ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                        return ip
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun connectToWifi(context: Context, ssid: String, bssid: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
            
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        Toast.makeText(context, "Подключение к $ssid через систему...", Toast.LENGTH_SHORT).show()
        
        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                super.onAvailable(network)
                try {
                    connectivityManager.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    // Ignore bind errors on some vendors
                }
                (context as? MainActivity)?.runOnUiThread {
                    Toast.makeText(context, "Успешно подключено к $ssid!", Toast.LENGTH_LONG).show()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                (context as? MainActivity)?.runOnUiThread {
                    Toast.makeText(context, "Подключение к $ssid отклонено или недоступно", Toast.LENGTH_LONG).show()
                }
            }
        })
    } else {
        Toast.makeText(context, "Для подключения откройте Wi-Fi настройки", Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            // Ignore
        }
    }
}

@Composable
fun WifiSignalIcon(signalPercentage: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barCount = 4
        val barWidth = width / (barCount * 1.5f)
        val activeBars = when {
            signalPercentage >= 75 -> 4
            signalPercentage >= 50 -> 3
            signalPercentage >= 25 -> 2
            signalPercentage > 0 -> 1
            else -> 0
        }
        
        for (i in 0 until barCount) {
            val barHeight = height * ((i + 1) / barCount.toFloat())
            val x = i * (barWidth * 1.5f)
            val y = height - barHeight
            val barColor = if (i < activeBars) color else color.copy(alpha = 0.2f)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
fun CopyIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            // Draw background copy sheet
            drawRect(
                color = Color(0xFF06B6D4),
                topLeft = androidx.compose.ui.geometry.Offset(3.dp.toPx(), 3.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(9.dp.toPx(), 9.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
            // Draw foreground copy sheet
            drawRect(
                color = Color(0xFF0F172A),
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(9.dp.toPx(), 9.dp.toPx())
            )
            drawRect(
                color = Color(0xFF06B6D4),
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(9.dp.toPx(), 9.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
