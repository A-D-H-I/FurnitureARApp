package com.example.furnitureapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private var visible = false
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f

    fun setLine(ax: Float, ay: Float, bx: Float, by: Float) {
        visible = true
        x1 = ax
        y1 = ay
        x2 = bx
        y2 = by
        invalidate()
    }

    fun clearLine() {
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return

        // solid straight line
        canvas.drawLine(x1, y1, x2, y2, linePaint)

        // endpoint dots (iPhone style)
        val r = dp(5f)
        canvas.drawCircle(x1, y1, r, dotPaint)
        canvas.drawCircle(x2, y2, r, dotPaint)
    }

    private fun dp(v: Float): Float {
        return v * max(1f, resources.displayMetrics.density)
    }
}
