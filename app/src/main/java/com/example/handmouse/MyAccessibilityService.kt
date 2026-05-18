package com.example.handmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot
import kotlin.math.max

class MyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun performTap(x: Float, y: Float): Boolean {
        val tapPath = Path().apply { moveTo(x, y) }
        val tapGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(tapGesture, null, null)
    }

    private fun performLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val distance = hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
        val duration = max(DRAG_MIN_DURATION_MS, (distance * DRAG_MS_PER_PIXEL).toLong())
        return performSwipe(startX, startY, endX, endY, duration.coerceAtMost(DRAG_MAX_DURATION_MS))
    }

    companion object {
        private const val TAP_DURATION_MS = 60L
        private const val LONG_PRESS_DURATION_MS = 650L
        private const val DRAG_MIN_DURATION_MS = 35L
        private const val DRAG_MAX_DURATION_MS = 140L
        private const val DRAG_MS_PER_PIXEL = 1.2f

        @Volatile
        private var instance: MyAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean = instance?.performTap(x, y) ?: false

        fun longPress(x: Float, y: Float): Boolean = instance?.performLongPress(x, y) ?: false

        fun drag(startX: Float, startY: Float, endX: Float, endY: Float): Boolean =
            instance?.performDrag(startX, startY, endX, endY) ?: false

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean =
            instance?.performSwipe(startX, startY, endX, endY, durationMs) ?: false
    }
}
