package com.example.bluetoothmanager

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

enum class SettingsGlyph { PERSON, CHAT, THEME, KEY, SPEAKER, RADIO, IMAGE, PIE, CLOCK, GLOBE }

/** Colored rounded-square tile with a white glyph, as in messenger settings lists. */
class SettingsTileDrawable(
    private val glyph: SettingsGlyph,
    private val tileColor: Int,
    private val density: Float
) : Drawable() {
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tileColor
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val left = b.exactCenterX() - size / 2f
        val top = b.exactCenterY() - size / 2f
        // Slightly lighter at the top for a soft, glossy tile.
        tilePaint.shader = LinearGradient(
            0f, top, 0f, top + size,
            lighten(tileColor), tileColor,
            Shader.TileMode.CLAMP
        )
        rect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, tilePaint)

        val save = canvas.save()
        // Glyphs are drawn on a 24-unit grid scaled to ~70% of the tile.
        val unit = size * 0.7f / 24f
        canvas.translate(left + size * 0.15f, top + size * 0.15f)
        canvas.scale(unit, unit)
        stroke.strokeWidth = 2f
        drawGlyph(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawGlyph(canvas: Canvas) {
        when (glyph) {
            SettingsGlyph.PERSON -> {
                canvas.drawCircle(12f, 8.5f, 3.6f, fill)
                rect.set(5.5f, 14f, 18.5f, 21f)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
            }
            SettingsGlyph.CHAT -> {
                rect.set(3.5f, 4.5f, 20.5f, 17f)
                canvas.drawRoundRect(rect, 5f, 5f, fill)
                path.reset()
                path.moveTo(6.5f, 15.5f)
                path.lineTo(5.5f, 21f)
                path.lineTo(11.5f, 16.5f)
                path.close()
                canvas.drawPath(path, fill)
                cut.strokeWidth = 1.8f
                cut.style = Paint.Style.STROKE
                cut.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(8f, 9f, 16f, 9f, cut)
                canvas.drawLine(8f, 12.5f, 13.5f, 12.5f, cut)
                cut.style = Paint.Style.FILL
            }
            SettingsGlyph.THEME -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                canvas.drawCircle(12f, 12f, 7.5f, stroke)
                canvas.drawArc(rect, 90f, 180f, true, fill)
            }
            SettingsGlyph.KEY -> {
                canvas.drawCircle(8.5f, 9f, 5f, fill)
                canvas.drawCircle(8f, 8.5f, 1.7f, cut)
                stroke.strokeWidth = 3f
                canvas.drawLine(11.5f, 12f, 19f, 19.5f, stroke)
                canvas.drawLine(16f, 16.5f, 18f, 14.5f, stroke)
                stroke.strokeWidth = 2f
            }
            SettingsGlyph.SPEAKER -> {
                path.reset()
                path.moveTo(3.5f, 9.5f)
                path.lineTo(7f, 9.5f)
                path.lineTo(11.5f, 5.5f)
                path.lineTo(11.5f, 18.5f)
                path.lineTo(7f, 14.5f)
                path.lineTo(3.5f, 14.5f)
                path.close()
                canvas.drawPath(path, fill)
                rect.set(9.5f, 8.5f, 16.5f, 15.5f)
                canvas.drawArc(rect, -50f, 100f, false, stroke)
                rect.set(7f, 5f, 21f, 19f)
                canvas.drawArc(rect, -50f, 100f, false, stroke)
            }
            SettingsGlyph.RADIO -> {
                canvas.drawCircle(12f, 12f, 2.4f, fill)
                rect.set(6.5f, 6.5f, 17.5f, 17.5f)
                canvas.drawArc(rect, -45f, 90f, false, stroke)
                canvas.drawArc(rect, 135f, 90f, false, stroke)
                rect.set(2.5f, 2.5f, 21.5f, 21.5f)
                canvas.drawArc(rect, -45f, 90f, false, stroke)
                canvas.drawArc(rect, 135f, 90f, false, stroke)
            }
            SettingsGlyph.IMAGE -> {
                rect.set(3.5f, 5f, 20.5f, 19f)
                canvas.drawRoundRect(rect, 3f, 3f, fill)
                path.reset()
                path.moveTo(5.5f, 17f)
                path.lineTo(10f, 11f)
                path.lineTo(13f, 14.5f)
                path.lineTo(15f, 12.5f)
                path.lineTo(18.5f, 17f)
                path.close()
                canvas.drawPath(path, cut)
                canvas.drawCircle(15.5f, 8.8f, 1.6f, cut)
            }
            SettingsGlyph.PIE -> {
                rect.set(4f, 5f, 18f, 19f)
                canvas.drawArc(rect, -90f, 270f, true, fill)
                // Detached quarter slice.
                rect.offset(1.8f, -1.8f)
                canvas.drawArc(rect, 180f, 90f, true, fill)
            }
            SettingsGlyph.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8f, fill)
                cut.strokeWidth = 2f
                cut.style = Paint.Style.STROKE
                cut.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(12f, 12f, 12f, 7.5f, cut)
                canvas.drawLine(12f, 12f, 15.5f, 14f, cut)
                cut.style = Paint.Style.FILL
            }
            SettingsGlyph.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                rect.set(8.5f, 4f, 15.5f, 20f)
                canvas.drawOval(rect, stroke)
                canvas.drawLine(4f, 12f, 20f, 12f, stroke)
                canvas.drawLine(12f, 4f, 12f, 20f, stroke)
            }
        }
    }

    private fun lighten(color: Int): Int {
        fun mix(channel: Int) = channel + ((255 - channel) * 0.22f).toInt()
        return Color.argb(Color.alpha(color), mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    override fun setAlpha(alpha: Int) {
        tilePaint.alpha = alpha
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        tilePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
