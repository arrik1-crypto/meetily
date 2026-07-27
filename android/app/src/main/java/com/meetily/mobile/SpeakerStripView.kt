package com.meetily.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Who was talking, across the length of the recording — one coloured run per
 * speaker turn, tappable to jump to that turn.
 *
 * The waveform above shows where it was loud; this shows where a particular
 * person was speaking, which is usually the thing being looked for. Both are
 * drawn against the same timeline so they line up.
 */
class SpeakerStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** One turn: where it starts and ends, and which speaker slot owns it. */
    data class Turn(val startMs: Long, val endMs: Long, val slot: Int)

    private var turns: List<Turn> = emptyList()
    private var totalMs = 0L
    private var positionMs = 0L

    /** Tapped position, in ms into the recording. */
    var onSeekMs: ((Long) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = context.resources.displayMetrics.density

    private val idle = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOutlineVariant
    )

    /**
     * Up to three speaker colours, matching the design's S1/S2/S3. Beyond
     * that, slots repeat rather than inventing colours — a meeting with
     * eight speakers is better served by a repeating palette than by seven
     * shades nobody can tell apart.
     */
    private val slotColors by lazy {
        intArrayOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
            MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorTertiary,
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary)
            ),
            MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSecondary
            )
        )
    }

    fun colorForSlot(slot: Int): Int =
        if (slot < 0) idle else slotColors[slot % slotColors.size]

    fun submit(turns: List<Turn>, totalMs: Long) {
        this.turns = turns
        this.totalMs = totalMs.coerceAtLeast(1L)
        invalidate()
    }

    fun setPositionMs(ms: Long) {
        positionMs = ms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return
        val h = height.toFloat()
        val radius = 2f * density

        // Ground: the stretches nobody is speaking.
        paint.color = idle
        paint.alpha = 90
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.alpha = 255
        val activeSlot = turns.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs
        }?.slot
        for (turn in turns) {
            val left = (turn.startMs.toFloat() / totalMs) * width
            val right = (turn.endMs.toFloat() / totalMs) * width
            // A one-second turn in an hour-long meeting is a third of a pixel;
            // give every turn a minimum width or short interjections vanish.
            val drawnRight = maxOf(right, left + 2f * density)
            paint.color = colorForSlot(turn.slot)
            paint.alpha = if (activeSlot == null || turn.slot == activeSlot) 255 else 130
            rect.set(left, 0f, drawnRight.coerceAtMost(width.toFloat()), h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            performClick()
            val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
            onSeekMs?.invoke((fraction * totalMs).toLong())
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
