package com.meetily.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable queue of heavy jobs that could not run when they were asked for.
 *
 * Two things land here:
 *  - DEFERRED work, because the user asked for it to wait for a charger, or
 *    because something heavy was already running (see JobGate).
 *  - INTERRUPTED work, written when a run starts and cleared when it ends,
 *    so a run killed by a force-stop or an OOM leaves a trace behind
 *    instead of vanishing.
 *
 * Deferred jobs drain on their own. Interrupted ones never do: the user
 * closed the app, possibly deliberately, and silently restarting minutes of
 * inference they thought they had killed would be its own bug. Those are
 * offered back instead.
 */
object JobQueue {

    const val KIND_CHECK = "check"
    const val KIND_SUMMARY = "summary"

    /**
     * A file the user asked to import while something else was running. The
     * audio is copied into app storage before queueing: the caller's
     * content-URI grant dies with the sharing activity, so a queued job that
     * only remembered the URI would find nothing there by the time it ran.
     */
    const val KIND_IMPORT = "import"

    /**
     * A week away from a charger should not detonate on plug-in. Oldest
     * optional entries fall off the back — see [trimmable] for which ones
     * never do.
     */
    const val MAX_JOBS = 5

    /**
     * Imports are user-initiated, so they are never silently dropped to make
     * room — losing one is the exact bug the queue exists to fix. This is a
     * sanity ceiling, refused at the door rather than trimmed from behind.
     */
    const val MAX_IMPORTS = 20

    data class Job(
        val kind: String,
        /** Blank for an import: there is no meeting until it runs. */
        val meetingId: String,
        /** Model key for a check or an import, template key for a summary. */
        val payload: String = "",
        val queuedAtMs: Long = 0L,
        /** Started but never finished, rather than deliberately deferred. */
        val interrupted: Boolean = false,
        /** Import only: the staged copy in AudioStore, and its display name. */
        val stagedFile: String = "",
        val sourceName: String = "",
        /**
         * The user asked for this to wait for a charger.
         *
         * It has to live on the job, not just at the call that queued it.
         * Without it the preference held only until the next drain trigger:
         * the job queued correctly off-charger, and then opening the app —
         * still on battery — drained it anyway, which is precisely what the
         * setting exists to prevent.
         */
        val chargingOnly: Boolean = false,
        /**
         * Check only: this pass is the meeting's FIRST transcript, not a
         * second opinion on one.
         *
         * Recomputing that at start from "the meeting has no words" is right
         * the first time and wrong after an unplug stop: the stopped pass
         * kept its partial words, so the resumed one looked like an accuracy
         * check, staged the rest for review, and never started the summary.
         */
        val firstTranscript: Boolean = false,
        /**
         * Never trimmed to make room: a recording's only transcript, or work
         * the user asked for by hand. Dropping either loses something nobody
         * agreed to lose, with no sign it happened.
         */
        val required: Boolean = false,
        /**
         * Summary only: queued by AutoSummary rather than by the user, so the
         * consent it was queued under has to still hold when it runs — see
         * JobGate.drain.
         */
        val auto: Boolean = false,
        /**
         * Check or import: the spoken language chosen for this one run ("de",
         * or "auto" to detect even when Settings pins one). Blank means no
         * choice was made and the Settings value applies when it runs.
         */
        val language: String = "",
        /**
         * Check only: the audio offset (ms) this job continues an unfinished
         * transcription from, recorded when a pass was stopped part-way
         * (an unplug requeue, or the in-flight marker of a continuing run).
         * 0 means from the beginning, which is what every job queued before
         * this field did. The importer takes the exact point from what is
         * stored with the words (Meeting.transcriptResumeMs, or a staged
         * check's draft), so this is the job's own record of it: what the
         * queue and its notices show, and how JobGate tells a continuation
         * whose transcript has been finished since. See TranscriptResume.
         */
        val resumeFromMs: Long = 0L,
        /**
         * Import only: this file is number [batchIndex] (from 1) of
         * [batchSize] handed over together (ImportQueue.stageBatch), so its
         * run's progress says "File 2 of 5". 0 for a file on its own, which
         * is what every job queued before these fields was.
         */
        val batchIndex: Int = 0,
        val batchSize: Int = 0
    )

