package com.meetily.mobile

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
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.search.MeetingGroups
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import java.text.DateFormat
import java.util.Date

/**
 * Prep before a recurring meeting: what happened last time, the open action
 * items across the series, and (with an LLM configured) a generated brief.
 * Launched from the calendar nudge's "Prep" action or a meeting's menu; the
 * series is matched by normalized title against the library.
 */
class PreMeetingBriefActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    private var series: List<Meeting> = emptyList()
    private var seriesName: String = ""
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_pre_brief)
        store = MeetingStore(this)
        settings = AppSettings(this)
        findViewById<MaterialToolbar>(R.id.briefToolbar).setNavigationOnClickListener {
            finish()
        }
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        // Reload every time: items get checked off here and meetings change
        // behind the long-press navigation.
        series = matchSeries(store.list(), query)
        seriesName = series.minByOrNull { it.title.length }?.title ?: query
        render(query)
    }

    /** Past meetings whose normalized title matches [query], newest first. */
    private fun matchSeries(all: List<Meeting>, query: String): List<Meeting> {
        val key = MeetingGroups.normalizeTitle(query)
        if (key.isBlank()) return emptyList()
        return all.filter { MeetingGroups.normalizeTitle(it.title) == key }
            .sortedByDescending { it.createdAtMs }
    }

    private fun render(query: String) {
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

        renderLast(series.first())
        renderOpenItems()

        val generate = findViewById<MaterialButton>(R.id.generateBriefButton)
        if (settings.useLlm && settings.llmConfigured) {
            generate.visibility = View.VISIBLE
            generate.setOnClickListener { generateBrief() }
        }
    }

    private fun renderLast(last: Meeting) {
        findViewById<View>(R.id.briefLastLabel).visibility = View.VISIBLE
        val card = findViewById<View>(R.id.briefLastCard)
        card.visibility = View.VISIBLE
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        findViewById<TextView>(R.id.briefLastTitle).text = getString(
            R.string.pre_brief_last_fmt,
            last.title, dateFormat.format(Date(last.createdAtMs))
        )
        val body = last.summary.ifBlank {
            // No saved summary: fall back to quick extractive key points.
            try {
                ExtractiveSummarizer.summarize(
                    last.transcriptText(), last.notes, last.highlightedTexts(), false
                )
            } catch (_: Exception) {
                ""
            }
        }
        findViewById<TextView>(R.id.briefLastBody).text =
            body.ifBlank { getString(R.string.pre_brief_no_summary) }
        card.setOnClickListener {
            startActivity(
                Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, last.id)
            )
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
                list.addView(row)
            }
        }
        if (open > 0) {
            findViewById<View>(R.id.briefOpenLabel).visibility = View.VISIBLE
        }
    }

    /** Same reload-then-write pattern as FollowUpsActivity. */
    private fun completeItem(meetingId: String, index: Int, task: String) {
        val meeting = store.load(meetingId) ?: return
        val at = when {
            index in meeting.actionItems.indices &&
                meeting.actionItems[index].task == task -> index
            else -> meeting.actionItems.indexOfFirst { it.task == task && !it.done }
        }
        if (at < 0) return
        meeting.actionItems[at] = meeting.actionItems[at].copy(done = true)
        store.save(meeting)
        if (meeting.actionItems[at].remindAtMs != null) {
            Reminders.cancelActionItem(this, meetingId, task)
        }
    }

    private fun generateBrief() {
        val button = findViewById<MaterialButton>(R.id.generateBriefButton)
        val progress = findViewById<View>(R.id.briefProgress)
        val output = findViewById<TextView>(R.id.briefText)
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        // Fresh copies: items may have been checked off moments ago.
        val fresh = matchSeries(store.list(), query)
        val blocks = fresh.take(4).map { meeting ->
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
        Thread {
            val result = try {
                LlmClient.preBrief(
                    settings.llmBaseUrl, settings.llmApiKey, settings.llmModel,
                    settings.localOnlyLlm, seriesName, blocks
                )
            } catch (e: Exception) {
                getString(R.string.llm_failed_fallback, e.message ?: "unknown error")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                button.isEnabled = true
                output.visibility = View.VISIBLE
                output.text = result
            }
        }.apply {
            name = "pre-brief"
            start()
        }
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
