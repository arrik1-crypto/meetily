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
 * sound as they want it.
 *
 * The ringer: RecordingService mutes the system and notification streams,
 * which ring with the ringer, only while the ringer is on normal, and on
 * many phones muting them moves the ringer to vibrate or silent. A process
 * that dies muted leaves it there. So the record also holds the ringer mode
 * before the mute and the one right after; at the next start, a ringer still
 * where Recap left it goes back to normal before those streams are unmuted.
 * A ringer the user has moved since is theirs: it stays, and a ring-aliased
 * stream is unmuted only once the ringer is on normal, where that cannot
 * switch the phone from vibrate or silent to ringing.
 *
 * Pure Kotlin, unit-tested.
 */
object MutedStreams {

    /** How long a record is honoured after the mute it describes. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** AudioManager.RINGER_MODE_NORMAL. */
    const val RINGER_NORMAL = 2

    /**
     * [streams] (AudioManager.STREAM_*) Recap muted at [mutedAtMs], wall
     * clock. When muting them moved the ringer, [ringerBefore] and
     * [ringerAfter] (AudioManager.RINGER_MODE_*) are where it was and where
     * the mute left it; both null otherwise.
     */
    data class Record(
        val mutedAtMs: Long,
        val streams: List<Int>,
        val ringerBefore: Int? = null,
        val ringerAfter: Int? = null
    ) {
        /** Whether the mute moved the ringer, as far as this record knows. */
        val movedRinger: Boolean
            get() = ringerBefore != null && ringerAfter != null && ringerBefore != ringerAfter
    }

    /**
     * The record for a mute that left [streams] muted. [ringerBefore] and
     * [ringerAfter] are the ringer mode before and after it, or -1 where
     * unknown or untouched; they are kept only when they say it moved.
     */
    fun record(mutedAtMs: Long, streams: List<Int>, ringerBefore: Int, ringerAfter: Int): Record {
        val moved = ringerBefore >= 0 && ringerAfter >= 0 && ringerBefore != ringerAfter
        return Record(
            mutedAtMs, streams.distinct(),
            ringerBefore.takeIf { moved }, ringerAfter.takeIf { moved }
        )
    }

    /** "1700000000000:1,5,3", then ":2>1" when the ringer moved from 2 to 1. */
    fun encode(record: Record): String {
        val base = record.mutedAtMs.toString() + ":" + record.streams.joinToString(",")
        return if (record.movedRinger) {
            base + ":" + record.ringerBefore + ">" + record.ringerAfter
        } else {
            base
        }
    }

    /** Null for anything [encode] did not write, or a record of nothing. */
    fun decode(text: String?): Record? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        val at = parts[0].toLongOrNull() ?: return null
        val streams = parts[1].split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toIntOrNull() ?: return null }
            .distinct()
        if (streams.isEmpty()) return null
        if (parts.size == 2) return Record(at, streams)
        val modes = parts[2].split('>')
        if (modes.size != 2) return null
        val before = modes[0].toIntOrNull() ?: return null
        val after = modes[1].toIntOrNull() ?: return null
        if (before < 0 || after < 0 || before == after) return null
        return Record(at, streams, before, after)
    }

    /**
     * What to keep written down after a restore: the streams still muted,
     * because the unmute was ignored (or has not landed yet, which a later
     * [toRestore] then finds unmuted and drops).
     */
    fun remaining(streams: List<Int>, isMuted: (Int) -> Boolean): List<Int> =
        streams.distinct().filter(isMuted)

    /**
     * The record once a session has muted what [new] says, with whatever
     * [old] still holds from before (streams waiting for a normal ringer)
     * kept beside it. The ringer move is the new mute's, else the old one's.
     */
    fun adding(old: Record?, new: Record): Record {
        if (old == null) return new
        val streams = (new.streams + old.streams).distinct()
        return if (new.movedRinger || !old.movedRinger) {
            new.copy(streams = streams)
        } else {
            new.copy(streams = streams, ringerBefore = old.ringerBefore, ringerAfter = old.ringerAfter)
        }
    }

    /**
     * The record once a session has unmuted [handled] itself, with the
     * ringer now at [ringerMode]: those still muted stay (an unmute Android
     * ignored), beside what [old] held from before; the ringer move stays
     * only while the ringer is still where the mute left it (the session's
     * own put-back was ignored too). Null when nothing is left.
     */
    fun settled(
        old: Record?,
        handled: List<Int>,
        isMuted: (Int) -> Boolean,
        ringerMode: Int,
        nowMs: Long
    ): Record? {
        val others = old?.streams.orEmpty().filter { it !in handled }
        val streams = (remaining(handled, isMuted) + others).distinct()
        if (streams.isEmpty()) return null
        val base = old ?: Record(nowMs, streams)
        val keepRinger = base.movedRinger && ringerMode == base.ringerAfter
        return Record(
            base.mutedAtMs, streams,
            base.ringerBefore.takeIf { keepRinger }, base.ringerAfter.takeIf { keepRinger }
        )
    }

    /**
     * What a later start does: set the ringer to [ringer] first when not
     * null, then unmute [unmute]; then write [keep] down in the record's
     * place, or forget it when null.
     */
    data class Restore(val ringer: Int?, val unmute: List<Int>, val keep: Record?)

    private val NOTHING = Restore(null, emptyList(), null)

    /**
     * The restore for [record] at [nowMs], with the ringer now at
     * [ringerMode] (-1 when unknown). Nothing for no record or one older
     * than [MAX_AGE_MS]. A ringer the mute moved and nobody has touched
     * since goes back first, and the ring-aliased streams with it; with the
     * ringer anywhere else, only streams that do not ring with it are
     * unmuted, and the ring-aliased ones still muted stay written down
     * (without the ringer move, which is no longer Recap's) for a start that
     * finds the ringer on normal.
     */
    fun toRestore(
        record: Record?,
        nowMs: Long,
        isMuted: (Int) -> Boolean,
        ringerMode: Int,
        ringAliased: (Int) -> Boolean
    ): Restore {
        if (record == null) return NOTHING
        val age = nowMs - record.mutedAtMs
        // A clock set back makes the age meaningless; treat it as stale.
        if (age < 0 || age > MAX_AGE_MS) return NOTHING
        val streams = record.streams.distinct()
        val ringerBack = record.movedRinger && ringerMode == record.ringerAfter &&
            streams.any(ringAliased)
        val ringer = if (ringerBack) record.ringerBefore else null
        val ringerNormal = (ringer ?: ringerMode) == RINGER_NORMAL
        val muted = streams.filter(isMuted)
        val unmute = muted.filter { ringerNormal || !ringAliased(it) }
        val waiting = muted.filter { !ringerNormal && ringAliased(it) }
        val keep = if (waiting.isEmpty()) null else Record(record.mutedAtMs, waiting)
        return Restore(ringer, unmute, keep)
    }
}