    /**
     * The work a job refers to. Imports share a blank meeting id, so the
     * staged file is what tells them apart — without it, queueing a second
     * file would silently replace the first.
     */
    private fun sameWork(a: Job, b: Job): Boolean =
        a.kind == b.kind && a.meetingId == b.meetingId && a.stagedFile == b.stagedFile

    /**
     * Queue identity. The in-flight marker and a deferred request for the
     * same work are DIFFERENT entries: a check queued for a meeting whose
     * check was already running used to replace the marker, and the running
     * pass's finish then removed the request the user had just been told
     * was queued.
     */
    private fun sameJob(a: Job, b: Job): Boolean =
        sameWork(a, b) && a.interrupted == b.interrupted

    /**
     * Whether [job] may be dropped to honour [MAX_JOBS]. Only optional
     * automatic passes are. In-flight markers do not count at all: trimming
     * one would lose the crash-recovery trace of a run that is still going.
     */
    private fun trimmable(job: Job): Boolean =
        job.kind != KIND_IMPORT && !job.interrupted && !job.required && !job.firstTranscript

    // --- Pure list rules (unit tested) -------------------------------------

    /**
     * Adds [job], replacing any existing entry with the same identity so a
     * meeting can never queue twice, and trimming to [max].
     *
     * A run marker (interrupted) also supersedes the deferred entry for the
     * same work: that run IS the deferred request, now started. A deferred
     * entry added while a marker exists sits beside it — see [sameJob].
     */
    fun add(existing: List<Job>, job: Job, max: Int = MAX_JOBS): List<Job> {
        val grown = existing.filterNot {
            if (job.interrupted) sameWork(it, job) else sameJob(it, job)
        } + job
        // Only the optional jobs are trimmed; see [trimmable] and MAX_IMPORTS.
        val optional = grown.filter { trimmable(it) }
        if (optional.size <= max) return grown
        val dropped = optional.take(optional.size - max).toSet()
        return grown.filterNot { it in dropped }
    }

    /**
     * The job that puts back a pass the charger stopped, still waiting for
     * power, to carry on from [resumeFromMs]. Never trimmed when it is a
     * recording's only transcript, or when it carries on a check whose
     * partial draft was kept for it: dropped, that draft would stay behind
     * with nothing to finish or remove it.
     */
    fun chargingRequeue(
        meetingId: String,
        modelKey: String,
        nowMs: Long,
        firstTranscript: Boolean,
        language: String,
        resumeFromMs: Long
    ): Job = Job(
        KIND_CHECK, meetingId, modelKey, nowMs,
        chargingOnly = true,
        firstTranscript = firstTranscript,
        required = firstTranscript || resumeFromMs > 0L,
        language = language,
        resumeFromMs = resumeFromMs
    )

    fun remove(existing: List<Job>, kind: String, meetingId: String): List<Job> =
        existing.filterNot { it.kind == kind && it.meetingId == meetingId }

    /** Removes only the in-flight marker, leaving any request queued behind it. */
    fun removeInterrupted(existing: List<Job>, kind: String, meetingId: String): List<Job> =
        existing.filterNot { it.kind == kind && it.meetingId == meetingId && it.interrupted }

    /** Removes only the deferred request, leaving any in-flight marker. */
    fun removeDeferred(existing: List<Job>, kind: String, meetingId: String): List<Job> =
        existing.filterNot { it.kind == kind && it.meetingId == meetingId && !it.interrupted }

    /** Removes one queued import by its staged file. */
    fun removeStaged(existing: List<Job>, stagedFile: String): List<Job> =
        existing.filterNot { it.kind == KIND_IMPORT && it.stagedFile == stagedFile }

    fun importCount(existing: List<Job>): Int =
        existing.count { it.kind == KIND_IMPORT }

    /** Deferred work, oldest first — what the drain walks. */
    fun runnable(existing: List<Job>): List<Job> = existing.filterNot { it.interrupted }

