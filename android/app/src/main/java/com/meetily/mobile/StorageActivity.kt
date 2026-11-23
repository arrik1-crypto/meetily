package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.MeetingTrash
import com.meetily.mobile.data.StorageUsage
import java.util.concurrent.Executors

/**
 * What Recap keeps on this phone and how to make room: bytes per area and
 * per model family, free space, the meetings with the largest recordings
 * (each can drop its audio and keep its transcript), what Recently deleted
 * holds (MeetingTrash), deleting downloaded models nothing uses, and the
 * automatic clean-up rule.
 *
 * Declared as the application's manageSpaceActivity, so Android's own
 * "Manage space" button for Recap opens this instead of offering to wipe
 * everything; Settings opens it too. Every measurement runs on a worker.
 */
class StorageActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var free: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var areas: LinearLayout
    private lateinit var largest: LinearLayout
    private lateinit var modelsSummary: TextView
    private lateinit var deleteModels: MaterialButton
    private lateinit var retentionSummary: TextView
    private lateinit var trashSummary: TextView
    private lateinit var trashEmpty: MaterialButton

    /** One scan at a time, in order; a rescan queued behind a running one simply follows it. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "storage-scan") }

    /** Bumped per scan, so only the newest result is shown. */
    private var scanGeneration = 0

    private var report: StorageScan.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_storage)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.edgeScroll)
        }
        findViewById<MaterialToolbar>(R.id.storageToolbar).setNavigationOnClickListener {
            finish()
        }
        status = findViewById(R.id.storageStatus)
        free = findViewById(R.id.storageFree)
        progress = findViewById(R.id.storageProgress)
        areas = findViewById(R.id.storageAreas)
        largest = findViewById(R.id.storageLargest)
        modelsSummary = findViewById(R.id.storageModelsSummary)
        deleteModels = findViewById(R.id.storageDeleteModels)
        retentionSummary = findViewById(R.id.storageRetentionSummary)
        deleteModels.setOnClickListener { confirmDeleteModels() }
        trashSummary = findViewById(R.id.storageTrashSummary)
        trashEmpty = findViewById(R.id.storageTrashEmpty)
        trashEmpty.setOnClickListener { confirmEmptyTrash() }
        findViewById<View>(R.id.storageTrashOpen).setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        findViewById<View>(R.id.storageRetentionButton).apply {
            contentDescription = getString(R.string.storage_cleanup_change_desc)
            setOnClickListener { RetentionUi.choose(this@StorageActivity) { onRetentionChanged() } }
        }
        renderRetention()
        // The daily clean-up stopped short of a large batch and asked: show
        // it once, not again on rotation.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_REVIEW_CLEANUP, false) == true) {
            val rule = RetentionRunner.rule(this)
            if (rule.enabled) RetentionUi.preview(this, rule, review = true) { onRetentionChanged() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Every return re-measures: a meeting deleted, a model downloaded.
        scan()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun onRetentionChanged() {
        renderRetention()
        scan()
    }

    private fun renderRetention() {
        retentionSummary.text = RetentionUi.summary(this, RetentionRunner.rule(this))
    }

    private fun scan() {
        val generation = ++scanGeneration
        progress.visibility = View.VISIBLE
        val app = applicationContext
        worker.execute {
            val result = try {
                StorageScan.run(app)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != scanGeneration) return@runOnUiThread
                progress.visibility = View.GONE
                if (result == null) {
                    A11y.setStatusText(status, getString(R.string.storage_scan_failed), announce = true)
                    return@runOnUiThread
                }
                // Spoken only for the first result, or when the total moved.
                val announce = report?.totalBytes != result.totalBytes
                report = result
                render(result, announce)
            }
        }
    }

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes.coerceAtLeast(0L))

    private fun render(report: StorageScan.Report, announce: Boolean) {
        A11y.setStatusText(status, getString(R.string.storage_total, size(report.totalBytes)), announce)
        free.visibility = View.VISIBLE
        free.text = getString(R.string.storage_free, size(report.freeBytes))

        areas.removeAllViews()
        for ((area, bytes) in report.totals) {
            areas.addView(row(getString(labelFor(area)), size(bytes), clickable = false))
        }

        largest.removeAllViews()
        if (report.largest.isEmpty()) {
            largest.addView(row(getString(R.string.storage_largest_empty), null, clickable = false))
        } else {
            for (meeting in report.largest) {
                val view = row(meeting.title, size(meeting.removableBytes), clickable = true)
                view.setOnClickListener { showMeetingOptions(meeting) }
                A11y.labelClick(view, getString(R.string.a11y_meeting_options))
                largest.addView(view)
            }
        }

        val trash = report.trash
        if (trash.isEmpty()) {
            trashSummary.text = getString(R.string.trash_none)
            trashEmpty.isEnabled = false
        } else {
            trashSummary.text = resources.getQuantityString(
                R.plurals.trash_storage_summary, trash.size, trash.size, size(report.trashBytes)
            )
            trashEmpty.isEnabled = true
        }

        val unused = report.unusedModels
        if (unused.isEmpty()) {
            modelsSummary.text = getString(R.string.storage_unused_none)
            deleteModels.isEnabled = false
        } else {
            modelsSummary.text = resources.getQuantityString(
                R.plurals.storage_unused_summary, unused.size, unused.size,
                size(unused.sumOf { it.bytes })
            )
            deleteModels.isEnabled = true
        }
    }

    private fun labelFor(area: StorageUsage.Area): Int = when (area) {
        StorageUsage.Area.AUDIO -> R.string.storage_area_audio
        StorageUsage.Area.PHOTOS_ATTACHMENTS -> R.string.storage_area_photos
        StorageUsage.Area.TRANSCRIPTS -> R.string.storage_area_transcripts
        StorageUsage.Area.WHISPER_MODELS -> R.string.storage_area_whisper
        StorageUsage.Area.NEMO_MODELS -> R.string.storage_area_nemo
        StorageUsage.Area.SPEAKER_MODELS -> R.string.storage_area_speaker
        StorageUsage.Area.LLM_MODELS -> R.string.storage_area_llm
        StorageUsage.Area.CACHES -> R.string.storage_area_caches
        StorageUsage.Area.OTHER -> R.string.storage_area_other
    }

    /**
     * A label with its size at the end, read as one item ("Meeting audio:
     * 1.2 GB"). At least 48dp tall, so the tappable ones meet the touch
     * target minimum.
     */
    private fun row(label: String, value: String?, clickable: Boolean): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = A11y.minTouchPx(this@StorageActivity)
            val pad = (8 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            if (clickable) {
                setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
                isClickable = true
                isFocusable = true
            } else {
                isFocusable = true
            }
            contentDescription = if (value == null) {
                label
            } else {
                getString(R.string.storage_row_desc, label, value)
            }
        }
        val labelView = TextView(this).apply {
            text = label
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(themeResource(com.google.android.material.R.attr.textAppearanceBodyMedium))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val valueView = TextView(this).apply {
                text = value
                setTextAppearance(themeResource(com.google.android.material.R.attr.textAppearanceLabelLarge))
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(
                valueView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (12 * density).toInt() }
            )
        }
        return row
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** The style or drawable [attr] points at in this theme (the app's Inter type scale). */
    private fun themeResource(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    /** A large recording: open its meeting, or drop the audio and keep the rest. */
    private fun showMeetingOptions(meeting: StorageUsage.MeetingAudio) {
        ActionSheet.show(
            this,
            meeting.title,
            listOf(
                ActionSheet.Item(ACTION_OPEN, getString(R.string.storage_open_meeting)),
                ActionSheet.Item(
                    ACTION_REMOVE_AUDIO,
                    getString(R.string.storage_remove_audio),
                    subtitle = size(meeting.removableBytes),
                    destructive = true,
                    separated = true
                )
            )
        ) { id ->
            when (id) {
                ACTION_OPEN -> startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                )
                ACTION_REMOVE_AUDIO -> removeAudio(meeting)
            }
        }
    }

    private fun removeAudio(meeting: StorageUsage.MeetingAudio) {
        if (AudioRemoval.isBlocked(this, meeting.id)) {
            Toast.makeText(
                this,
                if (ClearVoiceService.isPreparing(meeting.id)) R.string.clearvoice_busy
                else R.string.storage_audio_busy,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val untranscribed = !meeting.hasTranscript
        AudioRemoval.confirm(
            this, 1, meeting.removableBytes, untranscribed = untranscribed,
            unfinishedAtMs = meeting.unfinishedAtMs,
            clearVoiceCopies = if (meeting.clearVoiceBytes > 0L) 1 else 0
        ) {
            val app = applicationContext
            worker.execute {
                val freed = AudioRemoval.remove(app, MeetingStore(app), meeting.id)
                // -1 is also a meeting deleted, or its audio removed, since
                // the scan: only a busy one is worth trying again later.
                val message = when {
                    freed >= 0L ->
                        if (untranscribed) R.string.storage_audio_removed_only else R.string.storage_audio_removed
                    AudioRemoval.isBlocked(app, meeting.id) -> R.string.storage_audio_busy
                    else -> R.string.storage_audio_already_gone
                }
                runOnUiThread {
                    Toast.makeText(
                        app, message, if (freed >= 0L) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                    if (!isFinishing && !isDestroyed) scan()
                }
            }
        }
    }

    /**
     * Erases what Recently deleted held at the last scan, after saying how
     * many meetings and how much that is. A meeting deleted since is not
     * part of what was asked about, so it stays.
     */
    private fun confirmEmptyTrash() {
        val trash = report?.trash.orEmpty()
        if (trash.isEmpty()) return
        TrashUi.confirmEmpty(this, trash.size, trash.sumOf { it.bytes }) {
            trashEmpty.isEnabled = false
            val app = applicationContext
            val entries = trash.map { it.entry }
            TrashUi.execute {
                val erased = MeetingTrash(app).deleteForever(entries)
                runOnUiThread {
                    Toast.makeText(
                        app, app.resources.getQuantityString(R.plurals.trash_emptied, erased, erased),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!isFinishing && !isDestroyed) scan()
                }
            }
        }
    }

    /** Lists every unused model with its size, and deletes them on yes. */
    private fun confirmDeleteModels() {
        val unused = report?.unusedModels.orEmpty()
        if (unused.isEmpty()) return
        if (StorageScan.modelsBusy()) {
            Toast.makeText(this, R.string.storage_models_busy, Toast.LENGTH_LONG).show()
            return
        }
        val lines = unused.joinToString("\n") {
            getString(R.string.storage_model_line, it.name, size(it.bytes))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_delete_unused_title)
            .setMessage(
                getString(R.string.storage_delete_unused_message, size(unused.sumOf { it.bytes }), lines)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteModels.isEnabled = false
                val app = applicationContext
                worker.execute {
                    val deleted = StorageScan.deleteModels(app, unused)
                    runOnUiThread {
                        if (deleted == null) {
                            Toast.makeText(app, R.string.storage_models_busy, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                app,
                                app.resources.getQuantityString(
                                    R.plurals.storage_models_deleted, deleted.count, deleted.count,
                                    Formatter.formatShortFileSize(app, deleted.bytes)
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (!isFinishing && !isDestroyed) scan()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** From the clean-up notification: show the batch the daily run held back. */
        const val EXTRA_REVIEW_CLEANUP = "com.meetily.mobile.extra.REVIEW_CLEANUP"

        private const val ACTION_OPEN = "open"
        private const val ACTION_REMOVE_AUDIO = "remove_audio"
    }
}
