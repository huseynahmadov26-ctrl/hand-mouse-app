package com.example.handmouse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HandMouseApp()
        }
    }
}

@Composable
private fun HandMouseApp() {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val versionLabel = remember { context.appVersionLabel() }
    val prefs = remember {
        context.getSharedPreferences(ForegroundTrackingService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var cameraPermissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var overlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityEnabled by remember { mutableStateOf(context.isAccessibilityServiceEnabled()) }
    var trackingEnabled by remember { mutableStateOf(false) }
    var cursorSensitivity by remember {
        mutableFloatStateOf(prefs.getFloat(ForegroundTrackingService.KEY_CURSOR_SENSITIVITY, 1.15f))
    }
    var smoothing by remember {
        mutableFloatStateOf(prefs.getFloat(ForegroundTrackingService.KEY_SMOOTHING, 0.55f))
    }
    var clickThreshold by remember {
        mutableFloatStateOf(prefs.getFloat(ForegroundTrackingService.KEY_CLICK_THRESHOLD, 0.055f))
    }
    var scrollSpeed by remember {
        mutableFloatStateOf(prefs.getFloat(ForegroundTrackingService.KEY_SCROLL_SPEED, 1.3f))
    }
    var backgroundTracking by remember {
        mutableStateOf(prefs.getBoolean(ForegroundTrackingService.KEY_BACKGROUND_TRACKING, true))
    }
    var mirrorCursor by remember {
        mutableStateOf(prefs.getBoolean(ForegroundTrackingService.KEY_MIRROR_CURSOR, true))
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        trackingEnabled = granted
        if (granted) {
            context.startForegroundTrackingService()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Android 13+ notification permission only affects foreground notification visibility.
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermissionGranted = context.hasCameraPermission()
                overlayPermissionGranted = Settings.canDrawOverlays(context)
                accessibilityEnabled = context.isAccessibilityServiceEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7F8FA)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Hand Mouse",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5F6368)
                )

                StatusPanel(
                    cameraPermissionGranted = cameraPermissionGranted,
                    overlayPermissionGranted = overlayPermissionGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    trackingEnabled = trackingEnabled
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (cameraPermissionGranted) {
                                trackingEnabled = true
                                context.startForegroundTrackingService()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    ) {
                        Text("Start Tracking")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5F6368)),
                        onClick = {
                            trackingEnabled = false
                            context.stopForegroundTrackingService()
                        }
                    ) {
                        Text("Stop Tracking")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { context.openOverlaySettings() }
                    ) {
                        Text("Overlay Settings")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    ) {
                        Text("Accessibility")
                    }
                }

                SettingsPanel(
                    cursorSensitivity = cursorSensitivity,
                    smoothing = smoothing,
                    clickThreshold = clickThreshold,
                    scrollSpeed = scrollSpeed,
                    backgroundTracking = backgroundTracking,
                    mirrorCursor = mirrorCursor,
                    onCursorSensitivityChange = {
                        cursorSensitivity = it
                        prefs.putFloat(ForegroundTrackingService.KEY_CURSOR_SENSITIVITY, it)
                    },
                    onSmoothingChange = {
                        smoothing = it
                        prefs.putFloat(ForegroundTrackingService.KEY_SMOOTHING, it)
                    },
                    onClickThresholdChange = {
                        clickThreshold = it
                        prefs.putFloat(ForegroundTrackingService.KEY_CLICK_THRESHOLD, it)
                    },
                    onScrollSpeedChange = {
                        scrollSpeed = it
                        prefs.putFloat(ForegroundTrackingService.KEY_SCROLL_SPEED, it)
                    },
                    onBackgroundTrackingChange = {
                        backgroundTracking = it
                        prefs.putBoolean(ForegroundTrackingService.KEY_BACKGROUND_TRACKING, it)
                    },
                    onMirrorCursorChange = {
                        mirrorCursor = it
                        prefs.putBoolean(ForegroundTrackingService.KEY_MIRROR_CURSOR, it)
                    }
                )

                ServiceCameraPreviewPanel(
                    trackingEnabled = trackingEnabled,
                    cameraPermissionGranted = cameraPermissionGranted
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(
    cameraPermissionGranted: Boolean,
    overlayPermissionGranted: Boolean,
    accessibilityEnabled: Boolean,
    trackingEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusRow("Camera Permission", cameraPermissionGranted)
            StatusRow("Overlay Permission", overlayPermissionGranted)
            StatusRow("Accessibility Service", accessibilityEnabled)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (trackingEnabled) "Tracking Enabled" else "Tracking Disabled",
                color = if (trackingEnabled) Color(0xFF0B7D75) else Color(0xFFB3261E),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (enabled) "Granted" else "Missing",
            color = if (enabled) Color(0xFF0B7D75) else Color(0xFFB3261E),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsPanel(
    cursorSensitivity: Float,
    smoothing: Float,
    clickThreshold: Float,
    scrollSpeed: Float,
    backgroundTracking: Boolean,
    mirrorCursor: Boolean,
    onCursorSensitivityChange: (Float) -> Unit,
    onSmoothingChange: (Float) -> Unit,
    onClickThresholdChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onBackgroundTrackingChange: (Boolean) -> Unit,
    onMirrorCursorChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SettingSlider("Cursor sensitivity", cursorSensitivity, 0.6f..1.8f, onCursorSensitivityChange)
            SettingSlider("Smoothing", smoothing, 0f..1f, onSmoothingChange)
            SettingSlider("Click threshold", clickThreshold, 0.025f..0.12f, onClickThresholdChange)
            SettingSlider("Scroll speed", scrollSpeed, 0.5f..3f, onScrollSpeedChange)
            SettingsSwitch("Mirror cursor", mirrorCursor, onMirrorCursorChange)
            SettingsSwitch("Run in Background", backgroundTracking, onBackgroundTrackingChange)
        }
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label)
            Text(String.format("%.2f", value), color = Color(0xFF5F6368))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun ServiceCameraPreviewPanel(
    trackingEnabled: Boolean,
    cameraPermissionGranted: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111418))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (trackingEnabled && cameraPermissionGranted) {
                ServiceCameraPreview()
            } else {
                Text(
                    modifier = Modifier.align(Alignment.Center),
                    text = "Camera preview stopped",
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ServiceCameraPreview() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            ForegroundTrackingService.attachPreview(previewView.surfaceProvider)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            ForegroundTrackingService.attachPreview(null)
        }
    }
}

private fun SharedPreferences.putFloat(key: String, value: Float) {
    edit().putFloat(key, value).apply()
}

private fun SharedPreferences.putBoolean(key: String, value: Boolean) {
    edit().putBoolean(key, value).apply()
}

private fun Context.appVersionLabel(): String {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return "Version ${packageInfo.versionName} ($versionCode)"
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openOverlaySettings() {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )
    startActivity(intent)
}

private fun Context.startForegroundTrackingService() {
    val intent = Intent(this, ForegroundTrackingService::class.java).apply {
        action = ForegroundTrackingService.ACTION_START
    }
    ContextCompat.startForegroundService(this, intent)
}

private fun Context.stopForegroundTrackingService() {
    val intent = Intent(this, ForegroundTrackingService::class.java).apply {
        action = ForegroundTrackingService.ACTION_STOP
    }
    startService(intent)
}

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val expectedServiceName = ComponentName(this, MyAccessibilityService::class.java)
        .flattenToString()
    val enabledServices = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices.split(':').any { serviceName ->
        serviceName.equals(expectedServiceName, ignoreCase = true)
    }
}
