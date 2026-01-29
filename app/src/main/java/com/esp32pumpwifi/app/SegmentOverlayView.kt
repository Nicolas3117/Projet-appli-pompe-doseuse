package com.esp32pumpwifi.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class SegmentOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var segments: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (segments <= 1) return

        linePaint.strokeWidth = dpToPx(1f)

        val left = paddingLeft.toFloat()
        val right = width.toFloat() - paddingRight
        val top = paddingTop.toFloat()
        val bottom = height.toFloat() - paddingBottom

        val availableWidth = right - left
        if (availableWidth <= 0f) return

        val segmentWidth = availableWidth / segments
        for (i in 1 until segments) {
            val x = left + (segmentWidth * i)
            canvas.drawLine(x, top, x, bottom, linePaint)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return (dp * resources.displayMetrics.density).roundToInt().toFloat()
    }
}
