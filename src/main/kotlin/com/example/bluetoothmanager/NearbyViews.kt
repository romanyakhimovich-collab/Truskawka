package com.example.bluetoothmanager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** "Searching" radar: a center dot with rings expanding and fading out, looped while attached. */
class NearbyRadarView(
    context: Context,
    private val accentColor: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accentColor and 0x00FFFFFF) or 0x33000000
        style = Paint.Style.FILL
    }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(width, height) / 2f - ringPaint.strokeWidth
        for (ring in 0 until RING_COUNT) {
            val t = (phase + ring.toFloat() / RING_COUNT) % 1f
            ringPaint.alpha = ((1f - t) * 200).toInt()
            canvas.drawCircle(cx, cy, maxRadius * (0.18f + 0.82f * t), ringPaint)
        }
        canvas.drawCircle(cx, cy, 16f * density, haloPaint)
        canvas.drawCircle(cx, cy, 9f * density, centerPaint)
    }

    private companion object {
        const val RING_COUNT = 3
    }
}

/** Small status dot that softly pulses while [pulsing] is true. */
class PulseDotView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var pulsing: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value && isAttachedToWindow) animator.start() else animator.cancel()
            if (!value) alpha = 1f
        }
    private val animator = ValueAnimator.ofFloat(1f, 0.25f).apply {
        duration = 700L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulsing) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}
