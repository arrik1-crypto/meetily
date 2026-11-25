package com.meetily.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.CompleteActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.search.MeetingGroups
import com.meetily.mobile.summarize.AiDisclaimers
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.QuotedAiText
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.FutureTask

/**
 * Prep before a recurring meeting: what happened last time, the open action
 * items across the series, and (with an LLM configured) a generated brief.
 * Launched from the calendar nudge's "Prep" action or a meeting's menu; the
 * series is matched by normalized title against the library.
 */
class PreMeetingBriefActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    /**
     * The series as listed: titles, dates, action items and the fields the
     * notices need (loadSeries). Transcripts are read only for the meetings
     * shown or sent, never for the whole series.
     */
    private var series: List<Meeting> = emptyList()
    private var seriesName: String = ""
    private var query: String = ""

    /** A brief for [query] is shown or on its way; kept across a recreation. */
    private var briefAsked = false

    /**
     * Series matching scans every meeting file, so it runs here rather than
     * on the main thread. One thread, so a burst of resumes applies in order.
     */
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "brief-load")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_pre_brief)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.edgeScroll)
        }
        store = MeetingStore(this)
        settings = AppSettings(this)
        findViewById<MaterialToolbar>(R.id.briefToolbar).setNavigationOnClickListener {
            finish()
        }
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        // Recreated (rotation, fold, theme): wait on the brief the old
        // screen started, or show the one it already had.
        if (savedInstanceState?.getBoolean(STATE_BRIEF) == true) {
            briefRuns.existing(query)?.let { attachBrief(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BRIEF, briefAsked)
    }

    override fun onResume() {
        super.onResume()
        // Reload every time: items get checked off here and meetings change
        // behind the long-press navigation.
        loader.execute {
            val loaded = try {
                loadSeries(applicationContext, store, query)
            } catch (_: Throwable) {
                return@execute
            }
            // The extractive fallback reads a whole transcript; worker too.
            val lastBody = loaded.firstOrNull()?.let { lastBodyFor(it) }.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                series = loaded
                seriesName = series.minByOrNull { it.title.length }?.title ?: query
                render(query, lastBody)
            }
        }
    }

    override fun onDestroy() {
        loader.shutdown()
        // Left for good: nothing will come back for the brief.
        if (isFinishing && briefAsked) briefRuns.forget(query)
        super.onDestroy()
    }

    private fun render(query: String, lastBody: String) {
        val nameView = findViewById<TextView>(R.id.briefSeriesName)
        val metaView = findViewById<TextView>(R.id.briefSeriesMeta)
        nameView.text = seriesName.ifBlank { getString(R.string.pre_brief_title) }

        findViewById<View>(R.id.briefEmpty).visibility =
            if (series.isEmpty()) View.VISIBLE else View.GONE
        if (series.isEmpty()) {
            metaView.text = query
            return
        }
        metaView.text = resources.getQuantityString(
            R.plurals.pre_brief_meta, series.size, series.size
        )

        renderLast(series.first(), lastBody)
        renderOpenItems()

        val generate = findViewById<MaterialButton>(R.id.generateBriefButton)
        if (settings.useLlm && settings.llmConfigured) {
            generate.visibility = View.VISIBLE
            generate.setOnClickListener { generateBrief() }
        }
    }

    /** [last] is as listed; its transcript is read only when it has no summary. */
    private fun lastBodyFor(last: Meeting): String =
        last.summary.ifBlank {
            // No saved summary: fall back to quick extractive key points.
            try {
                val full = store.load(last.id) ?: return@ifBlank ""
                ExtractiveSummarizer.summarize(
                    full.transcriptText(), full.notes, full.highlightedTexts(), false,
                    com.meetily.mobile.data.MeetingLanguage
                        .englishHeuristics(full.transcriptLanguage())
                )
            } catch (_: Exception) {
                ""
            }
        }

    private fun renderLast(last: Meeting, body: String) {
        findViewById<View>(R.id.briefLastLabel).visibility = View.VISIBLE
        val card = findViewById<View>(R.id.briefLastCard)
        card.visibility = View.VISIBLE
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        findViewById<TextView>(R.id.briefLastTitle).text = getString(
            R.string.pre_brief_last_fmt,
            last.title, dateFormat.format(Date(last.createdAtMs))
        )
        // A clinical or legal summary keeps its fixed notice here too, in
        // front, where the card's line limit cannot cut it off.
        findViewById<TextView>(R.id.briefLastBody).text =
            if (body.isBlank()) {
                getString(R.string.pre_brief_no_summary)
            } else {
                AiDisclaimers.prependTo(
                    body, AiDisclaimers.texts(this, AiDisclaimers.summaryCategories(last))
                )
            }
        card.setOnClickListener {
            startActivity(
                Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, last.id)
            )
        }
        showLastReport(last, card)
    }

    private var lastReportButton: MaterialButton? = null

    /**
     * "Report AI output" for the last-time card's summary. It goes below the
     * card, not inside it: the whole card is one tap target that opens the
     * meeting, and its line limit could cut a control off. Hidden while the
     * card shows extractive key points or the no-summary line, which no
     * model wrote (QuotedAiText).
     */
    private fun showLastReport(last: Meeting, card: View) {
        val button = lastReportButton ?: AiReport.addButton(card) {}.also {
            lastReportButton = it
            // Two report buttons can share this screen; name this one.
            it.contentDescription = getString(R.string.polish_report_last_time_desc)
        }
        val text = QuotedAiText.lastCardText(last.summary, last.summaryModel)
        button.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) {
            button.setOnClickListener {
                AiReport.show(
                    this,
                    AiReport.Subject(
                        label = getString(R.string.aisafety_report_action),
                        surface = "pre_meeting_brief_last_time",
                        text = text,
                        templateKey = last.summaryTemplate ?: QuotedAiText.UNRECORDED,
                        modelKey = last.summaryModel
                    )
                )
            }
        }
    }

    private fun renderOpenItems() {
        val list = findViewById<LinearLayout>(R.id.briefItemsList)
        list.removeAllViews()
        findViewById<View>(R.id.briefOpenLabel).visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        var open = 0
        for (meeting in series) {
            for ((index, item) in meeting.actionItems.withIndex()) {
                if (item.done) continue
                open++
                val row = inflater.inflate(R.layout.item_action, list, false)
                val check = row.findViewById<CheckBox>(R.id.actionCheck)
                val text = row.findViewById<TextView>(R.id.actionText)
                val meta = row.findViewById<TextView>(R.id.actionOwner)
                text.text = item.task
                meta.text = buildString {
                    if (!item.owner.isNullOrBlank()) {
                        append(item.owner).append(" · ")
                    }
                    append(
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(meeting.createdAtMs))
                    )
                }
                meta.visibility = View.VISIBLE
                check.isChecked = false
                check.setOnCheckedChangeListener { _, checked ->
                    if (!checked) return@setOnCheckedChangeListener
                    completeItem(meeting.id, index, item.task)
                    text.paintFlags = text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    text.alpha = 0.55f
                    meta.alpha = 0.55f
                    check.isEnabled = false
                }
                row.setOnLongClickListener {
                    startActivity(
                        Intent(this, MeetingDetailActivity::class.java)
                            .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                    )
                    true
                }
                A11y.labelLongClick(row, getString(R.string.a11y_open_meeting))
                list.addView(row)
            }
        }
        if (open > 0) {
            findViewById<View>(R.id.briefOpenLabel).visibility = View.VISIBLE
        }
    }

    /** As FollowUpsActivity.completeItem: one locked write, off the main thread. */
    private fun completeItem(meetingId: String, index: Int, task: String) {
        val app = applicationContext
        loader.execute {
            if (CompleteActionItem.markDone(store, meetingId, index, task)) {
                Reminders.cancelActionItem(app, meetingId, task)
            }
        }
    }

    private fun generateBrief() {
        // Plain values only: the run outlives this screen.
        val app = applicationContext
        val store = this.store
        val query = this.query
        val series = seriesName
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val localOnly = settings.localOnlyLlm
        val modelKey = AiReport.currentModelKey(this)
        val run = briefRuns.start(query, "pre-brief") {
            brief(
                app, store, query, series,
                baseUrl, apiKey, model, localOnly, modelKey
            )
        }
        attachBrief(run)
    }

    /** Shows [run] in progress, then the brief or why there is none. */
    private fun attachBrief(run: FutureTask<Brief>) {
        briefAsked = true
        val button = findViewById<MaterialButton>(R.id.generateBriefButton)
        val progress = findViewById<View>(R.id.briefProgress)
        val output = findViewById<TextView>(R.id.briefText)
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        Thread {
            val result = try {
                run.get()
            } catch (e: Exception) {
                val why = (e.cause ?: e).message ?: getString(R.string.platform_error_unknown)
                Brief(getString(R.string.brief_failed, why), null, "")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                button.isEnabled = true
                output.visibility = View.VISIBLE
                output.text = result.text
                showBriefReport(result.raw, result.modelKey)
            }
        }.apply {
            name = "pre-brief-wait"
            start()
        }
    }

    private var briefReportButton: MaterialButton? = null

    /**
     * "Report AI output" under a generated brief; hidden for the error line
     * a failed run leaves. Added on first use, after the brief's text view.
     */
    private fun showBriefReport(brief: String?, modelKey: String) {
        val output = findViewById<TextView>(R.id.briefText)
        val button = briefReportButton ?: AiReport.addButton(output) {}.also {
            briefReportButton = it
            DisclaimerViews.copyWithNotices(output) {
                AiDisclaimers.texts(this, AiDisclaimers.union(series.take(4)))
            }
        }
        button.visibility = if (brief == null) View.GONE else View.VISIBLE
        if (brief != null) {
            button.setOnClickListener {
                AiReport.show(
                    this,
                    AiReport.Subject(
                        label = getString(R.string.aisafety_report_action),
                        surface = "pre_meeting_brief",
                        text = brief,
                        modelKey = modelKey
                    )
                )
            }
        }
    }

    /**
     * One generated brief: [text] as shown (with the notices of the
     * summaries it was built from), [raw] the model's own words for a report,
     * null when it failed and [text] says why.
     */
    private class Brief(val text: String, val raw: String?, val modelKey: String)

    companion object {
        const val EXTRA_QUERY = "query"
        private const val STATE_BRIEF = "brief"

        /** The brief asked for last, by query; see [RetainedRun]. */
        private val briefRuns = RetainedRun<String, Brief>()

        /**
         * The series for [query], newest first: titles, dates, action items
         * and the notice fields, read as one partial listing. A year of a
         * weekly meeting is fifty transcripts with every word timing, and
         * none of that is needed to list it.
         */
        private fun loadSeries(
            app: Context,
            store: MeetingStore,
            query: String
        ): List<Meeting> = matchSeries(
            store.listPartial(MeetingStore.ACTION_FIELDS + MeetingStore.NOTICE_FIELDS),
            query,
            MeetingGroups.Untitled.of(app)
        )

        /**
         * Past meetings whose normalized title matches [query], newest first.
         * Untitled recordings are not a series (MeetingGroups.Untitled):
         * every one of them would otherwise be "last time" for the others.
         * So none is a match, and an untitled recording's own title (the
         * meeting screen's query) has no series at all.
         */
        private fun matchSeries(
            all: List<Meeting>,
            query: String,
            untitled: MeetingGroups.Untitled
        ): List<Meeting> {
            val key = MeetingGroups.normalizeTitle(query)
            if (key.isBlank() || untitled.hasDefaultForm(query)) return emptyList()
            return all.filter {
                MeetingGroups.normalizeTitle(it.title) == key &&
                    !untitled.isUntitled(it.title, it.createdAtMs)
            }.sortedByDescending { it.createdAtMs }
        }

        /**
         * Reads the newest meetings of the series and asks for the brief.
         * Runs on its own thread and holds nothing of the screen: [app] is
         * the application context.
         */
        private fun brief(
            app: Context,
            store: MeetingStore,
            query: String,
            series: String,
            baseUrl: String,
            apiKey: String,
            model: String,
            localOnly: Boolean,
            modelKey: String
        ): Brief {
            // Fresh copies: items may have been checked off moments ago. Only
            // the four the brief is built from are read whole.
            val recent = try {
                loadSeries(app, store, query).take(4).mapNotNull { store.load(it.id) }
            } catch (_: Throwable) {
                emptyList()
            }
            val blocks = recent.map { meeting ->
                val label = meeting.title + ", " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(meeting.createdAtMs))
                val content = buildString {
                    if (meeting.summary.isNotBlank()) {
                        append("Summary:\n").append(meeting.summary.take(4_000)).append("\n\n")
                    }
                    val openItems = meeting.actionItems.filter { !it.done }
                    if (openItems.isNotEmpty()) {
                        append("Open action items:\n")
                        for (item in openItems) {
                            append("- ").append(item.task)
                            if (!item.owner.isNullOrBlank()) {
                                append(" (").append(item.owner).append(')')
                            }
                            append('\n')
                        }
                        append('\n')
                    }
                    append("Transcript:\n").append(meeting.transcriptTextWithSpeakers())
                }
                label to content
            }
            return try {
                val brief = LlmClient.preBrief(baseUrl, apiKey, model, localOnly, series, blocks)
                // Built from these summaries, so it carries their notices.
                Brief(
                    AiDisclaimers.appendTo(
                        brief, AiDisclaimers.texts(app, AiDisclaimers.union(recent))
                    ),
                    brief,
                    modelKey
                )
            } catch (e: Exception) {
                Brief(
                    app.getString(
                        R.string.brief_failed,
                        e.message ?: app.getString(R.string.platform_error_unknown)
                    ),
                    null,
                    modelKey
                )
            }
        }
    }
}
