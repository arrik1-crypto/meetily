package com.meetily.mobile

import android.content.Context
import android.content.SharedPreferences
import com.meetily.mobile.data.AppSettings

/**
 * "Download both" on the first-run setup step asks for on-device AI
 * summaries, but switching the AI engine on before its model has arrived
 * would make every summary in the meantime fail with "model not
 * downloaded". So the step only arms the switch here, and it is thrown when
 * ModelDownloadService finishes that model: the AI engine becomes the
 * on-device one and AI summaries are turned on. A failed or cancelled
 * download disarms it, and the built-in summariser carries on as before.
 *
 * The rule is PendingModelSelection.decide's, so it shares that coverage.
 */
object OnDeviceAiSetup {

    private const val PREFS = "meetily_ai_setup"
    private const val KEY_WAITING = "enable_when_downloaded"

    /**
     * Set when the switch was thrown in this process, so a Settings screen
     * open at the time can show it instead of saving its stale switch state
     * over it. See [takeJustEnabled].
     */
    @Volatile private var justEnabled = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Turns on-device AI on once [key] (a LocalLlmModels key) is on disk:
     * now, if it already is, otherwise when its download finishes.
     */
    fun enableWhenDownloaded(context: Context, key: String) {
        if (PendingModelSelection.isDownloaded(context, ModelDownloadService.KIND_LLM, key)) {
            prefs(context).edit().remove(KEY_WAITING).apply()
            enable(context, key, behindSettings = false)
        } else {
            prefs(context).edit().putString(KEY_WAITING, key).apply()
        }
    }

    /** Called by ModelDownloadService on the main thread when a download ends. */
    fun onDownloadFinished(
        context: Context,
        kind: String,
        key: String,
        cancelled: Boolean,
        error: String?
    ) {
        if (kind != ModelDownloadService.KIND_LLM) return
        val waiting = prefs(context).getString(KEY_WAITING, null) ?: return
        val downloaded = waiting == key &&
            PendingModelSelection.isDownloaded(context, ModelDownloadService.KIND_LLM, key)
        when (PendingModelSelection.decide(waiting, key, cancelled, error, downloaded)) {
            PendingModelSelection.Outcome.APPLY -> {
                prefs(context).edit().remove(KEY_WAITING).apply()
                enable(context, key, behindSettings = true)
            }
            PendingModelSelection.Outcome.DROP -> prefs(context).edit().remove(KEY_WAITING).apply()
            PendingModelSelection.Outcome.IGNORE -> Unit
        }
    }

    /** Downloads dropped by a cancel never finish; forget the one armed here. */
    fun onDownloadsDropped(context: Context, dropped: Collection<Pair<String, String>>) {
        val waiting = prefs(context).getString(KEY_WAITING, null) ?: return
        if (dropped.any { (kind, key) -> kind == ModelDownloadService.KIND_LLM && key == waiting }) {
            prefs(context).edit().remove(KEY_WAITING).apply()
        }
    }

    /**
     * True once after the switch was thrown in this process. SettingsActivity
     * asks when an LLM download ends, and ticks its own switches to match.
     */
    fun takeJustEnabled(): Boolean {
        val was = justEnabled
        justEnabled = false
        return was
    }

    /**
     * Only if [key] is still the selected model: had the user picked another
     * one in Settings since, switching AI on would be acting on a choice they
     * have already changed. [behindSettings] is true when this happens at the
     * end of a download, while a Settings screen may be open.
     */
    private fun enable(context: Context, key: String, behindSettings: Boolean) {
        val settings = AppSettings(context)
        if (settings.localLlmModel != key) return
        settings.llmEngine = "local"
        settings.useLlm = true
        if (behindSettings) justEnabled = true
    }
}
