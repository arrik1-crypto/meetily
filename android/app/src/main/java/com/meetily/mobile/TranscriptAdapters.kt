package com.meetily.mobile

import android.view.LayoutInflater
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meetily.mobile.data.ElapsedTime
import com.meetily.mobile.data.TranscriptSegment

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

/**
 * "★ 0:04:11 · Speaker 2" — time INTO the recording, not time of day.
 *
 * [meetingStartMs] is the recording's start; see [ElapsedTime] for why the
 * label has to agree with what tapping the line seeks to.
 */
internal fun segmentTimeLabel(
    context: android.content.Context,
    segment: TranscriptSegment,
    meetingStartMs: Long
): String {
    val time = ElapsedTime.label(segment, meetingStartMs)
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

    /** Body text size in sp; matches the meeting screen (see AppSettings). */
    var textSizeSp: Float = 15f
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * When this recording started, so line labels can read as time INTO the
     * meeting. Only used for lines with no audioMs — see [ElapsedTime].
     */
    var meetingStartMs: Long = 0L
        set(value) {
            // Polled once a second by the recording screen's timer, so a
            // repeat must not redraw the list out from under the user.
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

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
            holder.time.text =
                segmentTimeLabel(holder.itemView.context, segment, meetingStartMs)
            holder.text.text = segment.text
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            holder.time.setTextSize(
                TypedValue.COMPLEX_UNIT_SP, (textSizeSp - 4f).coerceAtLeast(9f)
            )
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && index < segments.size) {
                    onSegmentClick(index)
                }
            }
        } else if (holder is PartialHolder) {
            holder.text.text = partial
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
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
    private val onLongClick: (Int) -> Unit,
    // (segmentIndex, wordOffsetMs): tap-to-seek on a single word. Only wired
    // to lines that have word timings and a playable audio offset.
    private val onWordTap: ((Int, Long) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        class ChapterRow(
            val chapterIndex: Int,
            val title: String,
            val lineCount: Int,
            val span: String,
            val collapsed: Boolean
        ) : Row()

        class LineRow(val segIndex: Int, var segment: TranscriptSegment) : Row()
    }

    private val rows = mutableListOf<Row>()

    /**
     * Adapter position per segment, or -1 when its chapter is collapsed and
     * the line is not currently on screen. Callers must expand first (see
     * [ensureVisible]) before scrolling to a segment.
     */
    private var segmentPositions = IntArray(0)

    /** Chapter index per segment (-1 before the first chapter). */
    private var segmentChapter = IntArray(0)

    private val collapsed = mutableSetOf<Int>()
    private var lastSegments: List<TranscriptSegment> = emptyList()
    private var lastChapters: List<com.meetily.mobile.data.Chapter> = emptyList()

    /** Body text size in sp; metadata scales with it. See AppSettings. */
    var textSizeSp: Float = 15f
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val metaSizeSp: Float get() = (textSizeSp - 4f).coerceAtLeast(9f)

    /**
     * The meeting's start, so line labels and chapter spans read as time
     * INTO the recording rather than time of day. See [ElapsedTime].
     */
    var meetingStartMs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            // rebuild(), not just notifyDataSetChanged(): a chapter's span is
            // computed once and stored on its row, so redrawing alone would
            // leave the spans reading as clock times.
            rebuild()
        }

    // --- Playback follow ---------------------------------------------------

    /** Segment being spoken right now, or -1 when not following. */
    var activeSegment = -1
        private set

    /** Word within [activeSegment], or -1 when the line has no timings. */
    private var activeWord = -1

    /** Accent used behind the spoken word; set from the themed activity. */
    var wordHighlightColor: Int = 0

    /**
     * Moves the follow highlight. Only the rows that actually change are
     * rebound — this fires several times a second during playback, so a
     * blanket notify would fight the scroll and burn the frame budget.
     */
    fun setActive(segmentIndex: Int, wordIndex: Int) {
        if (segmentIndex == activeSegment && wordIndex == activeWord) return
        val previous = activeSegment
        activeSegment = segmentIndex
        activeWord = wordIndex
        if (previous != segmentIndex) rebindSegment(previous)
        rebindSegment(segmentIndex)
    }

    fun clearActive() = setActive(-1, -1)

    private fun rebindSegment(segmentIndex: Int) {
        if (segmentIndex < 0) return
        val position = segmentPositions.getOrNull(segmentIndex)?.takeIf { it >= 0 } ?: return
        notifyItemChanged(position)
    }

    fun submit(
        segments: List<TranscriptSegment>,
        chapters: List<com.meetily.mobile.data.Chapter> = emptyList(),
        collapseAllInitially: Boolean = false
    ) {
        lastSegments = segments
        val sorted = chapters.sortedBy { it.startMs }
        lastChapters = sorted
        if (collapseAllInitially && sorted.isNotEmpty()) {
            collapsed.clear()
            collapsed.addAll(sorted.indices)
        }
        // An empty submission is also how the Summary tab hides the
        // transcript; only forget the arrangement when a real transcript
        // genuinely has no chapters, or every tab switch would wipe it.
        if (sorted.isEmpty() && segments.isNotEmpty()) collapsed.clear()
        rebuild()
    }

    /** Rebuilds the row list from the last submitted data + collapse state. */
    private fun rebuild() {
        rows.clear()
        val segments = lastSegments
        val sorted = lastChapters
        segmentPositions = IntArray(segments.size) { -1 }
        segmentChapter = IntArray(segments.size) { -1 }

        // First pass: which chapter owns each segment, and its line count.
        var owner = -1
        var next = 0
        for ((i, seg) in segments.withIndex()) {
            while (next < sorted.size && sorted[next].startMs <= seg.timestampMs) {
                owner = next
                next++
            }
            segmentChapter[i] = owner
        }
        val counts = IntArray(sorted.size)
        for (c in segmentChapter) if (c >= 0) counts[c]++

        // Second pass: emit headers and the lines of expanded chapters.
        var emitted = -1
        for ((i, seg) in segments.withIndex()) {
            val chapter = segmentChapter[i]
            if (chapter >= 0 && chapter != emitted) {
                // Emit every chapter header up to this one, including any
                // that own no lines, so nothing silently disappears.
                for (c in (emitted + 1)..chapter) {
                    rows.add(
                        Row.ChapterRow(
                            chapterIndex = c,
                            title = sorted[c].title,
                            lineCount = counts[c],
                            span = spanLabel(segments, c),
                            collapsed = collapsed.contains(c)
                        )
                    )
                }
                emitted = chapter
            }
            if (chapter >= 0 && collapsed.contains(chapter)) continue
            segmentPositions[i] = rows.size
            rows.add(Row.LineRow(i, seg))
        }
        for (c in (emitted + 1) until sorted.size) {
            rows.add(
                Row.ChapterRow(c, sorted[c].title, counts[c], spanLabel(segments, c), collapsed.contains(c))
            )
        }
        notifyDataSetChanged()
    }

    /** "0:03:20 – 0:11:48" for the lines a chapter owns, or "" when none. */
    private fun spanLabel(segments: List<TranscriptSegment>, chapter: Int): String {
        var first: Long? = null
        var last: Long? = null
        for (i in segments.indices) {
            if (segmentChapter.getOrNull(i) != chapter) continue
            val at = ElapsedTime.offsetMs(segments[i], meetingStartMs)
            if (first == null) first = at
            last = at
        }
        val a = first ?: return ""
        val b = last ?: a
        return if (a == b) {
            ElapsedTime.format(a)
        } else {
            ElapsedTime.format(a) + " – " + ElapsedTime.format(b)
        }
    }

    fun toggleChapter(index: Int) {
        if (!collapsed.add(index)) collapsed.remove(index)
        rebuild()
    }

    fun setAllCollapsed(value: Boolean) {
        collapsed.clear()
        if (value) collapsed.addAll(lastChapters.indices)
        rebuild()
    }

    val hasChapters: Boolean get() = lastChapters.isNotEmpty()

    /**
     * Expands the chapter owning [segmentIndex] if needed, so callers that
     * scroll to a segment (playback follow, deep links, search hits, jump to
     * chapter) never target a hidden row.
     */
    fun ensureVisible(segmentIndex: Int) {
        val chapter = segmentChapter.getOrNull(segmentIndex) ?: return
        if (chapter >= 0 && collapsed.remove(chapter)) rebuild()
    }

    fun update(index: Int, segment: TranscriptSegment) {
        lastSegments.getOrNull(index)?.let { lastSegments = lastSegments.toMutableList().also { l -> l[index] = segment } }
        val pos = segmentPositions.getOrNull(index)?.takeIf { it >= 0 } ?: return
        val row = rows.getOrNull(pos) as? Row.LineRow ?: return
        row.segment = segment
        notifyItemChanged(pos)
    }

    /**
     * Adapter position of a segment, expanding its chapter first when the
     * line is currently collapsed away.
     */
    fun positionOfSegment(index: Int): Int {
        ensureVisible(index)
        return segmentPositions.getOrNull(index)?.takeIf { it >= 0 } ?: 0
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.lineTime)
        val speaker: TextView = view.findViewById(R.id.lineSpeaker)
        val text: TextView = view.findViewById(R.id.lineText)
        val defaultBackground = view.background
    }

    class ChapterHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.chapterTitle)
        val meta: TextView = view.findViewById(R.id.chapterMeta)
        val chevron: TextView = view.findViewById(R.id.chapterChevron)
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
            holder.chevron.text = if (row.collapsed) "\u25B8" else "\u25BE"
            val ctx = holder.itemView.context
            holder.meta.text = if (row.span.isBlank()) {
                ctx.resources.getQuantityString(
                    R.plurals.chapter_lines, row.lineCount, row.lineCount
                )
            } else {
                ctx.resources.getQuantityString(
                    R.plurals.chapter_lines_span, row.lineCount, row.lineCount, row.span
                )
            }
            holder.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSizeSp)
            holder.itemView.setOnClickListener {
                (rows.getOrNull(holder.bindingAdapterPosition) as? Row.ChapterRow)
                    ?.let { toggleChapter(it.chapterIndex) }
            }
            return
        }
        if (holder !is Holder || row !is Row.LineRow) return
        val segment = row.segment
        val time = ElapsedTime.label(segment, meetingStartMs)
        holder.time.text = if (segment.highlighted) "★ $time" else time
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        holder.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSizeSp)
        holder.speaker.setTextSize(TypedValue.COMPLEX_UNIT_SP, metaSizeSp)
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
        bindLineText(holder.text, row, holder.itemView)
        when {
            row.segIndex == activeSegment ->
                holder.itemView.setBackgroundResource(R.drawable.bg_line_active)
            segment.highlighted ->
                holder.itemView.setBackgroundResource(R.drawable.bg_line_highlight)
            else -> holder.itemView.background = holder.defaultBackground
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

    /**
     * Word-level tap-to-seek: each timed word becomes a ClickableSpan that
     * seeks playback (Pixel Recorder style). Spans are matched against the
     * displayed text sequentially, so lightly edited text degrades gracefully
     * (unmatched words just lose their span).
     */
    private fun bindLineText(view: TextView, row: Row.LineRow, rowView: View) {
        val segment = row.segment
        val words = segment.words
        val wordTap = onWordTap
        if (wordTap == null || words.isNullOrEmpty() || segment.audioMs == null) {
            view.text = segment.text
            view.movementMethod = null
            view.isClickable = false
            view.isLongClickable = false
            return
        }
        val span = android.text.SpannableString(segment.text)
        val spokenWord = if (row.segIndex == activeSegment) activeWord else -1
        var cursor = 0
        var any = false
        for ((wordIndex, word) in words.withIndex()) {
            val at = segment.text.indexOf(word.text, cursor)
            if (at < 0) continue
            if (wordIndex == spokenWord && wordHighlightColor != 0) {
                span.setSpan(
                    android.text.style.BackgroundColorSpan(wordHighlightColor),
                    at, at + word.text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    at, at + word.text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            span.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        wordTap(row.segIndex, word.ms)
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        // Words look like normal text, not links.
                    }
                },
                at, at + word.text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cursor = at + word.text.length
            any = true
        }
        view.text = span
        view.movementMethod =
            if (any) android.text.method.LinkMovementMethod.getInstance() else null
        if (any) {
            // Setting a movement method on Spannable text makes this TextView
            // focusable, clickable AND long-clickable, so it consumed touches
            // the row was listening for. Every whisper line carries word
            // timings, so on a normal transcript long-press did nothing —
            // and long-press is the only way to reach the line actions (edit,
            // tag, split). Hand both gestures back to the row.
            //
            // Word taps still work: LinkMovementMethod consumes ACTION_UP only
            // when it lands on a span, so a tap on a word seeks and a tap
            // anywhere else falls through to the click listener below.
            view.setOnClickListener { rowView.performClick() }
            view.setOnLongClickListener { rowView.performLongClick() }
        } else {
            view.setOnClickListener(null)
            view.setOnLongClickListener(null)
            view.isClickable = false
            view.isLongClickable = false
        }
    }

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
