package com.example.handmouse

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 80, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Hand Mouse"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Grant camera, overlay, and accessibility access. Then start tracking and switch to any app."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 32)
        }

        val cameraButton = Button(this).apply {
            text = "Grant camera"
            setOnClickListener { requestCameraPermission() }
        }

        val overlayButton = Button(this).apply {
            text = "Grant overlay"
            setOnClickListener { openOverlaySettings() }
        }

        val accessibilityButton = Button(this).apply {
            text = "Enable accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val startButton = Button(this).apply {
            text = "Start hand mouse"
            setOnClickListener { startHandMouse() }
        }

        val stopButton = Button(this).apply {
            text = "Stop hand mouse"
            setOnClickListener { stopService(Intent(this@MainActivity, HandMouseService::class.java)) }
        }

        root.addView(title, fullWidthParams())
        root.addView(subtitle, fullWidthParams())
        root.addView(statusText, fullWidthParams())
        root.addView(cameraButton, fullWidthParams())
        root.addView(overlayButton, fullWidthParams())
        root.addView(accessibilityButton, fullWidthParams())
        root.addView(startButton, fullWidthParams())
        root.addView(stopButton, fullWidthParams())
        setContentView(root)
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        }

    private fun requestCameraPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            REQUEST_PERMISSIONS
        )
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startHandMouse() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val intent = Intent(this, HandMouseService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = buildString {
            appendLine("Camera: ${if (hasCameraPermission()) "granted" else "missing"}")
            appendLine("Overlay: ${if (Settings.canDrawOverlays(this@MainActivity)) "granted" else "missing"}")
            append("Accessibility: ${if (isAccessibilityServiceEnabled()) "enabled" else "disabled"}")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
