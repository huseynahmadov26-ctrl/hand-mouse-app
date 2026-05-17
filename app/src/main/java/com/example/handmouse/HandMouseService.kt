package com.example.handmouse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HandMouseService : Service(), LifecycleOwner, HandTracker.Listener {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandTracker? = null
    private var cursorOverlay: CursorOverlay? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var smoothedX = Float.NaN
    private var smoothedY = Float.NaN
    private var wasPinching = false
    private var lastClickMs = 0L

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        if (Settings.canDrawOverlays(this)) {
            cursorOverlay = CursorOverlay(this).also { it.show() }
        }

        handTracker = HandTracker(this, this)
        previewSurfaceProvider = pendingPreviewSurfaceProvider
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startCamera()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraProvider?.unbindAll()
        handTracker?.close()
        cursorOverlay?.hide()
        cameraExecutor.shutdown()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

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

        val useCases = mutableListOf<UseCase>(analysis)
        previewSurfaceProvider?.let { surfaceProvider ->
            val preview = Preview.Builder().build().also { cameraPreview ->
                cameraPreview.setSurfaceProvider(surfaceProvider)
            }
            useCases += preview
        }

        val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        provider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())
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

    override fun onHandMoved(indexX: Float, indexY: Float, pinching: Boolean) {
        mainHandler.post {
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

            // Front camera movement feels natural when mirrored horizontally.
            val targetX = (1f - indexX) * screenWidth
            val targetY = indexY * screenHeight

            if (smoothedX.isNaN() || smoothedY.isNaN()) {
                smoothedX = targetX
                smoothedY = targetY
            } else {
                smoothedX += (targetX - smoothedX) * SMOOTHING_ALPHA
                smoothedY += (targetY - smoothedY) * SMOOTHING_ALPHA
            }

            cursorOverlay?.moveTo(smoothedX, smoothedY, pinching)

            val now = System.currentTimeMillis()
            if (pinching && !wasPinching && now - lastClickMs >= CLICK_COOLDOWN_MS) {
                if (MyAccessibilityService.tap(smoothedX, smoothedY)) {
                    lastClickMs = now
                }
            }
            wasPinching = pinching
        }
    }

    override fun onHandLost() {
        mainHandler.post {
            smoothedX = Float.NaN
            smoothedY = Float.NaN
            wasPinching = false
        }
    }

    override fun onTrackerError(message: String) {
        Log.e(TAG, message)
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        planes[0].buffer.apply {
            rewind()
            bitmap.copyPixelsFromBuffer(this)
        }
        return bitmap
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
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

    private fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        bindCameraUseCases()
    }

    companion object {
        private const val TAG = "HandMouseService"
        private const val CHANNEL_ID = "hand_mouse"
        private const val NOTIFICATION_ID = 100
        private const val CLICK_COOLDOWN_MS = 300L
        private const val SMOOTHING_ALPHA = 0.28f

        const val ACTION_START = "com.example.handmouse.START"
        const val ACTION_STOP = "com.example.handmouse.STOP"

        @Volatile
        private var instance: HandMouseService? = null

        @Volatile
        private var pendingPreviewSurfaceProvider: Preview.SurfaceProvider? = null

        fun attachPreview(provider: Preview.SurfaceProvider?) {
            pendingPreviewSurfaceProvider = provider
            instance?.mainHandler?.post {
                instance?.setPreviewSurfaceProvider(provider)
            }
        }
    }
}
