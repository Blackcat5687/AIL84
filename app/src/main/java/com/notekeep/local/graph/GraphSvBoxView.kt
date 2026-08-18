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

/**
 * Saturation/Value picking area, ported from the HTML's .hsv-box (a hue-colored square with a
 * white->transparent horizontal layer and a black->transparent vertical layer stacked on top,
 * plus a draggable cursor). Reports live [onChange] as the user drags, matching the original's
 * mousedown/mousemove/touchmove live-updating behavior (confirmation happens in the parent).
 */
class GraphSvBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((s: Float, v: Float) -> Unit)? = null

    var hue: Float = 0f
        set(value) { field = value; invalidate() }
    var saturation: Float = 0f
        set(value) { field = value; invalidate() }
    var value: Float = 0f
        set(value) { field = value; invalidate() }

    private val basePaint = Paint()
    private var satShader: LinearGradient? = null
    private var valShader: LinearGradient? = null
    private val satPaint = Paint()
    private val valPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val cursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(128, 0, 0, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            satShader = LinearGradient(0f, 0f, w.toFloat(), 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            satPaint.shader = satShader
        }
        if (h > 0) {
            valShader = LinearGradient(0f, h.toFloat(), 0f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            valPaint.shader = valShader
        }
    }

    override fun onDraw(canvas: Canvas) {
        basePaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), satPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), valPaint)

        val cx = saturation * width
        val cy = (1f - value) * height
        canvas.drawCircle(cx, cy, 8f, cursorShadowPaint)
        canvas.drawCircle(cx, cy, 7f, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (width == 0 || height == 0) return true
                saturation = min(1f, max(0f, event.x / width))
                value = min(1f, max(0f, 1f - event.y / height))
                onChange?.invoke(saturation, value)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return true
    }
}
