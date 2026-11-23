package com.meetily.mobile

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.export.LibraryExport
import com.meetily.mobile.summarize.AiDisclaimers
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs "Export library as Markdown" (LibraryExport) into a folder the user
 * picked with the system's folder picker (Settings, Data).
 *
 * One run at a time, on its own thread, owned by the process rather than by
 * the Settings screen: a rotation or leaving Settings (Back, Up, Save, Home)
 * does not stop it, and the screen shows its progress again when it comes
 * back ([listener]). The picker's access to the folder belongs to the
 * Settings screen that received it, and Android takes it back when that
 * screen closes, so the run holds the access itself while it lasts
 * ([holdFolder]) and gives it back at the end. It
 * is short-lived work like the backup export beside it (text files only,
 * no audio), so it runs as plain background work, the way that export
 * does, rather than as a foreground service: no new service type, no
 * notification. If the app goes to the background, Android may pause the
 * process until it returns; files already written stay.
 *
 * Meetings are read one at a time from the store, newest first. A meeting
 * that a recording, import, accuracy check or summary is writing right now
 * (AudioRemoval.isWritingContent) is left out and counted, so no
 * half-written meeting is exported. One whose clear-voice copy is being
 * made is not: the copy never touches the text this writes. Each file is
 * created new in the folder (names that are already there are avoided) and
 * nothing in the folder is overwritten or deleted, except a file this run
 * created and then failed to fill.
 *
 * Cancel stops between meetings. Every file written stays. The final count
 * (written, left out, failed) is shown on the Settings screen, or as a
 * toast when no screen is listening.
 */
object LibraryExportRunner {

    /** What a finished run did. */
    data class Result(
        /** Meetings in the library when the run started. */
        val total: Int,
        val written: Int,
        /** Left out because a recording or job was writing them. */
        val busy: Int,
        /** Could not be written (the folder refused the file). */
        val failed: Int,
        val cancelled: Boolean,
        /** The folder stopped accepting files and the run gave up. */
        val folderFailed: Boolean
    )

    /**
     * Where the run is. [total] is 0 while the library is being listed;
     * [result] is the last finished run's, kept until the next one starts.
     */
    data class State(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val cancelling: Boolean,
        val result: Result?
    )

    /** Hears each change of [State], on the main thread. */
    fun interface Listener {
        fun onLibraryExport(state: State)
    }

    /** Markdown, as the meeting's own export creates it. */
    private const val MIME_TYPE = "text/markdown"

    /** Give up after this many files in a row fail: the folder itself has gone. */
    private const val MAX_FAILURES_IN_A_ROW = 3

    /** The access the folder picker grants, and the run holds. */
    private const val FOLDER_ACCESS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Remembers the folder a run holds, so a run the process did not finish can give it back. */
    private const val PREFS = "meetily_library_export"
    private const val KEY_HELD_FOLDER = "held_folder"

    /** Written on the main thread only. */
    private var state = State(running = false, done = 0, total = 0, cancelling = false, result = null)

    private val cancel = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** The screen showing progress, if any. Main thread only. */
    @Volatile
    var listener: Listener? = null

    @MainThread
    fun state(): State = state

    /**
     * Starts exporting every meeting into the folder [tree] (a document
     * tree URI from the folder picker). False when a run is already going.
     */
    @MainThread
    fun start(context: Context, tree: Uri): Boolean {
        if (state.running) return false
        val app = context.applicationContext
        val held = holdFolder(app, tree)
        cancel.set(false)
        state = State(running = true, done = 0, total = 0, cancelling = false, result = null)
        Thread({ run(app, tree, held) }, "library-export").start()
        return true
    }

    /**
     * Keeps write access to [tree] for the length of the run. The picker
     * grants it to the Settings screen that asked, and Android revokes it
     * when that screen finishes (Back, Up or Save), while this run still
     * has files to create there. A persisted grant is the app's own, so it
     * outlives the screen; [releaseFolder] gives it back when the run ends,
     * so the export stays one-shot. Called while the picker's grant is
     * still held (from its result). A grant the app already held for the
     * folder is left alone and not given back; one left by a run the
     * process did not live to finish is given back first. True when the
     * grant was taken here.
     */
    @MainThread
    private fun holdFolder(app: Context, tree: Uri): Boolean {
        val resolver = app.contentResolver
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = prefs.getString(KEY_HELD_FOLDER, null)
        if (left != null && left != tree.toString()) releaseFolder(app, Uri.parse(left))
        val heldAlready = left != tree.toString() && try {
            resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        } catch (_: Exception) {
            false
        }
        if (heldAlready) return false
        return try {
            resolver.takePersistableUriPermission(tree, FOLDER_ACCESS)
            prefs.edit().putString(KEY_HELD_FOLDER, tree.toString()).apply()
            true
        } catch (_: Exception) {
            // Not persistable: the run goes on with the picker's grant,
            // which lasts while Settings stays open.
            false
        }
    }

