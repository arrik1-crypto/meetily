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
import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.WeeklyDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

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
            val blocks = WeeklyDigest.contextBlocks(meetings, formatDate)
            // Plain values only: the run outlives this activity, so it must
            // not hold on to it (AppSettings keeps the Context it was given).
            val baseUrl = settings.llmBaseUrl
            val apiKey = settings.llmApiKey
            val model = settings.llmModel
            val localOnly = settings.localOnlyLlm
            val pending = digestRun(blocks) {
                try {
                    LlmClient.digest(baseUrl, apiKey, model, localOnly, blocks)
                } catch (_: Exception) {
                    null
                }
            }
            if (pending == null) {
                // On-device, and a recording, import or summary holds the
                // phone: loading the chat model on top of it is the double
                // load JobGate exists to prevent. The offline digest stands.
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progress.visibility = View.GONE
                    Toast.makeText(this, R.string.digest_llm_busy, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val upgraded = try {
                pending.get()
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

    companion object {
        private val runLock = Any()
        private var runBlocks: List<Pair<String, String>>? = null
        private var run: FutureTask<String?>? = null

        /**
         * The written digest for [blocks]: the run already in flight or
         * finished for exactly this input, or a new one. Null when a new run
         * may not start now (see JobGate).
         *
         * Held outside the activity because the activity does not survive a
         * rotation, a theme switch or a fold, and each recreation used to
         * start a whole new digest while the orphaned one kept running to
         * the end — minutes of full CPU on-device, for a result nobody saw.
         * A recreated screen now waits on the same run, and reopening the
         * digest for an unchanged week shows the finished one at once.
         */
        private fun digestRun(
            blocks: List<Pair<String, String>>,
            compute: () -> String?
        ): FutureTask<String?>? {
            synchronized(runLock) {
                val existing = run
                if (existing != null && runBlocks == blocks) {
                    // A finished run that produced nothing is retried rather
                    // than served forever.
                    val failed = existing.isDone &&
                        (try { existing.get() } catch (_: Exception) { null }) == null
                    if (!failed) return existing
                }
                if (LocalLlm.isSelected() && !JobGate.canStartBatch()) return null
                val task = FutureTask<String?>(Callable { compute() })
                run = task
                runBlocks = blocks
                Thread(task, "digest").start()
                return task
            }
        }
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
