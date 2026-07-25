package com.meetily.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.meetily.mobile.data.Meeting
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
    private val onClick: (Meeting) -> Unit,
    private val onLongClick: (Meeting) -> Unit,
    private val onToggleStar: (Meeting) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val label: String, val count: String) : Row()
        data class Item(val meeting: Meeting, val time: String, val meta: String) : Row()
    }

    /** Work in flight for one meeting; [percent] < 0 renders indeterminate. */
    data class Progress(val label: String, val percent: Int)

    private val rows = mutableListOf<Row>()
    private val progressByMeeting = HashMap<String, Progress>()

    /**
     * Sets or clears the progress row inside [meetingId]'s card. Returns
     * whether that card is currently in the list — the caller falls back to a
     * banner when it is not (filtered out, or not loaded yet).
     */
    fun setProgress(meetingId: String, value: Progress?): Boolean {
        val previous = if (value == null) {
            progressByMeeting.remove(meetingId)
        } else {
            progressByMeeting.put(meetingId, value)
        }
        val index = rows.indexOfFirst { (it as? Row.Item)?.meeting?.id == meetingId }
        // Repaint the one row, never the list: progress ticks constantly and
        // a full rebuild would flicker the whole library.
        if (index >= 0 && previous != value) notifyItemChanged(index)
        return index >= 0
    }

    fun hasMeeting(meetingId: String): Boolean =
        rows.any { (it as? Row.Item)?.meeting?.id == meetingId }

    /** Repaints a single row after its meeting changed in place (starring). */
    fun refreshMeeting(meetingId: String) {
        val index = rows.indexOfFirst { (it as? Row.Item)?.meeting?.id == meetingId }
        if (index >= 0) notifyItemChanged(index)
    }

    fun submit(meetings: List<Meeting>) {
        rows.clear()
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
                    meta = metaLine(meeting)
                )
            )
        }
        flush()
        notifyDataSetChanged()
    }

    private fun countLabel(count: Int): String =
        if (count == 1) "1 meeting" else "$count meetings"

    private fun metaLine(meeting: Meeting): String {
        val parts = mutableListOf<String>()
        if (meeting.segments.isNotEmpty()) {
            parts.add("${meeting.segments.size} lines")
        }
        val speakers = meeting.segments.mapNotNull { it.speaker }.distinct().size
        if (speakers > 1) parts.add("$speakers speakers")
        if (meeting.summary.isNotBlank()) parts.add("summary ready")
        if (meeting.tags.isNotEmpty()) {
            parts.add(meeting.tags.joinToString(" ") { "#$it" })
        }
        if (parts.isEmpty()) parts.add("notes only")
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
            dayKey(now.timeInMillis) -> "Today"
            dayKey(yesterday.timeInMillis) -> "Yesterday"
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
        val star: android.widget.ImageButton = view.findViewById(R.id.meetingStar)
        val progressRow: View = view.findViewById(R.id.meetingProgressRow)
        val progressLabel: TextView = view.findViewById(R.id.meetingProgressLabel)
        val progressBar:
            com.google.android.material.progressindicator.LinearProgressIndicator =
            view.findViewById(R.id.meetingProgressBar)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_meeting_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_meeting, parent, false))
        }
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
                h.time.text = row.time
                h.title.text = meeting.title
                h.meta.text = "${row.time} · ${row.meta}"
                h.itemView.setOnClickListener { onClick(meeting) }
                h.itemView.setOnLongClickListener {
                    onLongClick(meeting)
                    true
                }
                bindStar(h, meeting)
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

    private fun bindProgress(h: ItemHolder, meeting: Meeting) {
        val progress = progressByMeeting[meeting.id]
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
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
