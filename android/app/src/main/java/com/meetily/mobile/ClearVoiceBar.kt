package com.meetily.mobile

import android.app.Activity
import android.text.format.Formatter
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.data.Meeting
import java.io.File

/**
 * The clear-voice row of the meeting screen's player bar: a chip that makes
 * the copy, shows it being made, or switches the player between the
 * recording and the copy at the same position; a status line; and a 48dp
 * button that deletes the copy (after asking). ClearVoiceService makes the
 * copy; this only shows it and asks for it.
 *
 * The chip is a pill drawn 36dp tall inside a 48dp target, says what a tap
 * does to a screen reader, and carries its on/off state; the status line is
 * a polite live region for the changes worth hearing (ProgressAnnouncer for
 * progress, every switch, the end of a run).
 */
class ClearVoiceBar(
    private val activity: Activity,
    private val row: View,
    private val chip: TextView,
    private val status: TextView,
    private val delete: ImageButton,
    private val host: Host
) : ClearVoiceService.Listener {

    /** What the meeting screen does for the row. */
    interface Host {
        /** The meeting on screen, once read. */
        fun meeting(): Meeting?

        /** The audio's length for the estimate: the player's, else the transcript's; 0 unknown. */
        fun audioDurationMs(): Long

        /** True while the player plays (or will play) the copy. */
        fun playingCopy(): Boolean

        /** Moves the player to the copy or back to the recording, at the same position. */
        fun switchTo(copy: Boolean)

        /** Drops the copy [name] from the meeting and deletes it (the screen's one save path). */
        fun dropCopy(name: String)

        /** A copy finished in the background: read the meeting again to pick it up. */
        fun refresh()
    }

    private val announcer = ProgressAnnouncer()
    private var attached = false

    init {
        chip.setOnClickListener { onChipTapped() }
        delete.setOnClickListener { confirmDelete() }
    }

    /** From onStart (and once the meeting is read): follow runs and draw the row. */
    fun attach() {
        if (!attached) {
            ClearVoiceService.addListener(this)
            attached = true
        }
        render()
    }

    /** From onStop. */
    fun detach() {
        if (attached) {
            ClearVoiceService.removeListener(this)
            attached = false
        }
    }

    /** The copy the meeting may play now, or null. */
    fun copyFile(): File? {
        val m = host.meeting() ?: return null
        if (ClearVoiceService.isPreparing(m.id)) return null
        return AudioStore.clearVoiceCopy(activity, m.audioFile, m.clearVoiceFile)
    }

    /** Draws the row from the meeting and the service; speaks the status line when [announce]. */
    fun render(announce: Boolean = false) {
        val m = host.meeting()
        if (m == null || m.audioFile.isNullOrBlank()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val preparing = ClearVoiceService.isPreparing(m.id)
        val other = !preparing && ClearVoiceService.preparingId() != null
        val copy = copyFile()
        val on = copy != null && host.playingCopy()
        val density = activity.resources.displayMetrics.density
        A11y.pillTouchTarget(
            chip,
            if (on || preparing) R.drawable.bg_pill_accent else R.drawable.bg_pill,
            visualHeightPx = (36 * density).toInt(),
            paddingHorizontalPx = (14 * density).toInt(),
            paddingVerticalPx = (7 * density).toInt()
        )
        chip.isSelected = on
        // Dimmed while another meeting's copy is made and this one has none
        // to switch to: a tap then only says why it cannot start.
        chip.alpha = if (other && copy == null) 0.6f else 1f
        A11y.setOnOff(chip, on)
        A11y.labelClick(
            chip,
            activity.getString(
                when {
                    preparing -> R.string.clearvoice_action_stop
                    copy == null -> R.string.clearvoice_action_make
                    on -> R.string.clearvoice_action_play_original
                    else -> R.string.clearvoice_action_play_copy
                }
            )
        )
        val text = when {
            preparing -> progressText(ClearVoiceService.percent, ClearVoiceService.minutesLeft)
            copy != null && on -> activity.getString(R.string.clearvoice_using_copy)
            copy != null -> activity.getString(R.string.clearvoice_using_original)
            other -> activity.getString(R.string.clearvoice_other_meeting)
            else -> ""
        }
        A11y.setStatusText(status, text, announce)
        delete.visibility = if (copy != null) View.VISIBLE else View.GONE
    }

    private fun progressText(percent: Int, minutesLeft: Long?): String =
        if (minutesLeft != null) {
            activity.getString(R.string.clearvoice_progress_eta, percent, minutesLabel(minutesLeft))
        } else {
            activity.getString(R.string.clearvoice_progress, percent)
        }

    private fun minutesLabel(minutes: Long): String =
        if (minutes >= 60) {
            activity.getString(R.string.duration_h_min, (minutes / 60).toInt(), (minutes % 60).toInt())
        } else {
            activity.getString(R.string.duration_min, minutes.toInt())
        }

    private fun live(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private fun onChipTapped() {
        val m = host.meeting() ?: return
        when {
            ClearVoiceService.isPreparing(m.id) -> confirmStop()
            copyFile() != null -> {
                host.switchTo(!host.playingCopy())
                render(announce = true)
            }
            else -> confirmStart(m)
        }
    }

    private fun confirmStart(m: Meeting) {
        ClearVoiceService.blockReason(activity, m.id)?.let {
            Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
            return
        }
        val durationMs = host.audioDurationMs()
        var message = activity.getString(R.string.clearvoice_confirm_message)
        if (durationMs > 0L) {
            message += "\n\n" + activity.getString(
                R.string.clearvoice_confirm_size,
                Formatter.formatShortFileSize(activity, ClearVoice.estimatedBytes(durationMs))
            )
        }
        if (ClearVoice.isLong(durationMs)) {
            message += "\n\n" + activity.getString(
                R.string.clearvoice_confirm_long, minutesLabel((durationMs + 30_000L) / 60_000L)
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clearvoice_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.clearvoice_confirm_start) { _, _ ->
                val current = host.meeting() ?: return@setPositiveButton
                // Asked again: something may have started while the question was up.
                val blocked = ClearVoiceService.blockReason(activity, current.id)
                if (blocked != null) {
                    Toast.makeText(activity, blocked, Toast.LENGTH_LONG).show()
                } else if (!ClearVoiceService.start(activity, current.id)) {
                    Toast.makeText(activity, R.string.clearvoice_blocked_other, Toast.LENGTH_LONG).show()
                } else {
                    announcer.reset()
                    announcer.shouldAnnounce(0)
                    render(announce = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun confirmStop() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clearvoice_stop_title)
            .setMessage(R.string.clearvoice_stop_message)
            .setPositiveButton(R.string.clearvoice_stop) { _, _ -> ClearVoiceService.cancel() }
            .setNegativeButton(R.string.clearvoice_keep_going, null)
            .showSecure()
    }

    private fun confirmDelete() {
        val m = host.meeting() ?: return
        val copy = copyFile() ?: return
        // A check queued to transcribe the copy, or a job writing the meeting.
        if (AudioRemoval.copyInUse(activity, m.id)) {
            Toast.makeText(activity, R.string.storage_audio_busy, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clearvoice_delete_title)
            .setMessage(
                activity.getString(
                    R.string.clearvoice_delete_message,
                    Formatter.formatShortFileSize(activity, copy.length())
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val current = host.meeting() ?: return@setPositiveButton
                val now = copyFile()
                if (now == null || AudioRemoval.copyInUse(activity, current.id)) {
                    Toast.makeText(activity, R.string.storage_audio_busy, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                host.dropCopy(now.name)
                Toast.makeText(activity, R.string.clearvoice_deleted, Toast.LENGTH_SHORT).show()
                render(announce = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    // --- ClearVoiceService.Listener ------------------------------------------

    override fun showsMeeting(meetingId: String): Boolean = host.meeting()?.id == meetingId

    override fun onClearVoiceProgress(meetingId: String, percent: Int, minutesLeft: Long?) {
        if (!live()) return
        if (host.meeting()?.id != meetingId) {
            render()
            return
        }
        render(announce = announcer.shouldAnnounce(percent))
    }

    override fun onClearVoiceDone(meetingId: String, result: ClearVoiceService.Result) {
        if (!live()) return
        if (host.meeting()?.id != meetingId) {
            // Another meeting's run ended: this chip can make copies again.
            render()
            return
        }
        Toast.makeText(activity, ClearVoiceService.messageFor(result), Toast.LENGTH_LONG).show()
        // The copy's name is on the stored meeting now; the screen's copy
        // takes it through the usual sync, which draws the row again.
        if (result == ClearVoiceService.Result.READY) host.refresh()
        render(announce = true)
    }
}
