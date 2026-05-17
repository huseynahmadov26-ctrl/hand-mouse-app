package com.example.handmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

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
        val tapPath = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_DURATION_MS = 60L

        @Volatile
        private var instance: MyAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean = instance?.performTap(x, y) ?: false
    }
}
