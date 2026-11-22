package com.meetily.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.DownloadSpace
import java.util.UUID

/**
 * The free-space check every model download goes through before it starts:
 * Settings' pickers (first downloads and re-downloads alike, via
 * SettingsActivity.pickModel) and the first-run setup step.
 *
 * With room to spare it says nothing. When the room is short it says by how
 * much, offers Android's storage manager, and lets the user download anyway;
 * the numbers are Android's own estimate, and a resumed download may need
 * less than a fresh one. See DownloadSpace for the arithmetic.
 */
object ModelSpaceCheck {

    /** What Android reports for the volume holding the app's files. */
    data class Room(val availableBytes: Long, val uuid: UUID?)

    /** Not read back: the user taps download again once they have made room. */
    private const val REQUEST_MANAGE_STORAGE = 0x5ACE

    /**
     * Room for new files on the volume that holds filesDir, where every
     * model lives: StorageManager.getAllocatableBytes, which also counts
     * cached files Android is willing to clear, or File.usableSpace when the
     * volume cannot be queried. Can take seconds, so worker threads only.
     */
    @WorkerThread
    fun measure(context: Context): Room {
        val dir = context.filesDir
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return Room(dir.usableSpace, null)
        return try {
            val uuid = manager.getUuidForPath(dir)
            Room(manager.getAllocatableBytes(uuid), uuid)
        } catch (_: Exception) {
            // IOException: the volume has no usable UUID. Anything else is
            // treated the same way rather than failing the download over it.
            Room(dir.usableSpace, null)
        }
    }

    /**
     * Checks there is room for downloads of [sizesMb] (several when they are
     * queued together) and runs [onProceed] on the main thread when there
     * is, or when the user chooses to download anyway. Nothing runs if they
     * cancel, or if [activity] is gone by the time the answer is known.
     */
    fun confirm(activity: Activity, sizesMb: List<Int>, onProceed: () -> Unit) {
        val required = DownloadSpace.requiredBytes(sizesMb)
        if (required <= 0L) {
            onProceed()
            return
        }
        val app = activity.applicationContext
        Thread {
            val room = measure(app)
            clearCachesIfThatMakesRoom(app, room, required)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val short = DownloadSpace.shortfallBytes(required, room.availableBytes)
                if (short == 0L) {
                    onProceed()
                } else {
                    showShortOfSpace(activity, required, room, short, onProceed)
                }
            }
        }.apply {
            name = "model-space-check"
            start()
        }
    }

    /**
     * When the room exists only once other apps' caches are cleared, asks
     * Android to clear them now (StorageManager.allocateBytes), so the
     * download does not run out of space halfway. Best effort.
     */
    @WorkerThread
    private fun clearCachesIfThatMakesRoom(context: Context, room: Room, required: Long) {
        val uuid = room.uuid ?: return
        if (!DownloadSpace.shouldClearCaches(required, context.filesDir.usableSpace, room.availableBytes)) {
            return
        }
        try {
            (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)
                ?.allocateBytes(uuid, required)
        } catch (_: Exception) {
            // The download still starts; it just may meet a full disk later.
        }
    }

    private fun showShortOfSpace(
        activity: Activity,
        required: Long,
        room: Room,
        short: Long,
        onProceed: () -> Unit
    ) {
        val manage = Intent(StorageManager.ACTION_MANAGE_STORAGE)
            .putExtra(StorageManager.EXTRA_REQUESTED_BYTES, required)
        room.uuid?.let { manage.putExtra(StorageManager.EXTRA_UUID, it) }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.devicefit_space_title)
            .setMessage(
                activity.getString(
                    R.string.devicefit_space_message,
                    Formatter.formatShortFileSize(activity, required),
                    Formatter.formatShortFileSize(activity, room.availableBytes.coerceAtLeast(0L)),
                    Formatter.formatShortFileSize(activity, short)
                )
            )
            .setPositiveButton(R.string.devicefit_space_continue) { _, _ -> onProceed() }
            .setNegativeButton(android.R.string.cancel, null)
        // Visible to resolveActivity through the manifest's <queries> entry.
        if (manage.resolveActivity(activity.packageManager) != null) {
            builder.setNeutralButton(R.string.devicefit_space_manage) { _, _ ->
                try {
                    // For a result, as StorageManager asks, so the storage
                    // screen can name the app that wants the room.
                    activity.startActivityForResult(manage, REQUEST_MANAGE_STORAGE)
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
        }
        builder.show()
    }
}
