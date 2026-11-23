package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.meetily.mobile.data.MeetingTrash
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Deleting into Recently deleted (MeetingTrash) from the screens: the move
 * itself with the rule deleting has always had, the undo that follows it,
 * and the questions asked before anything is erased for good. Shared by the
 * home list (one meeting or a selection), the Storage screen and the Recently
 * deleted screen, so the wording and the checks are the same everywhere.
 */
object TrashUi {

    /**
     * Every move, restore and erase, one at a time and never on the main
     * thread. Ends its thread when idle, like Find & replace's counter.
     */
    private val worker = ThreadPoolExecutor(
        0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue()
    ) { runnable -> Thread(runnable, "trash").apply { isDaemon = true } }

    fun execute(task: () -> Unit) {
        worker.execute(task)
    }

    /** What one delete moved, for its undo, and how many it left out. */
    class Batch(val moved: List<MeetingTrash.Moved>, val busy: Int)

    /**
     * Moves [ids] to Recently deleted. A meeting a recording, import,
     * accuracy check, summary or clear-voice copy is writing right now is
     * left out (AudioRemoval.isWriting), as deleting always has: the job's
     * next write would bring it back. Arms the daily run that erases them
     * when their time is up (RetentionJobService).
     */
    @WorkerThread
    fun moveToTrash(context: Context, ids: List<String>): Batch {
        val trash = MeetingTrash(context)
        val moved = ArrayList<MeetingTrash.Moved>()
        var busy = 0
        for (id in ids) {
            if (AudioRemoval.isWriting(context, id)) {
                busy++
                continue
            }
            trash.moveToTrash(id)?.let { moved.add(it) }
        }
        if (moved.isNotEmpty()) RetentionJobService.schedule(context)
        return Batch(moved, busy)
    }

    /**
     * After a delete (main thread): "Moved to Recently deleted" with Undo,
     * above [anchor]. Undo puts the meetings back with the work that was
     * queued for them, then [onChanged] runs on the main thread. Material
     * stretches the bar to the timeout set in Android's accessibility
     * settings (Android 10 and later), or keeps it up while a screen reader
     * explores by touch (earlier); missed, the meetings are still in
     * Recently deleted.
     */
    fun showUndo(activity: Activity, batch: Batch, anchor: View?, onChanged: () -> Unit) {
        val count = batch.moved.size
        if (count == 0 || activity.isFinishing || activity.isDestroyed) return
        val text = if (count == 1) {
            activity.getString(R.string.trash_moved_one)
        } else {
            activity.resources.getQuantityString(R.plurals.storage_bulk_deleted, count, count)
        }
        val bar = Snackbar.make(activity.findViewById<View>(android.R.id.content), text, Snackbar.LENGTH_LONG)
        bar.setAction(R.string.trash_undo) { undo(activity, batch, onChanged) }
        anchor?.let { bar.setAnchorView(it) }
        bar.show()
    }

    private fun undo(activity: Activity, batch: Batch, onChanged: () -> Unit) {
        val app = activity.applicationContext
        worker.execute {
            val trash = MeetingTrash(app)
            val restored = batch.moved.count { trash.undo(it) == MeetingTrash.Restore.RESTORED }
            // Put back still waiting for a charger: make sure plugging in
            // wakes it, as queueing it did (JobGate).
            if (batch.moved.any { moved -> moved.droppedJobs.any { it.chargingOnly } }) {
                ChargingJobService.schedule(app)
            }
            activity.runOnUiThread {
                Toast.makeText(
                    app, app.resources.getQuantityString(R.plurals.trash_undone, restored, restored),
                    Toast.LENGTH_SHORT
                ).show()
                if (!activity.isFinishing && !activity.isDestroyed) onChanged()
            }
        }
    }

    /** Asks before erasing one deleted meeting, naming what goes and what it frees. */
    fun confirmDeleteForever(activity: Activity, title: String, bytes: Long, onYes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.trash_delete_forever_title, title))
            .setMessage(activity.getString(R.string.trash_delete_forever_message, size(activity, bytes)))
            .setPositiveButton(R.string.trash_delete_forever) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            // The title is meeting content.
            .showSecure()
    }

    /** Asks before erasing everything in Recently deleted: [count] meetings, [bytes] in all. */
    fun confirmEmpty(activity: Activity, count: Int, bytes: Long, onYes: () -> Unit) {
        if (count <= 0) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.trash_empty_title)
            .setMessage(
                activity.resources.getQuantityString(
                    R.plurals.trash_empty_message, count, count, size(activity, bytes)
                )
            )
            .setPositiveButton(R.string.trash_empty) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun size(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))
}
