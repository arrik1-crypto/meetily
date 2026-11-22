package com.meetily.mobile

import com.meetily.mobile.RecordingStartStage.Resume
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A re-created recording screen (a tablet rotated at targetSdk 36, where the
 * portrait lock no longer applies) must never ask twice for the microphone or
 * for device-audio consent, and must never start a recording of its own once
 * the session it belonged to has ended.
 */
class RecordingStartStageTest {

    private fun resume(
        stage: RecordingStartStage,
        active: Boolean = false,
        queued: Boolean = false,
        mic: Boolean = true
    ) = RecordingStartStage.resume(stage, active, queued, mic)

    @Test
    fun aLiveSessionIsAlwaysAttached() {
        for (stage in RecordingStartStage.entries) {
            assertEquals(stage.name, Resume.ATTACH, resume(stage, active = true))
            assertEquals(stage.name, Resume.ATTACH, resume(stage, active = true, queued = true))
        }
    }

    @Test
    fun aFreshScreenRunsTheStartFlow() {
        assertEquals(Resume.START_FLOW, resume(RecordingStartStage.IDLE))
        assertEquals(Resume.START_FLOW, resume(RecordingStartStage.IDLE, mic = false))
    }

    @Test
    fun anOpenPromptIsAnsweredRatherThanAskedAgain() {
        assertEquals(Resume.WAIT_FOR_RESULT, resume(RecordingStartStage.AWAITING_PERMISSION))
        assertEquals(
            Resume.WAIT_FOR_RESULT,
            resume(RecordingStartStage.AWAITING_PERMISSION, mic = false)
        )
        assertEquals(Resume.WAIT_FOR_RESULT, resume(RecordingStartStage.AWAITING_PROJECTION))
        // Even with some other start queued, the consent dialog still owns it.
        assertEquals(
            Resume.WAIT_FOR_RESULT,
            resume(RecordingStartStage.AWAITING_PROJECTION, queued = true)
        )
    }

    @Test
    fun aQueuedStartIsWaitedForNotRepeated() {
        assertEquals(Resume.WAIT_FOR_START, resume(RecordingStartStage.STARTED, queued = true))
    }

    @Test
    fun anEndedSessionClosesTheScreenInsteadOfRecordingAgain() {
        assertEquals(Resume.CLOSE, resume(RecordingStartStage.STARTED))
        assertEquals(Resume.CLOSE, resume(RecordingStartStage.STARTED, mic = false))
    }

    @Test
    fun aRefusedMicrophoneIsNotAskedForOnEveryRotation() {
        assertEquals(Resume.SHOW_MIC_DENIED, resume(RecordingStartStage.MIC_DENIED, mic = false))
        // Granted in Settings in the meantime: start normally.
        assertEquals(Resume.START_FLOW, resume(RecordingStartStage.MIC_DENIED, mic = true))
    }

    @Test
    fun savedStagesRoundTripAndUnknownOnesStartFresh() {
        for (stage in RecordingStartStage.entries) {
            assertEquals(stage, RecordingStartStage.fromSaved(stage.name))
        }
        assertEquals(RecordingStartStage.IDLE, RecordingStartStage.fromSaved(null))
        assertEquals(RecordingStartStage.IDLE, RecordingStartStage.fromSaved("RENAMED_LATER"))
    }
}
