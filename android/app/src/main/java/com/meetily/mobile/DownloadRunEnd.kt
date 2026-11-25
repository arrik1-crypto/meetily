package com.meetily.mobile

/**
 * What ModelDownloadService does with a download that has ended, as pure
 * rules so they have JVM test coverage (DownloadRunEndTest).
 */
internal object DownloadRunEnd {

    /**
     * True when the parked pick of [kind]/[key] must be left alone because
     * the same model was picked again after this run was cancelled, or after
     * Android stopped it, and that new request waits in [queued]. The retry
     * decides the pick when it ends. Dropping it here, as a cancelled run's
     * end otherwise does, left the retry finishing with no pick to apply, so
     * the old model stayed in use while the user believed the new one was.
     */
    fun leaveToRetry(
        cancelled: Boolean,
        kind: String,
        key: String,
        queued: Collection<Pair<String, String>>
    ): Boolean = cancelled && queued.any { it.first == kind && it.second == key }

    /**
     * The error reported for a run that ended with [error]: Android's
     * explanation ([timeoutMessage]) when its daily time limit stopped the
     * run, none when the user cancelled it (a stalled read that the cancel
     * broke ends in a socket error the user does not need to hear about),
     * otherwise [error] as it is.
     */
    fun reportedError(
        userCancelled: Boolean,
        timedOut: Boolean,
        error: String?,
        timeoutMessage: String
    ): String? = when {
        timedOut -> timeoutMessage
        userCancelled -> null
        else -> error
    }
}
