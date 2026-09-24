package com.meetily.mobile

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * The Nocturne EQ: seven 3dp bars in the accent ramp (accdd/accd/accm/acc1
 * mirrored), each breathing on its own period so the cluster never looks
 * mechanical. Paused settles every bar to 18% height. Pure canvas — no
 * per-bar animators.
 */
class EqBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val heightsDp = floatArrayOf(12f, 18f, 26f, 30f, 26f, 18f, 12f)
    private val periods = floatArrayOf(0.9f, 1.1f, 0.8f, 1.0f, 0.85f, 1.15f, 0.95f)
    private val phases = floatArrayOf(0f, 1.3f, 2.1f, 0.7f, 2.9f, 1.7f, 0.4f)
    private val colorRes = intArrayOf(
        R.color.accdd, R.color.accd, R.color.accm, R.color.accent,
        R.color.accm, R.color.accd, R.color.accdd
    )
    // Resolved once rather than seven lookups a frame.
    private val colors = IntArray(colorRes.size) { ContextCompat.getColor(context, colorRes[it]) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Next frame, ~15 fps. This re-armed with postInvalidateOnAnimation on
     * every draw, which forced a frame each vsync for the whole meeting — at
     * 120 Hz on a screen held on, for bars whose slowest period is a second.
     * One named runnable, removed before it is re-posted, so a setPaused()
     * invalidate can never start a second loop alongside the first.
     */
    private val nextFrame = Runnable { invalidate() }

    @Volatile private var paused = false

    fun setPaused(value: Boolean) {
        paused = value
        if (!value) postInvalidateOnAnimation()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val barWidth = 3f * density
        val gap = 3f * density
        val radius = 2f * density
        val total = heightsDp.size * barWidth + (heightsDp.size - 1) * gap
        var x = (width - total) / 2f
        val centerY = height / 2f
        val t = (System.currentTimeMillis() % 1_000_000L) / 1000f
        for (i in heightsDp.indices) {
            val base = heightsDp[i] * density
            val factor = if (paused) {
                0.18f
            } else {
                0.575f + 0.425f * sin((t / periods[i] + phases[i]) * TWO_PI)
            }
            val barHeight = base * factor
            paint.color = colors[i]
            canvas.drawRoundRect(
                x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f,
                radius, radius, paint
            )
            x += barWidth + gap
        }
        removeCallbacks(nextFrame)
        if (!paused && isAttachedToWindow) postDelayed(nextFrame, FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(nextFrame)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TWO_PI = (2.0 * Math.PI).toFloat()
        private const val FRAME_MS = 66L
    }
}
