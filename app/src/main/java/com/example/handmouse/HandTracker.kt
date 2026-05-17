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

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener(this::onResult)
            .setErrorListener { error -> listener.onTrackerError(error.message ?: error.toString()) }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap) {
        val image = BitmapImageBuilder(bitmap).build()
        handLandmarker.detectAsync(image, SystemClock.uptimeMillis())
    }

    fun close() {
        handLandmarker.close()
    }

    private fun onResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") image: MPImage) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size <= INDEX_TIP) {
            listener.onHandLost()
            return
        }

        val thumbTip = hand[THUMB_TIP]
        val indexTip = hand[INDEX_TIP]
        val pinchDistance = hypot(
            (thumbTip.x() - indexTip.x()).toDouble(),
            (thumbTip.y() - indexTip.y()).toDouble()
        ).toFloat()

        listener.onHandMoved(
            indexX = indexTip.x().coerceIn(0f, 1f),
            indexY = indexTip.y().coerceIn(0f, 1f),
            pinching = pinchDistance < PINCH_THRESHOLD
        )
    }

    interface Listener {
        fun onHandMoved(indexX: Float, indexY: Float, pinching: Boolean)
        fun onHandLost()
        fun onTrackerError(message: String)
    }

    companion object {
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val PINCH_THRESHOLD = 0.055f
    }
}
