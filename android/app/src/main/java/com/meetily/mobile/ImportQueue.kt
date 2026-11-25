package com.meetily.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.whisper.VideoAudio
import com.meetily.mobile.whisper.VideoAudioExtractor
import java.util.UUID

/**
 * Parks an audio file the user asked to import while something heavy was
 * already running.
 *
 * The audio is COPIED into app storage before the job is queued. A queued job
 * that only remembered the content URI would usually find nothing there when
 * it finally ran: the read grant belongs to the activity that received the
 * share, and dies with it. AudioFileImporter already copies for the same
 * reason — this just moves that copy earlier, and the importer adopts the
 * staged file instead of copying it a second time. A video is not copied:
 * only its sound is kept (VideoAudioExtractor), and that is what is queued.
 */
object ImportQueue {

    /**
     * CANCELLED: the caller abandoned it mid-copy; nothing was queued or kept.
     * NO_SOUND: the file opened but has no audio track to transcribe.
     */
    enum class Result { QUEUED, TOO_MANY, FAILED, CANCELLED, NO_SOUND }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Batches whose run the user cancelled with "Cancel all" while their
     * files were still being staged (ImportService.cancelBatch): the files
     * not staged yet come back CANCELLED instead of joining the queue
     * behind the cancel. Removed when the batch's staging ends; an id added
     * after that is a few dozen bytes for the life of the process.
     */
    private val stoppedBatches: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Stops staging the rest of batch [batchId]; see [stoppedBatches]. */
    fun stopBatch(batchId: String) {
        if (batchId.isNotBlank()) stoppedBatches.add(batchId)
    }

    /**
     * Copies [uri] and queues it. Blocking work happens on a worker thread;
     * [onResult] is delivered on the main thread.
     *
     * [cancelled] is polled during the copy and once more before the job is
     * queued. Cancel on the "copying to the queue" screen used to reach
     * whatever ImportService was running instead — the very job this file
     * was waiting behind — while this file was queued anyway.
     */
    fun stageAndQueue(
        context: Context,
        uri: Uri,
        sourceName: String,
        modelKey: String,
        cancelled: () -> Boolean = { false },
        /** Spoken language chosen for this file; see JobQueue.Job.language. */
        language: String = "",
        onResult: (Result) -> Unit
    ) {
        val app = context.applicationContext
        if (JobQueue.importCount(JobQueue.load(app)) >= JobQueue.MAX_IMPORTS) {
            onResult(Result.TOO_MANY)
            return
        }
        Thread {
            val result = stage(app, uri, sourceName, modelKey, cancelled, language)
            main.post { onResult(result) }
        }.apply {
            name = "import-stage"
            start()
        }
    }

    /** One file of a batch: where it came from and what it is called. */
    class BatchFile(val uri: Uri, val sourceName: String)

    /**
     * Stages and queues [files] one after another on one worker thread, each
     * as "file i of n" of the batch (JobQueue.Job.batchIndex) so its run's
     * progress says which it is. [onFile] is told (on the main thread) before
     * each file is staged, [onResults] once with every file's result, in
     * order. The import cap is checked again for each file: one the queue is
     * full for comes back TOO_MANY. Each file is queued as soon as it is
     * staged and the queue drained, so the first starts while the rest are
     * still being copied. After [cancelled], or [stopBatch] for this batch,
     * files not yet staged come back CANCELLED. The jobs share one batch id
     * (JobQueue.Job.batchId).
     */
    fun stageBatch(
        context: Context,
        files: List<BatchFile>,
        modelKey: String,
        language: String,
        cancelled: () -> Boolean,
        onFile: (index: Int) -> Unit,
        onResults: (List<Result>) -> Unit
    ) {
        val app = context.applicationContext
        val batchId = UUID.randomUUID().toString()
        val stopped = { cancelled() || batchId in stoppedBatches }
        Thread {
            val results = ArrayList<Result>(files.size)
            for ((i, file) in files.withIndex()) {
                if (stopped()) {
                    results.add(Result.CANCELLED)
                    continue
                }
                main.post { onFile(i) }
                val result =
                    if (JobQueue.importCount(JobQueue.load(app)) >= JobQueue.MAX_IMPORTS) {
                        Result.TOO_MANY
                    } else {
                        stage(
                            app, file.uri, file.sourceName, modelKey, stopped, language,
                            batchIndex = i + 1, batchSize = files.size, batchId = batchId
                        )
                    }
                results.add(result)
                // Nothing may be running yet: the first queued file starts
                // now rather than once the whole batch is copied.
                if (result == Result.QUEUED) main.post { JobGate.drain(app) }
            }
            stoppedBatches.remove(batchId)
            main.post { onResults(results) }
        }.apply {
            name = "import-stage-batch"
            start()
        }
    }

    /**
     * Copies [uri] into AudioStore and queues it; blocking. A video keeps
     * only its sound, remuxed, when its audio track allows that
     * (VideoAudioExtractor); otherwise, or when the remux fails, the file is
     * copied as it is.
     */
    private fun stage(
        app: Context,
        uri: Uri,
        sourceName: String,
        modelKey: String,
        cancelled: () -> Boolean,
        language: String,
        batchIndex: Int = 0,
        batchSize: Int = 0,
        batchId: String = ""
    ): Result {
        // Outside the try so a copy that dies part-way (a dropped cloud
        // stream, a full disk) is deleted: no job ever names it.
        var partial: java.io.File? = null
        return try {
            val stagedId = "queued-" + UUID.randomUUID()
            val keep = VideoAudioExtractor.planFor(app, uri)
            if (keep == VideoAudio.Keep.NO_SOUND) return Result.NO_SOUND
            var staged: java.io.File? = null
            if (keep.remuxes) {
                val sound = AudioStore.newImportFile(
                    app, stagedId, VideoAudio.storedName(sourceName, keep)
                )
                partial = sound
                // A failed remux has already deleted its file.
                if (VideoAudioExtractor.extract(app, uri, keep, sound, cancelled) {}) {
                    staged = sound
                } else if (cancelled()) {
                    return Result.CANCELLED
                }
            }
            var aborted = false
            val kept = staged ?: AudioStore.newImportFile(app, stagedId, sourceName).also { copy ->
                partial = copy
                app.contentResolver.openInputStream(uri)?.use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) {
                                aborted = true
                                break
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (aborted || cancelled()) {
                kept.delete()
                Result.CANCELLED
            } else if (kept.length() <= 0L) {
                kept.delete()
                Result.FAILED
            } else {
                JobQueue.enqueue(
                    app,
                    JobQueue.Job(
                        kind = JobQueue.KIND_IMPORT,
                        meetingId = "",
                        payload = modelKey,
                        queuedAtMs = System.currentTimeMillis(),
                        stagedFile = kept.name,
                        sourceName = sourceName,
                        language = language,
                        batchIndex = batchIndex,
                        batchSize = batchSize,
                        batchId = batchId
                    )
                )
                Result.QUEUED
            }
        } catch (_: Throwable) {
            partial?.delete()
            Result.FAILED
        }
    }
}
