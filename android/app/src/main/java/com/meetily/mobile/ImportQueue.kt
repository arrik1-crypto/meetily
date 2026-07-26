package com.meetily.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
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
 * staged file instead of copying it a second time.
 */
object ImportQueue {

    enum class Result { QUEUED, TOO_MANY, FAILED }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Copies [uri] and queues it. Blocking work happens on a worker thread;
     * [onResult] is delivered on the main thread.
     */
    fun stageAndQueue(
        context: Context,
        uri: Uri,
        sourceName: String,
        modelKey: String,
        onResult: (Result) -> Unit
    ) {
        val app = context.applicationContext
        if (JobQueue.importCount(JobQueue.load(app)) >= JobQueue.MAX_IMPORTS) {
            onResult(Result.TOO_MANY)
            return
        }
        Thread {
            val result = try {
                val staged = AudioStore.newImportFile(
                    app, "queued-" + UUID.randomUUID(), sourceName
                )
                app.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                }
                if (staged.length() <= 0L) {
                    staged.delete()
                    Result.FAILED
                } else {
                    JobQueue.enqueue(
                        app,
                        JobQueue.Job(
                            kind = JobQueue.KIND_IMPORT,
                            meetingId = "",
                            payload = modelKey,
                            queuedAtMs = System.currentTimeMillis(),
                            stagedFile = staged.name,
                            sourceName = sourceName
                        )
                    )
                    Result.QUEUED
                }
            } catch (_: Throwable) {
                Result.FAILED
            }
            main.post { onResult(result) }
        }.apply {
            name = "import-stage"
            start()
        }
    }
}
