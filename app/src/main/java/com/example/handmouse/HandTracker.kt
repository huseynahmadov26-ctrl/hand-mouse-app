package com.example.handmouse

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

class HandTracker(
    context: Context,
    private val listener: Listener
) {
    private val handLandmarker: HandLandmarker
    private var lastFrameMs = 0L
    @Volatile
    private var processingFrame = false
    private var minFrameIntervalMs = 1000L / DEFAULT_TARGET_FPS
    private var clickThreshold = DEFAULT_CLICK_THRESHOLD

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .setResultListener(this::onResult)
            .setErrorListener { error ->
                processingFrame = false
                listener.onTrackerError(error.message ?: error.toString())
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun updateSettings(targetFps: Int, clickThreshold: Float) {
        minFrameIntervalMs = 1000L / targetFps.coerceIn(20, 30)
        this.clickThreshold = clickThreshold.coerceIn(0.025f, 0.12f)
    }

    fun canAcceptFrame(now: Long = SystemClock.uptimeMillis()): Boolean =
        !processingFrame && now - lastFrameMs >= minFrameIntervalMs

    fun detect(bitmap: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!canAcceptFrame(timestampMs)) {
            return false
        }

        processingFrame = true
        lastFrameMs = timestampMs
        val image = BitmapImageBuilder(bitmap).build()
        handLandmarker.detectAsync(image, timestampMs)
        return true
    }

    fun close() {
        handLandmarker.close()
    }

    private fun onResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") image: MPImage) {
        processingFrame = false

        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size <= MIDDLE_TIP) {
            listener.onHandLost()
            return
        }

        val thumbTip = hand[THUMB_TIP]
        val indexTip = hand[INDEX_TIP]
        val middleTip = hand[MIDDLE_TIP]

        val thumbIndexDistance = hypot(
            (thumbTip.x() - indexTip.x()).toDouble(),
            (thumbTip.y() - indexTip.y()).toDouble()
        ).toFloat()
        val indexMiddleDistance = hypot(
            (indexTip.x() - middleTip.x()).toDouble(),
            (indexTip.y() - middleTip.y()).toDouble()
        ).toFloat()

        listener.onHandResult(
            HandResult(
                indexX = indexTip.x().coerceIn(0f, 1f),
                indexY = indexTip.y().coerceIn(0f, 1f),
                thumbIndexPinching = thumbIndexDistance < clickThreshold,
                indexMiddlePinching = indexMiddleDistance < INDEX_MIDDLE_THRESHOLD
            )
        )
    }

    data class HandResult(
        val indexX: Float,
        val indexY: Float,
        val thumbIndexPinching: Boolean,
        val indexMiddlePinching: Boolean
    )

    interface Listener {
        fun onHandResult(result: HandResult)
        fun onHandLost()
        fun onTrackerError(message: String)
    }

    companion object {
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val MIDDLE_TIP = 12
        private const val DEFAULT_TARGET_FPS = 30
        private const val DEFAULT_CLICK_THRESHOLD = 0.055f
        private const val INDEX_MIDDLE_THRESHOLD = 0.06f
    }
}
