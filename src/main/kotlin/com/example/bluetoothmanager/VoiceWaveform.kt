package com.example.bluetoothmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Builds a loudness profile (one bar per slice) by decoding the voice file to PCM.
 * Works for both sent and received voice notes because it only needs the audio file.
 */
object VoiceWaveforms {
    const val BAR_COUNT = 36

    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val waiting = HashMap<String, MutableList<(FloatArray) -> Unit>>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voice-waveform").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cached(path: String): FloatArray? = cache[path]

    /** Delivers the waveform on the main thread; decodes at most once per file. */
    fun load(path: String, onReady: (FloatArray) -> Unit) {
        cache[path]?.let { onReady(it); return }
        val startDecode = synchronized(waiting) {
            val listeners = waiting[path]
            if (listeners != null) {
                listeners += onReady
                false
            } else {
                waiting[path] = mutableListOf(onReady)
                true
            }
        }
        if (!startDecode) return
        executor.execute {
            val peaks = runCatching { decode(path) }.getOrNull() ?: flat()
            cache[path] = peaks
            val listeners = synchronized(waiting) { waiting.remove(path).orEmpty() }
            mainHandler.post { listeners.forEach { it(peaks) } }
        }
    }

    private fun flat(): FloatArray = FloatArray(BAR_COUNT) { 0.12f }

    private fun decode(path: String): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return flat()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return flat()
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            fun windowSizeOf(f: MediaFormat): Int {
                val rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(22_050)
                val channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
                return (rate * channels / 50).coerceAtLeast(1) // 20 ms windows
            }

            var windowSize = windowSizeOf(format)
            val windows = ArrayList<Float>()
            var windowPeak = 0
            var windowCount = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    windowSize = windowSizeOf(codec.outputFormat)
                } else if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val samples = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (samples.hasRemaining()) {
                            val value = abs(samples.get().toInt())
                            if (value > windowPeak) windowPeak = value
                            if (++windowCount >= windowSize) {
                                windows += windowPeak / 32768f
                                windowPeak = 0
                                windowCount = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (windowCount > 0) windows += windowPeak / 32768f
            return toBars(windows)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun toBars(windows: List<Float>): FloatArray {
        if (windows.isEmpty()) return flat()
        val bars = FloatArray(BAR_COUNT) { index ->
            val from = index * windows.size / BAR_COUNT
            val to = ((index + 1) * windows.size / BAR_COUNT).coerceAtLeast(from + 1).coerceAtMost(windows.size)
            if (from >= windows.size) 0f else (from until to).maxOf { windows[it] }
        }
        val loudest = bars.maxOrNull()?.takeIf { it > 0.01f } ?: return flat()
        // sqrt lifts quiet speech so the shape stays readable.
        return FloatArray(BAR_COUNT) { sqrt(bars[it] / loudest).coerceIn(0.12f, 1f) }
    }

    private const val DECODE_TIMEOUT_MS = 5_000L
}

/** Loudness bars; bars before [progress] use the active color. Tap/drag seeks. */
class VoiceWaveformView(
    context: Context,
    private val activeColor: Int,
    private val idleColor: Int
) : View(context) {
    var peaks: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var onSeek: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bar = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val values = peaks
        val count = values?.size ?: VoiceWaveforms.BAR_COUNT
        if (count == 0 || width == 0) return
        val step = width.toFloat() / count
        val barWidth = (step * 0.55f).coerceAtLeast(density)
        val radius = barWidth / 2f
        val centerY = height / 2f
        val minHeight = 3f * density
        for (index in 0 until count) {
            val level = values?.get(index) ?: 0.12f
            val barHeight = (level * height).coerceAtLeast(minHeight)
            val left = index * step + (step - barWidth) / 2f
            bar.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f)
            paint.color = if ((index + 0.5f) / count <= progress) activeColor else idleColor
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seek = onSeek ?: return super.onTouchEvent(event)
        val fraction = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = fraction
            }
            MotionEvent.ACTION_MOVE -> progress = fraction
            MotionEvent.ACTION_UP -> {
                progress = fraction
                seek(fraction)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Round play/pause button drawn in code. */
class VoicePlayButtonView(
    context: Context,
    private val circleColor: Int,
    private val iconColor: Int
) : View(context) {
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }
    private val triangle = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, size / 2f, circlePaint)
        val unit = size / 10f
        if (playing) {
            val barWidth = unit * 1.1f
            val barHeight = unit * 3.6f
            val radius = barWidth / 2.5f
            rect.set(cx - unit * 1.6f, cy - barHeight / 2f, cx - unit * 1.6f + barWidth, cy + barHeight / 2f)
            canvas.drawRoundRect(rect, radius, radius, iconPaint)
            rect.offset(unit * 2.1f, 0f)
            canvas.drawRoundRect(rect, radius, radius, iconPaint)
        } else {
            // Optical centering: the triangle's visual center sits right of its bounding box.
            triangle.reset()
            triangle.moveTo(cx - unit * 1.3f, cy - unit * 2f)
            triangle.lineTo(cx + unit * 2.1f, cy)
            triangle.lineTo(cx - unit * 1.3f, cy + unit * 2f)
            triangle.close()
            canvas.drawPath(triangle, iconPaint)
        }
    }
}

/** Views of one voice bubble that playback progress updates. */
class VoicePlaybackViews(
    val button: VoicePlayButtonView,
    val waveform: VoiceWaveformView,
    val timeLabel: TextView
)
