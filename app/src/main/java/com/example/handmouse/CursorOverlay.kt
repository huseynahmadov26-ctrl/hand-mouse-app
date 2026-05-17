package com.example.handmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

class CursorOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val cursorSizePx = 36
    private val cursorView = CursorView(context)
    private var isShown = false

    private val layoutParams = WindowManager.LayoutParams(
        cursorSizePx,
        cursorSizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    fun show() {
        if (!isShown) {
            windowManager.addView(cursorView, layoutParams)
            isShown = true
        }
    }

    fun moveTo(screenX: Float, screenY: Float, clicking: Boolean) {
        if (!isShown) return

        layoutParams.x = (screenX - cursorSizePx / 2f).roundToInt()
        layoutParams.y = (screenY - cursorSizePx / 2f).roundToInt()
        cursorView.clicking = clicking
        windowManager.updateViewLayout(cursorView, layoutParams)
        cursorView.invalidate()
    }

    fun hide() {
        if (isShown) {
            windowManager.removeView(cursorView)
            isShown = false
        }
    }

    private class CursorView(context: Context) : View(context) {
        var clicking: Boolean = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 11, 125, 117)
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = if (clicking) width * 0.42f else width * 0.32f
            canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
            canvas.drawCircle(width / 2f, height / 2f, radius, strokePaint)
        }
    }
}
