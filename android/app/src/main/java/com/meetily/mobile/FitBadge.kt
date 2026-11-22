package com.meetily.mobile

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.meetily.mobile.data.DeviceFit

/**
 * The memory-fit badge every model picker card carries, and the warning
 * shown before a tight or too-big model is chosen. The warning never blocks:
 * the figures are estimates (see DeviceFit), and the user may know better.
 */
object FitBadge {

    fun label(context: Context, verdict: DeviceFit.Verdict): String {
        val base = context.getString(
            when (verdict.level) {
                DeviceFit.Level.FITS -> R.string.devicefit_badge_fits
                DeviceFit.Level.TIGHT -> R.string.devicefit_badge_tight
                DeviceFit.Level.TOO_BIG -> R.string.devicefit_badge_too_big
            }
        )
        return if (verdict.slowHere) context.getString(R.string.devicefit_badge_slow, base) else base
    }

    /** What a screen reader says for the badge: the level and the figures. */
    fun description(context: Context, verdict: DeviceFit.Verdict): String {
        val need = verdict.need
        val memory = when (verdict.level) {
            DeviceFit.Level.FITS ->
                context.getString(R.string.devicefit_desc_fits, need.comfortableRamGb)
            DeviceFit.Level.TIGHT -> context.getString(
                R.string.devicefit_desc_tight, need.minRamGb, need.comfortableRamGb
            )
            DeviceFit.Level.TOO_BIG ->
                context.getString(R.string.devicefit_desc_too_big, need.minRamGb)
        }
        return if (verdict.slowHere) {
            memory + " " + context.getString(R.string.devicefit_desc_slow)
        } else {
            memory
        }
    }

    /** Shows [verdict] on [view], or hides it when there is none. */
    fun bind(view: TextView, verdict: DeviceFit.Verdict?) {
        if (verdict == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = label(view.context, verdict)
        view.contentDescription = description(view.context, verdict)
        // Colour repeats what the words already say; it is never the only
        // cue. The accent is not used: on the pill it falls short of 4.5:1.
        view.setTextColor(
            MaterialColors.getColor(
                view,
                when (verdict.level) {
                    DeviceFit.Level.FITS -> com.google.android.material.R.attr.colorOnSurfaceVariant
                    DeviceFit.Level.TIGHT -> com.google.android.material.R.attr.colorOnSurface
                    DeviceFit.Level.TOO_BIG -> com.google.android.material.R.attr.colorError
                }
            )
        )
    }

    /**
     * Runs [onProceed] at once for a model that fits (or has no verdict);
     * otherwise asks first, naming the model as [name]. Cancel runs nothing.
     */
    fun confirmChoice(
        context: Context,
        name: String,
        verdict: DeviceFit.Verdict?,
        alreadyActive: Boolean,
        onProceed: () -> Unit
    ) {
        if (verdict == null || !DeviceFit.warnsOnChoice(verdict, alreadyActive)) {
            onProceed()
            return
        }
        val need = verdict.need
        val message = StringBuilder(
            if (verdict.level == DeviceFit.Level.TOO_BIG) {
                context.getString(R.string.devicefit_warn_too_big, name, need.minRamGb)
            } else {
                context.getString(
                    R.string.devicefit_warn_tight, name, need.minRamGb, need.comfortableRamGb
                )
            }
        )
        if (verdict.slowHere) {
            message.append(' ').append(context.getString(R.string.devicefit_warn_slow))
        }
        message.append("\n\n").append(context.getString(R.string.devicefit_warn_estimate))
        AlertDialog.Builder(context)
            .setTitle(
                if (verdict.level == DeviceFit.Level.TOO_BIG) {
                    R.string.devicefit_warn_title_too_big
                } else {
                    R.string.devicefit_warn_title_tight
                }
            )
            .setMessage(message)
            .setPositiveButton(R.string.devicefit_warn_continue) { _, _ -> onProceed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
