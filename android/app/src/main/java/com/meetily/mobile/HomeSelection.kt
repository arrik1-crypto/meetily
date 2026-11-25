package com.meetily.mobile

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.BulkActions
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.summarize.AiDisclaimers
import java.util.concurrent.Executors

/**
 * Multi-select on the home list (MainActivity).
 *
 * A long press selects a meeting and turns the header into a contextual
 * action bar: the count, select all, delete, and More (add a tag, remove
 * audio, export as a zip, or the one meeting's own sheet). Back leaves
 * selection mode. The selection survives rotation; the list repaints only
 * rows that changed (MeetingAdapter.setSelection), and screen readers hear
 * each row as checkable and the count as it changes. Each action works out
 * on a worker which selected meetings it may touch (BulkActions) and
 * confirms with counts before changing anything.
 *
 * Construct it as a property of the activity: it registers the export's
 * file picker, which has to happen before the activity starts.
 */
class HomeSelection(
    private val activity: AppCompatActivity,
    /** Reloads the library after an action changed it. */
    private val refreshLibrary: () -> Unit,
    /** The single-meeting action sheet (MainActivity.showMeetingActions). */
    private val showOptions: (Meeting) -> Unit
) {

    /** Selected meeting ids, in the order picked; null outside selection mode. */
    private var selection: Set<String>? = null

    /** True while meetings are being selected: a tap then toggles instead of opening. */
    val active: Boolean get() = selection != null

    /** The first library load has landed; until then an empty list means "not read yet". */
    private var libraryLoaded = false

    private lateinit var adapter: MeetingAdapter
    private lateinit var homeHeader: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var selectionSelectAll: ImageButton

    /** The meetings an export is writing, kept across the file picker (see [save]). */
    private var pendingExportIds: ArrayList<String>? = null

    /** Bulk work: one action at a time, never on the main thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bulk-actions") }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exit()
        }
    }

    private val exportZip =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val ids = pendingExportIds
            pendingExportIds = null
            if (uri != null && !ids.isNullOrEmpty()) writeExportZip(uri, ids)
        }

    /** Wires the selection bar and restores a selection from before a rotation. */
    fun setUp(adapter: MeetingAdapter, savedInstanceState: Bundle?) {
        this.adapter = adapter
        homeHeader = activity.findViewById(R.id.homeHeader)
        selectionBar = activity.findViewById(R.id.selectionBar)
        selectionCount = activity.findViewById(R.id.selectionCount)
        selectionSelectAll = activity.findViewById(R.id.selectionSelectAll)
        activity.findViewById<View>(R.id.selectionClose).setOnClickListener { exit() }
        selectionSelectAll.setOnClickListener { toggleSelectAll() }
        activity.findViewById<View>(R.id.selectionDelete).setOnClickListener { bulkDelete() }
        activity.findViewById<View>(R.id.selectionMore).setOnClickListener { showMore() }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        pendingExportIds = savedInstanceState?.getStringArrayList(KEY_EXPORT_IDS)
        selection = savedInstanceState?.getStringArrayList(KEY_SELECTION)
            ?.takeIf { it.isNotEmpty() }
            ?.let { LinkedHashSet(it) }
        render()
    }

    fun save(outState: Bundle) {
        selection?.let { outState.putStringArrayList(KEY_SELECTION, ArrayList(it)) }
        pendingExportIds?.let { outState.putStringArrayList(KEY_EXPORT_IDS, it) }
    }

    fun destroy() {
        worker.shutdown()
    }

    fun onLibraryLoaded() {
        libraryLoaded = true
    }

    /**
     * The list now shows [shown]. Selected meetings it stopped showing
     * (deleted, or filtered out) leave the selection: no action may reach a
     * hidden meeting. Not before the first load, when the list is empty only
     * because nothing has been read yet (a rotation restoring a selection).
     */
    fun onListShown(shown: List<Meeting>) {
        val selected = selection ?: return
        if (!libraryLoaded) return
        val kept = BulkActions.pruned(selected, shown.map { it.id })
        selection = kept.ifEmpty { null }
        render(announce = kept.size != selected.size && kept.isNotEmpty())
    }

    /** A long press: starts selecting, or toggles while selecting. */
    fun onLongPress(meeting: Meeting) {
        if (selection == null) {
            selection = linkedSetOf(meeting.id)
            render(announce = true)
        } else {
            toggle(meeting)
        }
    }

    fun toggle(meeting: Meeting) {
        update(BulkActions.toggled(selection.orEmpty(), meeting.id))
    }

    private fun toggleSelectAll() {
        update(BulkActions.selectAllToggled(selection.orEmpty(), adapter.listedMeetings().map { it.id }))
    }

    private fun update(next: Set<String>) {
        if (next.isEmpty()) {
            exit()
        } else {
            selection = next
            render(announce = true)
        }
    }

    fun exit() {
        if (selection == null) return
        selection = null
        render()
    }

    /**
     * Shows the selection: the bar in place of the header, the rows, and
     * the count, which a screen reader hears when [announce] is set (each
     * change the user makes).
     */
    private fun render(announce: Boolean = false) {
        val selected = selection
        selectionBar.visibility = if (selected != null) View.VISIBLE else View.GONE
        homeHeader.visibility = if (selected != null) View.GONE else View.VISIBLE
        back.isEnabled = selected != null
        adapter.setSelection(selected)
        if (selected == null) return
        A11y.setStatusText(
            selectionCount,
            activity.resources.getQuantityString(R.plurals.storage_selected_count, selected.size, selected.size),
            announce
        )
        val all = BulkActions.allSelected(selected, adapter.listedMeetings().map { it.id })
        selectionSelectAll.contentDescription =
            activity.getString(if (all) R.string.storage_clear_selection else R.string.storage_select_all)
    }

    /** The selected meetings as listed, in list order. */
    private fun selectedMeetings(): List<Meeting> {
        val ids = selection ?: return emptyList()
        return adapter.listedMeetings().filter { it.id in ids }
    }

    private fun live(): Boolean = !activity.isFinishing && !activity.isDestroyed

    /**
     * How many of the meetings handed to the latest [prepare] callback have
     * a clear-voice copy (counted in its bytes). Set on the main thread just
     * before that callback runs, and read by it.
     */
    private var preparedClearVoiceCopies = 0

    /**
     * Works out on [worker] which of the selected meetings [action] may
     * touch, then calls [onReady] on the main thread with them, how many
     * were left out, and the audio bytes the eligible ones hold (clear-voice
     * copies included). Nothing happens if the screen went away meanwhile.
     */
    private fun prepare(
        action: BulkActions.Action,
        onReady: (List<Meeting>, Int, Long) -> Unit
    ) {
        val meetings = selectedMeetings()
        if (meetings.isEmpty()) return
        val app = activity.applicationContext
        worker.execute {
            val recordingId = if (RecordingService.isRunning) MeetingStore(app).activeId() else null
            val waiting = AudioRemoval.waitingForAudio(app, meetings.map { it.id })
            val audioBytes = HashMap<String, Long>()
            val withCopy = HashSet<String>()
            val items = meetings.map { meeting ->
                val bytes = meeting.audioFile?.let { AudioStore.fileFor(app, it).length() } ?: 0L
                // The clear-voice copy goes with the recording (AudioStore.delete).
                val total = if (bytes > 0L) AudioStore.bytesWithCopy(app, meeting.audioFile) else 0L
                if (total > bytes) withCopy.add(meeting.id)
                audioBytes[meeting.id] = total
                BulkActions.Item(
                    id = meeting.id,
                    hasAudio = bytes > 0L,
                    recording = meeting.id == recordingId,
                    busy = (ImportService.isRunning && ImportService.currentMeetingId == meeting.id) ||
                        (SummaryService.isRunning && SummaryService.currentMeetingId == meeting.id) ||
                        ClearVoiceService.isPreparing(meeting.id) ||
                        PassageService.isRemoving(meeting.id) ||
                        AlignService.isAligning(meeting.id),
                    queued = meeting.id in waiting,
                    // The list's copy has every transcript line (only word
                    // timings are left out).
                    hasTranscript = meeting.segments.isNotEmpty(),
                    unfinished = meeting.transcriptResumeMs != null
                )
            }
            val split = BulkActions.split(action, items)
            val eligible = meetings.filter { it.id in split.eligible }
            val bytes = eligible.sumOf { audioBytes[it.id] ?: 0L }
            val copies = eligible.count { it.id in withCopy }
            activity.runOnUiThread {
                if (!live()) return@runOnUiThread
                if (eligible.isEmpty()) {
                    Toast.makeText(activity, R.string.storage_bulk_none, Toast.LENGTH_LONG).show()
                } else {
                    preparedClearVoiceCopies = copies
                    onReady(eligible, split.skipped.size, bytes)
                }
            }
        }
    }

    /**
     * "… left out" under the Delete and Add tag confirmations, when part of
     * the selection is. Only a recording, or work in progress for Delete,
     * leaves a meeting out of those (BulkActions.eligible); Remove audio has
     * its own note with its other reasons (AudioRemoval.confirm).
     */
    private fun skippedNote(skipped: Int): String =
        if (skipped == 0) "" else "\n\n" + activity.resources.getQuantityString(
            R.plurals.storage_bulk_skipped_busy, skipped, skipped
        )

    /** After a bulk action (main thread): say what happened, leave selection mode, show the result. */
    private fun finish(message: String) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
        if (!live()) return
        exit()
        refreshLibrary()
    }

    private fun bulkDelete() {
        prepare(BulkActions.Action.DELETE) { eligible, skipped, _ ->
            AlertDialog.Builder(activity)
                .setTitle(
                    activity.resources.getQuantityString(
                        R.plurals.storage_bulk_delete_title, eligible.size, eligible.size
                    )
                )
                .setMessage(
                    activity.resources.getQuantityString(R.plurals.storage_bulk_delete_message, eligible.size) +
                        skippedNote(skipped)
                )
                .setPositiveButton(R.string.delete) { _, _ ->
                    val app = activity.applicationContext
                    val ids = eligible.map { it.id }
                    worker.execute {
                        // Into Recently deleted with their files. A job may
                        // have started on one while the question was up:
                        // that one stays (TrashUi, as MainActivity.confirmDelete).
                        val batch = TrashUi.moveToTrash(app, ids)
                        activity.runOnUiThread {
                            if (batch.moved.isEmpty()) {
                                Toast.makeText(app, R.string.storage_bulk_none, Toast.LENGTH_LONG).show()
                            }
                            if (!live()) return@runOnUiThread
                            exit()
                            refreshLibrary()
                            TrashUi.showUndo(activity, batch, activity.findViewById(R.id.importButton)) {
                                refreshLibrary()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSecure()
        }
    }

    private fun bulkRemoveAudio() {
        prepare(BulkActions.Action.REMOVE_AUDIO) { eligible, skipped, bytes ->
            AudioRemoval.confirm(
                activity, eligible.size, bytes, skipped,
                clearVoiceCopies = preparedClearVoiceCopies
            ) {
                val app = activity.applicationContext
                val ids = eligible.map { it.id }
                worker.execute {
                    val store = MeetingStore(app)
                    var count = 0
                    var freed = 0L
                    for (id in ids) {
                        val removed = AudioRemoval.remove(app, store, id)
                        if (removed >= 0L) {
                            count++
                            freed += removed
                        }
                    }
                    val text = app.resources.getQuantityString(
                        R.plurals.storage_audio_removed_many, count, count,
                        Formatter.formatShortFileSize(app, freed)
                    )
                    activity.runOnUiThread { finish(text) }
                }
            }
        }
    }

    private fun bulkTag() {
        prepare(BulkActions.Action.ADD_TAG) { eligible, skipped, _ ->
            val density = activity.resources.displayMetrics.density
            val input = EditText(activity).apply {
                hint = activity.getString(R.string.storage_tag_hint)
                maxLines = 1
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                minHeight = A11y.minTouchPx(activity)
            }
            val box = FrameLayout(activity).apply {
                val pad = (20 * density).toInt()
                setPadding(pad, (8 * density).toInt(), pad, 0)
                addView(input)
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle(
                    activity.resources.getQuantityString(
                        R.plurals.storage_tag_title, eligible.size, eligible.size
                    )
                )
                .setView(box)
                .setPositiveButton(R.string.storage_tag_add, null)
                .setNegativeButton(android.R.string.cancel, null)
            if (skipped > 0) builder.setMessage(skippedNote(skipped).trim())
            val dialog = builder.showSecure()
            // Set after show, so an invalid tag keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val tag = BulkActions.normalizeTag(input.text.toString())
                if (tag == null) {
                    input.error = activity.getString(R.string.storage_tag_invalid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                val app = activity.applicationContext
                val ids = eligible.map { it.id }
                worker.execute {
                    val store = MeetingStore(app)
                    var tagged = 0
                    for (id in ids) {
                        // Under the store's write lock, like starring. A
                        // meeting that already has the tag is not rewritten
                        // (its file time is the clean-up's "changed
                        // recently") and not counted.
                        var changed = false
                        val saved = store.mutateIf(id) { meeting ->
                            val tags = BulkActions.withTag(meeting.tags, tag)
                            if (tags != null) {
                                meeting.tags = tags.toMutableList()
                                changed = true
                            }
                            changed
                        }
                        if (saved && changed) tagged++
                    }
                    val text = app.resources.getQuantityString(R.plurals.storage_tagged, tagged, tagged)
                    activity.runOnUiThread { finish(text) }
                }
            }
        }
    }

    private fun bulkExport() {
        prepare(BulkActions.Action.EXPORT) { eligible, _, _ ->
            pendingExportIds = ArrayList(eligible.map { it.id })
            try {
                exportZip.launch(activity.getString(R.string.storage_export_zip_name))
            } catch (e: Exception) {
                pendingExportIds = null
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.export_failed,
                        e.message ?: activity.getString(R.string.platform_error_no_picker)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * One Markdown file per meeting (MeetingExporter, with each summary's
     * fixed notices as on the meeting's own export), zipped into the
     * document the user picked. Written on [worker]: the destination may be
     * a provider that uploads as it is written.
     */
    private fun writeExportZip(uri: Uri, ids: List<String>) {
        val app = activity.applicationContext
        worker.execute {
            // The message to show, and whether the export was written.
            val result = try {
                val store = MeetingStore(app)
                var written = 0
                val raw = app.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException(app.getString(R.string.platform_error_no_destination))
                val text = com.meetily.mobile.export.ExportTextResources.load(app)
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(raw)).use { zip ->
                    val taken = HashSet<String>()
                    val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    for (id in ids) {
                        val meeting = store.load(id) ?: continue
                        val notices = AiDisclaimers.texts(app, AiDisclaimers.categories(meeting))
                        val name = BulkActions.uniqueName(
                            day.format(java.util.Date(meeting.createdAtMs)) + "-" +
                                MeetingExporter.suggestedFileName(meeting, "md"),
                            taken
                        )
                        taken.add(name)
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(
                            MeetingExporter.markdown(meeting, notices, text).toByteArray(Charsets.UTF_8)
                        )
                        zip.closeEntry()
                        written++
                    }
                }
                app.resources.getQuantityString(R.plurals.storage_exported, written, written) to true
            } catch (e: Exception) {
                app.getString(
                    R.string.export_failed, e.message ?: app.getString(R.string.platform_error_unknown)
                ) to false
            }
            activity.runOnUiThread {
                if (result.second) {
                    finish(result.first)
                } else {
                    Toast.makeText(app, result.first, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** The selection bar's More: tag, export, remove audio, and one meeting's own sheet. */
    private fun showMore() {
        val selected = selectedMeetings()
        if (selected.isEmpty()) return
        val items = ArrayList<ActionSheet.Item>()
        if (selected.size == 1) {
            items.add(ActionSheet.Item(ACTION_OPTIONS, activity.getString(R.string.a11y_meeting_options)))
        }
        items.add(ActionSheet.Item(ACTION_TAG, activity.getString(R.string.storage_bulk_tag)))
        items.add(ActionSheet.Item(ACTION_EXPORT, activity.getString(R.string.storage_bulk_export)))
        items.add(
            ActionSheet.Item(
                ACTION_REMOVE_AUDIO,
                activity.getString(R.string.storage_bulk_remove_audio),
                destructive = true,
                separated = true
            )
        )
        ActionSheet.show(
            activity,
            activity.resources.getQuantityString(R.plurals.storage_selected_count, selected.size, selected.size),
            items
        ) { id ->
            when (id) {
                ACTION_OPTIONS -> {
                    val meeting = selected.first()
                    exit()
                    showOptions(meeting)
                }
                ACTION_TAG -> bulkTag()
                ACTION_EXPORT -> bulkExport()
                ACTION_REMOVE_AUDIO -> bulkRemoveAudio()
            }
        }
    }

    private companion object {
        const val KEY_SELECTION = "storage_selection"
        const val KEY_EXPORT_IDS = "storage_export_ids"
        const val ACTION_OPTIONS = "options"
        const val ACTION_TAG = "tag"
        const val ACTION_EXPORT = "export"
        const val ACTION_REMOVE_AUDIO = "remove_audio"
    }
}
