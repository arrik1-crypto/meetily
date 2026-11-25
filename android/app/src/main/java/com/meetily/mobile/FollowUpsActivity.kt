package com.meetily.mobile

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.meetily.mobile.data.CompleteActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.export.TaskExport
import com.meetily.mobile.reminders.Reminders
import com.meetily.mobile.summarize.AiDisclaimers
import com.meetily.mobile.summarize.QuotedAiText
import java.text.DateFormat
import java.util.Date

/**
 * Every open action item across the whole library, newest meetings first.
 * Checking one off writes `done` back into the owning meeting (and cancels
 * its reminder); long-pressing a row opens that meeting.
 */
class FollowUpsActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView

    /**
     * Library reads happen here, never on the main thread: they scan every
     * meeting file, which grows with use. One thread, so a burst of resumes
     * applies in order.
     */
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "followups-load")
    }

    /** The meetings behind the rows on screen, reused by the exports. */
    private var shown: List<Meeting> = emptyList()

    /** (meeting id, task) checked off since [shown] was loaded. */
    private val completedHere = HashSet<Pair<String, String>>()

    private val exportIcs = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
            "text/calendar"
        )
    ) { uri ->
        if (uri != null) writeIcs(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_followups)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.edgeScroll)
        }
        store = MeetingStore(this)
        list = findViewById(R.id.followUpList)
        emptyView = findViewById(R.id.followUpEmpty)
        val toolbar = findViewById<MaterialToolbar>(R.id.followUpsToolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_followups)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_export_tasks) {
                showExportChoices()
                true
            } else if (item.itemId == R.id.aisafety_action_report_tasks) {
                // The items were extracted by the summary runs that wrote
                // them, as recorded with the items.
                val runs = meetingsWithOpenItems().map {
                    com.meetily.mobile.data.SummaryAlternatives.actionItemsRun(it)
                }
                AiReport.show(
                    this,
                    AiReport.Subject(
                        label = getString(R.string.aisafety_report_action),
                        surface = "follow_ups",
                        text = TaskExport.text(openItems()),
                        templateKey = QuotedAiText.joinKeys(runs.map { it.template }),
                        modelKey = QuotedAiText.joinKeys(runs.map { it.model })
                    )
                )
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        loader.shutdown()
        super.onDestroy()
    }

    // --- Export / handoff to task apps --------------------------------------

    /** Open items as last loaded; checked-off rows drop out on the next load. */
    private fun openItems(): List<TaskExport.Item> = openItemsOf(shown, completedHere)

    /** The meetings with an item still open here. */
    private fun meetingsWithOpenItems(): List<Meeting> = shown.filter { meeting ->
        meeting.actionItems.any { !it.done && (meeting.id to it.task) !in completedHere }
    }

    /**
     * The fixed notices of the summary runs the open items came from, by
     * the run that wrote the items (not the summary shown now, which a
     * switch may have changed), so a clinical plan's drugs and doses carry
     * the clinical notice wherever they are shared.
     */
    private fun openItemNotices(): List<String> = AiDisclaimers.texts(
        this,
        AiDisclaimers.unionActionItems(meetingsWithOpenItems())
    )

    private fun showExportChoices() {
        if (openItems().isEmpty()) {
            Toast.makeText(this, R.string.no_action_items, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            getString(R.string.export_actions_text),
            getString(R.string.export_actions_ics)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.export_actions_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    shareText()
                } else {
                    try {
                        exportIcs.launch("recap-followups.ics")
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.export_failed,
                                e.message ?: getString(R.string.platform_error_no_picker)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareText() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.followups_title))
            putExtra(
                Intent.EXTRA_TEXT,
                AiDisclaimers.appendTo(TaskExport.text(openItems()), openItemNotices())
            )
        }
        startActivity(
            Intent.createChooser(send, getString(R.string.export_actions_text))
        )
    }

    /**
     * Built from a fresh read rather than from [shown]: after a rotation or a
     * process death while the file picker was open, the result reaches a new
     * screen before its first load has landed, and an empty calendar was
     * written and reported as exported. On [loader], after any tick still
     * being written, so the read sees it.
     */
    private fun writeIcs(uri: android.net.Uri) {
        val texts = com.meetily.mobile.export.ExportTextResources.load(this)
        val ticked = completedHere.toSet()
        // The destination is often a cloud provider; its stream can block.
        loader.execute {
            val items = try {
                openItemsOf(
                    store.listPartial(MeetingStore.ACTION_FIELDS + MeetingStore.NOTICE_FIELDS),
                    ticked
                )
            } catch (_: Throwable) {
                emptyList()
            }
            if (items.isEmpty()) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, R.string.no_action_items, Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val ics = TaskExport.ics(items, System.currentTimeMillis(), texts)
            val error = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ics.toByteArray(Charsets.UTF_8))
                } ?: throw RuntimeException(getString(R.string.platform_error_no_destination))
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.platform_error_unknown)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error == null) {
                    Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.export_failed, error), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ticks made before this read are written before it (same thread),
        // so the read has them; only those may be forgotten when it lands.
        val tickedBefore = completedHere.toSet()
        // Titles, dates, action items and what decides the summary's
        // notice (openItemNotices) — never transcripts.
        loader.execute {
            val loaded = try {
                store.listPartial(MeetingStore.ACTION_FIELDS + MeetingStore.NOTICE_FIELDS)
            } catch (_: Throwable) {
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                shown = loaded
                // A task reopened in its meeting is open again here too.
                completedHere.removeAll(tickedBefore)
                render()
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        var open = 0
        // Loaded newest-first; keep that order so fresh follow-ups lead.
        for (meeting in shown) {
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
                        append(item.owner)
                        append(" · ")
                    }
                    append(meeting.title)
                    append(" · ")
                    append(dateFormat.format(Date(meeting.createdAtMs)))
                    item.remindAtMs?.let {
                        append(" · ⏰ ")
                        append(timeFormat.format(Date(it)))
                    }
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
        emptyView.visibility = if (open == 0) View.VISIBLE else View.GONE
        list.visibility = if (open == 0) View.GONE else View.VISIBLE
    }

    /**
     * Marks the item done in the owning meeting, as one locked
     * read-modify-write on [loader] (CompleteActionItem): a load and a save
     * of its own on the main thread could put back an older copy over a
     * summary or transcript written in between, and froze the list while a
     * long meeting was parsed and rewritten.
     */
    private fun completeItem(meetingId: String, index: Int, task: String) {
        // Out of Share and Export at once, before the write lands.
        completedHere.add(meetingId to task)
        val app = applicationContext
        loader.execute {
            if (CompleteActionItem.markDone(store, meetingId, index, task)) {
                Reminders.cancelActionItem(app, meetingId, task)
            }
        }
    }

    companion object {
        /**
         * The open items of [meetings], newest meetings first, leaving out
         * those ticked off on this screen ([completed], by meeting id and
         * task) whose write may not have been read back yet.
         */
        internal fun openItemsOf(
            meetings: List<Meeting>,
            completed: Set<Pair<String, String>>
        ): List<TaskExport.Item> {
            val out = mutableListOf<TaskExport.Item>()
            for (meeting in meetings) {
                for (item in meeting.actionItems) {
                    if (item.done || (meeting.id to item.task) in completed) continue
                    out.add(
                        TaskExport.Item(item.task, item.owner, item.remindAtMs, meeting.title)
                    )
                }
            }
            return out
        }
    }
}
