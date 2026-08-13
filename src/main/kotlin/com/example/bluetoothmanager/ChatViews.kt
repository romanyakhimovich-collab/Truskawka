package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import java.io.File

class CheckMarksView(
    context: Context,
    private val status: MessageStatus,
    color: Int
) : View(context) {
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = px(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (status) {
            MessageStatus.SENDING -> drawClock(canvas)
            MessageStatus.DELIVERED -> drawMark(canvas, px(6f))
            MessageStatus.READ -> {
                drawMark(canvas, px(1f))
                drawMark(canvas, px(7f))
            }
            MessageStatus.FAILED -> drawFail(canvas)
        }
    }

    private fun drawClock(canvas: Canvas) {
        canvas.drawCircle(px(9f), px(6f), px(4.2f), markPaint)
        canvas.drawLine(px(9f), px(6f), px(9f), px(3.5f), markPaint)
        canvas.drawLine(px(9f), px(6f), px(11.2f), px(7.5f), markPaint)
    }

    private fun drawMark(canvas: Canvas, startX: Float) {
        val path = Path().apply {
            moveTo(startX, px(6.5f))
            lineTo(startX + px(3.5f), px(10f))
            lineTo(startX + px(10f), px(2f))
        }
        canvas.drawPath(path, markPaint)
    }

    private fun drawFail(canvas: Canvas) {
        canvas.drawLine(px(5f), px(3f), px(13f), px(11f), markPaint)
        canvas.drawLine(px(13f), px(3f), px(5f), px(11f), markPaint)
    }

    private fun px(value: Float): Float =
        value * resources.displayMetrics.density
}

class BorderedImageView(
    context: Context,
    borderColor: Int
) : ImageView(context) {
    private val clipPath = Path()
    private val clipRect = RectF()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = borderColor
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(8).toFloat()
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(saveCount)

        val halfStroke = borderPaint.strokeWidth / 2f
        val rect = RectF(
            halfStroke,
            halfStroke,
            width - halfStroke,
            height - halfStroke
        )
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

class AvatarView(
    context: Context,
    private val label: String,
    private val imagePath: String?,
    private val accentColor: Int,
    private val verified: Boolean = false
) : View(context) {
    private val clipPath = Path()
    private val boundsRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (verified) LEAF_GREEN else SOFT_PINK_STROKE
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bitmap: Bitmap? by lazy {
        imagePath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { BitmapFactory.decodeFile(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        boundsRect.set(left, top, left + size, top + size)
        clipPath.reset()
        clipPath.addOval(boundsRect, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)
        val source = bitmap
        if (source != null && source.width > 0 && source.height > 0) {
            val scale = maxOf(size / source.width.toFloat(), size / source.height.toFloat())
            val dx = left + (size - source.width * scale) / 2f
            val dy = top + (size - source.height * scale) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            canvas.drawBitmap(source, matrix, imagePaint)
        } else {
            canvas.drawOval(boundsRect, fillPaint)
            val initial = label.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            textPaint.textSize = size * 0.42f
            val baseline = boundsRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(initial, boundsRect.centerX(), baseline, textPaint)
        }
        canvas.restoreToCount(save)
        canvas.drawOval(boundsRect.insetCopy(ringPaint.strokeWidth / 2f), ringPaint)
    }

    private fun RectF.insetCopy(value: Float): RectF =
        RectF(left + value, top + value, right - value, bottom - value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

class SavedNotesAvatarView(context: Context) : View(context) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF171018.toInt()
        style = Paint.Style.FILL
    }
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD6E0.toInt()
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5C8A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5C8A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f
        canvas.drawCircle(cx, cy, radius, circlePaint)

        rect.set(cx - size * 0.21f, cy - size * 0.28f, cx + size * 0.23f, cy + size * 0.26f)
        canvas.drawRoundRect(rect, size * 0.08f, size * 0.08f, paperPaint)
        canvas.drawLine(rect.left + size * 0.1f, rect.top + size * 0.17f, rect.right - size * 0.09f, rect.top + size * 0.17f, linePaint)
        canvas.drawLine(rect.left + size * 0.1f, rect.top + size * 0.29f, rect.right - size * 0.12f, rect.top + size * 0.29f, linePaint)
        canvas.drawLine(rect.left + size * 0.1f, rect.top + size * 0.41f, rect.right - size * 0.18f, rect.top + size * 0.41f, linePaint)

        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}

class AvatarCropView(
    context: Context,
    private val source: Bitmap
) : View(context) {
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val circleRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val clearPath = Path()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private var minScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastX = detector.focusX
                lastY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor).coerceIn(minScale, minScale * 5f)
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrain()
                lastX = detector.focusX
                lastY = detector.focusY
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                lastX = detector.focusX
                lastY = detector.focusY
            }
        }
    )

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = gestureFocusX(event)
                lastY = gestureFocusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val focusX = gestureFocusX(event)
                    val focusY = gestureFocusY(event)
                    imageMatrix.postTranslate(focusX - lastX, focusY - lastY)
                    constrain()
                    invalidate()
                    lastX = focusX
                    lastY = focusY
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = gestureFocusX(event, skipPointerIndex = event.actionIndex)
                lastY = gestureFocusY(event, skipPointerIndex = event.actionIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(source, imageMatrix, imagePaint)
        clearPath.reset()
        clearPath.fillType = Path.FillType.EVEN_ODD
        clearPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        clearPath.addOval(circleRect, Path.Direction.CCW)
        canvas.drawPath(clearPath, dimPaint)
        canvas.drawOval(circleRect, ringPaint)
    }

    fun crop(size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = size / circleRect.width()
        val exportMatrix = Matrix(imageMatrix).apply {
            postTranslate(-circleRect.left, -circleRect.top)
            postScale(scale, scale)
        }
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawBitmap(source, exportMatrix, imagePaint)
        return output
    }

    private fun reset() {
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return
        val diameter = minOf(width, height) * 0.78f
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        circleRect.set(left, top, left + diameter, top + diameter)
        minScale = maxOf(
            circleRect.width() / source.width.toFloat(),
            circleRect.height() / source.height.toFloat()
        )
        currentScale = minScale
        val dx = circleRect.centerX() - source.width * minScale / 2f
        val dy = circleRect.centerY() - source.height * minScale / 2f
        imageMatrix.reset()
        imageMatrix.setScale(minScale, minScale)
        imageMatrix.postTranslate(dx, dy)
        constrain()
        invalidate()
    }

    private fun constrain() {
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        imageMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.left > circleRect.left) dx = circleRect.left - rect.left
        if (rect.right < circleRect.right) dx = circleRect.right - rect.right
        if (rect.top > circleRect.top) dy = circleRect.top - rect.top
        if (rect.bottom < circleRect.bottom) dy = circleRect.bottom - rect.bottom
        imageMatrix.postTranslate(dx, dy)
        imageMatrix.invert(inverseMatrix)
    }

    private fun gestureFocusX(event: MotionEvent, skipPointerIndex: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == skipPointerIndex) continue
            sum += event.getX(index)
            count++
        }
        return if (count > 0) sum / count else lastX
    }

    private fun gestureFocusY(event: MotionEvent, skipPointerIndex: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == skipPointerIndex) continue
            sum += event.getY(index)
            count++
        }
        return if (count > 0) sum / count else lastY
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

