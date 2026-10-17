package com.nmcbride.monstervoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The big round power button. Off, it's a dark ring; on, it glows green and an
 * outer halo swells with the voice level.
 */
class MicButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var isOn = false
        set(value) {
            field = value
            invalidate()
        }

    /** Voice level, 0..1. */
    var level = 0f
        set(value) {
            field = value
            if (isOn) invalidate()
        }

    private var shownLevel = 0f
    private val density = resources.displayMetrics.density
    private val accent = context.getColor(R.color.accent)
    private val accentDeep = context.getColor(R.color.accent_deep)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val r = maxR * 0.72f

        // Ease the meter so it breathes instead of flickering.
        val target = if (isOn) sqrt(level.coerceIn(0f, 1f) * 6f).coerceAtMost(1f) else 0f
        shownLevel += (target - shownLevel) * 0.35f

        if (isOn) {
            val haloR = r + (maxR - r) * (0.35f + 0.65f * shownLevel)
            haloPaint.shader = RadialGradient(
                cx, cy, haloR,
                intArrayOf(withAlpha(accent, 150), withAlpha(accent, 40), Color.TRANSPARENT),
                floatArrayOf(r / haloR, (r + (haloR - r) * 0.5f) / haloR, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, haloR, haloPaint)
            fillPaint.shader = LinearGradient(cx, cy - r, cx, cy + r, accent, accentDeep, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, fillPaint)
            labelPaint.color = context.getColor(R.color.background)
            subPaint.color = withAlpha(context.getColor(R.color.background), 190)
        } else {
            fillPaint.shader = null
            fillPaint.color = context.getColor(R.color.surface_raised)
            canvas.drawCircle(cx, cy, r, fillPaint)
            ringPaint.color = withAlpha(accent, 200)
            canvas.drawCircle(cx, cy, r, ringPaint)
            labelPaint.color = accent
            subPaint.color = context.getColor(R.color.muted)
        }

        labelPaint.textSize = r * 0.30f
        subPaint.textSize = r * 0.12f
        canvas.drawText(if (isOn) "LIVE" else "START", cx, cy + labelPaint.textSize * 0.25f, labelPaint)
        canvas.drawText(if (isOn) "tap to stop" else "tap to go live", cx, cy + r * 0.42f, subPaint)

        if (isOn && kotlin.math.abs(target - shownLevel) > 0.01f) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
