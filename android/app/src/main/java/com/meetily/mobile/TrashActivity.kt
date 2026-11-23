package com.meetily.mobile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.MeetingTrash
import com.meetily.mobile.data.Trash
import com.meetily.mobile.reminders.Reminders
import java.text.DateFormat
import java.util.Date

/**
 * Recently deleted: every meeting deleted in the last 30 days (MeetingTrash),
 * with when it was deleted, how long it has left and the space it takes.
 * Each can be restored to the library as it was, or erased now; Empty
 * erases them all. Erasing asks first and says what goes.
 *
 * Opened from Settings and the Storage screen. Every read and change runs on
 * TrashUi's worker; the status line under the title says what each one did
 * as a polite live region.
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var none: TextView
    private lateinit var emptyButton: MaterialButton
    private lateinit var list: RecyclerView

    private val adapter = Adapter()
    private var items: List<MeetingTrash.Item> = emptyList()
    private var loaded = false

    /** Bumped per listing, so only the newest one is shown. */
    private var generation = 0

    private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_trash)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.trashList)
        }
        findViewById<MaterialToolbar>(R.id.trashToolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.trashStatus)
        none = findViewById(R.id.trashNone)
        emptyButton = findViewById(R.id.trashEmpty)
        emptyButton.setOnClickListener { confirmEmpty() }
        list = findViewById(R.id.trashList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Every return re-reads: a meeting deleted, or one erased by the
        // daily run meanwhile.
        load(note = null)
    }

    private fun live(): Boolean = !isFinishing && !isDestroyed

    /**
     * Reads the list on the worker, erasing first whatever is due (as app
     * start does), then shows it. [note] is what the action that asked for
     * this did; it is spoken with the new count.
     */
    private fun load(note: String?) {
        val gen = ++generation
        val app = applicationContext
        TrashUi.execute {
            val result = try {
                val trash = MeetingTrash(app)
                trash.purgeDue()
                trash.items()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (!live() || gen != generation) return@runOnUiThread
                if (result == null) {
                    A11y.setStatusText(status, getString(R.string.trash_load_failed), announce = true)
                } else {
                    render(result, note)
                }
            }
        }
    }

    private fun render(next: List<MeetingTrash.Item>, note: String?) {
        // Spoken for the first listing, an action's result, or a count that moved.
        val announce = !loaded || note != null || next.size != items.size
        items = next
        loaded = true
        adapter.notifyDataSetChanged()
        none.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        emptyButton.isEnabled = next.isNotEmpty()
        val count = if (next.isEmpty()) {
            getString(R.string.trash_none)
        } else {
            resources.getQuantityString(
                R.plurals.trash_summary, next.size, next.size, TrashUi.size(this, next.sumOf { it.bytes })
            )
        }
        A11y.setStatusText(status, if (note == null) count else "$note $count", announce)
    }

    private fun titleOf(item: MeetingTrash.Item): String =
        item.title.ifBlank { getString(R.string.trash_untitled) }

    private fun restore(item: MeetingTrash.Item) {
        val app = applicationContext
        val title = titleOf(item)
        TrashUi.execute {
            val result = MeetingTrash(app).restore(item.entry)
            val note = when (result) {
                MeetingTrash.Restore.RESTORED -> {
                    // A reminder due while it was here, or one a reboot
                    // cleared, is armed again (late rather than lost).
                    runCatching { Reminders.rescheduleAll(app) }
                    val rule = RetentionRunner.rule(app)
                    if (Trash.cleanUpWouldRetake(rule, item.createdAtMs, item.starred, System.currentTimeMillis())) {
                        app.getString(R.string.trash_restored_retake, title, rule.days)
                    } else {
                        app.getString(R.string.trash_restored, title)
                    }
                }
                MeetingTrash.Restore.IN_LIBRARY -> app.getString(R.string.trash_restore_in_library, title)
                MeetingTrash.Restore.GONE -> app.getString(R.string.trash_gone)
                MeetingTrash.Restore.FAILED -> app.getString(R.string.trash_restore_failed, title)
            }
            runOnUiThread { if (live()) load(note) }
        }
    }

    private fun deleteForever(item: MeetingTrash.Item) {
        val title = titleOf(item)
        TrashUi.confirmDeleteForever(this, title, item.bytes) {
            val app = applicationContext
            TrashUi.execute {
                val erased = MeetingTrash(app).deleteForever(listOf(item.entry)) > 0
                val note = if (erased) {
                    app.getString(R.string.trash_deleted_forever, title)
                } else {
                    app.getString(R.string.trash_gone)
                }
                runOnUiThread { if (live()) load(note) }
            }
        }
    }

    /**
     * Erases what the list shows, after saying how many meetings and how
     * much that is. One deleted since the list was read is not part of what
     * was asked about, so it stays.
     */
    private fun confirmEmpty() {
        val shown = items
        if (shown.isEmpty()) return
        TrashUi.confirmEmpty(this, shown.size, shown.sumOf { it.bytes }) {
            emptyButton.isEnabled = false
            val app = applicationContext
            TrashUi.execute {
                val erased = MeetingTrash(app).deleteForever(shown.map { it.entry })
                val note = app.resources.getQuantityString(R.plurals.trash_emptied, erased, erased)
                runOnUiThread { if (live()) load(note) }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_trash, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.trashItemTitle)
        private val detail: TextView = view.findViewById(R.id.trashItemDetail)
        private val restoreButton: MaterialButton = view.findViewById(R.id.trashItemRestore)
        private val deleteButton: MaterialButton = view.findViewById(R.id.trashItemDelete)

        fun bind(item: MeetingTrash.Item) {
            val name = titleOf(item)
            title.text = name
            val left = if (item.daysLeft > 0) {
                resources.getQuantityString(R.plurals.trash_days_left, item.daysLeft, item.daysLeft)
            } else {
                getString(R.string.trash_due)
            }
            detail.text = getString(
                R.string.trash_item_detail,
                dateFormat.format(Date(item.entry.deletedAtMs)), left, TrashUi.size(this@TrashActivity, item.bytes)
            )
            restoreButton.contentDescription = getString(R.string.trash_restore_desc, name)
            deleteButton.contentDescription = getString(R.string.trash_delete_forever_desc, name)
            // Off until the list is read again, so a second tap cannot act
            // on a meeting the first one already moved.
            restoreButton.isEnabled = true
            deleteButton.isEnabled = true
            restoreButton.setOnClickListener {
                restoreButton.isEnabled = false
                deleteButton.isEnabled = false
                restore(item)
            }
            deleteButton.setOnClickListener { deleteForever(item) }
        }
    }
}
