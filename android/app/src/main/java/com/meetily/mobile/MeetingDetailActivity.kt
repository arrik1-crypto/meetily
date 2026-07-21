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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.QaEntry
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.SummaryTemplate
import com.meetily.mobile.summarize.SummaryTemplates
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
    private lateinit var qaList: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var askSend: MaterialButton
    private lateinit var askRow: View
    private lateinit var askDisabledHint: View

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
        qaList = findViewById(R.id.qaList)
        askInput = findViewById(R.id.askInput)
        askSend = findViewById(R.id.askSend)
        askRow = findViewById(R.id.askRow)
        askDisabledHint = findViewById(R.id.askDisabledHint)

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

        findViewById<View>(R.id.generateButton).setOnClickListener {
            chooseTemplateAndSummarize()
        }
        askSend.setOnClickListener { sendQuestion() }

        renderSummary(m.summary)
        renderTranscript(m)
        renderQaHistory(m)
        notesInput.setText(m.notes)
        attendeesInput.setText(m.attendeesText())
    }

    override fun onResume() {
        super.onResume()
        val llmReady = settings.useLlm && settings.llmBaseUrl.isNotBlank()
        askRow.visibility = if (llmReady) View.VISIBLE else View.GONE
        askDisabledHint.visibility = if (llmReady) View.GONE else View.VISIBLE
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
            val timeView = line.findViewById<TextView>(R.id.lineTime)
            val time = timeFormat.format(Date(segment.timestampMs))
            timeView.text = if (segment.highlighted) "★ $time" else time
            line.findViewById<TextView>(R.id.lineText).text = segment.text
            val speakerView = line.findViewById<TextView>(R.id.lineSpeaker)
            if (segment.speaker.isNullOrBlank()) {
                speakerView.visibility = View.GONE
            } else {
                speakerView.text = segment.speaker
                speakerView.visibility = View.VISIBLE
            }
            if (segment.highlighted) {
                line.setBackgroundResource(R.drawable.bg_line_highlight)
            }
            line.setOnClickListener { assignSpeaker(index) }
            line.setOnLongClickListener {
                toggleHighlight(index)
                true
            }
            transcriptList.addView(line)
        }
    }

    private fun toggleHighlight(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        m.segments[index] = m.segments[index].copy(
            highlighted = !m.segments[index].highlighted
        )
        store.save(m)
        renderTranscript(m)
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

    private fun renderQaHistory(m: Meeting) {
        qaList.removeAllViews()
        for (entry in m.qa) {
            addQaView(entry.question, entry.answer)
        }
    }

    /** Adds a question/answer pair to the thread; returns the answer view for updating. */
    private fun addQaView(question: String, answer: String): TextView {
        val item = LayoutInflater.from(this).inflate(R.layout.item_qa, qaList, false)
        item.findViewById<TextView>(R.id.qaQuestion).text = question
        val answerView = item.findViewById<TextView>(R.id.qaAnswer)
        answerView.text = answer
        qaList.addView(item)
        return answerView
    }

    private fun sendQuestion() {
        val m = meeting ?: return
        val question = askInput.text.toString().trim()
        if (question.isBlank()) return
        saveEdits()

        askInput.setText("")
        askSend.isEnabled = false
        val answerView = addQaView(question, getString(R.string.ask_thinking))

        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val transcript = m.transcriptTextWithSpeakers()
        val notes = m.notes
        val summary = m.summary
        val attendees = m.attendees.toList()
        val history = m.qa.map { it.question to it.answer }

        Thread {
            val answer = try {
                LlmClient.ask(
                    baseUrl, apiKey, model, transcript, notes, summary,
                    attendees, history, question
                )
            } catch (e: Exception) {
                getString(R.string.ask_failed, e.message ?: "unknown error")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                answerView.text = answer
                askSend.isEnabled = true
                m.qa.add(QaEntry(question, answer))
                store.save(m)
            }
        }.start()
    }

    private fun chooseTemplateAndSummarize() {
        val labels = SummaryTemplates.ALL.map { getString(it.labelRes) }.toTypedArray()
        val current = SummaryTemplates.ALL
            .indexOfFirst { it.key == settings.summaryTemplate }
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_template)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val template = SummaryTemplates.ALL[which]
                settings.summaryTemplate = template.key
                generateSummary(template)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateSummary(template: SummaryTemplate) {
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
        val highlights = m.highlightedTexts()

        Thread {
            val result = try {
                if (useLlm && baseUrl.isNotBlank()) {
                    LlmClient.summarize(
                        baseUrl, apiKey, model, speakerTranscript, notes,
                        attendees, highlights, template
                    )
                } else {
                    ExtractiveSummarizer.summarize(
                        rawTranscript, notes, highlights, template.extractiveActionsOnly
                    )
                }
            } catch (e: Exception) {
                val fallback = ExtractiveSummarizer.summarize(
                    rawTranscript, notes, highlights, template.extractiveActionsOnly
                )
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
                chooseTemplateAndSummarize()
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
