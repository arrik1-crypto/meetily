package com.meetily.mobile

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.PassageAudio
import com.meetily.mobile.data.PassagePlan
import com.meetily.mobile.data.TranscriptResume
import com.meetily.mobile.security.AppLock
import com.meetily.mobile.whisper.PassageSilencer
import java.io.File

/**
 * The meeting screen's "Remove passage…": the range picker (first and last
 * line, and the word each starts at or ends after when it has word
 * timings), the confirmation that says exactly what goes, the progress of
 * the run (PassageService) and its outcome. What is removed is PassagePlan's;
 * this is the screen side, kept out of MeetingDetailActivity, which reaches
 * it through [Host].
 *
 * The screen listens for the whole of its life, not only while started, so
 * a run that ends while it is in the background still has the screen let go
 * of the recording it replaced and take the removal into its own copy.
 */
class PassageUi(
    private val activity: AppCompatActivity,
    private val host: Host
) : PassageService.Listener {

    /** What the meeting screen provides. Main thread only. */
    interface Host {
        /** The screen's live meeting, or null until it has been read. */
        fun liveMeeting(): Meeting?

        /** Writes the screen's edits to the stored meeting and waits for it. */
        fun flushEdits()

        /** The recording on disk, or null without audio. */
        fun audioFile(): File?

        /** The prepared player's length, or 0 when it is not prepared. */
        fun playerDurationMs(): Long

        /** Stops playback and lets go of the recording. */
        fun releasePlayer()

        /** [span] is gone from the stored meeting: take it into the screen's copy too. */
        fun passageRemoved(span: PassagePlan.Span)
    }

    private var meetingId: String? = null
    private var attached = false
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private val announcer = ProgressAnnouncer()

    /** The recording is being read for the picker; a second tap waits. */
    private var measuring = false

    /**
     * "Reading the recording…", only once the read has taken a moment. Most
     * reads (a header, or a container that states its length) end well
     * before it, and the toast would then sit over the picker saying the
     * recording is still being read.
     */
    private val main = Handler(Looper.getMainLooper())
    private val slowRead = Runnable { if (measuring) showReading() }

    /** From onCreate, with the meeting the screen shows: follow runs on it. */
    fun attach(meetingId: String) {
        this.meetingId = meetingId
        if (!attached) {
            PassageService.addListener(this)
            attached = true
        }
        // A run under way, from before a rotation or another visit.
        if (PassageService.isRunning && PassageService.currentMeetingId == meetingId) {
            showProgress(PassageService.percent, PassageService.minutesLeft)
        }
    }

    /** From onDestroy. */
    fun detach() {
        main.removeCallbacks(slowRead)
        if (attached) {
            PassageService.removeListener(this)
            attached = false
        }
        progressDialog?.dismiss()
        progressDialog = null
    }

    /** "Remove passage…" on transcript line [index]. */
    fun open(index: Int) {
        val m = host.liveMeeting() ?: return
        val segment = m.segments.getOrNull(index) ?: return
        if (segment.audioMs == null) {
            Toast.makeText(activity, R.string.passage_untimed, Toast.LENGTH_LONG).show()
            return
        }
        // One is being removed already: show it again (it may have been hidden).
        if (PassageService.isRunning && PassageService.currentMeetingId == m.id) {
            showProgress(PassageService.percent, PassageService.minutesLeft)
            return
        }
        PassageService.blockReason(activity, m.id)?.let {
            Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
            return
        }
        val file = host.audioFile()
        val known = host.playerDurationMs()
        if (file == null) {
            pickRange(m, index, known.takeIf { it > 0L }, null)
            return
        }
        // The recording's format, for what writing it again does to it (the
        // confirmation), and its length, which bounds the last line. A
        // worker: the platform opens raw ADTS, which states no length, by
        // walking every frame header, seconds on a long meeting.
        if (measuring) return
        measuring = true
        main.postDelayed(slowRead, READING_NOTICE_DELAY_MS)
        Thread {
            // A raw ADTS recording (every live one) states its format in its
            // first frame header. With the length known too (a prepared
            // player) that is all that is read, and the picker opens at once.
            val quick = if (known > 0L) PassageSilencer.adtsReencode(file) else null
            // Otherwise one open gives the format and the length together.
            val header = if (quick != null) null else PassageSilencer.readHeader(file)
            val reencode = quick ?: header?.reencode
            val durationMs = if (known > 0L) known else header?.durationMs ?: 0L
            activity.runOnUiThread {
                measuring = false
                main.removeCallbacks(slowRead)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val current = host.liveMeeting() ?: return@runOnUiThread
                if (current.id != m.id) return@runOnUiThread
                // The line may have moved while the file was read.
                val at = current.segments.indexOf(segment).takeIf { it >= 0 } ?: index
                if (current.segments.getOrNull(at)?.audioMs == null) return@runOnUiThread
                pickRange(current, at, durationMs.takeIf { it > 0L }, reencode)
            }
        }.apply { name = "passage-read" }.start()
    }

    /** Opening the recording for the picker may take a while: say so. */
    private fun showReading() {
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, R.string.passage_reading, Toast.LENGTH_SHORT).show()
    }

    // --- The range picker -------------------------------------------------------

    /** [reencode]: what writing the recording again does to it; null when unknown or without audio. */
    private fun pickRange(m: Meeting, index: Int, durationMs: Long?, reencode: PassageAudio.Reencode?) {
        val segments = m.segments.toList()
        val resume = m.transcriptResumeMs
        val lines = PassagePlan.lines(segments, durationMs, resume)
        val start = lines.indexOfFirst { it.index == index }
        if (start < 0) return

        val view = LayoutInflater.from(activity).inflate(R.layout.passage_range_dialog, null)
        val fromLine = view.findViewById<Spinner>(R.id.passage_from_line)
        val fromWord = view.findViewById<Spinner>(R.id.passage_from_word)
        val fromWordLabel = view.findViewById<View>(R.id.passage_from_word_label)
        val toLine = view.findViewById<Spinner>(R.id.passage_to_line)
        val toWord = view.findViewById<Spinner>(R.id.passage_to_word)
        val toWordLabel = view.findViewById<View>(R.id.passage_to_word_label)
        val summary = view.findViewById<TextView>(R.id.passage_range_summary)
        androidx.core.view.ViewCompat.setAccessibilityHeading(view.findViewById(R.id.passage_from_label), true)
        androidx.core.view.ViewCompat.setAccessibilityHeading(view.findViewById(R.id.passage_to_label), true)

        val lineLabels = lines.map { line -> lineLabel(segments[line.index], line.startMs) }
        fromLine.adapter = adapter(lineLabels)
        fromLine.setSelection(start)

        // The "to" list is the lines from the chosen first one on.
        var toOffset = start
        fun fillTo(from: Int, keep: Int) {
            toOffset = from
            toLine.adapter = adapter(lineLabels.subList(from, lineLabels.size))
            toLine.setSelection((keep - from).coerceAtLeast(0))
        }
        fillTo(start, start)

        fun fillWords(spinner: Spinner, label: View, lineAt: Int, last: Boolean) {
            val line = lines[lineAt]
            val words = segments[line.index].words.orEmpty()
            val shown = words.size > 1
            spinner.visibility = if (shown) View.VISIBLE else View.GONE
            label.visibility = if (shown) View.VISIBLE else View.GONE
            if (!shown) return
            spinner.adapter = adapter(words.map { word ->
                activity.getString(R.string.passage_item, TranscriptResume.clock(line.startMs + word.ms), word.text.trim())
            })
            spinner.setSelection(if (last) words.size - 1 else 0)
        }

        var plan: PassagePlan.Plan? = null
        fun update(announce: Boolean) {
            val first = fromLine.selectedItemPosition.coerceAtLeast(0)
            val last = toOffset + toLine.selectedItemPosition.coerceAtLeast(0)
            val firstIndex = lines.getOrNull(first)?.index ?: return
            val lastIndex = lines.getOrNull(last)?.index ?: return
            val firstWords = segments[firstIndex].words.orEmpty()
            val lastWords = segments[lastIndex].words.orEmpty()
            // The first and last word are the line's own edges.
            val firstWord = fromWord.selectedItemPosition
                .takeIf { fromWord.visibility == View.VISIBLE && it in 1 until firstWords.size }
            val lastWord = toWord.selectedItemPosition
                .takeIf { toWord.visibility == View.VISIBLE && it in 0 until lastWords.size - 1 }
            // A word range read backwards (the same line, the end before the start) is empty.
            val span = PassagePlan.spanFor(segments, firstIndex, lastIndex, durationMs, firstWord, lastWord, resume)
            plan = span?.let {
                PassagePlan.plan(segments, it, durationMs, m.highlightMarksMs, m.chapters, resume)
            }
            val text = plan?.let { summaryText(it) } ?: activity.getString(R.string.passage_summary_empty)
            A11y.setStatusText(summary, text, announce)
        }

        var settled = false
        fromLine.onItemSelectedListener = selected { position ->
            fillWords(fromWord, fromWordLabel, position, last = false)
            val keep = (toOffset + toLine.selectedItemPosition).coerceAtLeast(position)
            fillTo(position, keep)
            fillWords(toWord, toWordLabel, keep, last = true)
            if (settled) update(announce = true)
        }
        toLine.onItemSelectedListener = selected { position ->
            fillWords(toWord, toWordLabel, toOffset + position, last = true)
            if (settled) update(announce = true)
        }
        fromWord.onItemSelectedListener = selected { if (settled) update(announce = true) }
        toWord.onItemSelectedListener = selected { if (settled) update(announce = true) }
        fillWords(fromWord, fromWordLabel, start, last = false)
        fillWords(toWord, toWordLabel, start, last = true)
        update(announce = false)
        // Spinners report their first selection after layout; only later
        // changes are the user's, and only those are read out.
        view.post { settled = true }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.passage_range_title)
            .setView(view)
            .setPositiveButton(R.string.passage_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        AppLock.secure(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                update(announce = false)
                val chosen = plan
                if (chosen == null || chosen.span.isEmpty) {
                    A11y.setStatusText(summary, activity.getString(R.string.passage_summary_empty), true)
                    return@setOnClickListener
                }
                dialog.dismiss()
                confirm(m.id, chosen, reencode)
            }
        }
        dialog.show()
    }

    private fun adapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun selected(onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    /** A line in the picker: where it starts and how it begins. */
    private fun lineLabel(segment: com.meetily.mobile.data.TranscriptSegment, startMs: Long): String {
        val words = segment.text.trim().split(Regex("\\s+"))
        val opening = if (words.size > LABEL_WORDS) words.take(LABEL_WORDS).joinToString(" ") + "…" else segment.text.trim()
        val speaker = segmentSpeakerDisplay(activity, segment)
        val text = if (speaker.isNullOrBlank()) opening else "$speaker: $opening"
        return activity.getString(R.string.passage_item, TranscriptResume.clock(startMs), text)
    }

    private fun spanText(span: PassagePlan.Span): String =
        if (span.endMs == PassagePlan.END) {
            activity.getString(R.string.passage_span_to_end, TranscriptResume.clock(span.startMs))
        } else {
            activity.getString(
                R.string.passage_span, TranscriptResume.clock(span.startMs), TranscriptResume.clock(span.endMs)
            )
        }

    private fun lengthText(ms: Long): String {
        val seconds = (ms + 500L) / 1000L
        return when {
            seconds >= 3600L -> activity.getString(
                R.string.passage_length_h, (seconds / 3600L).toInt(), ((seconds % 3600L) / 60L).toInt()
            )
            seconds >= 60L -> activity.getString(
                R.string.passage_length_m, (seconds / 60L).toInt(), (seconds % 60L).toInt()
            )
            else -> activity.getString(R.string.passage_length_s, seconds.toInt())
        }
    }

    private fun summaryText(plan: PassagePlan.Plan): String {
        val res = activity.resources
        val lines = buildList {
            if (plan.removedLines.isNotEmpty()) {
                add(res.getQuantityString(R.plurals.passage_summary_lines, plan.removedLines.size, plan.removedLines.size))
            }
            if (plan.trimmedLines.isNotEmpty()) {
                add(res.getQuantityString(R.plurals.passage_summary_trimmed, plan.trimmedLines.size, plan.trimmedLines.size))
            }
        }.joinToString(" ").ifEmpty { activity.getString(R.string.passage_confirm_silence_only) }
        val length = plan.span.lengthMs()
        return if (length != null) {
            activity.getString(R.string.passage_summary, spanText(plan.span), lengthText(length), lines)
        } else {
            activity.getString(R.string.passage_summary_no_length, spanText(plan.span), lines)
        }
    }

    // --- The confirmation ----------------------------------------------------

    private fun confirm(meetingId: String, plan: PassagePlan.Plan, reencode: PassageAudio.Reencode?) {
        val m = host.liveMeeting()?.takeIf { it.id == meetingId } ?: return
        val res = activity.resources
        val hasAudio = host.audioFile() != null
        val parts = ArrayList<String>()
        val head = ArrayList<String>()
        val length = plan.span.lengthMs()
        head.add(
            if (length != null) activity.getString(R.string.passage_confirm_length, lengthText(length))
            else activity.getString(R.string.passage_confirm_length_unknown)
        )
        if (plan.removedLines.isNotEmpty()) {
            head.add(res.getQuantityString(R.plurals.passage_confirm_lines, plan.removedLines.size, plan.removedLines.size))
        }
        if (plan.trimmedLines.isNotEmpty()) {
            head.add(res.getQuantityString(R.plurals.passage_confirm_trimmed, plan.trimmedLines.size, plan.trimmedLines.size))
        }
        // Starred lines that go, and taps a transcription had still to place.
        val unplaced = m.transcriptResumeMs?.let { resume ->
            m.highlightMarksMs.count { plan.span.contains(it) && it >= resume }
        } ?: 0
        val highlights = plan.highlightedRemoved + unplaced
        if (highlights > 0) {
            head.add(res.getQuantityString(R.plurals.passage_confirm_highlights, highlights, highlights))
        }
        if (hasAudio) head.add(activity.getString(R.string.passage_confirm_audio))
        parts.add(head.joinToString("\n"))
        if (!hasAudio) parts.add(activity.getString(R.string.passage_confirm_no_audio))
        // The whole recording is written again, not only the passage.
        val downgrade = hasAudio && reencode == PassageAudio.Reencode.DOWNGRADED
        if (downgrade) {
            parts.add(activity.getString(R.string.passage_confirm_downgrade))
        } else if (hasAudio && reencode == PassageAudio.Reencode.OTHER_CODEC) {
            parts.add(activity.getString(R.string.passage_confirm_reencode))
        }
        if (!plan.changesTranscript) parts.add(activity.getString(R.string.passage_confirm_silence_only))
        if (plan.snapped) parts.add(activity.getString(R.string.passage_confirm_snapped))
        if (plan.chaptersMoved > 0) {
            parts.add(res.getQuantityString(R.plurals.passage_confirm_chapters_moved, plan.chaptersMoved, plan.chaptersMoved))
        }
        if (plan.chaptersDropped > 0) {
            parts.add(res.getQuantityString(R.plurals.passage_confirm_chapters_dropped, plan.chaptersDropped, plan.chaptersDropped))
        }
        val photos = PassagePlan.photosTakenDuring(m.photos, plan)
        if (photos > 0) parts.add(res.getQuantityString(R.plurals.passage_confirm_photos, photos, photos))
        if (hasAudio && com.meetily.mobile.data.AudioStore.clearVoiceCopy(activity, m.audioFile, m.clearVoiceFile) != null) {
            parts.add(activity.getString(R.string.passage_confirm_clearvoice))
        }
        if (PassagePlan.staleSummaries(plan, m)) {
            parts.add(
                activity.getString(
                    // Kept summaries of other styles are marked too.
                    if (m.summaries.isEmpty()) R.string.passage_confirm_summary else R.string.passage_confirm_summaries
                )
            )
        }
        // Only the transcript and the audio are changed: text made from them
        // earlier is not rewritten.
        if (plan.changesTranscript) parts.add(activity.getString(R.string.passage_confirm_derived))
        if (m.transcriptResumeMs != null && PassagePlan.resumeAfter(plan, m.transcriptResumeMs) == null) {
            parts.add(activity.getString(R.string.passage_confirm_resume_done))
        }
        parts.add(activity.getString(R.string.passage_confirm_final))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.passage_confirm_title, spanText(plan.span)))
            .setMessage(parts.joinToString("\n\n"))
            .setPositiveButton(R.string.passage_confirm_button) { _, _ -> start(meetingId, plan.span, downgrade) }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /**
     * A run found, on decoding, that the recording has to be mixed down to
     * 16 kHz mono, which its header did not show (so the confirmation did
     * not say it) and nothing was changed: asks for that, and runs again.
     */
    private fun confirmDowngrade(meetingId: String, span: PassagePlan.Span) {
        val message = activity.getString(R.string.passage_confirm_downgrade) + "\n\n" +
            activity.getString(R.string.passage_confirm_final)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.passage_confirm_title, spanText(span)))
            .setMessage(message)
            .setPositiveButton(R.string.passage_confirm_button) { _, _ -> start(meetingId, span, allowConvert = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    // --- Running it ------------------------------------------------------------

    /** [allowConvert]: the user saw that the whole recording is mixed down to 16 kHz mono. */
    private fun start(meetingId: String, span: PassagePlan.Span, allowConvert: Boolean) {
        val m = host.liveMeeting()?.takeIf { it.id == meetingId } ?: return
        // Asked again: a job may have started while the question was up.
        PassageService.blockReason(activity, m.id)?.let {
            Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
            return
        }
        // The run applies the removal to the stored meeting, which must hold
        // what was edited here first.
        host.flushEdits()
        val audio = m.audioFile
        if (audio.isNullOrBlank() || host.audioFile() == null) {
            val app = activity.applicationContext
            Thread {
                val result = PassageService.removeLinesOnly(app, meetingId, span)
                activity.runOnUiThread { onPassageDone(meetingId, result, span) }
            }.apply { name = "passage-lines" }.start()
            return
        }
        host.releasePlayer()
        if (!PassageService.start(activity, meetingId, audio, span, allowConvert)) {
            Toast.makeText(activity, R.string.passage_failed, Toast.LENGTH_LONG).show()
            return
        }
        announcer.reset()
        showProgress(0, null)
    }

    private fun showProgress(percent: Int, minutesLeft: Long?) {
        val shown = progressDialog
        if (shown != null && shown.isShowing) {
            renderProgress(percent, minutesLeft)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val side = (24 * density).toInt()
        val text = TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_Nocturne_BodyMedium)
        }
        val note = TextView(activity).apply {
            setText(R.string.passage_progress_note)
            setTextAppearance(R.style.TextAppearance_Nocturne_BodySmall)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            // The status line above it says the same in words.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(text)
            addView(bar)
            addView(note)
        }
        progressText = text
        progressBar = bar
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.passage_progress_title)
            .setView(column)
            .setPositiveButton(R.string.passage_hide, null)
            // Not destructive: a stopped run changes nothing.
            .setNegativeButton(R.string.passage_stop) { _, _ -> PassageService.cancel() }
            .create()
        AppLock.secure(dialog)
        dialog.setOnDismissListener {
            if (progressDialog === dialog) {
                progressDialog = null
                progressBar = null
                progressText = null
            }
        }
        progressDialog = dialog
        dialog.show()
        renderProgress(percent, minutesLeft)
    }

    private fun renderProgress(percent: Int, minutesLeft: Long?) {
        val text = progressText ?: return
        progressBar?.isIndeterminate = percent <= 0
        progressBar?.progress = percent.coerceIn(0, 100)
        val line = if (minutesLeft != null) {
            activity.getString(R.string.passage_progress_eta, percent, minutesText(minutesLeft))
        } else {
            activity.getString(R.string.passage_progress, percent)
        }
        A11y.setStatusText(text, line, announcer.shouldAnnounce(percent))
    }

    private fun minutesText(minutes: Long): String =
        if (minutes >= 60) {
            activity.getString(R.string.duration_h_min, (minutes / 60).toInt(), (minutes % 60).toInt())
        } else {
            activity.getString(R.string.duration_min, minutes.toInt())
        }

    // --- PassageService.Listener ------------------------------------------------

    /** Only while on screen: a screen in the background leaves the outcome to the notification. */
    override fun showsMeeting(meetingId: String): Boolean =
        meetingId == this.meetingId && !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    override fun onPassageProgress(meetingId: String, percent: Int, minutesLeft: Long?) {
        if (meetingId != this.meetingId) return
        if (progressDialog?.isShowing == true) renderProgress(percent, minutesLeft)
    }

    override fun onPassageDone(meetingId: String, result: PassageService.Result, span: PassagePlan.Span) {
        if (meetingId != this.meetingId) return
        progressDialog?.dismiss()
        progressDialog = null
        if (activity.isDestroyed) return
        if (result == PassageService.Result.REMOVED) host.passageRemoved(span)
        // In the background the notification says it instead.
        if (!showsMeeting(meetingId)) return
        if (result == PassageService.Result.NEEDS_CONSENT) {
            confirmDowngrade(meetingId, span)
            return
        }
        val message = if (result == PassageService.Result.REMOVED) {
            activity.getString(R.string.passage_removed_span, spanText(span))
        } else {
            activity.getString(PassageService.messageFor(result))
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** How many words of a line the picker shows. */
        const val LABEL_WORDS = 8

        /** How long a read for the picker runs before "Reading the recording…" shows. */
        const val READING_NOTICE_DELAY_MS = 400L
    }
}
