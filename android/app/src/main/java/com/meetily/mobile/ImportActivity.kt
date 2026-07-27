package com.meetily.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings

/**
 * Thin observer over ImportService: starts an import for a shared or picked
 * audio file, shows progress, and opens the meeting when done. The service
 * keeps working if this screen (or the whole app UI) goes away.
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fileNameView: TextView

    private var service: ImportService? = null
    private var bound = false
    private var opened = false

    private val observer = object : ImportService.Observer {
        override fun onImportProgress(meetingId: String?, percent: Int, detail: String) {
            if (isFinishing || isDestroyed) return
            progress.isIndeterminate = percent == 0
            progress.progress = percent
            statusView.text = detail.ifBlank {
                getString(R.string.import_status_running, percent)
            }
        }

        override fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        ) {
            if (isFinishing || isDestroyed) return
            when {
                meetingId != null && ImportService.isRecheck -> {
                    // A cancelled or partial check left the stored transcript
                    // alone and threw its draft away — nothing to review.
                    if (wasCancelled || warning != null) {
                        Toast.makeText(
                            this@ImportActivity,
                            if (wasCancelled) getString(R.string.check_cancelled)
                            else getString(R.string.check_incomplete_body),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    } else {
                        openCheck(meetingId)
                    }
                }
                meetingId != null -> {
                    when {
                        wasCancelled -> Toast.makeText(
                            this@ImportActivity,
                            R.string.import_cancelled_partial,
                            Toast.LENGTH_LONG
                        ).show()
                        warning != null -> Toast.makeText(
                            this@ImportActivity, warning, Toast.LENGTH_LONG
                        ).show()
                    }
                    openDetail(meetingId)
                }
                else -> {
                    Toast.makeText(
                        this@ImportActivity,
                        getString(R.string.import_failed, error ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ImportService.ImportBinder)?.service ?: return
            service = svc
            if (svc.sourceName.isNotBlank()) {
                fileNameView.text = svc.sourceName
            }
            svc.addObserver(observer)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_import)

        findViewById<MaterialToolbar>(R.id.importToolbar).setNavigationOnClickListener {
            finish() // import keeps running in the service
        }
        statusView = findViewById(R.id.importStatus)
        progress = findViewById(R.id.importProgress)
        fileNameView = findViewById(R.id.importFileName)
        findViewById<android.view.View>(R.id.importCancelButton).setOnClickListener {
            service?.requestCancel()
                ?: startService(
                    Intent(this, ImportService::class.java).setAction(ImportService.ACTION_CANCEL)
                )
            statusView.text = getString(R.string.import_stopping)
        }

        // The same progress screen backs an accuracy check; say which it is.
        if (ImportService.isRunning && ImportService.isRecheck) {
            findViewById<MaterialToolbar>(R.id.importToolbar).title =
                getString(R.string.check_accuracy_title)
        }

        val uri = incomingUri()
        // A re-created Activity still carries its original intent, so reading
        // the uri again would start the SAME file a second time — JobGate
        // would find an import running, stage another copy and queue it, and
        // the user would end up with the meeting twice. Portrait is pinned so
        // rotation is not a trigger, but scheduled dark-theme switching at
        // sunset is, along with font or display-size changes, locale changes
        // and a process-death restore during a long import.
        if (uri == null || savedInstanceState != null) {
            // Reopened from the progress notification, or re-created: observe.
            if (ImportService.isRunning) {
                bindService(
                    Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE
                )
                bound = true
            } else {
                Toast.makeText(this, R.string.import_no_audio, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        val downloaded =
            com.meetily.mobile.whisper.TranscriptionModels.downloadedKeys(this)
        val whisperReady = downloaded.isNotEmpty()
        if (!whisperReady) {
            Toast.makeText(this, R.string.import_needs_whisper, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        // Ask before the service starts, not after.
        //
        // Sharing a file into Recap is an entry point in its own right — a new
        // user can reach it without ever having tapped Record, which is where
        // the only other request lives. On Android 13+ the permission is
        // denied by default, so ImportService's startForeground notification
        // was discarded: no progress, no cancel action (the screen's own back
        // button says the import keeps running), and no "done" notification. A
        // multi-minute transcription ran completely invisibly.
        //
        // Asked once and then continued either way, matching the record path:
        // the import itself does not need the permission, only its visibility
        // does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = { beginImport(uri, downloaded) }
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        beginImport(uri, downloaded)
    }

    /** Set while the notification-permission dialog is up. */
    private var pendingStart: (() -> Unit)? = null

    private val notificationPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            // Granted or not, the import goes ahead — it just may run without
            // a visible notification, exactly as the record path behaves.
            val start = pendingStart
            pendingStart = null
            if (!isFinishing && !isDestroyed) start?.invoke()
        }

    private fun beginImport(uri: Uri, downloaded: List<String>) {
        // One downloaded model: nothing to choose. Otherwise ask which model
        // should transcribe THIS file (heavier models suit imports better
        // than live capture, so the per-run choice matters here).
        //
        // Both the display name and the duration are read off a content://
        // URI that may be backed by a cloud provider, where each probe is a
        // network fetch. They used to run on the main thread inside onCreate —
        // the query once, and setDataSource once PER DOWNLOADED MODEL, because
        // ModelPickerSheet calls entriesProvider() synchronously and re-calls
        // it after every delete. Four models meant four fetches before the
        // activity drew at all. Probe once, off the main thread, then show.
        Thread {
            val fileName = displayName(uri)
            val durationMs = if (downloaded.size == 1) 0L else sourceDurationMs(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fileNameView.text = fileName
                if (downloaded.size == 1) {
                    startImport(uri, fileName, downloaded.first())
                } else {
                    showModelPicker(uri, fileName, downloaded, durationMs)
                }
            }
        }.apply {
            name = "import-probe"
            start()
        }
    }

    private fun showModelPicker(
        uri: Uri,
        name: String,
        downloaded: List<String>,
        durationMs: Long
    ) {
        val settings = AppSettings(this)
        ModelPickerSheet.show(
            this,
            getString(R.string.import_choose_model),
            entriesProvider = {
                downloaded.map { key ->
                    ModelPickerSheet.Entry(
                        key = key,
                        title = com.meetily.mobile.whisper.TranscriptionModels
                            .displayName(key),
                        // This is the moment a heavy model gets chosen
                        // for a long file, so it is the moment to say what
                        // that costs on this phone.
                        meta = com.meetily.mobile.whisper.TranscriptionModels
                            .metaLineFor(this, key, durationMs),
                        downloaded = true,
                        selected = settings.whisperModel == key
                    )
                }
            },
            onPick = { key -> startImport(uri, name, key) },
            onDismissed = { finish() } // no pick, nothing to import
        )
    }

    /**
     * Runs the file now, or copies it aside and queues it.
     *
     * Being busy used to end the story: a toast, and the file the user had
     * just picked went nowhere. Queueing keeps the choice — including the
     * model they chose for it.
     */
    private fun startImport(uri: Uri, name: String, modelKey: String) {
        val queued = JobGate.requestImport(this, uri, name, modelKey) { result ->
            when (result) {
                ImportQueue.Result.QUEUED -> Toast.makeText(
                    this, getString(R.string.import_queued, name), Toast.LENGTH_LONG
                ).show()
                ImportQueue.Result.TOO_MANY -> Toast.makeText(
                    this, R.string.import_queue_full, Toast.LENGTH_LONG
                ).show()
                ImportQueue.Result.FAILED -> Toast.makeText(
                    this, R.string.import_queue_failed, Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
        if (queued) {
            statusView.text = getString(R.string.import_queueing)
            return
        }
        val start = Intent(this, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(uri)
            .putExtra(ImportService.EXTRA_NAME, name)
            .putExtra(ImportService.EXTRA_MODEL, modelKey)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(start)
        } else {
            startService(start)
        }
        bindService(Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    /**
     * Container-reported length of the file being imported, for the model
     * picker's time estimate. Best-effort: a source that will not report a
     * duration simply gets no estimate rather than a wrong one.
     */
    private fun sourceDurationMs(uri: Uri): Long = try {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } finally {
            mmr.release()
        }
    } catch (_: Throwable) {
        0L
    }

    private fun incomingUri(): Uri? {
        intent?.data?.let { return it }
        if (intent?.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return null
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: getString(R.string.import_title)
    }

    private fun openDetail(meetingId: String) {
        if (opened) return
        opened = true
        startActivity(
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
        )
        finish()
    }

    private fun openCheck(meetingId: String) {
        if (opened) return
        opened = true
        startActivity(
            Intent(this, TranscriptCheckActivity::class.java)
                .putExtra(TranscriptCheckActivity.EXTRA_MEETING_ID, meetingId)
        )
        finish()
    }

    override fun onDestroy() {
        service?.removeObserver(observer)
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
