package com.meetily.mobile

import com.meetily.mobile.security.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock's pure rules: when a screen stays locked (the gate and a lock
 * screen already up use the same one), and which task the lock screen's Back
 * treats as Recap's own.
 */
class AppLockRulesTest {

    @Test
    fun locksOnlyWhileOnLockedAndCheckable() {
        assertTrue(AppLock.mustLock(unlocked = false, appLockOn = true) { true })
        assertFalse(AppLock.mustLock(unlocked = true, appLockOn = true) { true })
        assertFalse(AppLock.mustLock(unlocked = false, appLockOn = false) { true })
    }

    @Test
    fun aRemovedScreenLockReleasesTheLock() {
        // No credential left to check against: a lock screen already up
        // stands down instead of failing every prompt.
        assertFalse(AppLock.mustLock(unlocked = false, appLockOn = true) { false })
    }

    @Test
    fun theCredentialIsAskedOnlyWhenItDecides() {
        var asked = 0
        val probe = { asked++; true }
        AppLock.mustLock(unlocked = false, appLockOn = false, credentialUsable = probe)
        AppLock.mustLock(unlocked = true, appLockOn = true, credentialUsable = probe)
        assertEquals(0, asked)
        AppLock.mustLock(unlocked = false, appLockOn = true, credentialUsable = probe)
        assertEquals(1, asked)
    }

    @Test
    fun ownTaskIsOneListedAsRecaps() {
        assertTrue(AppLock.isOwnTask(12, listOf(7, 12)))
        // A share or Manage space put the lock in another app's task.
        assertFalse(AppLock.isOwnTask(12, listOf(7)))
        assertFalse(AppLock.isOwnTask(12, emptyList()))
    }

    @Test
    fun unreadableTasksKeepTheUsualBack() {
        assertTrue(AppLock.isOwnTask(12, null))
    }
}
