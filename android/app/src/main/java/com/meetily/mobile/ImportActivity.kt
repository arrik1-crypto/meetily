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
import com.meetily.mobile.whisper.WhisperModels

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
        override fun onImportProgress(percent: Int) {
            if (isFinishing || isDestroyed) return
            progress.isIndeterminate = percent == 0
            progress.progress = percent
            statusView.text = getString(R.string.import_status_running, percent)
        }

        override fun onImportDone(
            meetingId: String?,
            wasCancelled: Boolean,
            error: String?,
            warning: String?
        ) {
            if (isFinishing || isDestroyed) return
            when {
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
            svc.observer = observer
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

        val uri = incomingUri()
        if (uri == null) {
            // Reopened from the progress notification: just observe.
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

        val settings = AppSettings(this)
        val whisperReady = WhisperModels.isRuntimeAvailable() &&
            WhisperModels.isDownloaded(this, WhisperModels.byKey(settings.whisperModel))
        if (!whisperReady) {
            Toast.makeText(this, R.string.import_needs_whisper, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        if (RecordingService.isRunning) {
            Toast.makeText(this, R.string.import_wait_recording, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (ImportService.isRunning) {
            Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show()
            bindService(
                Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE
            )
            bound = true
            return
        }

        val name = displayName(uri)
        fileNameView.text = name

        val start = Intent(this, ImportService::class.java)
            .setAction(ImportService.ACTION_START)
            .setData(uri)
            .putExtra(ImportService.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(start)
        } else {
            startService(start)
        }
        bindService(Intent(this, ImportService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
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

    override fun onDestroy() {
        service?.let { if (it.observer === observer) it.observer = null }
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
