package com.example.handmouse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HandMouseService : LifecycleService(), HandTracker.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlay: CursorOverlay
    private var handTracker: HandTracker? = null
    private var smoothedX = Float.NaN
    private var smoothedY = Float.NaN
    private var lastClickTimeMs = 0L
    private var wasPinching = false
    private var cameraStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        cameraExecutor = Executors.newSingleThreadExecutor()
        overlay = CursorOverlay(this)
        overlay.show()

        try {
            handTracker = HandTracker(this, this)
            startCamera()
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start hand tracker", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        handTracker?.close()
        overlay.hide()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        if (cameraStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis)
            cameraStarted = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = bitmap.rotate(imageProxy.imageInfo.rotationDegrees)
            handTracker?.detect(rotatedBitmap)
        } catch (error: Throwable) {
            Log.e(TAG, "Frame analysis failed", error)
        } finally {
            imageProxy.close()
        }
    }

    override fun onHand(indexX: Float, indexY: Float, pinchDetected: Boolean) {
        mainHandler.post {
            handleHandOnMain(indexX, indexY, pinchDetected)
        }
    }

    private fun handleHandOnMain(indexX: Float, indexY: Float, pinchDetected: Boolean) {
        val windowManager = getSystemService(WindowManager::class.java)
        val screenWidth: Float
        val screenHeight: Float

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width().toFloat()
            screenHeight = bounds.height().toFloat()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels.toFloat()
            screenHeight = metrics.heightPixels.toFloat()
        }

        // Front-camera coordinates feel natural when mirrored horizontally.
        val targetX = (1f - indexX) * screenWidth
        val targetY = indexY * screenHeight

        if (smoothedX.isNaN() || smoothedY.isNaN()) {
            smoothedX = targetX
            smoothedY = targetY
        } else {
            smoothedX += (targetX - smoothedX) * SMOOTHING_ALPHA
            smoothedY += (targetY - smoothedY) * SMOOTHING_ALPHA
        }

        overlay.moveTo(smoothedX, smoothedY, pinchDetected)

        val now = System.currentTimeMillis()
        // Fire once when the pinch begins, with a cooldown as a second guard.
        if (pinchDetected && !wasPinching && now - lastClickTimeMs >= CLICK_COOLDOWN_MS) {
            if (MyAccessibilityService.tap(smoothedX, smoothedY)) {
                lastClickTimeMs = now
            }
        }
        wasPinching = pinchDetected
    }

    override fun onHandLost() {
        mainHandler.post {
            smoothedX = Float.NaN
            smoothedY = Float.NaN
            wasPinching = false
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, message)
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = planes[0].buffer
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hand_mouse)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "HandMouseService"
        private const val CHANNEL_ID = "hand_mouse_tracking"
        private const val NOTIFICATION_ID = 42
        private const val CLICK_COOLDOWN_MS = 300L
        private const val SMOOTHING_ALPHA = 0.28f
    }
}
