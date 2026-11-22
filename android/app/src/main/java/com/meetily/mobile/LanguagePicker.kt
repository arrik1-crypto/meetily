package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingLanguage
import com.meetily.mobile.summarize.OutputLanguage
import com.meetily.mobile.whisper.TranscriptionModels
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * The screens' side of the meeting language: the language list, the
 * settings and import labels, and the meeting screen's chip with its
 * "Re-transcribe as…" flow. The decisions live in MeetingLanguage and
 * OutputLanguage; this only shows them.
 */
object LanguagePicker {

    /** [code]'s name in the phone's own language ("Deutsch" on a German phone). */
    fun displayName(code: String): String {
        val locale = Locale.getDefault()
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) {
            return MeetingLanguage.englishName(code) ?: code
        }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** A spoken-language setting or override, as shown: a name or "Auto-detect". */
    fun spokenLabel(context: Context, value: String?): String =
        MeetingLanguage.normalize(value)?.let { displayName(it) }
            ?: context.getString(R.string.language_auto)

    /** The summary-language setting, as shown: a name or "Same as meeting". */
    fun summaryLabel(context: Context, value: String): String =
        if (value == OutputLanguage.SAME) {
            context.getString(R.string.language_same_as_meeting)
        } else {
            MeetingLanguage.normalize(value)?.let { displayName(it) }
                ?: context.getString(R.string.language_same_as_meeting)
        }

    /**
     * A single-choice list: [firstLabel] (picked as [firstValue]) and then
     * every language in MeetingLanguage.CHOICES, alphabetical in the phone's
     * language. [current] is ticked when it is in the list.
     */
    fun show(
        context: Context,
        title: String,
        firstLabel: String,
        firstValue: String,
        current: String?,
        onPick: (String) -> Unit
    ) {
        val collator = Collator.getInstance()
        val codes = MeetingLanguage.CHOICES.sortedWith(compareBy(collator) { displayName(it) })
        val values = listOf(firstValue) + codes
        val labels = (listOf(firstLabel) + codes.map { displayName(it) }).toTypedArray()
        val checked = values.indexOf(MeetingLanguage.normalize(current) ?: firstValue)
            .coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                onPick(values[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The meeting screen's language chip: the spoken language, or "unknown"
     * on a meeting whose audio could still be transcribed again. Hidden
     * when there is neither, as on a meeting from the system recognizer.
     * It offers "Re-transcribe as…" only while there is audio; without it
     * the language is a plain label, not a pill to tap.
     */
    fun bindChip(chip: TextView, m: Meeting, onClick: () -> Unit) {
        val code = m.language
        if (code == null && m.audioFile.isNullOrBlank()) {
            chip.visibility = View.GONE
            return
        }
        val context = chip.context
        val name = code?.let { displayName(it) } ?: context.getString(R.string.language_unknown)
        chip.text = context.getString(
            if (m.translated) R.string.language_chip_translated else R.string.language_chip,
            name
        )
        if (m.audioFile.isNullOrBlank()) {
            // Nothing to transcribe again (the audio was removed): the layout's
            // plain text, with no tap, no pill and no 48dp target.
            A11y.clearClick(chip)
            chip.background = null
            chip.setPadding(0, 0, 0, 0)
            chip.minHeight = 0
            chip.minWidth = 0
        } else {
            val density = context.resources.displayMetrics.density
            // The home screen's filter pill: 36dp to look at, 48dp to touch.
            A11y.pillTouchTarget(
                chip,
                R.drawable.bg_pill,
                visualHeightPx = (36 * density).toInt(),
                paddingHorizontalPx = (14 * density).toInt(),
                paddingVerticalPx = (7 * density).toInt()
            )
            chip.setOnClickListener { onClick() }
            A11y.labelClick(chip, context.getString(R.string.language_retranscribe_as))
        }
        chip.visibility = View.VISIBLE
    }

    /**
     * "Re-transcribe as…": the language, then (when there is a choice) the
     * model, then a confirmation stating the cost; [start] gets the model
     * key and the language ("auto" or a code) and queues the pass through
     * the accuracy-check path. [audioMs] is the length of the meeting's
     * audio, for the estimate.
     */
    fun retranscribe(
        activity: Activity,
        m: Meeting,
        audioMs: Long,
        start: (modelKey: String, language: String) -> Unit
    ) {
        show(
            activity,
            activity.getString(R.string.language_retranscribe_as),
            activity.getString(R.string.language_auto),
            MeetingLanguage.AUTO,
            m.language
        ) { language ->
            if (activity.isFinishing || activity.isDestroyed) return@show
            withAudioLength(activity, m, audioMs) { length ->
                chooseModel(activity, m, length, language, start)
            }
        }
    }

    /**
     * [audioMs], or when that is unknown the saved audio's own length, read
     * off the main thread. A meeting with no transcript has no timings to
     * measure, and quoting the one-minute floor for an hour of audio told
     * the user a long run was a short one. 0 when neither is known.
     */
    private fun withAudioLength(
        activity: Activity,
        m: Meeting,
        audioMs: Long,
        then: (Long) -> Unit
    ) {
        val audioName = m.audioFile
        if (audioMs > 0L || audioName.isNullOrBlank()) {
            then(audioMs)
            return
        }
        val file = AudioStore.fileFor(activity, audioName)
        Thread {
            val probed = durationMs(file)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                then(probed)
            }
        }.apply {
            name = "language-audio-length"
            start()
        }
    }

    /** Container-reported length of [file]; 0 when it reports none. */
    private fun durationMs(file: File): Long = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } finally {
            mmr.release()
        }
    } catch (_: Throwable) {
        0L
    }

