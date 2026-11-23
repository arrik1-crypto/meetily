package com.meetily.mobile

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.SummaryAlternatives
import com.meetily.mobile.summarize.SummaryTemplates
import java.text.DateFormat
import java.util.Date

/**
 * The summary card's row of styles: a pill for each style (template) the
 * meeting has a summary from, the one shown selected, then "+" for a summary
 * with another style; and a line saying which summary is shown and when it
 * was written. Tapping a pill shows that summary at once, with no model run
 * (SummaryAlternatives.switchTo); holding one deletes it, after asking.
 *
 * Every pill is drawn 36dp tall inside a 48dp target and says what it is
 * and does to a screen reader; the one shown is selected, and the status
 * line is a polite live region for a switch or a deletion. The meeting
 * screen saves the change its one way ([Host.summariesChanged]).
 */
class SummaryVersionsBar(
    private val activity: AppCompatActivity,
    before: View,
    private val host: Host
) {

    /** What the meeting screen does for the row. Main thread only. */
    interface Host {
        /** The meeting on screen, once read. */
        fun meeting(): Meeting?

        /** [m]'s summaries changed here: save it and show the summary now active. */
        fun summariesChanged(m: Meeting)

        /** The style picker, for a summary with another style. */
        fun newSummary()
    }

    private val root: View
    private val pills: LinearLayout
    private val status: TextView

    /** The next [render] follows a switch or a deletion, so its status line is spoken. */
    private var announceNext = false

    init {
        val parent = before.parent as ViewGroup
        root = LayoutInflater.from(activity).inflate(R.layout.summary_versions_bar, parent, false)
        pills = root.findViewById(R.id.summaryVersionsPills)
        status = root.findViewById(R.id.summaryVersionsStatus)
        parent.addView(root, parent.indexOfChild(before))
    }

    /** While a summary is being written: its progress takes the card. */
    fun hide() {
        root.visibility = View.GONE
    }

    /** Draws the row from the meeting; gone while it has no summary. */
    fun render() {
        val announce = announceNext
        announceNext = false
        val m = host.meeting()
        if (m == null || !SummaryAlternatives.hasAny(m)) {
            root.visibility = View.GONE
            return
        }
        val templates = SummaryTemplates.allWithCustom(activity)
        val labels = templates.associate { it.key to it.label(activity) }
        val versions = SummaryAlternatives.versions(m, templates.map { it.key })
        val withLanguage = SummaryAlternatives.namesLanguages(versions)

        // Reused by position, so a switch (which never reorders the pills)
        // leaves the screen reader's focus on the pill just tapped.
        while (pills.childCount > versions.size + 1) pills.removeViewAt(pills.childCount - 1)
        while (pills.childCount < versions.size + 1) pills.addView(newPill())
        for ((index, version) in versions.withIndex()) {
            val name = styleName(version.template, labels)
            val shown = if (withLanguage && version.language != null) {
                activity.getString(
                    R.string.summaries_label_language, name, LanguagePicker.displayName(version.language)
                )
            } else {
                name
            }
            bindPill(pills.getChildAt(index) as TextView, m, version, shown)
        }
        bindNewPill(pills.getChildAt(versions.size) as TextView)

        val active = versions.firstOrNull { it.active }
        val text = if (active == null) {
            ""
        } else {
            val name = styleName(active.template, labels)
            if (active.createdAtMs > 0L) {
                activity.getString(
                    R.string.summaries_status, name,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(active.createdAtMs))
                )
            } else {
                activity.getString(R.string.summaries_status_undated, name)
            }
        }
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        A11y.setStatusText(status, text, announce)
        root.visibility = View.VISIBLE
    }

    private fun newPill(): TextView = TextView(activity).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = (220 * resources.displayMetrics.density).toInt()
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
    }

    private fun stylePill(pill: TextView, selected: Boolean) {
        val density = activity.resources.displayMetrics.density
        // The home screen's filter pill: 36dp to look at, 48dp to touch.
        A11y.pillTouchTarget(
            pill,
            if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill,
            visualHeightPx = (36 * density).toInt(),
            paddingHorizontalPx = (14 * density).toInt(),
            paddingVerticalPx = (7 * density).toInt()
        )
        pill.setTextColor(
            themeColor(
                if (selected) {
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
        pill.isSelected = selected
    }

    private fun bindPill(
        pill: TextView,
        m: Meeting,
        version: SummaryAlternatives.Version,
        shown: String
    ) {
        stylePill(pill, version.active)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.text = shown
        // The style alone: a style's own name may already end in "Summary".
        pill.contentDescription = if (version.stale) {
            activity.getString(R.string.summaries_pill_desc_stale, shown)
        } else {
            shown
        }
        if (version.active) {
            // The summary on screen: nothing to switch to, nothing to delete here.
            A11y.clearClick(pill)
            clearLongClick(pill)
            A11y.setActions(pill, emptyList())
            return
        }
        pill.setOnClickListener { show(version.template) }
        A11y.labelClick(pill, activity.getString(R.string.summaries_action_show))
        pill.setOnLongClickListener {
            confirmDelete(m.id, version.template, shown)
            true
        }
        A11y.labelLongClick(pill, activity.getString(R.string.summaries_action_delete))
        // Also in the actions menu, for screen readers without long press.
        A11y.setActions(
            pill,
            listOf(
                A11y.Action(activity.getString(R.string.summaries_action_delete)) {
                    confirmDelete(m.id, version.template, shown)
                }
            )
        )
    }

    private fun bindNewPill(pill: TextView) {
        stylePill(pill, selected = false)
        pill.text = activity.getString(R.string.summaries_new_pill)
        pill.gravity = Gravity.CENTER
        pill.contentDescription = activity.getString(R.string.summaries_new)
        pill.setOnClickListener { host.newSummary() }
        A11y.labelClick(pill, activity.getString(R.string.summaries_new))
        clearLongClick(pill)
        A11y.setActions(pill, emptyList())
    }

    /**
     * No hold on [pill], and no labelled hold left behind for a screen
     * reader by the summary this reused pill showed before.
     */
    private fun clearLongClick(pill: View) {
        // Before isLongClickable: setting a listener, even null, sets it.
        pill.setOnLongClickListener(null)
        pill.isLongClickable = false
        pill.setTag(R.id.a11y_long_click_label, null)
        ViewCompat.replaceAccessibilityAction(
            pill, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, null, null
        )
    }

    /** Shows the summary kept for [template]: instant, no model runs. */
    private fun show(template: String?) {
        val m = host.meeting() ?: return
        if (!SummaryAlternatives.switchTo(m, template)) return
        announceNext = true
        host.summariesChanged(m)
    }

    private fun confirmDelete(meetingId: String, template: String?, name: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.summaries_delete_title, name))
            .setMessage(R.string.summaries_delete_message)
            .setPositiveButton(R.string.summaries_delete_confirm) { _, _ ->
                val m = host.meeting() ?: return@setPositiveButton
                // The screen may have moved on while the question was up.
                if (m.id != meetingId || !SummaryAlternatives.delete(m, template)) {
                    return@setPositiveButton
                }
                host.summariesChanged(m)
                A11y.setStatusText(
                    status, activity.getString(R.string.summaries_deleted, name), announce = true
                )
                status.visibility = View.VISIBLE
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /** A style's name: its template's label, or what the summary is when that is gone. */
    private fun styleName(template: String?, labels: Map<String, String>): String = when {
        template == null -> activity.getString(R.string.summaries_label_earlier)
        else -> labels[template] ?: activity.getString(R.string.summaries_label_removed)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
