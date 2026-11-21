package com.meetily.mobile

import com.meetily.mobile.data.EngineDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade rule for the on-device default. A fresh install gets the new
 * default; an install that predates it keeps the engine it was using and is
 * offered the on-device one instead of being switched without a word.
 */
class EngineDefaultTest {

    @Test
    fun freshInstallTakesTheOnDeviceDefault() {
        val priorUse = EngineDefault.hasPriorUse(emptySet()) { false }
        assertFalse(priorUse)
        val decision = EngineDefault.decide(storedEngine = null, priorUse = priorUse)
        assertEquals(EngineDefault.Decision(writeEngine = null, showNotice = false), decision)
    }

    @Test
    fun upgraderWithNoStoredEngineIsPinnedToSystemAndOffered() {
        val priorUse = EngineDefault.hasPriorUse(setOf("onboarding_done")) { false }
        assertTrue(priorUse)
        val decision = EngineDefault.decide(storedEngine = null, priorUse = priorUse)
        assertEquals(EngineDefault.SYSTEM, decision.writeEngine)
        assertTrue(decision.showNotice)
    }

    @Test
    fun upgraderWithMeetingsButNoSettingsCountsAsPriorUse() {
        // Onboarding dismissed with Back never writes a preference, but the
        // meetings recorded since then are just as much an existing install.
        assertTrue(EngineDefault.hasPriorUse(emptySet()) { true })
        val decision = EngineDefault.decide(storedEngine = null, priorUse = true)
        assertEquals(EngineDefault.SYSTEM, decision.writeEngine)
    }

    @Test
    fun upgraderWhoChoseOnDeviceIsLeftAlone() {
        val decision = EngineDefault.decide(EngineDefault.ON_DEVICE, priorUse = true)
        assertEquals(EngineDefault.Decision(writeEngine = null, showNotice = false), decision)
    }

    @Test
    fun upgraderWithSystemStoredKeepsItAndIsOffered() {
        // Settings saved the key on every Save, so this is usually the old
        // default written back rather than a choice. Kept, but offered once.
        val decision = EngineDefault.decide(EngineDefault.SYSTEM, priorUse = true)
        assertEquals(null, decision.writeEngine)
        assertTrue(decision.showNotice)
    }

    @Test
    fun theMigrationsOwnKeysAreNotPriorUse() {
        val own = setOf(EngineDefault.KEY_MIGRATED, EngineDefault.KEY_NOTICE)
        assertFalse(EngineDefault.hasPriorUse(own) { false })
    }

    @Test
    fun meetingsAreOnlyCheckedWhenSettingsCannotTell() {
        var asked = false
        assertTrue(EngineDefault.hasPriorUse(setOf("theme_mode")) { asked = true; false })
        assertFalse(asked)
    }
}
