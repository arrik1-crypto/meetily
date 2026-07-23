package com.meetily.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meetily.mobile.data.TranscriptSegment
import java.text.DateFormat
import java.util.Date

/** Display name for a segment: manual tag, else auto cluster ("Speaker N"). */
internal fun segmentSpeakerDisplay(
    context: android.content.Context,
    segment: TranscriptSegment
): String? {
    val speaker = segment.speaker
    if (!speaker.isNullOrBlank()) return speaker
    val cluster = segment.clusterId ?: return null
    return context.getString(R.string.speaker_cluster_label, cluster)
}

internal fun segmentTimeLabel(
    context: android.content.Context,
    segment: TranscriptSegment
): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(segment.timestampMs))
    val star = if (segment.highlighted) "★ " else ""
    val display = segmentSpeakerDisplay(context, segment)
    return if (display.isNullOrBlank()) "$star$time" else "$star$time · $display"
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
            if (segment.highlighted) {
                holder.itemView.setBackgroundResource(R.drawable.bg_line_highlight)
            } else {
                holder.itemView.background = null
            }
            holder.time.text = segmentTimeLabel(holder.itemView.context, segment)
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

/**
 * Recycled, read-only transcript lines for the meeting detail screen, with
 * optional topic-chapter headers woven between them. Callbacks always carry
 * SEGMENT indices (not adapter positions), so callers stay oblivious to
 * where headers land.
 */
class TranscriptLinesAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class ChapterRow(val title: String) : Row()
        class LineRow(val segIndex: Int, var segment: TranscriptSegment) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var segmentPositions = IntArray(0)

    fun submit(
        segments: List<TranscriptSegment>,
        chapters: List<com.meetily.mobile.data.Chapter> = emptyList()
    ) {
        rows.clear()
        segmentPositions = IntArray(segments.size)
        val sorted = chapters.sortedBy { it.startMs }
        var next = 0
        for ((i, seg) in segments.withIndex()) {
            while (next < sorted.size && sorted[next].startMs <= seg.timestampMs) {
                rows.add(Row.ChapterRow(sorted[next].title))
                next++
            }
            segmentPositions[i] = rows.size
            rows.add(Row.LineRow(i, seg))
        }
        notifyDataSetChanged()
    }

    fun update(index: Int, segment: TranscriptSegment) {
        val pos = segmentPositions.getOrNull(index) ?: return
        val row = rows.getOrNull(pos) as? Row.LineRow ?: return
        row.segment = segment
        notifyItemChanged(pos)
    }

    /** Adapter position of a segment (for scroll-to-chapter jumps). */
    fun positionOfSegment(index: Int): Int =
        segmentPositions.getOrNull(index) ?: 0

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.lineTime)
        val speaker: TextView = view.findViewById(R.id.lineSpeaker)
        val text: TextView = view.findViewById(R.id.lineText)
        val defaultBackground = view.background
    }

    class ChapterHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.chapterTitle)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.ChapterRow) TYPE_CHAPTER else TYPE_LINE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CHAPTER) {
            ChapterHolder(inflater.inflate(R.layout.item_chapter_header, parent, false))
        } else {
            Holder(inflater.inflate(R.layout.item_transcript_line, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is ChapterHolder && row is Row.ChapterRow) {
            holder.title.text = row.title
            return
        }
        if (holder !is Holder || row !is Row.LineRow) return
        val segment = row.segment
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val time = timeFormat.format(Date(segment.timestampMs))
        holder.time.text = if (segment.highlighted) "★ $time" else time
        val display = segmentSpeakerDisplay(holder.itemView.context, segment)
        if (display.isNullOrBlank()) {
            holder.speaker.visibility = View.GONE
        } else {
            holder.speaker.text = display
            // Auto-detected cluster labels render dimmed until named.
            holder.speaker.alpha =
                if (segment.speaker.isNullOrBlank()) 0.55f else 1f
            holder.speaker.visibility = View.VISIBLE
        }
        holder.text.text = segment.text
        if (segment.highlighted) {
            holder.itemView.setBackgroundResource(R.drawable.bg_line_highlight)
        } else {
            holder.itemView.background = holder.defaultBackground
        }
        holder.itemView.setOnClickListener {
            (rows.getOrNull(holder.bindingAdapterPosition) as? Row.LineRow)
                ?.let { onClick(it.segIndex) }
        }
        holder.itemView.setOnLongClickListener {
            (rows.getOrNull(holder.bindingAdapterPosition) as? Row.LineRow)
                ?.let { onLongClick(it.segIndex) }
            true
        }
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_LINE = 0
        private const val TYPE_CHAPTER = 1
    }
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
