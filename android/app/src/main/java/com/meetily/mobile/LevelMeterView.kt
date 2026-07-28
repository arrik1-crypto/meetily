package com.meetily.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A scrolling bar of recent microphone levels.
 *
 * With live transcription off there is no text arriving to prove the app is
 * hearing the room, and a timer alone does not prove it either — a muted or
 * hijacked microphone counts up just as happily. This does: the bars move when
 * someone speaks and flatten when nobody does.
 *
 * Deliberately cheap. The RMS it draws is already computed by the capture loop
 * for silence detection, and it only invalidates when a new value arrives
 * (~10 Hz), so the meter is not what costs battery.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bars = FloatArray(64)
    private var head = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var activeColor = 0
    private var idleColor = 0

    init {
        val typed = context.obtainStyledAttributes(
            intArrayOf(
                androidx.appcompat.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        activeColor = typed.getColor(0, 0xFF4F8EF7.toInt())
        idleColor = typed.getColor(1, 0x66FFFFFF)
        typed.recycle()
    }

    /**
     * Adds one sample. [rms] is linear amplitude; speech sits around 0.02-0.2,
     * so it is mapped through a decibel-ish curve — a linear bar would show
     * almost nothing for normal conversation and then slam to full on a cough.
     */
    fun push(rms: Float) {
        val db = 20f * log10(max(rms, 1e-5f))
        // -60 dB (silence) .. -10 dB (loud) onto 0..1.
        val level = ((db + 60f) / 50f).coerceIn(0f, 1f)
        bars[head] = level
        head = (head + 1) % bars.size
        invalidate()
    }

    /** Flattens the meter, e.g. while paused. */
    fun clear() {
        bars.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val slot = w / bars.size
        val barWidth = max(1f, slot * 0.55f)
        val radius = barWidth / 2f
        val minHeight = barWidth
        for (i in bars.indices) {
            // Oldest on the left, newest on the right.
            val value = bars[(head + i) % bars.size]
            val barHeight = max(minHeight, value * h)
            val left = i * slot + (slot - barWidth) / 2f
            val top = (h - barHeight) / 2f
            paint.color = if (value > 0.02f) activeColor else idleColor
            paint.alpha = if (value > 0.02f) 255 else 90
            canvas.drawRoundRect(
                left, top, left + barWidth, top + barHeight, radius, radius, paint
            )
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = resolveSize(min(suggestedMinimumWidth, Int.MAX_VALUE), widthSpec)
        val h = resolveSize((56 * resources.displayMetrics.density).toInt(), heightSpec)
        setMeasuredDimension(w, h)
    }
}
