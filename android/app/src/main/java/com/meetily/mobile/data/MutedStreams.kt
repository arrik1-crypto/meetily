package com.meetily.mobile.data

/**
 * The system sounds a recording muted, remembered until they are put back.
 *
 * With the system speech recognizer and "mute recognizer sounds" on,
 * RecordingService mutes the system, notification and media streams so the
 * recognizer's start and stop beeps stay quiet, and unmutes them as the
 * session ends. Two things can leave them muted:
 *
 *  - The process dies first (a crash, a kill), so nothing unmutes them.
 *  - Android 17's background audio hardening, which applies whatever the
 *    app targets: a volume or mute change from an app with neither a
 *    visible activity nor a running foreground service is silently ignored.
 *    The normal ends of a session restore before the service leaves the
 *    foreground, but the onDestroy safety net runs after it has.
 *
 * So the streams are written down as they are muted, and a stream still
 * muted after a restore stays written down. The next recording, or the
 * next time the app is on screen, puts those back ([toRestore]).
 *
 * Only streams Recap itself muted are ever written down: one the user had
 * muted already is skipped at mute time and never appears here, so nothing
 * here can unmute it. A record older than [MAX_AGE_MS] is dropped unread,
 * because by then the user has had every chance to notice and to set the
 * sound as they want it. The ringer is never touched here: a ring-aliased
 * stream is put back only while the ringer is on normal, where unmuting it
 * cannot switch the phone from vibrate or silent to ringing.
 *
 * Pure Kotlin, unit-tested.
 */
object MutedStreams {

    /** How long a record is honoured after the mute it describes. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** [streams] (AudioManager.STREAM_*) Recap muted at [mutedAtMs], wall clock. */
    data class Record(val mutedAtMs: Long, val streams: List<Int>)

    /** "1700000000000:1,5,3" */
    fun encode(record: Record): String =
        record.mutedAtMs.toString() + ":" + record.streams.joinToString(",")

    /** Null for anything [encode] did not write, or a record of nothing. */
    fun decode(text: String?): Record? {
        if (text.isNullOrBlank()) return null
        val at = text.substringBefore(':', "").toLongOrNull() ?: return null
        val streams = text.substringAfter(':', "").split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toIntOrNull() ?: return null }
            .distinct()
        if (streams.isEmpty()) return null
        return Record(at, streams)
    }

    /**
     * What to keep written down after a restore: the streams still muted,
     * because the unmute was ignored (or has not landed yet, which a later
     * [toRestore] then finds unmuted and drops).
     */
    fun remaining(streams: List<Int>, isMuted: (Int) -> Boolean): List<Int> =
        streams.distinct().filter(isMuted)

    /**
     * The streams to unmute at a later start: those in [record] that are
     * still muted, recorded within [MAX_AGE_MS] of [nowMs], and, for a
     * ring-aliased one, only while the ringer is on normal.
     */
    fun toRestore(
        record: Record?,
        nowMs: Long,
        isMuted: (Int) -> Boolean,
        ringerNormal: Boolean,
        ringAliased: (Int) -> Boolean
    ): List<Int> {
        if (record == null) return emptyList()
        val age = nowMs - record.mutedAtMs
        // A clock set back makes the age meaningless; treat it as stale.
        if (age < 0 || age > MAX_AGE_MS) return emptyList()
        return record.streams.distinct().filter { stream ->
            isMuted(stream) && (ringerNormal || !ringAliased(stream))
        }
    }
}
