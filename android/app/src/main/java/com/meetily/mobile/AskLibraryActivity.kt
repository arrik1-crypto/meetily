package com.meetily.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.search.LibrarySearch
import com.meetily.mobile.security.LocalNetworkAccess
import com.meetily.mobile.summarize.AiDisclaimers
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

    /**
     * The question whose run this screen shows or waits on, kept across a
     * recreation so the new screen picks the same run back up ([runs]).
     */
    private var asked: String? = null

    /** Takes this screen's listener off the run it waits on; see [RetainedRun.Run.whenDone]. */
    private var detachRun: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_ask)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            bar(R.id.edgeBottomBar)
        }

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
        // Recreated (rotation, fold, theme): wait on the run the old screen
        // started, or show the answer it already had, rather than drop it.
        savedInstanceState?.getString(STATE_ASKED)?.let { question ->
            runs.existing(question)?.let { attach(question, it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        asked?.let { outState.putString(STATE_ASKED, it) }
    }

    override fun onDestroy() {
        // The run outlives this screen and must not keep it.
        detachRun?.invoke()
        detachRun = null
        // Left for good: nothing will come back for the answer.
        if (isFinishing) asked?.let { runs.forget(it) }
        super.onDestroy()
    }

    private fun ask() {
        if (asking) return
        val question = input.text.toString().trim()
        if (question.isBlank()) return
        val useLlm = settings.useLlm && settings.llmConfigured
        // Plain values only: the run outlives this screen.
        val app = applicationContext
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm
        val modelKey = AiReport.currentModelKey(this)
        val run = runs.start(question, "ask-library") {
            answer(app, question, useLlm, baseUrl, apiKey, model, localOnly, modelKey)
        }
        attach(question, run)
    }

    /** Shows [run] in progress, then its result, on this screen. */
    private fun attach(question: String, run: RetainedRun.Run<Answer>) {
        asked = question
        asking = true
        progress.visibility = View.VISIBLE
        answerView.visibility = View.GONE
        sourcesHeader.visibility = View.GONE
        sourcesList.removeAllViews()
        reportButton?.visibility = View.GONE
        sendButton.isEnabled = false
        detachRun?.invoke()
        detachRun = run.whenDone { done ->
            val result: Any = try {
                done.get()
            } catch (e: Exception) {
                // The retrieval itself failed. A failed written answer is
                // caught inside the run and shown with the moments instead.
                e.cause ?: e
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                asking = false
                sendButton.isEnabled = true
                progress.visibility = View.GONE
                if (result is Answer) {
                    renderResult(result.hits, result.answer, result.failure)
                    renderReport(question, result.answer, result.modelKey, result.notices)
                } else {
                    val why = (result as? Throwable)?.message
                        ?: getString(R.string.platform_error_unknown)
                    answerView.text = getString(R.string.ask_failed, why)
                    answerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private var reportButton: com.google.android.material.button.MaterialButton? = null

    /** Notices of the summaries the answer on screen was built from. */
    private var answerNotices: List<String> = emptyList()

    /**
     * The answer's notices and "Report AI output". The notices go with any
     * result, because the matching moments listed below may quote a
     * clinical or legal summary too. The report button only goes with a
     * written answer: the other states are fixed app text.
     */
    private fun renderReport(
        question: String,
        answer: String?,
        modelKey: String,
        notices: List<String>
    ) {
        answerNotices = notices
        if (notices.isNotEmpty()) {
            answerView.text = AiDisclaimers.appendTo(answerView.text.toString(), notices)
        }
        val button = reportButton ?: AiReport.addButton(answerView) {}.also {
            reportButton = it
            DisclaimerViews.copyWithNotices(answerView) { answerNotices }
        }
        button.visibility = if (answer == null) View.GONE else View.VISIBLE
        if (answer != null) {
            button.setOnClickListener {
                AiReport.show(
                    this,
                    AiReport.Subject(
                        label = getString(R.string.aisafety_report_action),
                        surface = "library_answer",
                        text = "Q: $question\nA: $answer",
                        modelKey = modelKey
                    )
                )
            }
        }
    }

    private fun renderResult(
        hits: List<LibrarySearch.Hit>,
        answer: String?,
        failure: Exception?
    ) {
        if (hits.isEmpty()) {
            answerView.text = getString(R.string.ask_no_matches)
            answerView.visibility = View.VISIBLE
            return
        }
        answerView.text = when {
            answer != null -> answer
            failure != null -> failureText(failure)
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
                val open = Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, hit.meeting.id)
                    .putExtra(MeetingDetailActivity.EXTRA_TITLE, hit.meeting.title)
                    .putExtra(MeetingDetailActivity.EXTRA_CREATED_AT_MS, hit.meeting.createdAtMs)
                // At the moment quoted first, with its term marked by Find in meeting.
                if (hit.segmentIndex >= 0) {
                    open.putExtra(MeetingDetailActivity.EXTRA_SEGMENT, hit.segmentIndex)
                    hit.term?.let { open.putExtra(MeetingDetailActivity.EXTRA_QUERY, it) }
                }
                startActivity(open)
            }
            sourcesList.addView(row)
        }
    }

    /**
     * Why there is no written answer, above the matching moments. Only a
     * server that was never reached is "couldn't be reached": an on-device
     * model not downloaded, Local-only AI refusing an address or a rejected
     * key say what they are, so the user knows what to fix.
     */
    private fun failureText(failure: Exception): String {
        LocalNetworkUi.failureText(this, failure)?.let {
            return getString(R.string.ask_llm_failed_lan, it)
        }
        if (LocalNetworkAccess.unreachableIn(failure) != null) {
            return getString(R.string.ask_llm_failed)
        }
        return getString(
            R.string.ask_llm_failed_reason,
            failure.message ?: getString(R.string.platform_error_unknown)
        )
    }

    /** What one run found and, with an LLM, wrote; [failure] is why it wrote nothing. */
    private class Answer(
        val hits: List<LibrarySearch.Hit>,
        val answer: String?,
        val failure: Exception?,
        val notices: List<String>,
        val modelKey: String
    )

    companion object {
        private const val STATE_ASKED = "asked"

        /** The run for the question asked last; see [RetainedRun]. */
        private val runs = RetainedRun<String, Answer>()

        /**
         * Retrieval, then (with an LLM) the written answer. Runs on its own
         * thread and holds nothing of the screen: [app] is the application
         * context.
         */
        private fun answer(
            app: Context,
            question: String,
            useLlm: Boolean,
            baseUrl: String,
            apiKey: String,
            model: String,
            localOnly: Boolean,
            modelKey: String
        ): Answer {
            // The light listing: everything retrieval, the answer's context
            // and its notices read, without the word timings (most of a
            // transcribed meeting's bytes and parse) that none of them use.
            val hits = LibrarySearch.search(MeetingStore(app).listLight(), question)
            var answer: String? = null
            var failure: Exception? = null
            val notices = AiDisclaimers.texts(app, AiDisclaimers.union(hits.map { it.meeting }))
            if (useLlm && hits.isNotEmpty()) {
                try {
                    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    val blocks = hits.map { hit ->
                        val m = hit.meeting
                        val label = "${m.title} — ${dateFormat.format(Date(m.createdAtMs))}"
                        // Matching moments first: on-device each block is cut
                        // to a small share of the window from its head, and
                        // the lines that matched the question matter most.
                        val content = buildString {
                            if (hit.excerpts.isNotEmpty()) {
                                append("Matching moments:\n")
                                for (excerpt in hit.excerpts) {
                                    append("- ").append(excerpt).append("\n")
                                }
                                append("\n")
                            }
                            if (m.summary.isNotBlank()) {
                                append("Summary:\n").append(m.summary).append("\n\n")
                            }
                            append("Transcript:\n")
                            append(m.transcriptTextWithSpeakers())
                        }
                        label to content
                    }
                    answer = LlmClient.askLibrary(baseUrl, apiKey, model, localOnly, blocks, question)
                } catch (e: Exception) {
                    failure = e
                }
            }
            return Answer(hits, answer, failure, notices, modelKey)
        }
    }
}
