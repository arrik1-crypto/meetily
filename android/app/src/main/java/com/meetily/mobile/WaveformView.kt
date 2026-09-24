package com.meetily.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

    private companion object {
        /** The "working on it" ripple: low, gentle, obviously synthetic. */
        val BASELINE = floatArrayOf(0.06f, 0.10f, 0.14f, 0.10f)

        /** How much to fade the card while it holds no real data. */
        const val ANALYSING_ALPHA = 90
    }

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
        analysing = false
        invalidate()
    }

    /**
     * Whether the loudness bars are still being worked out.
     *
     * Separate from "no bars", because the two look identical otherwise and
     * mean opposite things. A row of equal bars does not read as "still
     * loading" — it reads as "this recording is flat", which is a lie about
     * the audio rather than an admission about the app. While this is set,
     * the card draws a low, faint, uneven baseline that is obviously not a
     * waveform, and the caller says so in words next to it.
     */
    private var analysing = false

    fun setAnalysing(value: Boolean) {
        if (analysing == value) return
        analysing = value
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
            // Three states, deliberately distinguishable: real bars; a low
            // uneven baseline while they are being computed; and — if that
            // ever fails outright — the same flat row as before, which at
            // least does not claim to be data.
            val level = when {
                bars.isNotEmpty() -> bars[i]
                // A fixed, repeating ripple. Not random, so it does not
                // shimmer between redraws, but plainly not a waveform.
                analysing -> BASELINE[i % BASELINE.size]
                else -> 0.18f
            }
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
            // Faded while there is no real data, so the card reads as
            // pending rather than as a quiet recording.
            paint.alpha = when {
                bars.isEmpty() && analysing -> ANALYSING_ALPHA
                i == headIndex && bars.isNotEmpty() -> 255
                else -> 200
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false

    /**
     * Seeks on a tap or a sideways drag only. This sits inside the meeting's
     * vertical list, which takes a gesture only once it has moved past the
     * touch slop; seeking on ACTION_DOWN meant scrolling the page from a
     * point on the waveform jumped playback to wherever the finger landed.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                scrubbing = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (!scrubbing && dx > touchSlop && dx > dy) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) onSeek?.invoke(fractionAt(event.x))
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tap = kotlin.math.abs(event.x - downX) <= touchSlop &&
                    kotlin.math.abs(event.y - downY) <= touchSlop
                if (scrubbing || tap) {
                    if (tap) performClick()
                    onSeek?.invoke(fractionAt(event.x))
                }
                scrubbing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // The list took the gesture: it was a scroll, not a seek.
                scrubbing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fractionAt(x: Float): Float = (x / width.toFloat()).coerceIn(0f, 1f)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
