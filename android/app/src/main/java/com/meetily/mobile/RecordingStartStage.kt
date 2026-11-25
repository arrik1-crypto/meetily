package com.meetily.mobile

/**
 * How far the recording screen got towards starting its session, kept in the
 * screen's saved state so a re-created instance carries on from there.
 *
 * At targetSdk 36, Android 16 ignores the manifest's portrait lock on
 * displays 600dp and wider, so rotating a tablet or an unfolded foldable
 * re-creates [RecordingActivity]. The new instance used to run the whole
 * start flow again: a second microphone prompt (whose in-flight twin then
 * came back empty, read as "denied"), a second device-audio consent dialog
 * whose result then started the session twice, and, when the meeting had
 * just been finished, a brand-new recording nobody asked for.
 *
 * A screen the recording notification opens is attach-only for the same
 * reason. The notification can be tapped just as its session ends, and a
 * tap then opened a fresh screen that started a new recording.
 */
enum class RecordingStartStage {
    /** Nothing asked for yet: the consent, permission and start flow runs. */
    IDLE,

    /** A microphone or notification prompt is up; its result is still due. */
    AWAITING_PERMISSION,

    /** The microphone was refused. Re-asking on every rotation would nag. */
    MIC_DENIED,

    /**
     * The device-audio screen-lock warning is up. Consent and permissions are
     * already settled, so a re-created screen shows the warning again and
     * nothing before it: no second notification prompt, model toast or
     * calendar prefill.
     */
    AWAITING_DEVICE_AUDIO_WARNING,

    /** The device-audio consent dialog is up; its result starts the session. */
    AWAITING_PROJECTION,

    /** RecordingService.start was called: this screen belongs to that session. */
    STARTED;

    /** What a screen does once the service has bound. */
    enum class Resume {
        /** A session is live: show it. */
        ATTACH,

        /** Nothing was started: run the normal start flow. */
        START_FLOW,

        /** A prompt from the previous instance answers this one; ask nothing. */
        WAIT_FOR_RESULT,

        /** start() was sent but the service has not run it yet. */
        WAIT_FOR_START,

        /** Keep showing the refusal instead of prompting again. */
        SHOW_MIC_DENIED,

        /** Show the device-audio warning again; everything before it is done. */
        SHOW_DEVICE_AUDIO_WARNING,

        /**
         * The session this screen belonged to, or the notification that
         * opened it was posted for, is over; never start another.
         */
        CLOSE
    }

    companion object {
        /** The stage a saved state names, or [IDLE] for a fresh screen. */
        fun fromSaved(name: String?): RecordingStartStage =
            RecordingStartStage.entries.firstOrNull { it.name == name } ?: IDLE

        /**
         * [serviceActive]: the bound service has a live session.
         * [startQueued]: RecordingService.isRunning, which start() sets
         * before the service has run the request. [micGranted]: the
         * microphone permission as it stands now. [attachOnly]: the screen
         * was opened from the recording notification, to show a session.
         */
        fun resume(
            stage: RecordingStartStage,
            serviceActive: Boolean,
            startQueued: Boolean,
            micGranted: Boolean,
            attachOnly: Boolean
        ): Resume = when {
            serviceActive -> Resume.ATTACH
            attachOnly -> if (startQueued) Resume.WAIT_FOR_START else Resume.CLOSE
            stage == IDLE -> Resume.START_FLOW
            // A microphone revoked in Settings since (which also ends the
            // process) has to be asked for again before anything records.
            stage == AWAITING_DEVICE_AUDIO_WARNING ->
                if (micGranted) Resume.SHOW_DEVICE_AUDIO_WARNING else Resume.START_FLOW
            stage == AWAITING_PERMISSION || stage == AWAITING_PROJECTION ->
                Resume.WAIT_FOR_RESULT
            // Granted in Settings since: start the normal way.
            stage == MIC_DENIED ->
                if (micGranted) Resume.START_FLOW else Resume.SHOW_MIC_DENIED
            startQueued -> Resume.WAIT_FOR_START
            else -> Resume.CLOSE
        }
    }
}
