package com.example.handmouse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

open class ForegroundTrackingService : Service(), LifecycleOwner, HandTracker.Listener {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: SharedPreferences
    private var cameraProvider: ProcessCameraProvider? = null
    private var handTracker: HandTracker? = null
    private var cursorOverlay: CursorOverlay? = null
    private var reusableBitmap: Bitmap? = null
    private var reusableBitmapWidth = 0
    private var reusableBitmapHeight = 0
    private var reusableRowBuffer = ByteArray(0)
    private var reusableBitmapBuffer: ByteBuffer? = null
    private var reusableRotatedBitmap: Bitmap? = null
    private var reusableRotatedBitmapRotation = 0
    private val reusableRotationCanvas = Canvas()
    private val reusableRotationMatrix = Matrix()
    private var lastClickMs = 0L
    private var smoothedX = Float.NaN
    private var smoothedY = Float.NaN
    private var lastRawX = Float.NaN
    private var lastRawY = Float.NaN
    private var lastCursorUpdateMs = 0L
    private var screenWidth = 1f
    private var screenHeight = 1f
    private var gestureState = GestureState.IDLE
    private var pinchStartMs = 0L
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var scrollLastY = Float.NaN
    private var scrollAccumulator = 0f
    private var lastScrollMs = 0L
    private var settings = TrackingSettings()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settings = TrackingSettings.from(prefs)
        handTracker?.updateSettings(TARGET_FPS, settings.clickThreshold)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraExecutor = Executors.newSingleThreadExecutor()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        settings = TrackingSettings.from(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        refreshScreenSize()

        if (Settings.canDrawOverlays(this)) {
            cursorOverlay = CursorOverlay(this).also { it.show() }
        }

        handTracker = HandTracker(this, this).also {
            it.updateSettings(TARGET_FPS, settings.clickThreshold)
        }
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
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
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

        val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        provider.unbindAll()
        provider.bindToLifecycle(this, cameraSelector, analysis)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val tracker = handTracker ?: return
            val timestampMs = SystemClock.uptimeMillis()
            if (!tracker.canAcceptFrame(timestampMs)) {
                return
            }

            val bitmap = imageProxy.copyRgbaToReusableBitmap()
            val rotated = bitmap.rotateIfNeeded(imageProxy.imageInfo.rotationDegrees)
            val frameForMediaPipe = rotated.copy(Bitmap.Config.ARGB_8888, false)
            tracker.detect(frameForMediaPipe, timestampMs)
        } catch (error: Throwable) {
            Log.e(TAG, "Frame analysis failed", error)
        } finally {
            imageProxy.close()
        }
    }

    override fun onHandResult(result: HandTracker.HandResult) {
        mainHandler.post {
            refreshScreenSize()
            val mapped = mapIndexTipToScreen(result.indexX, result.indexY)
            updateCursor(mapped.first, mapped.second)

            if (result.indexMiddlePinching) {
                handleScrollGesture(mapped.second)
                resetClickState()
            } else {
                scrollLastY = Float.NaN
                scrollAccumulator = 0f
                handleClickHoldGesture(result.thumbIndexPinching)
            }

            cursorOverlay?.moveTo(
                x = smoothedX,
                y = smoothedY,
                clicking = result.thumbIndexPinching || gestureState == GestureState.HOLD_ACTIVE || gestureState == GestureState.DRAGGING,
                scrolling = result.indexMiddlePinching
            )
        }
    }

    override fun onHandLost() {
        mainHandler.post {
            smoothedX = Float.NaN
            smoothedY = Float.NaN
            lastRawX = Float.NaN
            lastRawY = Float.NaN
            lastCursorUpdateMs = 0L
            scrollLastY = Float.NaN
            scrollAccumulator = 0f
            resetClickState()
        }
    }

    override fun onTrackerError(message: String) {
        Log.e(TAG, message)
    }

    private fun mapIndexTipToScreen(indexX: Float, indexY: Float): Pair<Float, Float> {
        val sensitivity = settings.cursorSensitivity
        val normalizedX = if (settings.mirrorCursor) 1f - indexX else indexX
        val scaledX = (normalizedX - 0.5f) * sensitivity + 0.5f
        val scaledY = (indexY - 0.5f) * sensitivity + 0.5f
        return Pair(
            (scaledX.coerceIn(0f, 1f) * screenWidth),
            (scaledY.coerceIn(0f, 1f) * screenHeight)
        )
    }

    private fun updateCursor(targetX: Float, targetY: Float) {
        val now = SystemClock.uptimeMillis()
        if (smoothedX.isNaN() || smoothedY.isNaN()) {
            smoothedX = targetX
            smoothedY = targetY
            lastRawX = targetX
            lastRawY = targetY
            lastCursorUpdateMs = now
            return
        }

        val rawJump = hypot((targetX - lastRawX).toDouble(), (targetY - lastRawY).toDouble()).toFloat()
        val maxAllowedJump = hypot(screenWidth.toDouble(), screenHeight.toDouble()).toFloat() * MAX_RAW_JUMP_RATIO
        if (rawJump > maxAllowedJump) {
            return
        }

        lastRawX = targetX
        lastRawY = targetY
        val elapsedMs = if (lastCursorUpdateMs == 0L) TARGET_FRAME_MS else (now - lastCursorUpdateMs).coerceIn(1L, 80L)
        lastCursorUpdateMs = now

        val dx = targetX - smoothedX
        val dy = targetY - smoothedY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance < JITTER_DEAD_ZONE_PX) {
            return
        }

        val maxStep = hypot(screenWidth.toDouble(), screenHeight.toDouble()).toFloat() * MAX_STEP_RATIO
        val stepScale = if (distance > maxStep) maxStep / distance else 1f
        val distanceBoost = (distance / CURSOR_DISTANCE_BOOST_PX).coerceIn(0f, 1f)
        val frameScale = (elapsedMs / TARGET_FRAME_MS.toFloat()).coerceIn(0.5f, 2.2f)
        val alpha = (settings.smoothingAlpha * (1f + distanceBoost * CURSOR_DISTANCE_BOOST) * frameScale)
            .coerceIn(MIN_CURSOR_ALPHA, MAX_CURSOR_ALPHA)
        smoothedX += dx * alpha * stepScale
        smoothedY += dy * alpha * stepScale
        smoothedX = smoothedX.coerceIn(0f, screenWidth)
        smoothedY = smoothedY.coerceIn(0f, screenHeight)
    }

    private fun handleClickHoldGesture(pinching: Boolean) {
        val now = System.currentTimeMillis()
        if (gestureState == GestureState.IDLE && pinching && now - lastClickMs < CLICK_COOLDOWN_MS) {
            return
        }

        when (gestureState) {
            GestureState.IDLE -> {
                if (pinching) {
                    gestureState = GestureState.CLICK_PENDING
                    pinchStartMs = now
                    pinchStartX = smoothedX
                    pinchStartY = smoothedY
                    dragLastX = smoothedX
                    dragLastY = smoothedY
                }
            }

            GestureState.CLICK_PENDING -> {
                if (!pinching) {
                    if (now - pinchStartMs < HOLD_THRESHOLD_MS) {
                        if (MyAccessibilityService.tap(smoothedX, smoothedY)) {
                            cursorOverlay?.pulseClick()
                            lastClickMs = now
                        }
                    }
                    gestureState = GestureState.IDLE
                } else if (now - pinchStartMs >= HOLD_THRESHOLD_MS) {
                    MyAccessibilityService.longPress(pinchStartX, pinchStartY)
                    lastClickMs = now
                    gestureState = GestureState.HOLD_ACTIVE
                }
            }

            GestureState.HOLD_ACTIVE -> {
                if (!pinching) {
                    gestureState = GestureState.IDLE
                } else if (distanceFrom(dragLastX, dragLastY, smoothedX, smoothedY) > DRAG_START_PX) {
                    MyAccessibilityService.drag(dragLastX, dragLastY, smoothedX, smoothedY)
                    dragLastX = smoothedX
                    dragLastY = smoothedY
                    gestureState = GestureState.DRAGGING
                }
            }

            GestureState.DRAGGING -> {
                if (!pinching) {
                    gestureState = GestureState.IDLE
                } else if (distanceFrom(dragLastX, dragLastY, smoothedX, smoothedY) > DRAG_STEP_PX) {
                    MyAccessibilityService.drag(dragLastX, dragLastY, smoothedX, smoothedY)
                    dragLastX = smoothedX
                    dragLastY = smoothedY
                }
            }
        }
    }

    private fun handleScrollGesture(currentY: Float) {
        val previousY = scrollLastY
        scrollLastY = currentY
        if (previousY.isNaN()) return

        val deltaY = currentY - previousY
        scrollAccumulator += deltaY * settings.scrollSpeed
        val now = System.currentTimeMillis()
        if (abs(scrollAccumulator) < SCROLL_TRIGGER_PX || now - lastScrollMs < SCROLL_MIN_INTERVAL_MS) {
            return
        }

        val travel = scrollAccumulator.coerceIn(-SCROLL_MAX_TRAVEL_PX, SCROLL_MAX_TRAVEL_PX)
        val centerX = screenWidth * 0.5f
        val centerY = screenHeight * 0.5f
        val startY = (centerY + travel * 0.5f).coerceIn(80f, screenHeight - 80f)
        val endY = (centerY - travel * 0.5f).coerceIn(80f, screenHeight - 80f)
        if (MyAccessibilityService.swipe(centerX, startY, centerX, endY, SCROLL_DURATION_MS)) {
            lastScrollMs = now
            scrollAccumulator = 0f
        }
    }

    private fun resetClickState() {
        gestureState = GestureState.IDLE
    }

    private fun refreshScreenSize() {
        val windowManager = getSystemService(WindowManager::class.java)
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
    }

    private fun ImageProxy.copyRgbaToReusableBitmap(): Bitmap {
        val bitmap = if (reusableBitmap == null || reusableBitmapWidth != width || reusableBitmapHeight != height) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                reusableBitmap = it
                reusableBitmapWidth = width
                reusableBitmapHeight = height
            }
        } else {
            reusableBitmap!!
        }

        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val packedRowBytes = width * RGBA_BYTES_PER_PIXEL
        if (reusableRowBuffer.size < packedRowBytes * height) {
            reusableRowBuffer = ByteArray(packedRowBytes * height)
            reusableBitmapBuffer = ByteBuffer.wrap(reusableRowBuffer)
        }

        buffer.rewind()
        var outputOffset = 0
        for (rowIndex in 0 until height) {
            buffer.get(reusableRowBuffer, outputOffset, packedRowBytes)
            outputOffset += packedRowBytes
            val padding = rowStride - packedRowBytes
            if (padding > 0 && rowIndex < height - 1) {
                buffer.position(buffer.position() + padding)
            }
        }

        val bitmapBuffer = reusableBitmapBuffer ?: ByteBuffer.wrap(reusableRowBuffer).also {
            reusableBitmapBuffer = it
        }
        bitmapBuffer.position(0)
        bitmapBuffer.limit(packedRowBytes * height)
        bitmap.copyPixelsFromBuffer(bitmapBuffer)
        return bitmap
    }

    private fun Bitmap.rotateIfNeeded(degrees: Int): Bitmap {
        if (degrees == 0) return this

        val targetWidth = if (degrees % 180 == 0) width else height
        val targetHeight = if (degrees % 180 == 0) height else width
        val rotated = if (reusableRotatedBitmap == null || reusableRotatedBitmapRotation != degrees ||
            reusableRotatedBitmap?.width != targetWidth || reusableRotatedBitmap?.height != targetHeight
        ) {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                reusableRotatedBitmap = it
                reusableRotatedBitmapRotation = degrees
            }
        } else {
            reusableRotatedBitmap!!
        }

        reusableRotationMatrix.reset()
        reusableRotationMatrix.postRotate(degrees.toFloat())
        reusableRotationCanvas.setBitmap(rotated)
        reusableRotationCanvas.drawBitmap(this, reusableRotationMatrix, null)
        return rotated
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

    private enum class GestureState {
        IDLE,
        CLICK_PENDING,
        HOLD_ACTIVE,
        DRAGGING
    }

    data class TrackingSettings(
        val cursorSensitivity: Float = 1.15f,
        val smoothingAlpha: Float = 0.28f,
        val clickThreshold: Float = 0.055f,
        val scrollSpeed: Float = 1.3f,
        val backgroundTracking: Boolean = true,
        val mirrorCursor: Boolean = true
    ) {
        companion object {
            fun from(prefs: SharedPreferences): TrackingSettings =
                TrackingSettings(
                    cursorSensitivity = prefs.getFloat(KEY_CURSOR_SENSITIVITY, 1.15f),
                    smoothingAlpha = prefs.getFloat(KEY_SMOOTHING, 0.28f),
                    clickThreshold = prefs.getFloat(KEY_CLICK_THRESHOLD, 0.055f),
                    scrollSpeed = prefs.getFloat(KEY_SCROLL_SPEED, 1.3f),
                    backgroundTracking = prefs.getBoolean(KEY_BACKGROUND_TRACKING, true),
                    mirrorCursor = prefs.getBoolean(KEY_MIRROR_CURSOR, true)
                )
        }
    }

    companion object {
        private const val TAG = "ForegroundTracking"
        private const val CHANNEL_ID = "hand_mouse"
        private const val NOTIFICATION_ID = 100
        private const val TARGET_FPS = 30
        private const val TARGET_FRAME_MS = 1000L / TARGET_FPS
        private const val HOLD_THRESHOLD_MS = 500L
        private const val CLICK_COOLDOWN_MS = 500L
        private const val JITTER_DEAD_ZONE_PX = 0.75f
        private const val MAX_RAW_JUMP_RATIO = 0.55f
        private const val MAX_STEP_RATIO = 0.28f
        private const val MIN_CURSOR_ALPHA = 0.12f
        private const val MAX_CURSOR_ALPHA = 0.82f
        private const val CURSOR_DISTANCE_BOOST_PX = 180f
        private const val CURSOR_DISTANCE_BOOST = 0.65f
        private const val DRAG_START_PX = 18f
        private const val DRAG_STEP_PX = 8f
        private const val SCROLL_TRIGGER_PX = 28f
        private const val SCROLL_MAX_TRAVEL_PX = 520f
        private const val SCROLL_MIN_INTERVAL_MS = 90L
        private const val SCROLL_DURATION_MS = 170L
        private const val RGBA_BYTES_PER_PIXEL = 4

        const val PREFS_NAME = "hand_mouse_settings"
        const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
        const val KEY_SMOOTHING = "smoothing"
        const val KEY_CLICK_THRESHOLD = "click_threshold"
        const val KEY_SCROLL_SPEED = "scroll_speed"
        const val KEY_BACKGROUND_TRACKING = "background_tracking"
        const val KEY_MIRROR_CURSOR = "mirror_cursor"

        const val ACTION_START = "com.example.handmouse.START"
        const val ACTION_STOP = "com.example.handmouse.STOP"

        @Volatile
        private var instance: ForegroundTrackingService? = null

        fun attachPreview(@Suppress("UNUSED_PARAMETER") provider: Any?) = Unit

        private fun distanceFrom(startX: Float, startY: Float, endX: Float, endY: Float): Float =
            hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
    }
}
