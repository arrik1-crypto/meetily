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
 * model) and its deletion. Settings forwards the download service's
 * callbacks for its kind here.
 */
class AlignModelRow(
    private val activity: AppCompatActivity,
    private val status: TextView,
    private val progress: LinearProgressIndicator,
    private val button: Button
) {
    private val announcer = ProgressAnnouncer()

    /** The last progress the download service reported, for a re-render mid-download. */
    private var lastPercent = 0

    fun bind() {
        button.setOnClickListener { onButton() }
        render()
    }

    /** Shows what is on the phone now. A few stats. */
    fun render() {
        if (activity.isFinishing || activity.isDestroyed) return
        val model = AlignerModels.DEFAULT
        val downloading = ModelDownloadService.isRunning &&
            ModelDownloadService.currentKind == ModelDownloadService.KIND_ALIGN
        if (downloading) {
            onProgress(lastPercent)
            return
        }
        progress.visibility = View.GONE
        if (AlignerModels.isDownloaded(activity, model)) {
            status.text = activity.getString(
                R.string.align_model_status_ready,
                model.displayName,
                Formatter.formatShortFileSize(activity, AlignerModels.bytesOnDisk(activity))
            )
            button.setText(R.string.align_model_delete)
        } else {
            status.text = activity.getString(
                R.string.align_model_status_missing,
                Formatter.formatShortFileSize(activity, model.approxSizeMb * MB),
                Formatter.formatShortFileSize(activity, model.unpackedMb * MB)
            )
            button.setText(R.string.align_model_download)
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
        A11y.setStatusText(
            status,
            activity.getString(R.string.align_model_downloading, percent.coerceIn(0, 100)),
            announcer.shouldAnnounce(percent)
        )
    }

    fun onQueued() {
        if (activity.isFinishing || activity.isDestroyed) return
        button.isEnabled = false
        A11y.setStatusText(status, activity.getString(R.string.align_model_queued), true)
    }

    fun onDone(cancelled: Boolean, error: String?) {
        lastPercent = 0
        announcer.reset()
        if (!cancelled && error == null) A11y.announceNextChange(status)
        render()
    }

    private fun onButton() {
        val model = AlignerModels.DEFAULT
        if (AlignerModels.isDownloaded(activity, model)) confirmDelete() else download()
    }

    private fun download() {
        val model = AlignerModels.DEFAULT
        button.isEnabled = false
        ModelSpaceCheck.confirm(
            activity, listOf(AlignerModels.spaceNeededMb(model)),
            onMeasured = { render() }
        ) {
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
                val busy = AlignService.isBusy() || (
                    ModelDownloadService.isRunning &&
                        (ModelDownloadService.currentKind == ModelDownloadService.KIND_ALIGN ||
                            ModelDownloadService.queuedItems.any { it.first == ModelDownloadService.KIND_ALIGN })
                    )
                if (busy) {
                    Toast.makeText(activity, R.string.align_model_delete_busy, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val app = activity.applicationContext
                button.isEnabled = false
                Thread {
                    AlignerModels.delete(app, model)
                    activity.runOnUiThread {
                        render()
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Toast.makeText(activity, R.string.align_model_deleted, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.apply { name = "align-model-delete" }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.meetily.mobile.security.AppLock.secure(dialog)
        dialog.show()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
