package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.Retention
import java.text.DateFormat
import java.util.Date

/**
 * The automatic clean-up dialogs, from Settings and the Storage screen.
 *
 * Choosing a rule never saves it straight away. Its first run is shown —
 * how many meetings it reaches now, which ones, what is kept — and only a
 * yes saves the rule and runs it. Turning it off needs no question. The
 * same preview answers the daily run when it stopped short of a large
 * batch (RetentionRunner.runAutomatic).
 */
object RetentionUi {

    /** How many meetings the preview names before "…and N more". */
    private const val LISTED = 8

    fun summary(context: Context, rule: Retention.Rule): String = when {
        !rule.enabled -> context.getString(R.string.storage_retention_off)
        rule.mode == Retention.Mode.AUDIO ->
            context.getString(R.string.storage_retention_audio_summary, rule.days)
        else -> context.getString(R.string.storage_retention_meetings_summary, rule.days)
    }

    /** Off, remove old audio, or delete old meetings; then the age. */
    fun choose(activity: Activity, onChanged: () -> Unit) {
        val current = RetentionRunner.rule(activity)
        val modes = arrayOf<CharSequence>(
            activity.getString(R.string.storage_retention_mode_off),
            activity.getString(R.string.storage_retention_mode_audio),
            activity.getString(R.string.storage_retention_mode_meetings)
        )
        val checked = when {
            !current.enabled -> 0
            current.mode == Retention.Mode.AUDIO -> 1
            else -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.storage_retention_title)
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> if (current.enabled) {
                        RetentionRunner.setRule(activity, Retention.OFF)
                        Toast.makeText(activity, R.string.storage_retention_off_toast, Toast.LENGTH_SHORT)
                            .show()
                        onChanged()
                    }
                    1 -> chooseDays(activity, Retention.Mode.AUDIO, current, onChanged)
                    else -> chooseDays(activity, Retention.Mode.MEETINGS, current, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseDays(
        activity: Activity,
        mode: Retention.Mode,
        current: Retention.Rule,
        onChanged: () -> Unit
    ) {
        val choices = Retention.DAY_CHOICES
        val labels = choices.map {
            activity.resources.getQuantityString(R.plurals.storage_retention_days, it, it)
        }.toTypedArray<CharSequence>()
        val preferred = if (current.enabled && current.mode == mode) current.days else Retention.DEFAULT_DAYS
        val checked = choices.indexOf(preferred).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.storage_retention_days_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                preview(activity, Retention.Rule(mode, choices[which]), review = false, onChanged = onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Works out what [rule] reaches now (on a worker), shows it, and on yes
     * saves the rule (unless [review]: it is already on) and applies it.
     * Cancelling saves nothing. [onChanged] runs on the main thread after a
     * rule was saved or a run finished.
     */
    fun preview(activity: Activity, rule: Retention.Rule, review: Boolean, onChanged: () -> Unit) {
        if (!rule.enabled) return
        val app = activity.applicationContext
        val waiting = AlertDialog.Builder(activity)
            .setMessage(R.string.storage_retention_checking)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Thread {
            val plan = try {
                RetentionRunner.plan(app, rule)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                // Cancelled, or the screen went away meanwhile.
                if (activity.isFinishing || activity.isDestroyed || !waiting.isShowing) {
                    return@runOnUiThread
                }
                waiting.dismiss()
                if (plan == null) {
                    Toast.makeText(activity, R.string.storage_scan_failed, Toast.LENGTH_LONG).show()
                } else {
                    showPreview(activity, plan, review, onChanged)
                }
            }
        }.apply {
            name = "retention-plan"
            start()
        }
    }

    private fun showPreview(
        activity: Activity,
        plan: RetentionRunner.Plan,
        review: Boolean,
        onChanged: () -> Unit
    ) {
        val rule = plan.rule
        val res = activity.resources
        val count = plan.selected.size
        val message = buildString {
            append(
                when {
                    count == 0 -> activity.getString(R.string.storage_retention_now_none)
                    rule.mode == Retention.Mode.AUDIO -> res.getQuantityString(
                        R.plurals.storage_retention_now_audio, count, count,
                        Formatter.formatShortFileSize(activity, plan.audioBytes)
                    )
                    else -> res.getQuantityString(R.plurals.storage_retention_now_meetings, count, count)
                }
            )
            if (count > 0) {
                val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
                append("\n")
                for (candidate in plan.selected.take(LISTED)) {
                    append("\n• ")
                    append(plan.titles[candidate.id].orEmpty())
                    append(" (")
                    append(dateFormat.format(Date(candidate.createdAtMs)))
                    append(")")
                }
                if (count > LISTED) {
                    append("\n")
                    append(res.getQuantityString(R.plurals.storage_retention_more, count - LISTED, count - LISTED))
                }
            }
            if (!review) {
                append("\n\n")
                append(
                    activity.getString(
                        if (rule.mode == Retention.Mode.AUDIO) R.string.storage_retention_later_audio
                        else R.string.storage_retention_later_meetings,
                        rule.days
                    )
                )
            }
            append("\n\n")
            append(activity.getString(R.string.storage_retention_kept))
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (review) R.string.storage_retention_review_title
                else R.string.storage_retention_preview_title
            )
            .setMessage(message)
        if (review) {
            if (count > 0) {
                builder.setPositiveButton(R.string.storage_retention_review_confirm) { _, _ ->
                    applyNow(activity, plan, onChanged)
                }
            }
            builder.setNegativeButton(R.string.storage_retention_review_later, null)
            builder.setNeutralButton(R.string.storage_retention_review_turn_off) { _, _ ->
                RetentionRunner.setRule(activity, Retention.OFF)
                Toast.makeText(activity, R.string.storage_retention_off_toast, Toast.LENGTH_SHORT).show()
                onChanged()
            }
        } else {
            builder.setPositiveButton(
                if (count > 0) R.string.storage_retention_turn_on_now else R.string.storage_retention_turn_on
            ) { _, _ ->
                if (count > 0) {
                    // Armed once this first run is done (applyNow), so the
                    // daily job cannot start alongside it.
                    RetentionRunner.setRule(activity, rule, arm = false)
                    applyNow(activity, plan, onChanged)
                } else {
                    RetentionRunner.setRule(activity, rule)
                    Toast.makeText(activity, R.string.storage_retention_on, Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            }
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        // Meeting titles: kept out of screenshots when the user asked for that.
        builder.showSecure()
    }

    /**
     * Applies [plan] on a worker and says what it did, then arms the daily
     * run if the rule is (still) on: a first run saves its rule unarmed.
     */
    private fun applyNow(activity: Activity, plan: RetentionRunner.Plan, onChanged: () -> Unit) {
        val app = activity.applicationContext
        Thread {
            val outcome = try {
                RetentionRunner.apply(app, plan)
            } catch (_: Exception) {
                RetentionRunner.Outcome(0, 0L)
            } finally {
                RetentionJobService.ensureScheduled(app)
            }
            val text = when {
                outcome.count == 0 -> app.getString(R.string.storage_retention_on)
                plan.rule.mode == Retention.Mode.AUDIO -> app.resources.getQuantityString(
                    R.plurals.storage_retention_done_audio, outcome.count, outcome.count,
                    Formatter.formatShortFileSize(app, outcome.bytes)
                )
                else -> app.resources.getQuantityString(
                    R.plurals.storage_retention_done_meetings, outcome.count, outcome.count
                )
            }
            activity.runOnUiThread {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                if (!activity.isFinishing && !activity.isDestroyed) onChanged()
            }
        }.apply {
            name = "retention-apply"
            start()
        }
    }
}
