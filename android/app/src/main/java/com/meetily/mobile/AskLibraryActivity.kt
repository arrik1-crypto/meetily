package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.search.LibrarySearch
import com.meetily.mobile.summarize.LlmClient
import java.text.DateFormat
import java.util.Date

/**
 * Ask a question across the whole meeting library. Retrieval is fully
 * on-device (LibrarySearch); with an LLM configured the top meetings are
 * packed into one cited answer, otherwise the matching moments themselves
 * are the answer.
 */
class AskLibraryActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    private lateinit var input: EditText
    private lateinit var sendButton: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var answerView: TextView
    private lateinit var sourcesHeader: View
    private lateinit var sourcesList: LinearLayout

    @Volatile private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_ask)

        store = MeetingStore(this)
        settings = AppSettings(this)

        findViewById<MaterialToolbar>(R.id.askToolbar).setNavigationOnClickListener {
            finish()
        }
        input = findViewById(R.id.askLibraryInput)
        sendButton = findViewById(R.id.askLibrarySend)
        progress = findViewById(R.id.askProgress)
        answerView = findViewById(R.id.askAnswer)
        sourcesHeader = findViewById(R.id.askSourcesHeader)
        sourcesList = findViewById(R.id.askSources)

        sendButton.setOnClickListener { ask() }
        input.setOnEditorActionListener { _, _, _ ->
            ask()
            true
        }
    }

    private fun ask() {
        if (asking) return
        val question = input.text.toString().trim()
        if (question.isBlank()) return
        asking = true
        progress.visibility = View.VISIBLE
        answerView.visibility = View.GONE
        sourcesHeader.visibility = View.GONE
        sourcesList.removeAllViews()
        sendButton.isEnabled = false

        val useLlm = settings.useLlm && settings.llmBaseUrl.isNotBlank()
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm

        Thread {
            val hits = LibrarySearch.search(store.list(), question)
            var answer: String? = null
            var llmFailed = false
            if (useLlm && hits.isNotEmpty()) {
                try {
                    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    val blocks = hits.map { hit ->
                        val m = hit.meeting
                        val label = "${m.title} — ${dateFormat.format(Date(m.createdAtMs))}"
                        val content = buildString {
                            if (m.summary.isNotBlank()) {
                                append("Summary:\n").append(m.summary).append("\n\n")
                            }
                            if (hit.excerpts.isNotEmpty()) {
                                append("Matching moments:\n")
                                for (excerpt in hit.excerpts) {
                                    append("- ").append(excerpt).append("\n")
                                }
                                append("\n")
                            }
                            append("Transcript:\n")
                            append(m.transcriptTextWithSpeakers())
                        }
                        label to content
                    }
                    answer = LlmClient.askLibrary(baseUrl, apiKey, model, localOnly, blocks, question)
                } catch (_: Exception) {
                    llmFailed = true
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                asking = false
                sendButton.isEnabled = true
                progress.visibility = View.GONE
                renderResult(hits, answer, llmFailed)
            }
        }.start()
    }

    private fun renderResult(
        hits: List<LibrarySearch.Hit>,
        answer: String?,
        llmFailed: Boolean
    ) {
        if (hits.isEmpty()) {
            answerView.text = getString(R.string.ask_no_matches)
            answerView.visibility = View.VISIBLE
            return
        }
        answerView.text = when {
            answer != null -> answer
            llmFailed -> getString(R.string.ask_llm_failed)
            else -> getString(R.string.ask_matches_only)
        }
        answerView.visibility = View.VISIBLE
        sourcesHeader.visibility = View.VISIBLE

        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val inflater = layoutInflater
        for (hit in hits) {
            val row = inflater.inflate(R.layout.item_ask_source, sourcesList, false)
            row.findViewById<TextView>(R.id.sourceTitle).text = hit.meeting.title
            row.findViewById<TextView>(R.id.sourceDate).text =
                dateFormat.format(Date(hit.meeting.createdAtMs))
            val excerptView = row.findViewById<TextView>(R.id.sourceExcerpt)
            if (hit.excerpts.isEmpty()) {
                excerptView.visibility = View.GONE
            } else {
                excerptView.text = hit.excerpts.joinToString("\n") { "“$it”" }
            }
            row.setOnClickListener {
                startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, hit.meeting.id)
                )
            }
            sourcesList.addView(row)
        }
        if (hits.isEmpty()) {
            Toast.makeText(this, R.string.ask_no_matches, Toast.LENGTH_SHORT).show()
        }
    }
}
