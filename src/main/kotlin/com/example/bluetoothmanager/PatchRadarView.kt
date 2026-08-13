package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

class PatchRadarView(
    context: Context,
    private val labelProvider: (String) -> String
) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SOFT_PINK_STROKE
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STRAWBERRY_RED
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1FE94F64
        style = Paint.Style.FILL
    }
    private val directPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LEAF_GREEN
        style = Paint.Style.FILL
    }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FF4359
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BERRY_TEXT_DIM
        textSize = dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var directCount: Int = 0
    private var meshCount: Int = 0

    fun setPeerCounts(direct: Int, mesh: Int) {
        directCount = direct
        meshCount = mesh
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.42f

        haloPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            0x26E94F64,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, haloPaint)
        haloPaint.shader = null
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.72f, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.45f, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.18f, centerPaint)
        canvas.drawText(labelProvider("you"), cx, cy + dp(4), centerTextPaint)

        drawPeers(canvas, cx, cy, radius * 0.52f, directCount, directPaint)
        drawPeers(canvas, cx, cy, radius * 0.84f, meshCount, meshPaint)

        canvas.drawText("${labelProvider("direct")}: $directCount", cx, height - dp(26).toFloat(), textPaint)
        canvas.drawText("${labelProvider("mesh_hops")}: $meshCount", cx, height - dp(10).toFloat(), textPaint)
    }

    private fun drawPeers(canvas: Canvas, cx: Float, cy: Float, radius: Float, count: Int, paint: Paint) {
        if (count <= 0) return
        repeat(count.coerceAtMost(14)) { index ->
            val angle = (index.toFloat() / count.toFloat()) * (Math.PI * 2.0)
            val x = cx + (kotlin.math.cos(angle).toFloat() * radius)
            val y = cy + (kotlin.math.sin(angle).toFloat() * radius)
            canvas.drawCircle(x, y, dp(4).toFloat(), paint)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
