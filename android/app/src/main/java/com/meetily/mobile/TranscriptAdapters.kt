package com.meetily.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meetily.mobile.data.TranscriptSegment
import java.text.DateFormat
import java.util.Date

internal fun segmentTimeLabel(segment: TranscriptSegment): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(segment.timestampMs))
    val star = if (segment.highlighted) "★ " else ""
    val speaker = segment.speaker
    return if (speaker.isNullOrBlank()) "$star$time" else "$star$time · $speaker"
}

/**
 * Live transcript for the recording screen: recycled segment bubbles plus an
 * optional trailing partial-result row. Replaces the old per-segment View
 * inflation into a LinearLayout, which degraded on hours-long meetings.
 */
class LiveTranscriptAdapter(
    private val onSegmentClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val segments = mutableListOf<TranscriptSegment>()
    private var partial: String = ""

    fun reset(list: List<TranscriptSegment>, partialText: String) {
        segments.clear()
        segments.addAll(list)
        partial = partialText
        notifyDataSetChanged()
    }

    fun append(segment: TranscriptSegment) {
        segments.add(segment)
        notifyItemInserted(segments.size - 1)
    }

    fun update(index: Int, segment: TranscriptSegment) {
        if (index in segments.indices) {
            segments[index] = segment
            notifyItemChanged(index)
        }
    }

    fun segmentAt(index: Int): TranscriptSegment? = segments.getOrNull(index)

    fun setPartial(text: String) {
        val had = partial.isNotEmpty()
        val has = text.isNotEmpty()
        partial = text
        when {
            !had && has -> notifyItemInserted(segments.size)
            had && !has -> notifyItemRemoved(segments.size)
            had && has -> notifyItemChanged(segments.size)
        }
    }

    override fun getItemCount(): Int = segments.size + if (partial.isEmpty()) 0 else 1

    override fun getItemViewType(position: Int): Int =
        if (position < segments.size) TYPE_SEGMENT else TYPE_PARTIAL

    class SegmentHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.segmentTime)
        val text: TextView = view.findViewById(R.id.segmentText)
    }

    class PartialHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SEGMENT) {
            SegmentHolder(inflater.inflate(R.layout.item_transcript_segment, parent, false))
        } else {
            PartialHolder(inflater.inflate(R.layout.item_transcript_partial, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SegmentHolder) {
            val segment = segments[position]
            holder.itemView.setBackgroundResource(
                if (segment.highlighted) R.drawable.bg_bubble_highlight else R.drawable.bg_bubble
            )
            holder.time.text = segmentTimeLabel(segment)
            holder.text.text = segment.text
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && index < segments.size) {
                    onSegmentClick(index)
                }
            }
        } else if (holder is PartialHolder) {
            holder.text.text = partial
        }
    }

    companion object {
        private const val TYPE_SEGMENT = 0
        private const val TYPE_PARTIAL = 1
    }
}

/** Recycled, read-only transcript lines for the meeting detail screen. */
class TranscriptLinesAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<TranscriptLinesAdapter.Holder>() {

    private val items = mutableListOf<TranscriptSegment>()

    fun submit(segments: List<TranscriptSegment>) {
        items.clear()
        items.addAll(segments)
        notifyDataSetChanged()
    }

    fun update(index: Int, segment: TranscriptSegment) {
        if (index in items.indices) {
            items[index] = segment
            notifyItemChanged(index)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.lineTime)
        val speaker: TextView = view.findViewById(R.id.lineSpeaker)
        val text: TextView = view.findViewById(R.id.lineText)
        val defaultBackground = view.background
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcript_line, parent, false)
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val segment = items[position]
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val time = timeFormat.format(Date(segment.timestampMs))
        holder.time.text = if (segment.highlighted) "★ $time" else time
        if (segment.speaker.isNullOrBlank()) {
            holder.speaker.visibility = View.GONE
        } else {
            holder.speaker.text = segment.speaker
            holder.speaker.visibility = View.VISIBLE
        }
        holder.text.text = segment.text
        if (segment.highlighted) {
            holder.itemView.setBackgroundResource(R.drawable.bg_line_highlight)
        } else {
            holder.itemView.background = holder.defaultBackground
        }
        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onClick(index)
        }
        holder.itemView.setOnLongClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onLongClick(index)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Adapter exposing one pre-inflated View as a single list item. Used for the
 * detail screen's document header inside a ConcatAdapter: the same View
 * instance is reused on rebind, so EditText focus/text state survives.
 */
class StaticViewAdapter(private val view: View) :
    RecyclerView.Adapter<StaticViewAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(view)

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    override fun getItemCount(): Int = 1
}
