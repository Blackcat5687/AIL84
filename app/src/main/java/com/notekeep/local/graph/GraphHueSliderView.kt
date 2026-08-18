package com.notekeep.local.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Horizontal hue slider (0-360), ported from the HTML's .hue-slider gradient input[type=range]. */
class GraphHueSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((hue: Float) -> Unit)? = null

    var hue: Float = 0f
        set(value) { field = value; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222"); style = Paint.Style.STROKE; strokeWidth = 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            val colors = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            )
            trackPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val trackHeight = height * 0.55f
        val top = (height - trackHeight) / 2f
        canvas.drawRoundRect(0f, top, width.toFloat(), top + trackHeight, trackHeight / 2f, trackHeight / 2f, trackPaint)

        val thumbX = (hue / 360f) * width
        val thumbRadius = height * 0.42f
        canvas.drawCircle(thumbX, height / 2f, thumbRadius, thumbPaint)
        canvas.drawCircle(thumbX, height / 2f, thumbRadius, thumbBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (width == 0) return true
                hue = min(360f, max(0f, (event.x / width) * 360f))
                onChange?.invoke(hue)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return true
    }
}
