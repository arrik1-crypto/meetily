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
     * automatic entries fall off the back.
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
        val sourceName: String = ""
    )

    /**
     * Queue identity. Imports share a blank meeting id, so the staged file is
     * what tells them apart — without it, queueing a second file would
     * silently replace the first.
     */
    private fun sameJob(a: Job, b: Job): Boolean =
        a.kind == b.kind && a.meetingId == b.meetingId && a.stagedFile == b.stagedFile

    // --- Pure list rules (unit tested) -------------------------------------

    /**
     * Adds [job], replacing any existing entry for the same kind+meeting so a
     * meeting can never queue twice, and trimming to [max].
     */
    fun add(existing: List<Job>, job: Job, max: Int = MAX_JOBS): List<Job> {
        val grown = existing.filterNot { sameJob(it, job) } + job
        // Only the automatic jobs are trimmed; see MAX_IMPORTS.
        val automatic = grown.filter { it.kind != KIND_IMPORT }
        if (automatic.size <= max) return grown
        val dropped = automatic.take(automatic.size - max).toSet()
        return grown.filterNot { it in dropped }
    }

    fun remove(existing: List<Job>, kind: String, meetingId: String): List<Job> =
        existing.filterNot { it.kind == kind && it.meetingId == meetingId }

    /** Removes one queued import by its staged file. */
    fun removeStaged(existing: List<Job>, stagedFile: String): List<Job> =
        existing.filterNot { it.kind == KIND_IMPORT && it.stagedFile == stagedFile }

    fun importCount(existing: List<Job>): Int =
        existing.count { it.kind == KIND_IMPORT }

    /** Deferred work, oldest first — what the drain walks. */
    fun runnable(existing: List<Job>): List<Job> = existing.filterNot { it.interrupted }

    /** Work that died mid-run and needs the user's say-so to restart. */
    fun interrupted(existing: List<Job>): List<Job> = existing.filter { it.interrupted }

    // --- Persistence --------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences("meetily_jobs", Context.MODE_PRIVATE)

    fun load(context: Context): List<Job> = try {
        val raw = prefs(context).getString("jobs", "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val kind = o.optString("kind")
            val meetingId = o.optString("meetingId")
            // An import legitimately has no meeting id yet.
            if (kind.isBlank()) return@mapNotNull null
            if (meetingId.isBlank() && kind != KIND_IMPORT) return@mapNotNull null
            Job(
                kind = kind,
                meetingId = meetingId,
                payload = o.optString("payload"),
                queuedAtMs = o.optLong("queuedAtMs"),
                interrupted = o.optBoolean("interrupted"),
                stagedFile = o.optString("stagedFile"),
                sourceName = o.optString("sourceName")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(context: Context, jobs: List<Job>) {
        val array = JSONArray()
        for (job in jobs) {
            array.put(
                JSONObject()
                    .put("kind", job.kind)
                    .put("meetingId", job.meetingId)
                    .put("payload", job.payload)
                    .put("queuedAtMs", job.queuedAtMs)
                    .put("interrupted", job.interrupted)
                    .put("stagedFile", job.stagedFile)
                    .put("sourceName", job.sourceName)
            )
        }
        prefs(context).edit().putString("jobs", array.toString()).apply()
    }

    @Synchronized
    fun enqueue(context: Context, job: Job) {
        save(context, add(load(context), job))
    }

    @Synchronized
    fun dequeue(context: Context, kind: String, meetingId: String) {
        save(context, remove(load(context), kind, meetingId))
    }

    @Synchronized
    fun dequeueStaged(context: Context, stagedFile: String) {
        save(context, removeStaged(load(context), stagedFile))
    }

    fun isQueued(context: Context, kind: String, meetingId: String): Boolean =
        load(context).any {
            it.kind == kind && it.meetingId == meetingId && !it.interrupted
        }

    /** Deferred jobs whose target still exists. */
    fun pending(context: Context): List<Job> {
        val store = MeetingStore(context)
        return runnable(load(context)).filter { job ->
            if (job.kind == KIND_IMPORT) {
                AudioStore.exists(context, job.stagedFile)
            } else {
                store.load(job.meetingId) != null
            }
        }
    }

    /**
     * Marks a run as in flight. Cleared by [finished] on every clean exit, so
     * anything still marked on next launch died with the process.
     */
    fun markRunning(context: Context, kind: String, meetingId: String, payload: String) {
        enqueue(
            context,
            Job(kind, meetingId, payload, System.currentTimeMillis(), interrupted = true)
        )
    }

    fun finished(context: Context, kind: String, meetingId: String) {
        dequeue(context, kind, meetingId)
    }
}
