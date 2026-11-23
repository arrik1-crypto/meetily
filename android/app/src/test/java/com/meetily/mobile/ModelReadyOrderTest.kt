package com.meetily.mobile

import com.meetily.mobile.whisper.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TranscriptionModels.isReady asks whether a model's files are on the phone
 * before asking whether its runtime loads, because the runtime check loads a
 * native library. A meeting screen asks this of every model on the main
 * thread; with no model on the phone, nothing native may load.
 */
class ModelReadyOrderTest {

    @Test
    fun runtimeIsNotCheckedForAModelThatIsNotOnThePhone() {
        var runtimeAsked = 0
        val ready = TranscriptionModels.ready(
            downloaded = { false },
            runtime = { runtimeAsked++; true }
        )
        assertFalse(ready)
        assertEquals(0, runtimeAsked)
    }

    @Test
    fun aDownloadedModelIsReadyOnlyWhenItsRuntimeLoads() {
        var runtimeAsked = 0
        assertTrue(TranscriptionModels.ready(downloaded = { true }, runtime = { runtimeAsked++; true }))
        assertFalse(TranscriptionModels.ready(downloaded = { true }, runtime = { runtimeAsked++; false }))
        assertEquals(2, runtimeAsked)
    }

    @Test
    fun answersAreTheSameAsBeforeTheReorder() {
        for (downloaded in listOf(false, true)) {
            for (runtime in listOf(false, true)) {
                assertEquals(
                    runtime && downloaded,
                    TranscriptionModels.ready(downloaded = { downloaded }, runtime = { runtime })
                )
            }
        }
    }
}
