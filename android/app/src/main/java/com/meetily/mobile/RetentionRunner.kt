package com.meetily.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.MeetingAssets
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.Retention
import com.meetily.mobile.data.SafeFiles
import com.meetily.mobile.data.SpeakerSuggestions
import com.meetily.mobile.data.TranscriptDraft
import java.io.File

/**
 * Runs the automatic clean-up rule (Retention): stores it, works out what
 * it reaches, applies it, and reports.
 *
 * The rule is saved only once the user has seen what its first run does
 * and said yes (RetentionUi). From then on RetentionJobService runs it once
 * a day, and a run that deleted something says so in a quiet notification.
 * Nothing here sends anything anywhere: it deletes files on this phone.
 */
object RetentionRunner {

    /** The app's settings file; see AppSettings. */
    private const val PREFS = "meetily_settings"
    private const val KEY_RULE = "retention_rule"

    private const val CHANNEL_ID = "storage_cleanup"
    private const val NOTIF_ID = 7_310

    /** The meeting list's fields a plan reads; never transcripts. */
    private val PLAN_FIELDS = MeetingStore.HEADER_FIELDS + setOf("starred", "audioFile", "actionItems")

    fun rule(context: Context): Retention.Rule =
        Retention.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RULE, null)
        )

    /** Saves [rule] and arms or cancels the daily run to match. */
    fun setRule(context: Context, rule: Retention.Rule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RULE, Retention.encode(rule))
            .apply()
        if (rule.enabled) {
            RetentionJobService.schedule(context)
        } else {
            RetentionJobService.cancel(context)
        }
    }

    /** What [rule] reaches now, with titles for showing it. */
    class Plan(
        val rule: Retention.Rule,
        val selected: List<Retention.Candidate>,
        val titles: Map<String, String>
    ) {
        val audioBytes: Long get() = Retention.audioBytes(selected)
    }

    /** What a run did: meetings reached, and audio bytes freed by it. */
    data class Outcome(val count: Int, val bytes: Long)

    /**
     * The meetings [rule] reaches at [nowMs]. Reads every meeting's header,
     * star, audio reference and action items (no transcripts), and stats
     * its file and audio. Worker threads only.
     */
    @WorkerThread
    fun plan(context: Context, rule: Retention.Rule, nowMs: Long = System.currentTimeMillis()): Plan {
        val store = MeetingStore(context)
        val meetings = store.listPartial(PLAN_FIELDS)
        val meetingsDir = File(context.filesDir, "meetings")
        val protectedIds = HashSet(busyIds(context, store))
        val candidates = meetings.map { meeting ->
            // Waiting for the user: a staged accuracy check or speaker names
            // to review. Both are about this meeting as it is now.
            if (TranscriptDraft.isPending(context, meeting.id) ||
                SpeakerSuggestions.isPending(context, meeting.id)
            ) {
                protectedIds.add(meeting.id)
            }
            val fileName = "${meeting.id}.json"
            Retention.Candidate(
                id = meeting.id,
                createdAtMs = meeting.createdAtMs,
                modifiedMs = if (SafeFiles.isPlainName(fileName)) {
                    File(meetingsDir, fileName).lastModified()
                } else {
                    0L
                },
                starred = meeting.starred,
                audioBytes = meeting.audioFile?.let { AudioStore.fileFor(context, it).length() } ?: 0L,
                hasFutureReminder = meeting.actionItems.any {
                    !it.done && (it.remindAtMs ?: 0L) > nowMs
                }
            )
        }
        return Plan(
            rule,
            Retention.select(rule, candidates, protectedIds, nowMs),
            meetings.associate { it.id to it.title }
        )
    }

    /**
     * Meetings something is working on or has queued: the recording (or one
     * a crash left marked, not yet recovered), a running import, accuracy
     * check or summary, and every queued or interrupted job.
     */
    private fun busyIds(context: Context, store: MeetingStore): Set<String> {
        val ids = HashSet<String>()
        store.activeId()?.let { ids.add(it) }
        if (ImportService.isRunning) ImportService.currentMeetingId?.let { ids.add(it) }
        if (SummaryService.isRunning) ids.add(SummaryService.currentMeetingId)
        for (job in JobQueue.load(context)) {
            if (job.meetingId.isNotBlank()) ids.add(job.meetingId)
        }
        return ids
    }

    /**
     * Applies [approved], a plan the user saw (or the daily run made). It is
     * worked out again first and only meetings in both are touched, so one
     * starred, edited or given a job since is left alone. One run at a
     * time. Worker threads only.
     */
    @Synchronized
    @WorkerThread
    fun apply(context: Context, approved: Plan): Outcome {
        val rule = approved.rule
        if (!rule.enabled) return Outcome(0, 0L)
        val still = plan(context, rule).selected.mapTo(HashSet()) { it.id }
        val store = MeetingStore(context)
        var count = 0
        var bytes = 0L
        for (candidate in approved.selected) {
            if (candidate.id !in still) continue
            when (rule.mode) {
                Retention.Mode.AUDIO -> {
                    val freed = AudioRemoval.remove(context, store, candidate.id)
                    if (freed >= 0L) {
                        count++
                        bytes += freed
                    }
                }
                Retention.Mode.MEETINGS -> {
                    if (AudioRemoval.isWriting(context, candidate.id)) continue
                    // The stored copy names every photo and attachment.
                    val meeting = store.load(candidate.id) ?: continue
                    if (meeting.starred) continue
                    bytes += meeting.audioFile?.let { AudioStore.fileFor(context, it).length() } ?: 0L
                    MeetingAssets.deleteAll(context, meeting)
                    store.delete(meeting.id)
                    count++
                }
                Retention.Mode.OFF -> Unit
            }
        }
        return Outcome(count, bytes)
    }

    /**
     * The daily run. Skipped while a recording, import or summary is going
     * (tomorrow's run catches up), and stopped short of deleting more whole
     * meetings than [Retention.AUTO_MAX_MEETINGS]: that asks first instead.
     */
    @WorkerThread
    fun runAutomatic(context: Context) {
        val rule = rule(context)
        if (!rule.enabled) {
            RetentionJobService.cancel(context)
            return
        }
        if (RecordingService.isRunning || ImportService.isRunning || SummaryService.isRunning) return
        val plan = plan(context, rule)
        if (plan.selected.isEmpty()) return
        if (Retention.needsReview(rule, plan.selected)) {
            notifyReview(context, plan.selected.size)
            return
        }
        val outcome = apply(context, plan)
        if (outcome.count > 0) notifyDone(context, rule, outcome)
    }

    private fun notifyDone(context: Context, rule: Retention.Rule, outcome: Outcome) {
        val res = context.resources
        val title = if (rule.mode == Retention.Mode.AUDIO) {
            res.getQuantityString(
                R.plurals.storage_retention_done_audio, outcome.count, outcome.count,
                Formatter.formatShortFileSize(context, outcome.bytes)
            )
        } else {
            res.getQuantityString(R.plurals.storage_retention_done_meetings, outcome.count, outcome.count)
        }
        val text = context.getString(
            if (rule.mode == Retention.Mode.AUDIO) R.string.storage_notif_audio_text
            else R.string.storage_notif_meetings_text,
            rule.days
        )
        post(context, title, text, review = false)
    }

    private fun notifyReview(context: Context, count: Int) {
        post(
            context,
            context.resources.getQuantityString(R.plurals.storage_notif_review_title, count, count),
            context.getString(R.string.storage_notif_review_text),
            review = true
        )
    }

    /** One notification, replaced by the next: low importance, no sound. */
    private fun post(context: Context, title: String, text: String, review: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.storage_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            Intent(context, StorageActivity::class.java)
                .putExtra(StorageActivity.EXTRA_REVIEW_CLEANUP, review)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_delete)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off for the app; the clean-up still happened.
        }
    }
}
