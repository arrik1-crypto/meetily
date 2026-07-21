package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import java.text.DateFormat
import java.util.Date

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    private var meeting: Meeting? = null

    private lateinit var dateView: TextView
    private lateinit var summaryView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var notesInput: EditText
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        store = MeetingStore(this)
        settings = AppSettings(this)

        dateView = findViewById(R.id.detailDate)
        summaryView = findViewById(R.id.detailSummary)
        transcriptView = findViewById(R.id.detailTranscript)
        notesInput = findViewById(R.id.detailNotes)
        progress = findViewById(R.id.summaryProgress)

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        meeting = id?.let { store.load(it) }
        val m = meeting
        if (m == null) {
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = m.title
        dateView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        summaryView.text = m.summary.ifBlank { getString(R.string.no_summary_yet) }
        transcriptView.text = m.transcriptText().ifBlank { getString(R.string.no_transcript) }
        notesInput.setText(m.notes)
    }

    override fun onPause() {
        super.onPause()
        saveNotes()
    }

    private fun saveNotes() {
        val m = meeting ?: return
        val newNotes = notesInput.text.toString()
        if (newNotes != m.notes) {
            m.notes = newNotes
            store.save(m)
        }
    }

    private fun generateSummary() {
        val m = meeting ?: return
        saveNotes()

        if (m.transcriptText().isBlank() && m.notes.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_summarize, Toast.LENGTH_SHORT).show()
            return
        }

        progress.visibility = View.VISIBLE
        summaryView.text = getString(R.string.summarizing)

        val useLlm = settings.useLlm
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val transcript = m.transcriptText()
        val notes = m.notes

        Thread {
            val result = try {
                if (useLlm && baseUrl.isNotBlank()) {
                    LlmClient.summarize(baseUrl, apiKey, model, transcript, notes)
                } else {
                    ExtractiveSummarizer.summarize(transcript, notes)
                }
            } catch (e: Exception) {
                val fallback = ExtractiveSummarizer.summarize(transcript, notes)
                getString(R.string.llm_failed_fallback, e.message ?: "unknown error") +
                    "\n\n" + fallback
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                summaryView.text = result
                m.summary = result
                store.save(m)
            }
        }.start()
    }

    private fun shareMeeting() {
        val m = meeting ?: return
        saveNotes()
        val text = buildString {
            append(m.title).append('\n')
            append(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(m.createdAtMs))
            ).append("\n\n")
            if (m.summary.isNotBlank()) {
                append("SUMMARY\n").append(m.summary).append("\n\n")
            }
            if (m.notes.isNotBlank()) {
                append("NOTES\n").append(m.notes).append("\n\n")
            }
            append("TRANSCRIPT\n").append(m.transcriptText())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, m.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_meeting)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_summarize -> {
                generateSummary()
                true
            }
            R.id.action_share -> {
                shareMeeting()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
    }
}
