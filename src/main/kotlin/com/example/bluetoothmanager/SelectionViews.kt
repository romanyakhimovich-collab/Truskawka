package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

enum class ChatIcon { CLOSE, COPY, FORWARD, DELETE, MORE, REFRESH, BACK }

/** Outline toolbar icons drawn in a 24dp design grid, centered in the bounds. */
class ChatIconDrawable(
    private val icon: ChatIcon,
    color: Int,
    private val density: Float
) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val unit = density
        val save = canvas.save()
        // Map a 24x24 grid to the center of the bounds.
        canvas.translate(bounds.exactCenterX() - 12f * unit, bounds.exactCenterY() - 12f * unit)
        fun d(v: Float) = v * unit
        when (icon) {
            ChatIcon.CLOSE -> {
                canvas.drawLine(d(6f), d(6f), d(18f), d(18f), stroke)
                canvas.drawLine(d(18f), d(6f), d(6f), d(18f), stroke)
            }
            ChatIcon.COPY -> {
                rect.set(d(8f), d(7f), d(19f), d(20f))
                canvas.drawRoundRect(rect, d(2.5f), d(2.5f), stroke)
                path.reset()
                path.moveTo(d(5f), d(16f))
                path.lineTo(d(5f), d(6.5f))
                path.quadTo(d(5f), d(4f), d(7.5f), d(4f))
                path.lineTo(d(15f), d(4f))
                canvas.drawPath(path, stroke)
            }
            ChatIcon.FORWARD -> {
                path.reset()
                path.moveTo(d(4f), d(18f))
                path.lineTo(d(4f), d(15f))
                path.quadTo(d(4f), d(10f), d(9f), d(10f))
                path.lineTo(d(19f), d(10f))
                path.moveTo(d(15f), d(6f))
                path.lineTo(d(19f), d(10f))
                path.lineTo(d(15f), d(14f))
                canvas.drawPath(path, stroke)
            }
            ChatIcon.DELETE -> {
                canvas.drawLine(d(4f), d(6.5f), d(20f), d(6.5f), stroke)
                path.reset()
                path.moveTo(d(9f), d(6.5f))
                path.lineTo(d(9.5f), d(4f))
                path.lineTo(d(14.5f), d(4f))
                path.lineTo(d(15f), d(6.5f))
                path.moveTo(d(6f), d(6.5f))
                path.lineTo(d(7f), d(18.5f))
                path.quadTo(d(7.2f), d(20.5f), d(9.2f), d(20.5f))
                path.lineTo(d(14.8f), d(20.5f))
                path.quadTo(d(16.8f), d(20.5f), d(17f), d(18.5f))
                path.lineTo(d(18f), d(6.5f))
                canvas.drawPath(path, stroke)
                canvas.drawLine(d(10f), d(10f), d(10f), d(17f), stroke)
                canvas.drawLine(d(14f), d(10f), d(14f), d(17f), stroke)
            }
            ChatIcon.BACK -> {
                canvas.drawLine(d(19f), d(12f), d(5f), d(12f), stroke)
                path.reset()
                path.moveTo(d(11f), d(6f))
                path.lineTo(d(5f), d(12f))
                path.lineTo(d(11f), d(18f))
                canvas.drawPath(path, stroke)
            }
            ChatIcon.REFRESH -> {
                rect.set(d(5f), d(5f), d(19f), d(19f))
                canvas.drawArc(rect, -60f, 300f, false, stroke)
                // Arrow head at the arc's start (top-right).
                path.reset()
                path.moveTo(d(14.5f), d(3.5f))
                path.lineTo(d(15.5f), d(6.9f))
                path.lineTo(d(19f), d(6.2f))
                canvas.drawPath(path, stroke)
            }
            ChatIcon.MORE -> {
                canvas.drawCircle(d(12f), d(5.5f), d(1.8f), fill)
                canvas.drawCircle(d(12f), d(12f), d(1.8f), fill)
                canvas.drawCircle(d(12f), d(18.5f), d(1.8f), fill)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Selection marker: empty ring, or a filled green circle with a check mark. */
class SelectionCheckView(
    context: Context,
    private val checked: Boolean,
    ringColor: Int,
    checkedColor: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = checkedColor
        style = Paint.Style.FILL
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (!checked) {
            canvas.drawCircle(cx, cy, size / 2f - ring.strokeWidth, ring)
            return
        }
        canvas.drawCircle(cx, cy, size / 2f, disc)
        ring.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, size / 2f - ring.strokeWidth / 2f, ring)
        val u = size / 24f
        path.reset()
        path.moveTo(cx - 5f * u, cy + 0.5f * u)
        path.lineTo(cx - 1.5f * u, cy + 4f * u)
        path.lineTo(cx + 5.5f * u, cy - 3.5f * u)
        canvas.drawPath(path, check)
    }
}

/** Message row in selection mode: swallows all touches so a tap anywhere toggles selection. */
class SelectableRowLayout(context: Context) : LinearLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
