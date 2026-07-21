package com.meetily.mobile

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meetily.mobile.data.Meeting
import java.text.DateFormat
import java.util.Date

class MeetingAdapter(
    private val onClick: (Meeting) -> Unit,
    private val onLongClick: (Meeting) -> Unit
) : RecyclerView.Adapter<MeetingAdapter.Holder>() {

    private val items = mutableListOf<Meeting>()

    fun submit(meetings: List<Meeting>) {
        items.clear()
        items.addAll(meetings)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.meetingTitle)
        val date: TextView = view.findViewById(R.id.meetingDate)
        val snippet: TextView = view.findViewById(R.id.meetingSnippet)
        val chip: TextView = view.findViewById(R.id.meetingChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val meeting = items[position]
        val context = holder.itemView.context

        holder.title.text = meeting.title
        holder.date.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))

        val snippet = when {
            meeting.summary.isNotBlank() -> meeting.summary
            meeting.segments.isNotEmpty() -> meeting.transcriptText()
            meeting.notes.isNotBlank() -> meeting.notes
            else -> context.getString(R.string.no_content_yet)
        }
        holder.snippet.text = snippet.replace('\n', ' ').take(160)

        if (meeting.summary.isNotBlank()) {
            holder.chip.setText(R.string.chip_summarized)
            holder.chip.setBackgroundResource(R.drawable.bg_pill_accent)
            holder.chip.setTextColor(
                themeColor(holder.chip, com.google.android.material.R.attr.colorOnPrimaryContainer)
            )
        } else {
            holder.chip.setText(
                if (meeting.segments.isNotEmpty()) R.string.chip_transcript else R.string.chip_notes
            )
            holder.chip.setBackgroundResource(R.drawable.bg_pill)
            holder.chip.setTextColor(
                themeColor(holder.chip, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }

        holder.itemView.setOnClickListener { onClick(meeting) }
        holder.itemView.setOnLongClickListener {
            onLongClick(meeting)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun themeColor(view: View, attr: Int): Int {
        val value = TypedValue()
        view.context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
