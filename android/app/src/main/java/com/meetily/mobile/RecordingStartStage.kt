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
 */
enum class RecordingStartStage {
    /** Nothing asked for yet: the consent, permission and start flow runs. */
    IDLE,

    /** A microphone or notification prompt is up; its result is still due. */
    AWAITING_PERMISSION,

    /** The microphone was refused. Re-asking on every rotation would nag. */
    MIC_DENIED,

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

        /** The session this screen belonged to is over; never start another. */
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
         * microphone permission as it stands now.
         */
        fun resume(
            stage: RecordingStartStage,
            serviceActive: Boolean,
            startQueued: Boolean,
            micGranted: Boolean
        ): Resume = when {
            serviceActive -> Resume.ATTACH
            stage == IDLE -> Resume.START_FLOW
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