class ZoomableImageView(
    context: Context,
    private val onDragAtOriginalSize: () -> Unit
) : ImageView(context) {
    private val contentMatrix = Matrix()
    private var minScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor).coerceIn(minScale, minScale * 5f)
                val factor = target / currentScale
                currentScale = target
                contentMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainImage()
                imageMatrix = contentMatrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale > minScale * 1.4f) minScale else minScale * 2.4f
                val factor = target / currentScale
                currentScale = target
                contentMatrix.postScale(factor, factor, e.x, e.y)
                constrainImage()
                imageMatrix = contentMatrix
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentScale <= minScale * 1.05f) {
                    onDragAtOriginalSize()
                    return true
                }
                return false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { resetImageMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetImageMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = scaleDetector.focusX
                lastY = scaleDetector.focusY
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && currentScale > minScale * 1.01f) {
                    contentMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    constrainImage()
                    imageMatrix = contentMatrix
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun resetImageMatrix() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return

        val scale = minOf(
            width.toFloat() / drawable.intrinsicWidth.toFloat(),
            height.toFloat() / drawable.intrinsicHeight.toFloat()
        )
        val dx = (width - drawable.intrinsicWidth * scale) / 2f
        val dy = (height - drawable.intrinsicHeight * scale) / 2f

        minScale = scale
        currentScale = scale
        contentMatrix.reset()
        contentMatrix.setScale(scale, scale)
        contentMatrix.postTranslate(dx, dy)
        imageMatrix = contentMatrix
    }

    private fun constrainImage() {
        val drawable = drawable ?: return
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        contentMatrix.mapRect(rect)

        val dx = when {
            rect.width() <= width -> width / 2f - rect.centerX()
            rect.left > 0f -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= height -> height / 2f - rect.centerY()
            rect.top > 0f -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        contentMatrix.postTranslate(dx, dy)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
