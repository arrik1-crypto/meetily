package com.meetily.mobile

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val meeting = items[position]
        holder.title.text = meeting.title
        holder.date.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(meeting.createdAtMs))
        val snippet = when {
            meeting.summary.isNotBlank() -> meeting.summary
            meeting.segments.isNotEmpty() -> meeting.transcriptText()
            meeting.notes.isNotBlank() -> meeting.notes
            else -> holder.itemView.context.getString(R.string.no_content_yet)
        }
        holder.snippet.text = snippet.replace('\n', ' ').take(140)
        holder.itemView.setOnClickListener { onClick(meeting) }
        holder.itemView.setOnLongClickListener {
            onLongClick(meeting)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
