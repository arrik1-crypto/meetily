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
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.export.TaskExport
import com.meetily.mobile.reminders.Reminders
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
            } else {
                false
            }
        }
    }

    // --- Export / handoff to task apps --------------------------------------

    private fun openItems(): List<TaskExport.Item> {
        val out = mutableListOf<TaskExport.Item>()
        for (meeting in store.list()) {
            for (item in meeting.actionItems) {
                if (item.done) continue
                out.add(
                    TaskExport.Item(item.task, item.owner, item.remindAtMs, meeting.title)
                )
            }
        }
        return out
    }

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
                            getString(R.string.export_failed, e.message ?: "no file picker"),
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
            putExtra(Intent.EXTRA_TEXT, TaskExport.text(openItems()))
        }
        startActivity(
            Intent.createChooser(send, getString(R.string.export_actions_text))
        )
    }

    private fun writeIcs(uri: android.net.Uri) {
        try {
            val ics = TaskExport.ics(openItems(), System.currentTimeMillis())
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ics.toByteArray(Charsets.UTF_8))
            } ?: throw RuntimeException("could not open destination")
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.export_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        var open = 0
        // list() is newest-first; keep that order so fresh follow-ups lead.
        for (meeting in store.list()) {
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
                list.addView(row)
            }
        }
        emptyView.visibility = if (open == 0) View.VISIBLE else View.GONE
        list.visibility = if (open == 0) View.GONE else View.VISIBLE
    }

    /**
     * Marks the item done in the owning meeting. Reload-then-write so a stale
     * in-memory copy can't clobber edits made elsewhere; matched by index
     * with a task-text guard, falling back to text search.
     */
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
}
