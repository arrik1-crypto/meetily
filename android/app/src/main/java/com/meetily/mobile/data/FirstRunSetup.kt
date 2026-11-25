package com.meetily.mobile.data

/**
 * What each answer on the first-run "Set up on-device AI" step sets up.
 * OnboardingActivity shows the step and runs the downloads; the decisions
 * are here so they are unit-tested.
 */
object FirstRunSetup {

    enum class Choice {
        /** The suggested transcription model and the suggested LLM. */
        BOTH,

        /** The suggested transcription model alone. */
        TRANSCRIPTION_ONLY,

        /** Nothing: everything stays as it was. */
        LATER
    }

    /**
     * True when the step is worth showing: the phone has a transcription
     * model to suggest, and on-device transcription is the engine in use
     * (an install pinned to Android's speech service has its own offer of
     * the on-device engine on the home screen, and a model downloaded here
     * would sit unused).
     */
    fun shouldOffer(recommendation: DeviceFit.Recommendation?, onDeviceEngine: Boolean): Boolean =
        onDeviceEngine && recommendation?.transcription != null

    /** The models [choice] sets up, transcription first so it is ready first. */
    fun picks(recommendation: DeviceFit.Recommendation, choice: Choice): List<DeviceFit.Pick> =
        when (choice) {
            Choice.LATER -> emptyList()
            Choice.TRANSCRIPTION_ONLY -> listOfNotNull(recommendation.transcription)
            Choice.BOTH -> listOfNotNull(recommendation.transcription, recommendation.llm)
        }

    /** Those of [picks] that still have to download. */
    fun toDownload(
        picks: List<DeviceFit.Pick>,
        downloaded: (DeviceFit.Pick) -> Boolean
    ): List<DeviceFit.Pick> = picks.filter { !downloaded(it) }

    /** Download sizes of [picks], MB, for the free-space check and the size line. */
    fun sizesMb(picks: List<DeviceFit.Pick>): List<Int> = picks.map { it.need.sizeMb }

    /**
     * Whether the home screen opens the welcome screen as it is created:
     * until onboarding is done, but only on a fresh start. A screen
     * re-created from saved state (a rotation, the theme button, a dark-mode
     * switch) does not, so backing out of the welcome screen is not undone
     * by turning the phone; the next fresh start shows it again.
     */
    fun opensWelcome(restored: Boolean, onboardingDone: Boolean): Boolean =
        !restored && !onboardingDone
}
