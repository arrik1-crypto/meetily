package com.meetily.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.meetily.mobile.data.Waveform

/**
 * The meeting's audio as a row of bars: played ones in the accent, the rest
 * in the border colour, and the bar under the playhead lit.
 *
 * Tapping seeks. That is the point of drawing it at all — a plain seek bar
 * gives no clue where anyone was talking, so finding "the bit near the end
 * where it got loud" means scrubbing blind.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 0..1 per bar; empty until computed. */
    private var bars: FloatArray = FloatArray(0)
    private var progress = 0f

    /** Fraction 0..1 of the recording the user tapped. */
    var onSeek: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = context.resources.displayMetrics.density

    // The design's own ramp: played bars accm, unplayed brd, playhead acc1.
    // These are colour resources with night variants, so both themes follow.
    private val played = ContextCompat.getColor(context, R.color.accm)
    private val unplayed = ContextCompat.getColor(context, R.color.line_strong)
    private val head = ContextCompat.getColor(context, R.color.accent)

    fun setBars(values: FloatArray) {
        bars = values
        invalidate()
    }

    /** [fraction] 0..1 through the recording. */
    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        // Redraw only when it would move a visible amount: this is driven by
        // a playback ticker, and a full invalidate per tick for a sub-pixel
        // change is wasted work on the very screen that is also decoding.
        if (kotlin.math.abs(clamped - progress) < 0.002f) return
        progress = clamped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = if (bars.isEmpty()) Waveform.BARS else bars.size
        if (count <= 0 || width <= 0) return
        val gap = 2f * density
        val radius = 2f * density
        val barWidth = ((width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val usable = height.toFloat()
        val headIndex = (progress * count).toInt().coerceIn(0, count - 1)

        for (i in 0 until count) {
            // A flat placeholder while the peaks are still being computed —
            // an empty card would read as "no audio", which is a lie.
            val level = if (bars.isEmpty()) 0.18f else bars[i]
            // Floor: a silent stretch should still show a bar, or the card
            // looks broken rather than quiet.
            val h = (usable * (0.20f + 0.74f * level.coerceIn(0f, 1f)))
                .coerceAtLeast(2f * density)
            val left = i * (barWidth + gap)
            val top = (usable - h) / 2f
            rect.set(left, top, left + barWidth, top + h)
            paint.color = when {
                i == headIndex && bars.isNotEmpty() -> head
                i < headIndex -> played
                else -> unplayed
            }
            paint.alpha = if (i == headIndex && bars.isNotEmpty()) 255 else 200
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
            if (event.action == MotionEvent.ACTION_DOWN) performClick()
            onSeek?.invoke(fraction)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
