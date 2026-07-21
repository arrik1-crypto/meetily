package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore

class MainActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var adapter: MeetingAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = MeetingStore(this)
        emptyView = findViewById(R.id.emptyView)

        val recycler = findViewById<RecyclerView>(R.id.meetingList)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MeetingAdapter(
            onClick = { meeting ->
                startActivity(
                    Intent(this, MeetingDetailActivity::class.java)
                        .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                )
            },
            onLongClick = { meeting -> confirmDelete(meeting) }
        )
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewMeeting).setOnClickListener {
            startActivity(Intent(this, RecordingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val meetings = store.list()
        adapter.submit(meetings)
        emptyView.visibility = if (meetings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(meeting: Meeting) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_meeting_title)
            .setMessage(getString(R.string.delete_meeting_message, meeting.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(meeting.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
