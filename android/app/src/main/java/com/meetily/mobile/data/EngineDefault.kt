package com.meetily.mobile.data

/**
 * The transcription engine a meeting uses when the user has not picked one,
 * and the upgrade rule that came with changing it.
 *
 * Fresh installs transcribe on the phone. Android's speech recognizer, the
 * old default, is documented as likely to stream audio to remote servers and
 * keeps no recording to re-transcribe later, so it is now opt-in.
 *
 * Existing installs keep what they had. Before this change the engine key was
 * only written when Settings was saved, so most upgraders have no stored
 * value at all; reading the new default for them would switch their next
 * meeting to audio only, with no live words, without a word said. Instead
 * "system" is written explicitly and the home screen offers the on-device
 * engine once. Pure logic, unit-tested; AppSettings does the reading and
 * writing.
 */
object EngineDefault {

    /** Any on-device engine (whisper.cpp, Parakeet, Nemotron). */
    const val ON_DEVICE = "whisper"

    /** Android's SpeechRecognizer. */
    const val SYSTEM = "system"

    const val KEY_ENGINE = "transcription_engine"
    const val KEY_MIGRATED = "engine_default_migrated"
    const val KEY_NOTICE = "on_device_engine_notice_pending"

    /** Keys this migration writes itself, which say nothing about prior use. */
    private val OWN_KEYS = setOf(KEY_MIGRATED, KEY_NOTICE)

    /**
     * What to do on the first launch of a build with the on-device default.
     * [writeEngine] is the value to store, or null to store nothing.
     * [showNotice] asks the home screen to offer the on-device engine once.
     */
    data class Decision(val writeEngine: String?, val showNotice: Boolean)

    /**
     * True when this install was used before the migration ran: any setting
     * of its own is stored (onboarding_done is one, written on the first
     * "Get started"), or a meeting exists. [meetingsExist] is only consulted
     * when the preferences are empty, so it can touch the disk.
     */
    fun hasPriorUse(prefKeys: Set<String>, meetingsExist: () -> Boolean): Boolean =
        prefKeys.any { it !in OWN_KEYS } || meetingsExist()

    /**
     * [storedEngine] is the raw stored value, null when never written.
     *
     *  - Fresh install (nothing stored, no prior use): store nothing, so the
     *    on-device default applies. No notice.
     *  - Upgrade with no stored engine: it was on the old "system" default,
     *    so write "system" and offer the on-device engine.
     *  - Upgrade already on the on-device engine: nothing to do.
     *  - Upgrade with "system" stored: kept. The notice is still offered,
     *    because Settings wrote the key on every save, so a stored "system"
     *    usually means the old default was saved, not that it was chosen.
     */
    fun decide(storedEngine: String?, priorUse: Boolean): Decision = when {
        storedEngine == null && !priorUse -> Decision(null, false)
        storedEngine == null -> Decision(SYSTEM, true)
        storedEngine == ON_DEVICE -> Decision(null, false)
        else -> Decision(null, true)
    }
}
