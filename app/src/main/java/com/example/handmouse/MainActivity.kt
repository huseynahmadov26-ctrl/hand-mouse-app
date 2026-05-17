package com.example.handmouse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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

    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var overlayPermissionGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var accessibilityEnabled by remember {
        mutableStateOf(context.isAccessibilityServiceEnabled())
    }
    var trackingEnabled by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        trackingEnabled = granted
        if (granted) {
            context.startHandMouseService()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Android 13+ notification permission only affects the foreground notification display.
    }

    // Refresh permission status when returning from Android settings screens.
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Hand Mouse",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
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
                            if (cameraPermissionGranted) {
                                trackingEnabled = true
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                context.startHandMouseService()
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
                            context.stopHandMouseService()
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
            // UI status fields requested by the project brief.
            StatusRow("Camera Permission Status", cameraPermissionGranted)
            StatusRow("Overlay Permission Status", overlayPermissionGranted)
            StatusRow("Accessibility Service Status", accessibilityEnabled)
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
private fun ServiceCameraPreviewPanel(
    trackingEnabled: Boolean,
    cameraPermissionGranted: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
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
            // The foreground service owns CameraX. This UI only supplies the preview surface.
            // Hand tracking logic runs in HandMouseService through the ImageAnalysis use case.
            HandMouseService.attachPreview(previewView.surfaceProvider)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            // When the app UI disappears, the service keeps hand tracking with analysis only.
            HandMouseService.attachPreview(null)
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openOverlaySettings() {
    // Overlay handling will be added later with WindowManager and SYSTEM_ALERT_WINDOW.
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )
    startActivity(intent)
}

private fun Context.startHandMouseService() {
    val intent = Intent(this, HandMouseService::class.java).apply {
        action = HandMouseService.ACTION_START
    }
    ContextCompat.startForegroundService(this, intent)
}

private fun Context.stopHandMouseService() {
    val intent = Intent(this, HandMouseService::class.java).apply {
        action = HandMouseService.ACTION_STOP
    }
    startService(intent)
}

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    // Accessibility tap handling will be added later inside MyAccessibilityService.
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
