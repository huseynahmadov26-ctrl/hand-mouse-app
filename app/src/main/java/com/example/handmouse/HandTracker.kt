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
import kotlin.math.max

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
    private var thumbIndexPinching = false
    private var indexMiddlePinching = false
    private var thumbIndexStableFrames = 0

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
            thumbIndexPinching = false
            indexMiddlePinching = false
            thumbIndexStableFrames = 0
            listener.onHandLost()
            return
        }

        val wrist = hand[WRIST]
        val thumbTip = hand[THUMB_TIP]
        val indexMcp = hand[INDEX_MCP]
        val indexTip = hand[INDEX_TIP]
        val middleMcp = hand[MIDDLE_MCP]
        val middleTip = hand[MIDDLE_TIP]
        val pinkyMcp = hand[PINKY_MCP]

        val palmWidth = hypot(
            (indexMcp.x() - pinkyMcp.x()).toDouble(),
            (indexMcp.y() - pinkyMcp.y()).toDouble()
        ).toFloat()
        val palmLength = hypot(
            (wrist.x() - middleMcp.x()).toDouble(),
            (wrist.y() - middleMcp.y()).toDouble()
        ).toFloat()
        val handScale = max(max(palmWidth, palmLength), MIN_HAND_SCALE)

        val thumbIndexDistance = hypot(
            (thumbTip.x() - indexTip.x()).toDouble(),
            (thumbTip.y() - indexTip.y()).toDouble()
        ).toFloat() / handScale
        val indexMiddleDistance = hypot(
            (indexTip.x() - middleTip.x()).toDouble(),
            (indexTip.y() - middleTip.y()).toDouble()
        ).toFloat() / handScale

        val thumbIndexCloseThreshold = clickThreshold.coerceIn(PINCH_CLOSE_THRESHOLD, PINCH_OPEN_THRESHOLD)
        val thumbIndexOpenThreshold = PINCH_OPEN_THRESHOLD
        when {
            thumbIndexDistance < thumbIndexCloseThreshold -> {
                thumbIndexStableFrames = (thumbIndexStableFrames + 1).coerceAtMost(PINCH_STABLE_FRAMES)
            }
            thumbIndexDistance > thumbIndexOpenThreshold -> {
                thumbIndexStableFrames = (thumbIndexStableFrames - 1).coerceAtLeast(-PINCH_STABLE_FRAMES)
            }
        }

        thumbIndexPinching = when {
            thumbIndexStableFrames >= PINCH_STABLE_FRAMES -> true
            thumbIndexStableFrames <= -PINCH_STABLE_FRAMES -> false
            else -> thumbIndexPinching
        }

        indexMiddlePinching = if (indexMiddlePinching) {
            indexMiddleDistance < INDEX_MIDDLE_OPEN_THRESHOLD
        } else {
            indexMiddleDistance < INDEX_MIDDLE_CLOSE_THRESHOLD
        }

        listener.onHandResult(
            HandResult(
                indexX = indexTip.x().coerceIn(0f, 1f),
                indexY = indexTip.y().coerceIn(0f, 1f),
                thumbIndexPinching = thumbIndexPinching,
                indexMiddlePinching = indexMiddlePinching
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
        private const val WRIST = 0
        private const val THUMB_TIP = 4
        private const val INDEX_MCP = 5
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
        private const val MIDDLE_TIP = 12
        private const val PINKY_MCP = 17
        private const val DEFAULT_TARGET_FPS = 30
        private const val DEFAULT_CLICK_THRESHOLD = 0.055f
        private const val DEFAULT_HAND_SCALE = 0.18f
        private const val MIN_HAND_SCALE = 0.08f
        private const val PINCH_CLOSE_THRESHOLD = 0.04f
        private const val PINCH_OPEN_THRESHOLD = 0.06f
        private const val PINCH_STABLE_FRAMES = 3
        private const val INDEX_MIDDLE_CLOSE_THRESHOLD = 0.34f
        private const val INDEX_MIDDLE_OPEN_THRESHOLD = 0.48f
    }
}
