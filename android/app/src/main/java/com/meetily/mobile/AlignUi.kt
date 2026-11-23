package com.meetily.mobile

import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.DeviceProbe
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordAlign
import com.meetily.mobile.security.AppLock
import com.meetily.mobile.whisper.AlignerModels

/**
 * The meeting screen's "Re-time words" (one line) and "Re-time edited
 * lines" (every line whose timings an edit estimated): the checks that
 * explain why it cannot run (no recording, not English, no model, busy),
 * the offer to download the model, the confirmation, the progress of the
 * run (AlignService) and its outcome. Kept out of MeetingDetailActivity,
 * which reaches it through [Host].
 *
 * The screen listens for the whole of its life, not only while started, so
 * a run that ends while it is in the background still has its results
 * taken into the screen's own copy, which the next save writes.
 */
class AlignUi(
    private val activity: AppCompatActivity,
    private val host: Host
) : AlignService.Listener {

    /** What the meeting screen provides. Main thread only. */
    interface Host {
        /** The screen's live meeting, or null until it has been read. */
        fun liveMeeting(): Meeting?

        /** Writes the screen's edits to the stored meeting and waits for it. */
        fun flushEdits()

        /** True when the recording is on disk. */
        fun hasAudio(): Boolean

        /** The prepared player's length, or 0 when it is not prepared. */
        fun playerDurationMs(): Long

        /** A run measured [results]: take them into the screen's copy and save it. */
        fun retimed(results: List<WordAlign.Retimed>)
    }

    private var meetingId: String? = null
    private var attached = false
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private val announcer = ProgressAnnouncer()

    /** Meetings of unknown language the user said are English, on this screen. */
    private val confirmedEnglish = HashSet<String>()

    /** From onCreate, with the meeting the screen shows: follow runs on it. */
    fun attach(meetingId: String) {
        this.meetingId = meetingId
        if (!attached) {
            AlignService.addListener(this)
            attached = true
        }
        if (AlignService.isRunning && AlignService.currentMeetingId == meetingId) {
            showProgress(AlignService.done, AlignService.total)
        }
    }

    /** From onDestroy. */
    fun detach() {
        if (attached) {
            AlignService.removeListener(this)
            attached = false
        }
        progressDialog?.dismiss()
        progressDialog = null
    }

    /**
     * True when line [segment] gets the "Re-time words" action
     * (WordAlign.offersLine): estimated or missing timings always, and
     * timings of unknown origin in a meeting that keeps its recording and
     * whose speech is English or not recorded. Asked on every bind: reads
     * fields only, and splits the line into words only when it qualifies.
     */
    fun offers(segment: TranscriptSegment): Boolean {
        val m = host.liveMeeting()
        val unknownOffered = m != null && WordAlign.mayRetimeUnknown(
            !m.audioFile.isNullOrBlank(),
            WordAlign.speech(m.language, m.translated)
        )
        return WordAlign.offersLine(segment, unknownOffered)
    }

    /**
     * True when the menu's "Re-time edited lines" shows: the meeting has a
     * recording and a line the run would take (WordAlign.needsRetime, the
     * same rule [retimeEdited] counts with). Only lines with estimated or
     * missing timings are split into words, and only until one qualifies.
     */
    fun offersAll(m: Meeting?): Boolean =
        m != null && !m.audioFile.isNullOrBlank() && m.segments.any { WordAlign.needsRetime(it) }

    /** "Re-time words" on transcript line [index]. */
    fun retimeLine(index: Int) {
        val m = host.liveMeeting() ?: return
        val segment = m.segments.getOrNull(index) ?: return
        val target = WordAlign.target(segment)
        if (target == null || !WordAlign.alignable(segment)) {
            toast(R.string.align_line_untimed)
            return
        }
        preflight(m) { start(m.id, target) }
    }

    /** "Re-time edited lines" from the menu. */
    fun retimeEdited() {
        val m = host.liveMeeting() ?: return
        val count = WordAlign.linesToRetime(m.segments).size
        if (count == 0) {
            toast(R.string.align_none_to_retime)
            return
        }
        preflight(m) {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.resources.getQuantityString(R.plurals.align_confirm_all_title, count, count))
                .setMessage(R.string.align_confirm_all_message)
                .setPositiveButton(R.string.align_confirm_all_go) { _, _ -> start(m.id, null) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            AppLock.secure(dialog)
            dialog.show()
        }
    }

    /**
     * Everything that stops a run before it starts, each explained: a run
     * on this meeting already going (shown again), no recording, speech not
     * English (unknown is asked), no model (offered), something busy. Then
     * [onReady].
     */
    private fun preflight(m: Meeting, onReady: () -> Unit) {
        if (AlignService.isRunning && AlignService.currentMeetingId == m.id) {
            showProgress(AlignService.done, AlignService.total)
            return
        }
        if (m.audioFile.isNullOrBlank() || !host.hasAudio()) {
            toast(R.string.align_no_audio)
            return
        }
        when (WordAlign.speech(m.language, m.translated)) {
            WordAlign.Speech.ENGLISH -> Unit
            WordAlign.Speech.TRANSLATED -> {
                toast(R.string.align_translated)
                return
            }
            WordAlign.Speech.OTHER -> {
                toast(R.string.align_not_english)
                return
            }
            WordAlign.Speech.UNKNOWN -> if (m.id !in confirmedEnglish) {
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.align_unknown_title)
                    .setMessage(R.string.align_unknown_message)
                    .setPositiveButton(R.string.align_unknown_confirm) { _, _ ->
                        confirmedEnglish.add(m.id)
                        preflight(m, onReady)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                AppLock.secure(dialog)
                dialog.show()
                return
            }
        }
        val model = AlignerModels.DEFAULT
        if (!AlignerModels.isDownloaded(activity, model)) {
            offerModel()
            return
        }
        AlignService.blockReason(activity, m.id)?.let {
            toast(it)
            return
        }
        onReady()
    }

    /** No model yet: say what it is and offer the download, or say it is on its way. */
    private fun offerModel() {
        if (ModelDownloadService.isRunning && (
                ModelDownloadService.currentKind == ModelDownloadService.KIND_ALIGN ||
                    ModelDownloadService.queuedItems.any { it.first == ModelDownloadService.KIND_ALIGN }
                )
        ) {
            toast(R.string.align_download_running)
            return
        }
        val model = AlignerModels.DEFAULT
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.align_need_model_title)
            .setMessage(
                activity.getString(
                    R.string.align_need_model_message,
                    Formatter.formatShortFileSize(activity, model.approxSizeMb * MB),
                    Formatter.formatShortFileSize(activity, model.unpackedMb * MB)
                )
            )
            .setPositiveButton(R.string.align_need_model_download) { _, _ -> download() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        AppLock.secure(dialog)
        dialog.show()
    }

    /** The space check, the memory check, then the download service; as Settings' pickers do. */
    private fun download() {
        val model = AlignerModels.DEFAULT
        ModelSpaceCheck.confirm(activity, listOf(AlignerModels.spaceNeededMb(model))) {
            FitBadge.confirmChoice(
                activity, model.displayName,
                DeviceProbe.verdict(activity, AlignerModels.need(model)),
                alreadyActive = false
            ) {
                if (ModelDownloadService.start(activity, ModelDownloadService.KIND_ALIGN, model.key)) {
                    toast(R.string.align_download_started)
                } else {
                    toast(R.string.devicefit_download_not_started)
                }
            }
        }
    }

    private fun start(meetingId: String, line: WordAlign.Target?) {
        val m = host.liveMeeting()?.takeIf { it.id == meetingId } ?: return
        // Asked again: a job may have started while a question was up.
        AlignService.blockReason(activity, m.id)?.let {
            toast(it)
            return
        }
        // The run reads the stored lines, which must hold what was edited here.
        host.flushEdits()
        if (!AlignService.start(activity, meetingId, line, host.playerDurationMs())) {
            toast(R.string.align_failed)
            return
        }
        announcer.reset()
        showProgress(0, 0)
    }

    private fun showProgress(done: Int, total: Int) {
        val shown = progressDialog
        if (shown != null && shown.isShowing) {
            renderProgress(done, total)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val side = (24 * density).toInt()
        val text = TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_Nocturne_BodyMedium)
        }
        val note = TextView(activity).apply {
            setText(R.string.align_progress_note)
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
            .setTitle(R.string.align_progress_title)
            .setView(column)
            .setPositiveButton(R.string.align_hide, null)
            // Not destructive: what was measured is kept, nothing else changes.
            .setNegativeButton(R.string.align_stop) { _, _ -> AlignService.cancel() }
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
        renderProgress(done, total)
    }

    private fun renderProgress(done: Int, total: Int) {
        val text = progressText ?: return
        val percent = if (total > 0) done * 100 / total else -1
        progressBar?.isIndeterminate = total <= 0
        progressBar?.progress = percent.coerceIn(0, 100)
        val line = if (total > 0) {
            activity.resources.getQuantityString(R.plurals.align_progress, total, done, total)
        } else {
            activity.getString(R.string.align_progress_loading)
        }
        A11y.setStatusText(text, line, announcer.shouldAnnounce(percent))
    }

    private fun toast(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    // --- AlignService.Listener ------------------------------------------------

    /** Only while on screen: a screen in the background leaves the outcome to the notification. */
    override fun showsMeeting(meetingId: String): Boolean =
        meetingId == this.meetingId && !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    override fun onAlignProgress(meetingId: String, done: Int, total: Int) {
        if (meetingId != this.meetingId) return
        if (progressDialog?.isShowing == true) renderProgress(done, total)
    }

    override fun onAlignDone(meetingId: String, outcome: AlignService.Outcome) {
        if (meetingId != this.meetingId) return
        progressDialog?.dismiss()
        progressDialog = null
        if (activity.isDestroyed) return
        // Into this screen's copy too, by the same rule as the store's, so
        // the next save carries them and a line edited here keeps its edit.
        if (outcome.retimed.isNotEmpty()) host.retimed(outcome.retimed)
        if (!showsMeeting(meetingId)) return
        if (outcome.result == AlignService.Result.NO_MODEL) {
            offerModel()
            return
        }
        Toast.makeText(activity, AlignService.messageFor(activity, outcome), Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
