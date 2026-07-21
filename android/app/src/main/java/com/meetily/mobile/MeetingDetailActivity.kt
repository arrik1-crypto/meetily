package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
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

    private lateinit var titleView: TextView
    private lateinit var dateView: TextView
    private lateinit var metaView: TextView
    private lateinit var summaryView: TextView
    private lateinit var aiPanel: View
    private lateinit var transcriptList: LinearLayout
    private lateinit var notesInput: EditText
    private lateinit var attendeesInput: EditText
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        store = MeetingStore(this)
        settings = AppSettings(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        toolbar.setNavigationOnClickListener { finish() }

        titleView = findViewById(R.id.detailTitle)
        dateView = findViewById(R.id.detailDate)
        metaView = findViewById(R.id.detailMeta)
        summaryView = findViewById(R.id.detailSummary)
        aiPanel = findViewById(R.id.aiPanel)
        transcriptList = findViewById(R.id.transcriptList)
        notesInput = findViewById(R.id.detailNotes)
        attendeesInput = findViewById(R.id.detailAttendees)
        progress = findViewById(R.id.summaryProgress)

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        meeting = id?.let { store.load(it) }
        val m = meeting
        if (m == null) {
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        titleView.text = m.title
        dateView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        val wordCount = m.transcriptText()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
        metaView.text = getString(R.string.detail_meta, m.segments.size, wordCount)

        findViewById<View>(R.id.generateButton).setOnClickListener { generateSummary() }

        renderSummary(m.summary)
        renderTranscript(m)
        notesInput.setText(m.notes)
        attendeesInput.setText(m.attendeesText())
    }

    private fun renderSummary(summary: String) {
        if (summary.isBlank()) {
            summaryView.visibility = View.GONE
            aiPanel.visibility = View.VISIBLE
        } else {
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE
            aiPanel.visibility = View.GONE
        }
    }

    private fun renderTranscript(m: Meeting) {
        transcriptList.removeAllViews()
        findViewById<View>(R.id.tagHint).visibility =
            if (m.segments.isEmpty()) View.GONE else View.VISIBLE
        if (m.segments.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_transcript)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(dateView.currentTextColor)
            }
            transcriptList.addView(empty)
            return
        }
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val inflater = LayoutInflater.from(this)
        for ((index, segment) in m.segments.withIndex()) {
            val line = inflater.inflate(R.layout.item_transcript_line, transcriptList, false)
            line.findViewById<TextView>(R.id.lineTime).text =
                timeFormat.format(Date(segment.timestampMs))
            line.findViewById<TextView>(R.id.lineText).text = segment.text
            val speakerView = line.findViewById<TextView>(R.id.lineSpeaker)
            if (segment.speaker.isNullOrBlank()) {
                speakerView.visibility = View.GONE
            } else {
                speakerView.text = segment.speaker
                speakerView.visibility = View.VISIBLE
            }
            line.setOnClickListener { assignSpeaker(index) }
            transcriptList.addView(line)
        }
    }

    private fun assignSpeaker(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        saveEdits()
        SpeakerPicker.show(this, m.attendees, m.segments[index].speaker) { name ->
            if (index !in m.segments.indices) return@show
            m.segments[index] = m.segments[index].copy(speaker = name)
            if (!name.isNullOrBlank() &&
                m.attendees.none { it.equals(name, ignoreCase = true) }
            ) {
                m.attendees.add(name)
                attendeesInput.setText(m.attendeesText())
            }
            store.save(m)
            renderTranscript(m)
        }
    }

    override fun onPause() {
        super.onPause()
        saveEdits()
    }

    private fun saveEdits() {
        val m = meeting ?: return
        val newNotes = notesInput.text.toString()
        val newAttendees = Meeting.parseAttendees(attendeesInput.text.toString())
        if (newNotes != m.notes || newAttendees != m.attendees) {
            m.notes = newNotes
            m.attendees = newAttendees
            store.save(m)
        }
    }

    private fun generateSummary() {
        val m = meeting ?: return
        saveEdits()

        if (m.transcriptText().isBlank() && m.notes.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_summarize, Toast.LENGTH_SHORT).show()
            return
        }

        aiPanel.visibility = View.GONE
        progress.visibility = View.VISIBLE
        summaryView.visibility = View.VISIBLE
        summaryView.text = getString(R.string.summarizing)

        val useLlm = settings.useLlm
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val rawTranscript = m.transcriptText()
        val speakerTranscript = m.transcriptTextWithSpeakers()
        val notes = m.notes
        val attendees = m.attendees.toList()

        Thread {
            val result = try {
                if (useLlm && baseUrl.isNotBlank()) {
                    LlmClient.summarize(baseUrl, apiKey, model, speakerTranscript, notes, attendees)
                } else {
                    ExtractiveSummarizer.summarize(rawTranscript, notes)
                }
            } catch (e: Exception) {
                val fallback = ExtractiveSummarizer.summarize(rawTranscript, notes)
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
        saveEdits()
        val text = buildString {
            append(m.title).append('\n')
            append(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(m.createdAtMs))
            ).append("\n\n")
            if (m.attendees.isNotEmpty()) {
                append("ATTENDEES\n").append(m.attendeesText()).append("\n\n")
            }
            if (m.summary.isNotBlank()) {
                append("SUMMARY\n").append(m.summary).append("\n\n")
            }
            if (m.notes.isNotBlank()) {
                append("NOTES\n").append(m.notes).append("\n\n")
            }
            append("TRANSCRIPT\n").append(m.transcriptTextWithSpeakers())
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
