package com.meetily.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.search.HomeSearch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The front page's daily index: meetings grouped under uppercase day
 * datelines (Today / Yesterday / the date), each row a margin time plus a
 * serif headline and a quiet meta line. No cards — hierarchy is type and
 * whitespace, per the Broadsheet system.
 */
class MeetingAdapter(
    /** For the datelines and meta lines, written before any row is bound. */
    private val context: android.content.Context,
    private val onClick: (Meeting) -> Unit,
    private val onLongClick: (Meeting) -> Unit,
    private val onToggleStar: (Meeting) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val label: String, val count: String) : Row()
        data class Item(
            val meeting: Meeting,
            val time: String,
            val meta: String,
            /** While searching, where the meeting matched (bindSnippet). */
            val moment: HomeSearch.Moment? = null
        ) : Row()
    }

    /** Work in flight for one meeting; [percent] < 0 renders indeterminate. */
    data class Progress(val label: String, val percent: Int)

    private val rows = mutableListOf<Row>()
    private val progressByMeeting = HashMap<String, Progress>()

    /**
     * Meetings whose next progress repaint a screen reader should speak. The
     * caller's ProgressAnnouncer picks those updates (milestones, not every
     * tick); while a card is listed its row is where the progress shows, so
     * it is also where the progress is heard. A set, since an import and a
     * summary can both reach a milestone in one pass.
     */
    private val announceProgressFor = HashSet<String>()

    /**
     * Sets or clears the progress row inside [meetingId]'s card. Returns
     * whether that card is currently in the list — the caller falls back to a
     * banner when it is not (filtered out, or not loaded yet). With
     * [announce], the repaint this causes is spoken by a screen reader.
     */
    fun setProgress(meetingId: String, value: Progress?, announce: Boolean = false): Boolean {
        val index = rows.indexOfFirst { (it as? Row.Item)?.meeting?.id == meetingId }
        if (value != null && index < 0) {
            // Not stored for a card that is not listed: the caller shows the
            // banner instead and, having painted nothing, never wipes it — so
            // a stored entry outlived the job and froze the bar on the card
            // once a search or chip let it back into the list. The next sync
            // after the card reappears stores it again while work continues.
            progressByMeeting.remove(meetingId)
            announceProgressFor.remove(meetingId)
            return false
        }
        val previous = if (value == null) {
            progressByMeeting.remove(meetingId)
        } else {
            progressByMeeting.put(meetingId, value)
        }
        // A newer update that is not to be spoken withdraws a request still
        // standing, so a card that was scrolled off screen at its milestone
        // is not read out by a bind long after. The same value painted again
        // (the other job's tick re-renders both) leaves it alone.
        val speak = announce && value != null
        if (speak) {
            announceProgressFor.add(meetingId)
        } else if (value == null || previous != value) {
            announceProgressFor.remove(meetingId)
        }
        // Repaint the one row, never the list: progress ticks constantly and
        // a full rebuild would flicker the whole library.
        if (index >= 0 && (previous != value || speak)) notifyItemChanged(index)
        return index >= 0
    }

    fun hasMeeting(meetingId: String): Boolean =
        rows.any { (it as? Row.Item)?.meeting?.id == meetingId }

    // --- Multi-select ------------------------------------------------------

    /**
     * The selected meeting ids while the home list is in selection mode,
     * null outside it. HomeSelection owns the selection and MainActivity
     * decides what a tap means; rows only show it: a selected card, the
     * star hidden (a tap there would star rather than select), labelled tap
     * and long-press, and a checkable node for screen readers.
     */
    private var selection: Set<String>? = null

    /**
     * Opens the single-meeting action sheet, offered to screen readers as
     * an action on each row outside selection mode (a long press now starts
     * selecting instead).
     */
    var onOptions: ((Meeting) -> Unit)? = null

    /** Shows [selected] (null leaves selection mode), repainting only rows that changed. */
    fun setSelection(selected: Set<String>?) {
        val previous = selection
        selection = selected
        val modeChanged = (previous == null) != (selected == null)
        for ((index, row) in rows.withIndex()) {
            val id = (row as? Row.Item)?.meeting?.id ?: continue
            if (modeChanged || previous?.contains(id) != selected?.contains(id)) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }

    /** The meetings listed now, in list order. */
    fun listedMeetings(): List<Meeting> = rows.mapNotNull { (it as? Row.Item)?.meeting }

    /** Repaints a single row after its meeting changed in place (starring). */
    fun refreshMeeting(meetingId: String) {
        val index = rows.indexOfFirst { (it as? Row.Item)?.meeting?.id == meetingId }
        if (index >= 0) notifyItemChanged(index)
    }

    /**
     * Meta lines by meeting INSTANCE. Every filter pass and search result
     * rebuilds all rows, and the meta line walks every segment for speakers;
     * a library reload hands over new instances, so identity is exactly
     * "same data as last time". Starring changes an instance in place, but
     * the star is not part of the meta line.
     */
    private var metaCache = java.util.IdentityHashMap<Meeting, String>()

    /**
     * [moments] are, by meeting id, where a search matched: each matching
     * row shows the words around its match. Empty outside a search.
     */
    fun submit(meetings: List<Meeting>, moments: Map<String, HomeSearch.Moment> = emptyMap()) {
        rows.clear()
        val previousMeta = metaCache
        metaCache = java.util.IdentityHashMap(meetings.size)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val dateFormat = DateFormat.getDateInstance(DateFormat.FULL)
        var currentKey: String? = null
        var pending = mutableListOf<Row.Item>()
        var pendingLabel = ""

        fun flush() {
            if (pending.isEmpty()) return
            rows.add(Row.Header(pendingLabel, countLabel(pending.size)))
            rows.addAll(pending)
            pending = mutableListOf()
        }

        for (meeting in meetings) {
            val key = dayKey(meeting.createdAtMs)
            if (key != currentKey) {
                flush()
                currentKey = key
                pendingLabel = dayLabel(meeting.createdAtMs, dateFormat)
            }
            pending.add(
                Row.Item(
                    meeting = meeting,
                    time = timeFormat.format(Date(meeting.createdAtMs)),
                    meta = metaCache.getOrPut(meeting) {
                        previousMeta[meeting] ?: metaLine(meeting)
                    },
                    moment = moments[meeting.id]
                )
            )
        }
        flush()
        notifyDataSetChanged()
    }

    private fun countLabel(count: Int): String =
        context.resources.getQuantityString(R.plurals.platform_home_meetings, count, count)

    private fun metaLine(meeting: Meeting): String {
        val res = context.resources
        val parts = mutableListOf<String>()
        if (meeting.segments.isNotEmpty()) {
            val lines = meeting.segments.size
            parts.add(res.getQuantityString(R.plurals.platform_home_lines, lines, lines))
        }
        val speakers = meeting.segments.mapNotNull { it.speaker }.distinct().size
        if (speakers > 1) {
            parts.add(res.getQuantityString(R.plurals.platform_home_speakers, speakers, speakers))
        }
        if (meeting.summary.isNotBlank()) parts.add(res.getString(R.string.platform_home_summary_ready))
        if (meeting.tags.isNotEmpty()) {
            parts.add(meeting.tags.joinToString(" ") { "#$it" })
        }
        if (parts.isEmpty()) parts.add(res.getString(R.string.platform_home_notes_only))
        return parts.joinToString(" · ")
    }

    private fun dayKey(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun dayLabel(ms: Long, dateFormat: DateFormat): String {
        val now = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when (dayKey(ms)) {
            dayKey(now.timeInMillis) -> context.getString(R.string.platform_home_today)
            dayKey(yesterday.timeInMillis) -> context.getString(R.string.platform_home_yesterday)
            else -> dateFormat.format(Date(ms))
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.sectionLabel)
        val count: TextView = view.findViewById(R.id.sectionCount)
    }

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.meetingCard)
        val time: TextView = view.findViewById(R.id.meetingTime)
        val title: TextView = view.findViewById(R.id.meetingTitle)
        val meta: TextView = view.findViewById(R.id.meetingMeta)
        val snippet: TextView = view.findViewById(R.id.meetingSnippet)
        val star: android.widget.ImageButton = view.findViewById(R.id.meetingStar)
        val progressRow: View = view.findViewById(R.id.meetingProgressRow)
        val progressLabel: TextView = view.findViewById(R.id.meetingProgressLabel)
        val progressBar:
            com.google.android.material.progressindicator.LinearProgressIndicator =
            view.findViewById(R.id.meetingProgressBar)

        /** The meeting this card was last bound to. */
        var meetingId: String? = null
    }

    /**
     * Exposes a row as checkable, and checked when selected, while the list
     * is in selection mode. Read from [selection] when asked, so the click
     * event a toggle sends already carries the new state.
     */
    private inner class SelectionDelegate(private val holder: ItemHolder) : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val selected = selection ?: return
            info.isCheckable = true
            info.isChecked = holder.meetingId?.let { it in selected } == true
        }

        override fun onInitializeAccessibilityEvent(host: View, event: android.view.accessibility.AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(host, event)
            val selected = selection ?: return
            event.isChecked = holder.meetingId?.let { it in selected } == true
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_meeting_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_meeting, parent, false)).also {
                ViewCompat.setAccessibilityDelegate(it.itemView, SelectionDelegate(it))
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        // A selection change repaints just the selection, not the whole row.
        val row = rows[position]
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION } &&
            row is Row.Item && holder is ItemHolder
        ) {
            bindSelection(holder, row.meeting)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                (holder as HeaderHolder).label.text = row.label
                holder.count.text = row.count
            }
            is Row.Item -> {
                val h = holder as ItemHolder
                val meeting = row.meeting
                h.meetingId = meeting.id
                h.time.text = row.time
                h.title.text = meeting.title
                h.meta.text = "${row.time} · ${row.meta}"
                h.itemView.setOnClickListener { onClick(meeting) }
                h.itemView.setOnLongClickListener {
                    onLongClick(meeting)
                    true
                }
                bindStar(h, meeting)
                bindSelection(h, meeting)
                bindSnippet(h, row.moment)
                bindProgress(h, meeting)
            }
        }
    }

    /** Flagged meetings keep an accent edge and a filled star. */
    private fun bindStar(h: ItemHolder, meeting: Meeting) {
        val context = h.itemView.context
        h.card.setBackgroundResource(
            if (meeting.starred) R.drawable.bg_card_starred else R.drawable.bg_card
        )
        h.star.setImageResource(
            if (meeting.starred) R.drawable.ic_star else R.drawable.ic_star_outline
        )
        h.star.imageTintList = android.content.res.ColorStateList.valueOf(
            MaterialColors.getColor(
                h.star,
                if (meeting.starred) {
                    com.google.android.material.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOutlineVariant
                }
            )
        )
        h.star.contentDescription = context.getString(
            if (meeting.starred) R.string.unstar_meeting else R.string.star_meeting
        )
        h.star.setOnClickListener { onToggleStar(meeting) }
    }

    /**
     * The row's part in multi-select. Outside selection mode a tap opens the
     * meeting, a long press starts selecting, and the action sheet is a
     * screen-reader action; inside it both toggle the row. Runs after
     * [bindStar], whose card background a selected row replaces.
     */
    private fun bindSelection(h: ItemHolder, meeting: Meeting) {
        val context = h.itemView.context
        val selected = selection
        val isSelected = selected?.contains(meeting.id) == true
        h.card.setBackgroundResource(
            when {
                isSelected -> R.drawable.bg_card_selected
                meeting.starred -> R.drawable.bg_card_starred
                else -> R.drawable.bg_card
            }
        )
        h.star.visibility = if (selected == null) View.VISIBLE else View.INVISIBLE
        val toggle = context.getString(if (isSelected) R.string.storage_deselect else R.string.storage_select)
        A11y.labelClick(
            h.itemView,
            if (selected == null) context.getString(R.string.storage_open_meeting) else toggle
        )
        A11y.labelLongClick(h.itemView, toggle)
        val options = onOptions
        A11y.setActions(
            h.itemView,
            if (selected == null && options != null) {
                listOf(A11y.Action(context.getString(R.string.a11y_meeting_options)) { options(meeting) })
            } else {
                emptyList()
            }
        )
    }

    /**
     * The words around a search match, with the match marked, after where
     * they are: a time into the recording, or the field they come from. A
     * screen reader hears them as a sentence, without the ellipses.
     */
    private fun bindSnippet(h: ItemHolder, moment: HomeSearch.Moment?) {
        if (moment == null) {
            h.snippet.visibility = View.GONE
            h.snippet.text = null
            h.snippet.contentDescription = null
            return
        }
        val context = h.itemView.context
        val snippet = moment.snippet
        val time = moment.timeLabel
        val label = time ?: context.getString(
            when (moment.field) {
                HomeSearch.Field.NOTES -> R.string.moment_snippet_notes
                HomeSearch.Field.SUMMARY -> R.string.moment_snippet_summary
                HomeSearch.Field.OTHER_SUMMARY -> R.string.moment_snippet_other_summary
                HomeSearch.Field.ACTION_ITEM -> R.string.moment_snippet_action
                HomeSearch.Field.PHOTO_TEXT -> R.string.moment_snippet_photo
                else -> R.string.moment_snippet_summary
            }
        )
        val prefix = "$label · "
        val text = android.text.SpannableString(prefix + snippet.text)
        if (snippet.hasMatch) {
            val start = prefix.length + snippet.matchStart
            val end = prefix.length + snippet.matchEnd
            text.setSpan(
                android.text.style.BackgroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(context, R.color.moment_find_match)
                ),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        h.snippet.text = text
        val words = snippet.spoken()
        h.snippet.contentDescription = if (time != null) {
            context.getString(R.string.moment_snippet_a11y_time, time, words)
        } else {
            context.getString(
                when (moment.field) {
                    HomeSearch.Field.NOTES -> R.string.moment_snippet_a11y_notes
                    HomeSearch.Field.OTHER_SUMMARY -> R.string.moment_snippet_a11y_other_summary
                    HomeSearch.Field.ACTION_ITEM -> R.string.moment_snippet_a11y_action
                    HomeSearch.Field.PHOTO_TEXT -> R.string.moment_snippet_a11y_photo
                    else -> R.string.moment_snippet_a11y_summary
                },
                words
            )
        }
        h.snippet.visibility = View.VISIBLE
    }

    private fun bindProgress(h: ItemHolder, meeting: Meeting) {
        val progress = progressByMeeting[meeting.id]
        // Silent unless this repaint is one to speak (below): a recycled card
        // must not carry a live region over from the meeting bound before.
        A11y.endAnnouncement(h.progressRow)
        if (progress == null) {
            h.progressRow.visibility = View.GONE
            return
        }
        h.progressRow.visibility = View.VISIBLE
        h.progressLabel.text = progress.label
        val wantIndeterminate = progress.percent < 0
        if (h.progressBar.isIndeterminate != wantIndeterminate) {
            // Material indicators refuse an in-place mode switch while
            // visible, so blink the bar around the change.
            h.progressBar.visibility = View.INVISIBLE
            h.progressBar.isIndeterminate = wantIndeterminate
            h.progressBar.visibility = View.VISIBLE
        }
        if (!wantIndeterminate) h.progressBar.progress = progress.percent
        if (announceProgressFor.remove(meeting.id)) {
            speakProgress(h, meeting.id, progress.label)
        }
    }

    /**
     * Has a screen reader read [h]'s progress row out. Rows are bound during
     * RecyclerView's layout pass, while they are off the list (detached for
     * the pass, or not added yet), and the accessibility event that new text
     * raises there reaches no screen reader. So the row becomes a live region
     * just after the pass, once it is back in the list, and its label is set
     * again; that change is what gets spoken.
     */
    private fun speakProgress(h: ItemHolder, meetingId: String, label: String) {
        h.progressRow.post {
            // Rebound to another meeting, or repainted again, in between.
            if (h.meetingId != meetingId || h.progressRow.visibility != View.VISIBLE ||
                h.progressLabel.text.toString() != label
            ) return@post
            A11y.announceNextChange(h.progressRow)
            h.progressLabel.text = label
        }
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /** notifyItemChanged payload: only the selection state moved. */
        private const val PAYLOAD_SELECTION = "selection"
    }
}
