package com.meetily.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.WeeklyDigest
import java.text.DateFormat
import java.util.Date

/**
 * "Your week in meetings": always shows the offline digest immediately
 * (meetings, open action items by owner, highlights); with an LLM
 * configured it upgrades in place to a written digest with themes and
 * decisions. Shareable as text.
 */
class DigestActivity : AppCompatActivity() {

    private lateinit var digestView: TextView
    private var shareText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_digest)

        val toolbar = findViewById<MaterialToolbar>(R.id.digestToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.digest_title)
        toolbar.setNavigationOnClickListener { finish() }

        digestView = findViewById(R.id.digestText)
        val progress = findViewById<LinearProgressIndicator>(R.id.digestProgress)
        val settings = AppSettings(this)
        val useLlm = settings.useLlm && settings.llmConfigured
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val formatDate: (Long) -> String = { dateFormat.format(Date(it)) }

        Thread {
            val meetings = WeeklyDigest.weekMeetings(
                MeetingStore(this).list(), System.currentTimeMillis()
            )
            if (meetings.isEmpty()) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        digestView.text = getString(R.string.digest_empty)
                    }
                }
                return@Thread
            }
            val local = WeeklyDigest.buildLocal(
                meetings, getString(R.string.digest_unassigned), formatDate
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                shareText = local
                digestView.text = local
                if (useLlm) progress.visibility = View.VISIBLE
            }
            if (!useLlm) return@Thread
            val upgraded = try {
                LlmClient.digest(
                    settings.llmBaseUrl,
                    settings.llmApiKey,
                    settings.llmModel,
                    settings.localOnlyLlm,
                    WeeklyDigest.contextBlocks(meetings, formatDate)
                )
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (upgraded != null) {
                    shareText = upgraded
                    digestView.text = upgraded
                } else {
                    Toast.makeText(this, R.string.digest_llm_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_digest, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_share_digest) {
            if (shareText.isNotBlank()) {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.digest_title))
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        },
                        getString(R.string.share_digest)
                    )
                )
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
