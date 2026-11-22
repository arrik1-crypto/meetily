package com.meetily.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.DeviceProbe
import com.meetily.mobile.data.DownloadSpace
import com.meetily.mobile.data.EngineDefault
import com.meetily.mobile.data.FirstRunSetup
import com.meetily.mobile.licences.ModelTerms
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.TranscriptionModels

/**
 * First run: the intro, then "Set up on-device AI".
 *
 * On-device transcription is the default, but a new install has no model,
 * so every meeting stayed audio only until the user found Settings →
 * Transcription. The second step suggests a transcription model and a
 * summary model that suit this phone (DeviceFit) and downloads them through
 * the same licence gate, free-space check, ModelDownloadService and
 * PendingModelSelection path as Settings. "Choose later" changes nothing.
 * Whatever the answer, onboardingDone is set exactly as before.
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = STEP_INTRO
    private var device: DeviceFit.Device? = null
    private var recommendation: DeviceFit.Recommendation? = null

    /** Room for downloads once measured (kept across recreation), else -1. */
    private var freeBytes = -1L
    private var measuring = false

    /** The free-space check for a download answer is running. */
    private var checking = false

    /**
     * An answer is being acted on: the downloads are starting, or wait on
     * the notification question. Further taps are ignored until it is done.
     */
    private var applied = false

    /**
     * The answer waiting on the notification-permission question (kept
     * across recreation, since the question is another activity), or null.
     */
    private var awaitingNotifications: FirstRunSetup.Choice? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Granted or not, the downloads go ahead: only their progress
            // notification needs the permission, as on the record path.
            val choice = awaitingNotifications ?: return@registerForActivityResult
            awaitingNotifications = null
            val rec = recommendation
            if (rec == null || isFinishing || isDestroyed) return@registerForActivityResult
            startDownloads(FirstRunSetup.picks(rec, choice), choice)
        }

    private val backToIntro = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showStep(STEP_INTRO, moveFocus = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_onboarding)
        EdgeToEdge.setUp(this) { root() }
        onBackPressedDispatcher.addCallback(this, backToIntro)

        freeBytes = savedInstanceState?.getLong(KEY_FREE_BYTES, -1L) ?: -1L
        awaitingNotifications = savedInstanceState?.getString(KEY_AWAITING)?.let { name ->
            FirstRunSetup.Choice.entries.firstOrNull { it.name == name }
        }
        refreshRecommendation()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.setupTitle), true)

        findViewById<View>(R.id.getStartedButton).setOnClickListener {
            if (offerSetup()) showStep(STEP_SETUP, moveFocus = true) else finishOnboarding()
        }
        findViewById<View>(R.id.setupTranscriptionButton).setOnClickListener {
            choose(FirstRunSetup.Choice.TRANSCRIPTION_ONLY)
        }
        findViewById<View>(R.id.setupLaterButton).setOnClickListener { finishOnboarding() }

        // A recreation (rotation, a resize, night mode) comes back to the
        // step it left; the ScrollView restores its own position after this.
        val restored = savedInstanceState?.getInt(KEY_STEP, STEP_INTRO) ?: STEP_INTRO
        showStep(
            if (restored == STEP_SETUP && offerSetup()) STEP_SETUP else STEP_INTRO,
            moveFocus = false
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putLong(KEY_FREE_BYTES, freeBytes)
        outState.putString(KEY_AWAITING, awaitingNotifications?.name)
    }

    /**
     * The phone and what to suggest for it. Free space, once measured, is
     * part of it: a summary model the space cannot hold beside the
     * transcription model is not suggested (DeviceFit.recommend).
     */
    private fun refreshRecommendation() {
        device = DeviceProbe.device(this, freeBytes.takeIf { it >= 0L })
        recommendation = device?.let { dev ->
            DeviceFit.recommend(dev) { key ->
                TranscriptionModels.fitNeedOrNull(key) ?: LocalLlmModels.fitNeedOrNull(key)
            }
        }
    }

    private fun offerSetup(): Boolean = FirstRunSetup.shouldOffer(
        recommendation,
        AppSettings(this).transcriptionEngine == EngineDefault.ON_DEVICE
    )

    /** Exactly what "Get started" always did. */
    private fun finishOnboarding() {
        AppSettings(this).onboardingDone = true
        finish()
    }

    private fun showStep(next: Int, moveFocus: Boolean) {
        step = next
        val setup = next == STEP_SETUP
        findViewById<View>(R.id.onboardingIntro).visibility = if (setup) View.GONE else View.VISIBLE
        findViewById<View>(R.id.onboardingSetup).visibility = if (setup) View.VISIBLE else View.GONE
        findViewById<View>(R.id.getStartedButton).visibility = if (setup) View.GONE else View.VISIBLE
        backToIntro.isEnabled = setup
        findViewById<ScrollView>(R.id.onboardingScroll).scrollTo(0, 0)
        if (setup) {
            bindSetup()
            measureFreeSpace()
        }
        if (moveFocus) {
            // The page changed under a screen reader: start it at the top.
            val target = findViewById<View>(
                if (setup) R.id.setupTitle else R.id.getStartedButton
            )
            target.post {
                target.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null
                )
            }
        }
    }

    // --- The setup step -------------------------------------------------------

    private fun bindSetup() {
        val rec = recommendation ?: return
        val transcription = rec.transcription ?: return
        bindDevice()
        bindPick(
            transcription,
            R.id.setupTranscriptionName, R.id.setupTranscriptionSize,
            R.id.setupTranscriptionFit, R.id.setupTranscriptionWhy, R.id.setupTranscriptionLicence
        )
        val llm = rec.llm
        findViewById<View>(R.id.setupLlmCard).visibility =
            if (llm != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.setupNoLlm).apply {
            visibility = if (llm == null) View.VISIBLE else View.GONE
            setText(
                if (rec.llmLeftOutForSpace) R.string.devicefit_setup_no_llm_space
                else R.string.devicefit_setup_no_llm
            )
        }
        if (llm != null) {
            bindPick(
                llm, R.id.setupLlmName, R.id.setupLlmSize,
                R.id.setupLlmFit, R.id.setupLlmWhy, R.id.setupLlmLicence
            )
        }
        // With no summary model to offer, the main button downloads the
        // transcription model and the "only" button would say the same.
        val main = findViewById<MaterialButton>(R.id.setupBothButton)
        val only = findViewById<View>(R.id.setupTranscriptionButton)
        if (llm != null) {
            main.setText(R.string.devicefit_setup_both)
            main.setOnClickListener { choose(FirstRunSetup.Choice.BOTH) }
            only.visibility = View.VISIBLE
        } else {
            main.setText(R.string.devicefit_setup_transcription_one)
            main.setOnClickListener { choose(FirstRunSetup.Choice.TRANSCRIPTION_ONLY) }
            only.visibility = View.GONE
        }

        val pending = pendingDownloads(FirstRunSetup.Choice.BOTH)
        val totalMb = FirstRunSetup.sizesMb(pending).sum()
        findViewById<TextView>(R.id.setupData).apply {
            visibility = if (totalMb > 0) View.VISIBLE else View.GONE
            text = getString(
                if (pending.size > 1) R.string.devicefit_setup_total
                else R.string.devicefit_setup_total_one,
                formatMb(totalMb)
            )
        }
        findViewById<View>(R.id.setupMetered).visibility =
            if (totalMb > 0 && network() == Net.METERED) View.VISIBLE else View.GONE
        bindStorage()
    }

    private fun bindDevice() {
        val dev = device ?: return
        findViewById<TextView>(R.id.setupDevice).text = if (freeBytes >= 0L) {
            getString(
                R.string.devicefit_setup_device_storage,
                dev.displayRamGb, Formatter.formatShortFileSize(this, freeBytes)
            )
        } else {
            getString(R.string.devicefit_setup_device, dev.displayRamGb)
        }
    }

    private fun bindPick(
        pick: DeviceFit.Pick,
        nameId: Int,
        sizeId: Int,
        fitId: Int,
        whyId: Int,
        licenceId: Int
    ) {
        val key = pick.need.key
        findViewById<TextView>(nameId).text = displayName(pick)
        findViewById<TextView>(sizeId).text = if (isDownloaded(pick)) {
            getString(R.string.devicefit_setup_downloaded)
        } else {
            getString(R.string.devicefit_setup_size, pick.need.sizeMb)
        }
        FitBadge.bind(findViewById(fitId), pick.verdict)
        findViewById<TextView>(whyId).text = whyText(pick)
        findViewById<TextView>(licenceId).apply {
            val line = ModelTerms.pickerLine(this@OnboardingActivity, key)
            text = line
            visibility = if (line == null) View.GONE else View.VISIBLE
        }
    }

    private fun whyText(pick: DeviceFit.Pick): String {
        val llm = pick.need.kind == DeviceFit.Kind.LLM
        val reason = getString(
            when (pick.limit) {
                DeviceFit.Limit.NONE ->
                    if (llm) R.string.devicefit_why_llm_best else R.string.devicefit_why_best
                DeviceFit.Limit.MEMORY ->
                    if (llm) R.string.devicefit_why_llm_memory else R.string.devicefit_why_memory
                DeviceFit.Limit.PROCESSOR ->
                    if (llm) R.string.devicefit_why_llm_processor
                    else R.string.devicefit_why_processor
            }
        )
        if (llm) return reason
        val language = getString(
            if (pick.need.englishOnly) R.string.devicefit_why_english
            else R.string.devicefit_why_multilingual
        )
        return "$language $reason"
    }

    /** The line saying both downloads will not fit, once storage is known. */
    private fun bindStorage() {
        val view = findViewById<TextView>(R.id.setupStorage)
        if (freeBytes < 0L) {
            view.visibility = View.GONE
            return
        }
        val required = DownloadSpace.requiredBytes(
            FirstRunSetup.sizesMb(pendingDownloads(FirstRunSetup.Choice.BOTH))
        )
        val short = DownloadSpace.shortfallBytes(required, freeBytes)
        if (short == 0L) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        A11y.setStatusText(
            view,
            getString(R.string.devicefit_setup_short, Formatter.formatShortFileSize(this, short)),
            announce = true
        )
    }

    /** Free space, once per screen; it can take seconds, so off the main thread. */
    private fun measureFreeSpace() {
        if (freeBytes >= 0L || measuring) return
        measuring = true
        val app = applicationContext
        Thread {
            val room = ModelSpaceCheck.measure(app)
            runOnUiThread {
                measuring = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                freeBytes = room.availableBytes.coerceAtLeast(0L)
                // The suggestion can change with the space (a summary model
                // with no room beside the transcription model is dropped),
                // so the whole step is bound again, not just its storage line.
                refreshRecommendation()
                if (step == STEP_SETUP && offerSetup()) bindSetup()
            }
        }.apply {
            name = "onboarding-space"
            start()
        }
    }

    // --- Acting on the answer ---------------------------------------------------

    /**
     * Network, then each licence that needs accepting, then free space, then
     * the downloads. Cancelling any question leaves the user on this step
     * with nothing changed.
     */
    private fun choose(choice: FirstRunSetup.Choice) {
        // A second tap while the first is still being acted on would run
        // the whole chain twice (two space dialogs, two toasts).
        if (checking || applied) return
        val rec = recommendation ?: return finishOnboarding()
        val picks = FirstRunSetup.picks(rec, choice)
        val downloads = FirstRunSetup.toDownload(picks) { isDownloaded(it) }
        if (downloads.isEmpty()) {
            applyChoice(picks, choice)
            return
        }
        val totalMb = FirstRunSetup.sizesMb(downloads).sum()
        when (network()) {
            Net.OFFLINE -> AlertDialog.Builder(this)
                .setMessage(R.string.devicefit_offline)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            Net.METERED -> AlertDialog.Builder(this)
                .setTitle(R.string.devicefit_metered_title)
                .setMessage(getString(R.string.devicefit_metered_message, formatMb(totalMb)))
                .setPositiveButton(R.string.devicefit_metered_go) { _, _ ->
                    acceptLicences(downloads) { checkSpaceThenApply(picks, downloads, choice) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            Net.UNMETERED ->
                acceptLicences(downloads) { checkSpaceThenApply(picks, downloads, choice) }
        }
    }

    /** One licence question at a time (see ModelTerms.confirm), then [then]. */
    private fun acceptLicences(remaining: List<DeviceFit.Pick>, then: () -> Unit) {
        val next = remaining.firstOrNull()
        if (next == null) {
            then()
            return
        }
        ModelTerms.confirm(this, next.need.key, displayName(next)) {
            if (!isFinishing && !isDestroyed) acceptLicences(remaining.drop(1), then)
        }
    }

    private fun checkSpaceThenApply(
        picks: List<DeviceFit.Pick>,
        downloads: List<DeviceFit.Pick>,
        choice: FirstRunSetup.Choice
    ) {
        if (isFinishing || isDestroyed || checking) return
        // The check measures on a worker thread, which can take seconds; the
        // download buttons wait for it ("Choose later" stays usable).
        checking = true
        setChoicesEnabled(false)
        ModelSpaceCheck.confirm(
            this,
            FirstRunSetup.sizesMb(downloads),
            onMeasured = {
                checking = false
                setChoicesEnabled(true)
            }
        ) {
            if (!isFinishing && !isDestroyed) applyChoice(picks, choice)
        }
    }

    private fun setChoicesEnabled(enabled: Boolean) {
        findViewById<View>(R.id.setupBothButton).isEnabled = enabled
        findViewById<View>(R.id.setupTranscriptionButton).isEnabled = enabled
    }

    /**
     * Asks for the notification permission first when a download is about
     * to start without it (Android 13+, where a new install has not been
     * asked yet: the download's progress and "done" notifications would be
     * dropped unseen), then starts the downloads whatever the answer.
     */
    private fun applyChoice(picks: List<DeviceFit.Pick>, choice: FirstRunSetup.Choice) {
        if (applied) return
        applied = true
        if (picks.any { !isDownloaded(it) } && notificationsNeedAsking()) {
            awaitingNotifications = choice
            try {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            } catch (_: ActivityNotFoundException) {
                awaitingNotifications = null
            }
        }
        startDownloads(picks, choice)
    }

    private fun notificationsNeedAsking(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Picks each model as Settings does, so a model on disk is selected now
     * and one that has to download is selected by ModelDownloadService when
     * it arrives, then starts the downloads, transcription first.
     */
    private fun startDownloads(picks: List<DeviceFit.Pick>, choice: FirstRunSetup.Choice) {
        applied = true
        var started = false
        var refused = false
        for (pick in picks) {
            val kind = kindOf(pick)
            val key = pick.need.key
            if (!PendingModelSelection.pick(this, kind, key)) continue
            if (ModelDownloadService.start(this, kind, key)) {
                started = true
            } else {
                // Android would not start the service (see
                // ModelDownloadService.start): nothing waits on it.
                PendingModelSelection.clearIf(this, kind, key)
                refused = true
            }
        }
        if (refused) {
            // Stay on this step so the same button can be tried again; a
            // download that did start carries on, and the service ignores a
            // second request for it.
            applied = false
            Toast.makeText(this, R.string.devicefit_download_not_started, Toast.LENGTH_LONG).show()
            return
        }
        if (choice == FirstRunSetup.Choice.BOTH) {
            recommendation?.llm?.let { OnDeviceAiSetup.enableWhenDownloaded(this, it.need.key) }
        }
        if (started) {
            Toast.makeText(
                applicationContext,
                if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    R.string.devicefit_setup_started
                } else {
                    R.string.devicefit_setup_started_settings
                },
                Toast.LENGTH_LONG
            ).show()
        }
        finishOnboarding()
    }

    // --- Helpers ---------------------------------------------------------------

    private fun kindOf(pick: DeviceFit.Pick): String =
        if (pick.need.kind == DeviceFit.Kind.LLM) ModelDownloadService.KIND_LLM
        else ModelDownloadService.KIND_WHISPER

    private fun isDownloaded(pick: DeviceFit.Pick): Boolean =
        PendingModelSelection.isDownloaded(this, kindOf(pick), pick.need.key)

    private fun displayName(pick: DeviceFit.Pick): String =
        if (pick.need.kind == DeviceFit.Kind.LLM) LocalLlmModels.byKey(pick.need.key).displayName
        else TranscriptionModels.displayName(pick.need.key)

    private fun pendingDownloads(choice: FirstRunSetup.Choice): List<DeviceFit.Pick> {
        val rec = recommendation ?: return emptyList()
        return FirstRunSetup.toDownload(FirstRunSetup.picks(rec, choice)) { isDownloaded(it) }
    }

    private fun formatMb(mb: Int): String =
        Formatter.formatShortFileSize(this, mb.toLong() * 1024L * 1024L)

    private enum class Net { OFFLINE, METERED, UNMETERED }

    /**
     * The connection a download would use now. Android reports no network
     * as metered, so that is checked first and said plainly.
     */
    private fun network(): Net = try {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        when {
            manager == null -> Net.UNMETERED
            manager.activeNetwork == null -> Net.OFFLINE
            manager.isActiveNetworkMetered -> Net.METERED
            else -> Net.UNMETERED
        }
    } catch (_: SecurityException) {
        Net.UNMETERED
    }

    private companion object {
        const val STEP_INTRO = 0
        const val STEP_SETUP = 1
        const val KEY_STEP = "onboarding_step"
        const val KEY_FREE_BYTES = "onboarding_free_bytes"
        const val KEY_AWAITING = "onboarding_awaiting_notifications"
    }
}
