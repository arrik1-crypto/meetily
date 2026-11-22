package com.meetily.mobile

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.FindReplace
import com.meetily.mobile.data.FindReplace.Area
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.security.AppLock
import com.meetily.mobile.whisper.Vocab
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The meeting screen's find & replace: the dialog with its live preview, the
 * one-level undo, and the offer to add a corrected term to custom
 * vocabulary. What a replace does is FindReplace's; this is the screen side,
 * kept out of MeetingDetailActivity, which reaches it through [Host].
 *
 * The preview is counted on a worker over a copy of the texts taken when the
 * dialog opens. The replace itself runs on the screen's live meeting and is
 * saved through the screen's own merge-and-save path, like any other edit.
 */
class MeetingReplace(
    private val activity: AppCompatActivity,
    private val host: Host
) {

    /** What the meeting screen provides. Main thread only. */
    interface Host {
        /** The screen's live meeting, or null until it has been read. */
        fun liveMeeting(): Meeting?

        /** Takes the notes, attendees and tags fields into the meeting. */
        fun takeFieldEdits()

        /** Saves [meeting] as the screen saves any edit, and shows what changed. */
        fun replaced(meeting: Meeting)

        /** A bar at the bottom of the screen a snackbar should sit above, if shown. */
        fun snackbarAnchor(): View?
    }

    private val model: ReplaceUndoModel =
        ViewModelProvider(activity, ReplaceUndoModel.FACTORY)[ReplaceUndoModel::class.java]

    private val main = Handler(Looper.getMainLooper())

    /** True while the last replace can be undone (the menu's "Undo last replace"). */
    fun canUndo(meetingId: String?): Boolean {
        val snapshot = model.snapshot ?: return false
        return meetingId != null && meetingId == model.meetingId && snapshot.counts.total() > 0
    }

    /**
     * A run on this meeting is about to write its summary, notes or speaker
     * tags. Replacing under it would be overwritten by it, or would leave a
     * summary from the old wording marked as current.
     */
    private fun busy(m: Meeting): Boolean =
        SummaryService.isRunning && SummaryService.currentMeetingId == m.id

    /** "Fix a word everywhere…" on transcript line [index]: which word, then the dialog. */
    fun fixWord(index: Int) {
        val segment = host.liveMeeting()?.segments?.getOrNull(index) ?: return
        val words = FindReplace.wordsOf(segment.text, segment.words)
        when (words.size) {
            0 -> return
            1 -> open(words[0])
            else -> AlertDialog.Builder(activity)
                .setTitle(R.string.replace_pick_word_title)
                .setItems(words.toTypedArray()) { _, which -> open(words[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .showSecure()
        }
    }

    /**
     * The find & replace dialog. With [word], it opens to fix that word:
     * found as typed, and the replacement field holding it to be corrected.
     */
    fun open(word: String? = null) {
        val m = host.liveMeeting() ?: return
        if (busy(m)) {
            Toast.makeText(activity, R.string.replace_busy, Toast.LENGTH_LONG).show()
            return
        }
        host.takeFieldEdits()
        val source = FindReplace.Source.of(m)
        val hasSummary = m.summary.isNotBlank()

        val view = LayoutInflater.from(activity).inflate(R.layout.replace_dialog, null)
        val findInput = view.findViewById<EditText>(R.id.replace_find_input)
        val withInput = view.findViewById<EditText>(R.id.replace_with_input)
        val wholeWord = view.findViewById<CheckBox>(R.id.replace_whole_word)
        val matchCase = view.findViewById<CheckBox>(R.id.replace_match_case)
        val transcriptBox = view.findViewById<CheckBox>(R.id.replace_area_transcript)
        val notesBox = view.findViewById<CheckBox>(R.id.replace_area_notes)
        val summaryBox = view.findViewById<CheckBox>(R.id.replace_area_summary)
        val actionsBox = view.findViewById<CheckBox>(R.id.replace_area_actions)
        val preview = view.findViewById<TextView>(R.id.replace_preview)
        val staleNote = view.findViewById<View>(R.id.replace_stale_note)
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.replace_areas_label), true)

        if (word != null) {
            findInput.setText(word)
            withInput.setText(word)
            withInput.selectAll()
            withInput.requestFocus()
        } else {
            findInput.requestFocus()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.replace_title)
            .setView(view)
            .setPositiveButton(R.string.replace_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        AppLock.secure(dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        fun query() = FindReplace.Query.of(
            findInput.text.toString(),
            withInput.text.toString(),
            FindReplace.Options(wholeWord = wholeWord.isChecked, matchCase = matchCase.isChecked)
        )

        fun areas(): Set<Area> = buildSet {
            if (transcriptBox.isChecked) add(Area.TRANSCRIPT)
            if (notesBox.isChecked) add(Area.NOTES)
            if (summaryBox.isChecked) add(Area.SUMMARY)
            if (actionsBox.isChecked) add(Area.ACTION_ITEMS)
        }

        // The counts for [countedFor], once the worker has them.
        var counts: FindReplace.Counts? = null
        var countedFor: FindReplace.Query? = null
        val generation = AtomicInteger()

        fun render() {
            val q = query()
            val current = counts?.takeIf { countedFor == q }
            val c = current ?: FindReplace.Counts()
            val res = activity.resources
            fun changes(n: Int) = res.getQuantityString(R.plurals.replace_changes, n, n)
            transcriptBox.text = if (c.transcriptLines > 0) {
                activity.getString(
                    R.string.replace_area_transcript,
                    changes(c.transcriptMatches),
                    res.getQuantityString(R.plurals.replace_lines, c.transcriptLines, c.transcriptLines)
                )
            } else {
                activity.getString(R.string.replace_area_transcript_none, changes(0))
            }
            notesBox.text = activity.getString(R.string.replace_area_notes, changes(c.notes))
            summaryBox.text = activity.getString(R.string.replace_area_summary, changes(c.summary))
            actionsBox.text = activity.getString(R.string.replace_area_actions, changes(c.actionItems))

            val areas = areas()
            val total = c.total(areas)
            val counting = q.find.isNotEmpty() && current == null
            val status = when {
                q.find.isEmpty() -> activity.getString(R.string.replace_preview_empty)
                // Opened to fix a word: both fields still read the same.
                q.replace == q.find -> activity.getString(R.string.replace_preview_same)
                counting -> activity.getString(R.string.replace_preview_counting)
                total == 0 -> activity.getString(R.string.replace_preview_none)
                else -> res.getQuantityString(R.plurals.replace_preview_total, total, total)
            }
            // Spoken once the count lands, not on every keystroke.
            A11y.setStatusText(
                preview, status,
                announce = !counting && !android.text.TextUtils.equals(preview.text, status)
            )
            staleNote.visibility =
                if (hasSummary && Area.TRANSCRIPT in areas && c.transcriptMatches > 0) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = total > 0
        }

        val recount = Runnable {
            val q = query()
            val ticket = generation.incrementAndGet()
            if (q.find.isEmpty()) {
                counts = null
                countedFor = null
                render()
                return@Runnable
            }
            COUNTER.execute {
                if (generation.get() != ticket) return@execute
                val result = try {
                    FindReplace.count(source, q)
                } catch (_: RuntimeException) {
                    FindReplace.Counts()
                }
                main.post {
                    if (generation.get() != ticket || !dialog.isShowing) return@post
                    counts = result
                    countedFor = q
                    render()
                }
            }
        }

        fun scheduleCount() {
            main.removeCallbacks(recount)
            render()
            main.postDelayed(recount, COUNT_DELAY_MS)
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleCount()
        }
        findInput.addTextChangedListener(watcher)
        withInput.addTextChangedListener(watcher)
        wholeWord.setOnCheckedChangeListener { _, _ -> scheduleCount() }
        matchCase.setOnCheckedChangeListener { _, _ -> scheduleCount() }
        for (box in listOf(transcriptBox, notesBox, summaryBox, actionsBox)) {
            box.setOnCheckedChangeListener { _, _ -> render() }
        }
        dialog.setOnDismissListener {
            main.removeCallbacks(recount)
            generation.incrementAndGet()
        }

        dialog.show()
        var applied = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            // A second tap queued before the dismiss must not replace again.
            if (applied) return@setOnClickListener
            applied = true
            val q = query()
            val areas = areas()
            dialog.dismiss()
            runReplace(q, areas)
        }
        render()
        // Straight away: the dialog may open with a word already filled in.
        main.post(recount)
    }

    private fun runReplace(query: FindReplace.Query, areas: Set<Area>) {
        val m = host.liveMeeting() ?: return
        if (busy(m)) {
            Toast.makeText(activity, R.string.replace_busy, Toast.LENGTH_LONG).show()
            return
        }
        host.takeFieldEdits()
        val snapshot = FindReplace.apply(m, query, areas)
        if (snapshot == null) {
            Toast.makeText(activity, R.string.replace_nothing, Toast.LENGTH_LONG).show()
            return
        }
        model.snapshot = snapshot
        model.meetingId = m.id
        host.replaced(m)

        val total = snapshot.counts.total()
        val done = activity.resources.getQuantityString(R.plurals.replace_done, total, total)
        val settings = AppSettings(activity)
        val term = FindReplace.vocabTerm(query, snapshot.counts, settings.customVocab)
            ?.takeIf { it.lowercase() !in model.declinedTerms }
        if (term == null) {
            showUndoBar(done)
            return
        }
        var added = false
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.replace_vocab_title, term))
            .setMessage(R.string.replace_vocab_body)
            .setPositiveButton(R.string.replace_vocab_add) { _, _ ->
                settings.customVocab = Vocab.withPreset(settings.customVocab, listOf(term))
                added = true
            }
            .setNegativeButton(R.string.replace_vocab_skip) { _, _ ->
                model.declinedTerms.add(term.lowercase())
            }
            // After the question, so the undo offer is not hidden under it.
            .setOnDismissListener {
                if (activity.isFinishing || activity.isDestroyed) return@setOnDismissListener
                showUndoBar(
                    if (added) "$done. ${activity.getString(R.string.replace_vocab_added, term)}" else done
                )
            }
            .showSecure()
    }

    private fun showUndoBar(message: CharSequence) {
        val bar = Snackbar.make(
            activity.findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_LONG
        )
        bar.setAction(R.string.replace_undo) { undo() }
        host.snackbarAnchor()?.let { bar.setAnchorView(it) }
        bar.show()
    }

    /** Undoes the last replace, keeping anything edited since. */
    fun undo() {
        val m = host.liveMeeting() ?: return
        val snapshot = model.snapshot ?: return
        if (model.meetingId != m.id) {
            model.snapshot = null
            return
        }
        if (busy(m)) {
            Toast.makeText(activity, R.string.replace_busy, Toast.LENGTH_LONG).show()
            return
        }
        host.takeFieldEdits()
        val result = FindReplace.undo(m, snapshot)
        model.snapshot = null
        host.replaced(m)
        val message = when {
            result.restored == 0 -> activity.getString(R.string.replace_undo_nothing)
            result.skipped > 0 -> activity.resources.getQuantityString(
                R.plurals.replace_undone_partly, result.skipped, result.skipped
            )
            else -> activity.getString(R.string.replace_undone)
        }
        val bar = Snackbar.make(
            activity.findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_LONG
        )
        host.snackbarAnchor()?.let { bar.setAnchorView(it) }
        bar.show()
    }

    private companion object {
        /** Typing pause before the preview is counted again. */
        const val COUNT_DELAY_MS = 200L

        /**
         * One worker for every preview, which ends when idle: a dialog torn
         * down with its screen never runs its dismiss listener, and a worker
         * of its own would then be left waiting for good.
         */
        val COUNTER = ThreadPoolExecutor(
            0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { runnable -> Thread(runnable, "replace-preview").apply { isDaemon = true } }
    }
}

/**
 * The last replace on a meeting screen, kept across a rotation so "Undo last
 * replace" survives it. One level; never saved, so it ends with the screen.
 */
class ReplaceUndoModel : ViewModel() {
    var snapshot: FindReplace.Snapshot? = null
    var meetingId: String? = null

    /** Terms the user said "Not now" to on this screen; not offered again. */
    val declinedTerms = HashSet<String>()

    companion object {
        /** Builds the model directly, without the default factory's reflection. */
        val FACTORY: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReplaceUndoModel() as T
        }
    }
}
