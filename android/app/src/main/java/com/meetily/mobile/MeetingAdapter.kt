package com.meetily.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
    private val onLongClick: (Meeting) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val label: String, val count: String) : Row()
        data class Item(val meeting: Meeting, val time: String, val meta: String) : Row()
    }

    private val rows = mutableListOf<Row>()

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
        val time: TextView = view.findViewById(R.id.meetingTime)
        val title: TextView = view.findViewById(R.id.meetingTitle)
        val meta: TextView = view.findViewById(R.id.meetingMeta)
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
                h.time.text = row.time
                h.title.text = row.meeting.title
                h.meta.text = row.meta
                h.itemView.setOnClickListener { onClick(row.meeting) }
                h.itemView.setOnLongClickListener {
                    onLongClick(row.meeting)
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