    /** Gives back the grant [holdFolder] took for [tree]. */
    private fun releaseFolder(app: Context, tree: Uri) {
        try {
            app.contentResolver.releasePersistableUriPermission(tree, FOLDER_ACCESS)
        } catch (_: Exception) {
            // Already gone: revoked, or the folder's app was removed.
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HELD_FOLDER).apply()
    }

    /** Asks the run to stop after the meeting it is writing. */
    @MainThread
    fun cancel() {
        if (!state.running || state.cancelling) return
        cancel.set(true)
        publish(state.copy(cancelling = true))
    }

    /** The finished run as one sentence or a few, for the screen and the toast. */
    fun message(context: Context, result: Result): String {
        val res = context.resources
        val out = StringBuilder()
        out.append(
            when {
                result.folderFailed -> res.getQuantityString(
                    R.plurals.export_library_folder_failed, result.written, result.written
                )
                result.cancelled -> res.getQuantityString(
                    R.plurals.export_library_stopped, result.written, result.written
                )
                result.total == 0 -> context.getString(R.string.export_library_none)
                else -> res.getQuantityString(R.plurals.export_library_done, result.written, result.written)
            }
        )
        if (result.busy > 0) {
            out.append(' ').append(
                res.getQuantityString(R.plurals.export_library_busy, result.busy, result.busy)
            )
        }
        if (result.failed > 0) {
            out.append(' ').append(
                res.getQuantityString(R.plurals.export_library_failed, result.failed, result.failed)
            )
        }
        return out.toString()
    }

    @MainThread
    private fun publish(next: State) {
        state = next
        listener?.onLibraryExport(next)
    }

    @WorkerThread
    private fun run(app: Context, tree: Uri, held: Boolean) {
        var total = 0
        var written = 0
        var busy = 0
        var failed = 0
        var folderFailed = false
        try {
            val resolver = app.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            val taken = existingNames(resolver, DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId))
            val store = MeetingStore(app)
            // Ids only: each meeting is read in full when its turn comes.
            val ids = store.listPartial(setOf("createdAtMs")).map { it.id }
            total = ids.size
            post(total, 0)
            val zone = ZoneId.systemDefault()
            val throttle = ProgressThrottle(250L)
            var failuresInARow = 0
            for ((index, id) in ids.withIndex()) {
                if (cancel.get()) break
                if (AudioRemoval.isWritingContent(app, id)) {
                    busy++
                } else {
                    // Gone since the list was read: deleted, so not part of the library.
                    val meeting = store.load(id)
                    if (meeting != null) {
                        val notices = AiDisclaimers.texts(app, AiDisclaimers.categories(meeting))
                        val name = LibraryExport.fileName(meeting, zone, taken)
                        val text = LibraryExport.document(meeting, notices, zone)
                        if (writeFile(resolver, folder, name, text)) {
                            written++
                            failuresInARow = 0
                        } else {
                            failed++
                            failuresInARow++
                            if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                                folderFailed = true
                                break
                            }
                        }
                    }
                }
                val done = index + 1
                if (throttle.shouldPost(done * 100 / total, SystemClock.elapsedRealtime())) {
                    post(total, done)
                }
            }
        } catch (e: Exception) {
            // The folder could not be read or written at all (access lost,
            // provider gone).
            folderFailed = true
        } finally {
            if (held) releaseFolder(app, tree)
            val result = Result(total, written, busy, failed, cancel.get() && !folderFailed, folderFailed)
            main.post { finish(app, result) }
        }
    }

    @WorkerThread
    private fun post(total: Int, done: Int) {
        main.post {
            if (state.running) publish(state.copy(total = total, done = done))
        }
    }

    @MainThread
    private fun finish(app: Context, result: Result) {
        val next = State(running = false, done = result.total, total = result.total, cancelling = false, result = result)
        state = next
        val screen = listener
        if (screen != null) {
            screen.onLibraryExport(next)
        } else {
            Toast.makeText(app, message(app, result), Toast.LENGTH_LONG).show()
        }
    }

    /** Lowercased names already in the folder, so the export does not collide with them. */
    @WorkerThread
    private fun existingNames(resolver: ContentResolver, children: Uri): MutableSet<String> {
        val names = HashSet<String>()
        try {
            resolver.query(
                children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names.add(it.lowercase(Locale.ROOT)) }
                }
            }
        } catch (_: Exception) {
            // Best effort: the provider renames a clash itself.
        }
        return names
    }

    /**
     * Creates [name] in [folder] and writes [text] into it. A file created
     * but not filled is deleted again, so a failure leaves nothing behind.
     */
    @WorkerThread
    private fun writeFile(resolver: ContentResolver, folder: Uri, name: String, text: String): Boolean {
        val document = try {
            DocumentsContract.createDocument(resolver, folder, MIME_TYPE, name)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val out = resolver.openOutputStream(document) ?: throw java.io.IOException("no stream")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, document)
            } catch (_: Exception) {
            }
            false
        }
    }
}