    /**
     * The next job that may start given the current power state, or null.
     *
     * A charging-only job off power is SKIPPED, not blocking: an accuracy
     * check waiting for a charger must not hold up a summary the user asked
     * to run at the end of the meeting, or an import they just picked.
     */
    fun nextRunnable(existing: List<Job>, charging: Boolean): Job? =
        runnable(existing).firstOrNull { !it.chargingOnly || charging }

    /** Work that died mid-run and needs the user's say-so to restart. */
    fun interrupted(existing: List<Job>): List<Job> = existing.filter { it.interrupted }

    // --- Persistence --------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences("meetily_jobs", Context.MODE_PRIVATE)

    fun load(context: Context): List<Job> =
        decode(prefs(context).getString("jobs", "[]") ?: "[]")

    fun save(context: Context, jobs: List<Job>) {
        prefs(context).edit().putString("jobs", encode(jobs)).apply()
    }

    /**
     * The stored queue as jobs. Rows written by older versions lack the
     * newer fields and read as their defaults, which is what those jobs did;
     * an unreadable queue reads as empty. Pure, so it is unit-tested.
     */
    fun decode(raw: String): List<Job> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i -> fromJson(array.optJSONObject(i)) }
    } catch (_: Exception) {
        emptyList()
    }

    /** [jobs] as the stored queue; [decode] reads it back. */
    fun encode(jobs: List<Job>): String {
        val array = JSONArray()
        for (job in jobs) array.put(toJson(job))
        return array.toString()
    }

    private fun fromJson(o: JSONObject?): Job? {
        if (o == null) return null
        val kind = o.optString("kind")
        val meetingId = o.optString("meetingId")
        // An import legitimately has no meeting id yet.
        if (kind.isBlank()) return null
        if (meetingId.isBlank() && kind != KIND_IMPORT) return null
        return Job(
            kind = kind,
            meetingId = meetingId,
            payload = o.optString("payload"),
            queuedAtMs = o.optLong("queuedAtMs"),
            interrupted = o.optBoolean("interrupted"),
            stagedFile = o.optString("stagedFile"),
            sourceName = o.optString("sourceName"),
            // Absent in jobs queued before this field existed; false
            // reproduces exactly what those jobs did.
            chargingOnly = o.optBoolean("chargingOnly"),
            // Absent in older rows; false is what those jobs did.
            firstTranscript = o.optBoolean("firstTranscript"),
            required = o.optBoolean("required"),
            auto = o.optBoolean("auto"),
            // Absent in older rows; blank is "use the setting", which
            // is what every run did before the choice existed.
            language = o.optString("language"),
            // Absent in older rows: 0, from the beginning, as they ran.
            resumeFromMs = o.optLong("resumeFromMs").coerceAtLeast(0L),
            // Absent in older rows: 0, not part of a batch.
            batchIndex = o.optInt("batchIndex").coerceAtLeast(0),
            batchSize = o.optInt("batchSize").coerceAtLeast(0)
        )
    }

    private fun toJson(job: Job): JSONObject {
        val o = JSONObject()
            .put("kind", job.kind)
            .put("meetingId", job.meetingId)
            .put("payload", job.payload)
            .put("queuedAtMs", job.queuedAtMs)
            .put("interrupted", job.interrupted)
            .put("stagedFile", job.stagedFile)
            .put("sourceName", job.sourceName)
            .put("chargingOnly", job.chargingOnly)
            .put("firstTranscript", job.firstTranscript)
            .put("required", job.required)
            .put("auto", job.auto)
            .put("language", job.language)
        if (job.resumeFromMs > 0L) o.put("resumeFromMs", job.resumeFromMs)
        if (job.batchSize > 0) {
            o.put("batchIndex", job.batchIndex)
            o.put("batchSize", job.batchSize)
        }
        return o
    }

    @Synchronized
    fun enqueue(context: Context, job: Job) {
        save(context, add(load(context), job))
    }

    @Synchronized
    fun dequeue(context: Context, kind: String, meetingId: String) {
        save(context, remove(load(context), kind, meetingId))
    }

    /** Takes a deferred request off the queue as it starts; see [removeDeferred]. */
    @Synchronized
    fun dequeueDeferred(context: Context, kind: String, meetingId: String) {
        save(context, removeDeferred(load(context), kind, meetingId))
    }

    @Synchronized
    fun dequeueStaged(context: Context, stagedFile: String) {
        save(context, removeStaged(load(context), stagedFile))
    }

    /**
     * Whether [job]'s target still exists. A stat, not a parse: this runs on
     * the main thread from every home-screen resume, and loading each queued
     * meeting just to see that it was there parsed megabytes of JSON for
     * jobs that mostly could not run yet anyway.
     */
    fun targetExists(context: Context, job: Job): Boolean =
        if (job.kind == KIND_IMPORT) {
            AudioStore.exists(context, job.stagedFile)
        } else {
            MeetingStore(context).exists(job.meetingId)
        }

    /**
     * The next job that may start now, or null. The power filter runs before
     * the existence check, so jobs that cannot run yet cost nothing.
     */
    fun nextPending(context: Context, charging: Boolean): Job? =
        runnable(load(context)).firstOrNull {
            (!it.chargingOnly || charging) && targetExists(context, it)
        }

    /** Whether anything queued is waiting for a charger. */
    fun hasChargingOnly(context: Context): Boolean =
        runnable(load(context)).any { it.chargingOnly }

    fun isQueued(context: Context, kind: String, meetingId: String): Boolean =
        load(context).any {
            it.kind == kind && it.meetingId == meetingId && !it.interrupted
        }

    /** Deferred jobs whose target still exists. */
    fun pending(context: Context): List<Job> =
        runnable(load(context)).filter { targetExists(context, it) }

    /**
     * Marks a run as in flight. Cleared by [finished] on every clean exit, so
     * anything still marked on next launch died with the process.
     */
    fun markRunning(
        context: Context,
        kind: String,
        meetingId: String,
        payload: String,
        firstTranscript: Boolean = false,
        /** The run's language choice, so a restart offered later keeps it. */
        language: String = "",
        /** Where the run continues an unfinished transcription from; see [Job.resumeFromMs]. */
        resumeFromMs: Long = 0L
    ) {
        enqueue(
            context,
            Job(
                kind, meetingId, payload, System.currentTimeMillis(), interrupted = true,
                firstTranscript = firstTranscript, language = language,
                resumeFromMs = resumeFromMs
            )
        )
    }

    /**
     * Clears the in-flight marker of a run that ended. Only the marker: a
     * request queued for the same meeting while it ran is a new request.
     */
    @Synchronized
    fun finished(context: Context, kind: String, meetingId: String) {
        save(context, removeInterrupted(load(context), kind, meetingId))
    }

    /**
     * Marks a queued import as in flight, keyed by its staged file.
     *
     * Drain takes the import off the queue before the service starts, and
     * nothing references the staged audio until the first batch writes a
     * meeting — so a process death during model load or that first batch
     * lost the import silently and orphaned its audio. The marker is what
     * lets the next launch offer it back or clean it up.
     */
    fun markImportRunning(
        context: Context,
        stagedFile: String,
        sourceName: String,
        payload: String,
        language: String = ""
    ) {
        enqueue(
            context,
            Job(
                KIND_IMPORT, "", payload, System.currentTimeMillis(), interrupted = true,
                stagedFile = stagedFile, sourceName = sourceName, language = language
            )
        )
    }

    /**
     * Records which meeting a running import is filling. The staged file is
     * renamed after that meeting's id before any meeting is saved, so without
     * this a death in between leaves audio that no entry can name.
     */
    @Synchronized
    fun importAdopted(context: Context, stagedFile: String, meetingId: String) {
        save(
            context,
            load(context).map {
                if (it.kind == KIND_IMPORT && it.interrupted && it.stagedFile == stagedFile) {
                    it.copy(meetingId = meetingId)
                } else {
                    it
                }
            }
        )
    }
}
