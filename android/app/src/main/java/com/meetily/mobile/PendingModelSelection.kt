package com.meetily.mobile

import android.content.Context
import android.content.SharedPreferences
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.TranscriptionModels

/**
 * A model the user picked that is still downloading.
 *
 * Picking a model in Settings used to write the selection straight away,
 * before a 550 MB+ file had arrived. A recording started in the meantime
 * found no ready model and quietly recorded audio only. Now the pick is
 * parked here, one per kind (transcription, speaker ID, on-device AI), and
 * the live setting keeps pointing at the model that works until the new
 * one has finished downloading.
 *
 * The switch is made by ModelDownloadService when the download completes,
 * not by Settings, so it still happens if the user has left Settings or the
 * activity was destroyed meanwhile. A failed or cancelled download drops
 * the parked pick and the old model stays in charge.
 *
 * Kinds are ModelDownloadService.KIND_* strings.
 */
object PendingModelSelection {

    private const val PREFS = "meetily_pending_models"
    private const val KEY_SUFFIX = "_key"
    private const val TIME_SUFFIX = "_at"

    /**
     * A parked pick with no download behind it is stale: the process died
     * mid-download (the service is not sticky), so it will never complete.
     * This long is allowed for the service to come up after the pick.
     */
    private const val STALE_MS = 60_000L

    private val KINDS = listOf(
        ModelDownloadService.KIND_WHISPER,
        ModelDownloadService.KIND_DIARIZE,
        ModelDownloadService.KIND_LLM
    )

    /** What a finished download means for the pick parked for its kind. */
    enum class Outcome {
        /** The parked model is on disk: make it the live selection. */
        APPLY,

        /** The parked model's download failed or was cancelled: forget it. */
        DROP,

        /** Some other model finished; the parked pick is not affected. */
        IGNORE
    }

    /** Pure decision, so the rule has JVM test coverage. */
    internal fun decide(
        pendingKey: String?,
        finishedKey: String,
        cancelled: Boolean,
        error: String?,
        downloaded: Boolean
    ): Outcome = when {
        pendingKey == null || pendingKey != finishedKey -> Outcome.IGNORE
        !cancelled && error == null && downloaded -> Outcome.APPLY
        else -> Outcome.DROP
    }

    /**
     * The model to fall back to after [deletedKey], the live selection, was
     * deleted: the first entry of [downloadedInOrder] that is not the
     * deleted one, or null when nothing else is on the device (the
     * selection is then left as it is; every status line reads it as "not
     * downloaded", and nothing treats it as ready).
     */
    internal fun replacementAfterDelete(
        deletedKey: String,
        downloadedInOrder: List<String>
    ): String? = downloadedInOrder.firstOrNull { it != deletedKey }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, kind: String): String? =
        prefs(context).getString(kind + KEY_SUFFIX, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, kind: String, key: String) {
        prefs(context).edit()
            .putString(kind + KEY_SUFFIX, key)
            .putLong(kind + TIME_SUFFIX, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context, kind: String) {
        prefs(context).edit()
            .remove(kind + KEY_SUFFIX)
            .remove(kind + TIME_SUFFIX)
            .apply()
    }

    /** Clears the parked pick for [kind] only if it is [key]. */
    fun clearIf(context: Context, kind: String, key: String) {
        if (get(context, kind) == key) clear(context, kind)
    }

    /**
     * Drops parked picks that no download will ever complete. Only call
     * while ModelDownloadService is not running.
     */
    fun clearStale(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val edit = p.edit()
        var changed = false
        for (kind in KINDS) {
            if (p.getString(kind + KEY_SUFFIX, null) == null) continue
            val at = p.getLong(kind + TIME_SUFFIX, 0L)
            if (now - at < 0 || now - at > STALE_MS) {
                edit.remove(kind + KEY_SUFFIX).remove(kind + TIME_SUFFIX)
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    fun isDownloaded(context: Context, kind: String, key: String): Boolean = when (kind) {
        ModelDownloadService.KIND_LLM ->
            LocalLlmModels.isDownloaded(context, LocalLlmModels.byKey(key))
        ModelDownloadService.KIND_DIARIZE ->
            DiarizationModels.isDownloaded(context, DiarizationModels.byKey(key))
        else -> TranscriptionModels.isDownloaded(context, key)
    }

    /** The live selection for [kind]. */
    fun selected(context: Context, kind: String): String {
        val settings = AppSettings(context)
        return when (kind) {
            ModelDownloadService.KIND_LLM -> settings.localLlmModel
            ModelDownloadService.KIND_DIARIZE -> settings.diarizationModel
            else -> settings.whisperModel
        }
    }

    /**
     * Makes [key] the live selection for [kind] right now. Switching the
     * on-device AI model frees the loaded one (non-blocking; a generation in
     * flight frees it on its way out) so the next chat loads the new model.
     */
    fun select(context: Context, kind: String, key: String) {
        val settings = AppSettings(context)
        when (kind) {
            ModelDownloadService.KIND_LLM -> {
                if (settings.localLlmModel != key) {
                    settings.localLlmModel = key
                    LocalLlm.release()
                }
            }
            ModelDownloadService.KIND_DIARIZE -> settings.diarizationModel = key
            else -> settings.whisperModel = key
        }
    }

    /**
     * Called by ModelDownloadService on the main thread when a download
     * ends, BEFORE it tells any observer, so a Settings screen refreshing on
     * that callback already sees the new selection. Returns true when the
     * live selection changed.
     */
    fun onDownloadFinished(
        context: Context,
        kind: String,
        key: String,
        cancelled: Boolean,
        error: String?
    ): Boolean {
        val pending = get(context, kind) ?: return false
        return when (
            decide(pending, key, cancelled, error, pending == key && isDownloaded(context, kind, key))
        ) {
            Outcome.APPLY -> {
                select(context, kind, key)
                clear(context, kind)
                true
            }
            Outcome.DROP -> {
                clear(context, kind)
                false
            }
            Outcome.IGNORE -> false
        }
    }

    /**
     * Downloads dropped from the queue by a cancel will never finish, so
     * their parked picks go with them.
     */
    fun onDownloadsDropped(context: Context, dropped: Collection<Pair<String, String>>) {
        for ((kind, key) in dropped) clearIf(context, kind, key)
    }
}