    private fun chooseModel(
        activity: Activity,
        m: Meeting,
        audioMs: Long,
        language: String,
        start: (String, String) -> Unit
    ) {
        val downloaded = TranscriptionModels.downloadedKeys(activity)
        if (downloaded.isEmpty()) {
            android.widget.Toast.makeText(
                activity, R.string.import_needs_whisper, android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val pinned = MeetingLanguage.normalize(language)
        // A pinned language needs a model that can be told that language.
        // Auto needs one that reports what it heard: a NeMo or English-only
        // pass records no detected language, and an accepted pass keeps the
        // old one, so a wrong language could never be corrected that way.
        val candidates = if (pinned == null) {
            downloaded.filter { TranscriptionModels.detectsLanguage(it) }
        } else {
            downloaded.filter { TranscriptionModels.acceptsLanguage(it, pinned) }
        }
        if (candidates.isEmpty()) {
            val message = when {
                pinned == null -> activity.getString(R.string.language_auto_needs_whisper)
                !MeetingLanguage.nemotronSupports(pinned) ->
                    activity.getString(R.string.language_needs_whisper, displayName(pinned))
                else -> activity.getString(R.string.language_needs_model)
            }
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        // The model that made this transcript, then the one in Settings,
        // then the most accurate one installed.
        val preferred = listOfNotNull(
            m.transcriptModel, com.meetily.mobile.data.AppSettings(activity).whisperModel
        ).firstOrNull { it in candidates }
            ?: candidates.maxByOrNull { TranscriptionModels.qualityRank(it) }
            ?: candidates.first()
        if (candidates.size == 1) {
            // No picker to show, but the same fit warning as one.
            FitBadge.confirmChoice(
                activity,
                TranscriptionModels.displayName(preferred),
                com.meetily.mobile.data.DeviceProbe.verdict(
                    activity, TranscriptionModels.fitNeedOrNull(preferred)
                ),
                alreadyActive = false
            ) { confirm(activity, m, audioMs, preferred, language, start) }
            return
        }
        ModelPickerSheet.show(
            activity,
            activity.getString(R.string.language_choose_model),
            entriesProvider = {
                candidates.map { key ->
                    ModelPickerSheet.Entry(
                        key = key,
                        title = TranscriptionModels.displayName(key),
                        meta = TranscriptionModels.metaLineFor(activity, key, audioMs),
                        downloaded = true,
                        selected = key == preferred,
                        fit = com.meetily.mobile.data.DeviceProbe.verdict(
                            activity, TranscriptionModels.fitNeedOrNull(key)
                        ),
                        // A run is never a no-op re-pick, so the fit warning
                        // always applies.
                        active = false
                    )
                }
            },
            onPick = { key -> confirm(activity, m, audioMs, key, language, start) }
        )
    }

    private fun confirm(
        activity: Activity,
        m: Meeting,
        audioMs: Long,
        modelKey: String,
        language: String,
        start: (String, String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val pinned = MeetingLanguage.normalize(language)
        val title = if (pinned == null) {
            activity.getString(R.string.language_retranscribe_auto_title)
        } else {
            activity.getString(R.string.language_retranscribe_title, displayName(pinned))
        }
        val modelName = TranscriptionModels.displayName(modelKey)
        // No words yet: the pass writes the transcript instead of staging a
        // comparison (see ImportService.firstTranscript). With no known
        // length there is no estimate, rather than the one-minute floor.
        val body = when {
            audioMs <= 0L -> activity.getString(
                if (m.segments.isEmpty()) R.string.language_transcribe_body_no_estimate
                else R.string.language_retranscribe_body_no_estimate,
                modelName
            )
            else -> activity.getString(
                if (m.segments.isEmpty()) R.string.language_transcribe_body
                else R.string.language_retranscribe_body,
                modelName,
                com.meetily.mobile.whisper.ModelSpeed.estimateLabel(activity, modelKey, audioMs)
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.language_retranscribe_go) { _, _ ->
                start(modelKey, pinned ?: MeetingLanguage.AUTO)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
