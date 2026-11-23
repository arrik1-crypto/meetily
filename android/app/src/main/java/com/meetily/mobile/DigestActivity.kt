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
import com.meetily.mobile.summarize.AiDisclaimers
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.QuotedAiText
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

    /**
     * The written digest as the model produced it (for Report AI output),
     * and the notices of the summaries it was built from.
     */
    @Volatile private var generatedDigest: String? = null
    @Volatile private var generatedModel: String? = null
    @Volatile private var digestNotices: List<String> = emptyList()

    /**
     * The offline digest, for Report AI output while it is the one shown;
     * null when it quotes no summary a model wrote.
     */
    private var offlineReport: AiReport.Subject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_digest)
        EdgeToEdge.setUp(this) {
            root(bottom = false)
            list(R.id.edgeScroll)
        }

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
            // Dates first, then only this week's meetings in full.
            val store = MeetingStore(this)
            val meetings = WeeklyDigest.loadWeek(
                store.listPartial(setOf("createdAtMs")), System.currentTimeMillis()
            ) { store.load(it) }
            if (meetings.isEmpty()) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        digestView.text = getString(R.string.digest_empty)
                    }
                }
                return@Thread
            }
            // Lines from clinical or legal summaries, and action items a
            // clinical or legal run wrote, bring their notices (the items'
            // run is not the summary shown's after a switch).
            val notices = AiDisclaimers.texts(this, AiDisclaimers.union(meetings))
            digestNotices = notices
            val listing = WeeklyDigest.buildLocal(
                meetings, getString(R.string.digest_unassigned), formatDate
            )
            val local = AiDisclaimers.appendTo(listing, notices)
            // The listing itself is fixed code, but it quotes each summary's
            // first line and the open action items, so it is reportable when
            // a model wrote any of those, named by the runs that did.
            val quoted = QuotedAiText.quotedRuns(meetings)
            val offline = if (quoted.isEmpty()) {
                null
            } else {
                AiReport.Subject(
                    label = getString(R.string.aisafety_report_action),
                    surface = "weekly_digest_offline",
                    text = listing,
                    templateKey = QuotedAiText.joinKeys(quoted.map { it.template })
                        ?: QuotedAiText.UNRECORDED,
                    modelKey = QuotedAiText.joinKeys(quoted.map { it.model })
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                shareText = local
                digestView.text = local
                DisclaimerViews.copyWithNotices(digestView) { digestNotices }
                offlineReport = offline
                invalidateOptionsMenu()
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
            val modelKey = AiReport.currentModelKey(this)
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
                    val withNotices = AiDisclaimers.appendTo(upgraded, notices)
                    shareText = withNotices
                    digestView.text = withNotices
                    generatedDigest = upgraded
                    generatedModel = modelKey
                    invalidateOptionsMenu()
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // The written digest is AI output. The offline one is a listing, and
        // reportable only when it quotes a summary a model wrote.
        menu.findItem(R.id.aisafety_action_report_digest)?.isVisible =
            generatedDigest != null || offlineReport != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.aisafety_action_report_digest) {
            val text = generatedDigest
            if (text != null) {
                AiReport.show(
                    this,
                    AiReport.Subject(
                        label = getString(R.string.aisafety_report_action),
                        surface = "weekly_digest",
                        text = text,
                        modelKey = generatedModel
                    )
                )
            } else {
                // No written digest yet, so the listing is what is shown.
                offlineReport?.let { AiReport.show(this, it) }
            }
            return true
        }
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
