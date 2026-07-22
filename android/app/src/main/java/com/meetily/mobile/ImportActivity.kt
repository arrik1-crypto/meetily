package com.meetily.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.whisper.AudioFileImporter
import com.meetily.mobile.whisper.WhisperModels

/**
 * Imports a shared or picked audio file: decodes it on-device, transcribes
 * with Whisper (plus speaker detection when enabled), and opens the
 * resulting meeting. Entry points: the home screen's import action and the
 * system share sheet (audio MIME types).
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var progress: LinearProgressIndicator

    @Volatile private var cancelled = false
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_import)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<MaterialToolbar>(R.id.importToolbar).setNavigationOnClickListener {
            cancelled = true
        }
        statusView = findViewById(R.id.importStatus)
        progress = findViewById(R.id.importProgress)
        findViewById<android.view.View>(R.id.importCancelButton).setOnClickListener {
            cancelled = true
            statusView.text = getString(R.string.import_stopping)
        }

        val uri = incomingUri()
        if (uri == null) {
            Toast.makeText(this, R.string.import_no_audio, Toast.LENGTH_LONG).show()
            finish()
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

        val name = displayName(uri)
        findViewById<TextView>(R.id.importFileName).text = name

        Thread {
            try {
                val id = AudioFileImporter(this, settings).import(
                    uri = uri,
                    title = name.substringBeforeLast('.').ifBlank { name },
                    sourceName = name,
                    onProgress = { percent ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            progress.isIndeterminate = false
                            progress.progress = percent
                            statusView.text =
                                getString(R.string.import_status_running, percent)
                        }
                    },
                    cancelled = { cancelled }
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (cancelled) {
                        Toast.makeText(
                            this, R.string.import_cancelled_partial, Toast.LENGTH_LONG
                        ).show()
                    }
                    openDetail(id)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        getString(R.string.import_failed, e.message ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }.start()
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
        // Stops the worker on real teardown; partial transcript stays saved.
        cancelled = true
        super.onDestroy()
    }

    override fun onBackPressed() {
        cancelled = true
        super.onBackPressed()
    }
}
