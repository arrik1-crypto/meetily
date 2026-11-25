package com.meetily.mobile

import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.meetily.mobile.data.DeviceProbe
import com.meetily.mobile.whisper.AlignerModels

/**
 * Settings' "Word re-timing (English)" row: whether the optional model for
 * "Re-time words" (AlignerModels) is on the phone, its download (through
 * ModelDownloadService, after the same space and memory checks as every
 * model) and its deletion. A download that was kept but not unpacked (a
 * full disk, a stopped unpack) is offered to unpack or delete, so its
 * space can always be taken back. Settings forwards the download
 * service's callbacks for its kind here. While the model downloads,
 * [cancel] (under the progress bar) runs [onCancel].
 */
class AlignModelRow(
    private val activity: AppCompatActivity,
    private val status: TextView,
    private val progress: LinearProgressIndicator,
    private val button: Button,
    private val cancel: View,
    private val onCancel: () -> Unit
) {
    private val announcer = ProgressAnnouncer()

    /** The last progress the download service reported, for a re-render mid-download. */
    private var lastPercent = 0

    /** What the row shows, and when it is worked out after a download ends. */
    private val state = AlignRowState(
        aligning = {
            ModelDownloadService.isRunning &&
                ModelDownloadService.currentKind == ModelDownloadService.KIND_ALIGN
        },
        downloaded = { AlignerModels.isDownloaded(activity, AlignerModels.DEFAULT) },
        kept = { AlignerModels.hasKeptArchive(activity, AlignerModels.DEFAULT) },
        later = { block ->
            status.post { if (!activity.isFinishing && !activity.isDestroyed) block() }
        },
        draw = { shown -> draw(shown) }
    )

    fun bind() {
        button.setOnClickListener { onButton() }
        cancel.setOnClickListener { onCancel() }
        render()
    }

    /** Shows what is on the phone now. A few stats. */
    fun render() {
        if (activity.isFinishing || activity.isDestroyed) return
        state.render()
    }

    private fun draw(shown: AlignRowState.Shown) {
        if (shown == AlignRowState.Shown.DOWNLOADING) {
            onProgress(lastPercent)
            return
        }
        val model = AlignerModels.DEFAULT
        progress.visibility = View.GONE
        cancel.visibility = View.GONE
        when (shown) {
            AlignRowState.Shown.DOWNLOADED -> {
                status.text = activity.getString(
                    R.string.align_model_status_ready,
                    model.displayName,
                    Formatter.formatShortFileSize(activity, AlignerModels.bytesOnDisk(activity))
                )
                button.setText(R.string.align_model_delete)
            }
            AlignRowState.Shown.KEPT -> {
                status.text = activity.getString(
                    R.string.align_model_status_kept,
                    Formatter.formatShortFileSize(activity, AlignerModels.bytesOnDisk(activity)),
                    Formatter.formatShortFileSize(activity, model.unpackedMb * MB)
                )
                button.setText(R.string.align_model_kept_action)
            }
            else -> { // MISSING
                status.text = activity.getString(
                    R.string.align_model_status_missing,
                    Formatter.formatShortFileSize(activity, model.approxSizeMb * MB),
                    Formatter.formatShortFileSize(activity, model.unpackedMb * MB)
                )
                button.setText(R.string.align_model_download)
            }
        }
        button.isEnabled = true
    }

    fun onProgress(percent: Int) {
        lastPercent = percent
        if (activity.isFinishing || activity.isDestroyed) return
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = percent <= 0
        progress.progress = percent.coerceIn(0, 100)
        button.isEnabled = false
        if (ModelDownloadService.currentStopping) {
            // Cancelled, and not ended yet (a replay after Settings came back).
            onStopping()
            return
        }
        cancel.visibility = View.VISIBLE
        // From the download's share on, the archive is being unpacked
        // (all of it, when a kept download is unpacked).
        val text = if (percent >= AlignerModels.DOWNLOAD_SHARE) {
            R.string.align_model_unpacking
        } else {
            R.string.align_model_downloading
        }
        A11y.setStatusText(
            status,
            activity.getString(text, percent.coerceIn(0, 100)),
            announcer.shouldAnnounce(percent)
        )
    }

    fun onQueued() {
        if (activity.isFinishing || activity.isDestroyed) return
        button.isEnabled = false
        cancel.visibility = View.GONE
        A11y.setStatusText(status, activity.getString(R.string.align_model_queued), true)
    }

    /** Cancel was tapped: says so until the download service reports the end. */
    fun onStopping() {
        if (activity.isFinishing || activity.isDestroyed) return
        cancel.visibility = View.GONE
        A11y.setStatusText(status, activity.getString(R.string.devicefit_download_stopping), true)
    }

    /**
     * Another model's download ended, which may have dropped a download of
     * this one waiting behind it (a cancel, Android's time limit): drawn
     * again once the service has settled, as after its own (AlignRowState).
     */
    fun refreshWhenSettled() {
        state.onDone()
    }

    /**
     * A download of the model ended, or ended while Settings was away.
     * Settings forwards the service's done callback here, which finishRun
     * makes before it clears isRunning and currentKind. The row is drawn
     * once finishRun has returned (AlignRowState.onDone), so it shows what
     * is on disk (downloaded, a kept download, or nothing) or a queued
     * download of the model, not the one that just ended.
     */
    fun onDone(cancelled: Boolean, error: String?) {
        lastPercent = 0
        announcer.reset()
        if (!cancelled && error == null) A11y.announceNextChange(status)
        state.onDone()
    }

    private fun onButton() {
        val model = AlignerModels.DEFAULT
        when {
            AlignerModels.isDownloaded(activity, model) -> confirmDelete()
            AlignerModels.hasKeptArchive(activity, model) -> offerKept()
            else -> download()
        }
    }

    /** A download kept but not unpacked: unpack it, or delete it to free its space. */
    private fun offerKept() {
        val model = AlignerModels.DEFAULT
        val size = Formatter.formatShortFileSize(activity, AlignerModels.bytesOnDisk(activity))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.align_model_kept_title)
            .setMessage(
                activity.getString(
                    R.string.align_model_kept_message, size,
                    Formatter.formatShortFileSize(activity, model.unpackedMb * MB),
                    Formatter.formatShortFileSize(activity, model.approxSizeMb * MB)
                )
            )
            .setPositiveButton(R.string.align_model_unpack) { _, _ -> download() }
            .setNeutralButton(R.string.align_model_delete_download) { _, _ ->
                deleteNow(R.string.align_model_download_deleted)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.meetily.mobile.security.AppLock.secure(dialog)
        dialog.show()
    }

    private fun download() {
        val model = AlignerModels.DEFAULT
        button.isEnabled = false
        // Only the unpacked files when the archive was kept: download()
        // then only unpacks it. Plus any model download running or waiting,
        // which takes its room before this one starts.
        val sizes = ModelSpaceCheck.sizesFor(
            ModelDownloadService.KIND_ALIGN, model.key, AlignerModels.spaceNeededMb(activity, model)
        )
        ModelSpaceCheck.confirm(activity, sizes, onMeasured = { render() }) {
            FitBadge.confirmChoice(
                activity, model.displayName,
                DeviceProbe.verdict(activity, AlignerModels.need(model)),
                alreadyActive = false
            ) {
                val wasRunning = ModelDownloadService.isRunning
                if (!ModelDownloadService.start(activity, ModelDownloadService.KIND_ALIGN, model.key)) {
                    Toast.makeText(activity, R.string.devicefit_download_not_started, Toast.LENGTH_LONG).show()
                    render()
                } else if (wasRunning) {
                    onQueued()
                } else {
                    onProgress(0)
                }
            }
        }
    }

    /** Says what goes and what stays, then deletes; never while it downloads or runs. */
    private fun confirmDelete() {
        val model = AlignerModels.DEFAULT
        val size = Formatter.formatShortFileSize(activity, AlignerModels.bytesOnDisk(activity))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.align_model_delete_title)
            .setMessage(
                activity.getString(
                    R.string.align_model_delete_message, size,
                    Formatter.formatShortFileSize(activity, model.approxSizeMb * MB)
                )
            )
            .setPositiveButton(R.string.align_model_delete_confirm) { _, _ ->
                deleteNow(R.string.align_model_deleted)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.meetily.mobile.security.AppLock.secure(dialog)
        dialog.show()
    }

    /**
     * Deletes the model and whatever of its download is left, off the main
     * thread, then says [done]; refused while it downloads, waits to, or a
     * run uses it.
     */
    private fun deleteNow(done: Int) {
        val model = AlignerModels.DEFAULT
        val busy = AlignService.isBusy() || (
            ModelDownloadService.isRunning &&
                (ModelDownloadService.currentKind == ModelDownloadService.KIND_ALIGN ||
                    ModelDownloadService.queuedItems.any { it.first == ModelDownloadService.KIND_ALIGN })
            )
        if (busy) {
            Toast.makeText(activity, R.string.align_model_delete_busy, Toast.LENGTH_LONG).show()
            return
        }
        val app = activity.applicationContext
        button.isEnabled = false
        Thread {
            AlignerModels.delete(app, model)
            activity.runOnUiThread {
                render()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, done, Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "align-model-delete" }.start()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
