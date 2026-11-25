package com.meetily.mobile

import com.meetily.mobile.DeviceAudioLock.NotificationLine
import com.meetily.mobile.LiveUpdates.Job
import com.meetily.mobile.LiveUpdates.RecordingChip
import com.meetily.mobile.data.JobQueue
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 16 Live Updates: which notifications ask to be promoted, the
 * ProgressStyle bar of a job or a batch, and the recording's chip.
 */
class LiveUpdatesTest {

    private val promotable = listOf(
        Job.RECORDING, Job.IMPORT, Job.TRANSCRIPTION, Job.CLEAR_VOICE, Job.MODEL_DOWNLOAD,
        Job.PASSAGE_REMOVAL
    )

    @Test
    fun userStartedWorkAsksFromAndroid16() {
        for (job in promotable) {
            assertTrue("$job on 36", LiveUpdates.requestsPromotion(job, true, 36))
            assertTrue("$job on 37", LiveUpdates.requestsPromotion(job, true, 37))
        }
    }

    @Test
    fun nothingAsksBelowAndroid16() {
        for (job in Job.entries) {
            for (sdk in listOf(26, 33, 34, 35)) {
                assertFalse("$job on $sdk", LiveUpdates.requestsPromotion(job, true, sdk))
            }
        }
    }

    @Test
    fun workTheUserDidNotStartNeverAsks() {
        // Queued, waiting for a charger, automatic: ordinary notifications.
        for (job in Job.entries) {
            assertFalse("$job", LiveUpdates.requestsPromotion(job, false, 36))
        }
    }

    @Test
    fun backgroundKindsNeverAskEvenWhenTheUserStartedThem() {
        assertFalse(LiveUpdates.requestsPromotion(Job.CLEAN_UP, true, 36))
        assertFalse(LiveUpdates.requestsPromotion(Job.SUMMARY, true, 36))
        assertEquals(promotable.toSet(), Job.entries.filter { it.promotable }.toSet())
    }

    @Test
    fun mediaPlaybackKeepsItsMediaStyleAndNeverAsks() {
        // The user starts it, but a MediaStyle notification cannot be a
        // Live Update; the media controls are its prominent place.
        for (sdk in listOf(35, 36, 37)) {
            assertFalse(LiveUpdates.requestsPromotion(Job.MEDIA_PLAYBACK, true, sdk))
        }
    }

    @Test
    fun removingAPassageIsTheUsersOwnJob() {
        assertTrue(LiveUpdates.requestsPromotion(Job.PASSAGE_REMOVAL, true, 36))
        // Swiped away: the rest of that run is an ordinary notification.
        assertFalse(LiveUpdates.requestsPromotion(Job.PASSAGE_REMOVAL, true, 36, dismissed = true))
        assertFalse(LiveUpdates.requestsPromotion(Job.PASSAGE_REMOVAL, true, 35))
    }

    @Test
    fun aDismissedLiveUpdateIsNotPromotedAgain() {
        for (job in promotable) {
            assertFalse("$job", LiveUpdates.requestsPromotion(job, true, 36, dismissed = true))
        }
    }

    @Test
    fun aPickedFileIsAlwaysUserStarted() {
        // Started at once, or off the import queue as one of a batch.
        assertTrue(LiveUpdates.transcriptionUserStarted(false, byHand = true, chargingOnly = false))
        assertTrue(LiveUpdates.transcriptionUserStarted(false, byHand = false, chargingOnly = false))
        assertEquals(Job.IMPORT, LiveUpdates.transcriptionJob(passOverMeeting = false))
    }

    @Test
    fun aPassOverAMeetingIsUserStartedOnlyWhenItsStartSaysSo() {
        // Re-transcribe or Continue tapped now, or a hand-requested pass off
        // the queue (EXTRA_USER_STARTED).
        assertTrue(LiveUpdates.transcriptionUserStarted(true, byHand = true, chargingOnly = false))
        // The automatic check, or an audio-only recording's transcript.
        assertFalse(LiveUpdates.transcriptionUserStarted(true, byHand = false, chargingOnly = false))
        // Waiting for a charger is background work, whoever asked for it.
        assertFalse(LiveUpdates.transcriptionUserStarted(true, byHand = false, chargingOnly = true))
        assertFalse(LiveUpdates.transcriptionUserStarted(true, byHand = true, chargingOnly = true))
        assertEquals(Job.TRANSCRIPTION, LiveUpdates.transcriptionJob(passOverMeeting = true))
    }

    @Test
    fun aPassTheUserAskedForByHandIsTheirsOffTheQueueToo() {
        // Yes to the interrupted-work prompt (Continue from where the words
        // stop), or Re-transcribe or Continue tapped while other work ran.
        assertTrue(LiveUpdates.queuedPassUserStarted(required = true, chargingOnly = false))
        // The automatic check and an audio-only recording's transcript are
        // never required.
        assertFalse(LiveUpdates.queuedPassUserStarted(required = false, chargingOnly = false))
        // The requeue of a pass the charger stopped, required or not.
        assertFalse(LiveUpdates.queuedPassUserStarted(required = true, chargingOnly = true))
        assertFalse(LiveUpdates.queuedPassUserStarted(required = false, chargingOnly = true))
    }

    @Test
    fun aQueuedPassIsPromotedOnlyWhenTheUserAskedForIt() {
        // The jobs the app queues, through to the service's decision.
        fun promoted(job: JobQueue.Job): Boolean =
            LiveUpdates.requestsPromotion(
                Job.TRANSCRIPTION,
                LiveUpdates.transcriptionUserStarted(
                    passOverMeeting = true,
                    byHand = LiveUpdates.queuedPassUserStarted(job.required, job.chargingOnly),
                    chargingOnly = job.chargingOnly
                ),
                36
            )
        val check = JobQueue.KIND_CHECK
        // MainActivity's interrupted-work Yes, MeetingDetailActivity's and
        // ContinueTranscription's queued passes.
        assertTrue(
            promoted(
                JobQueue.Job(
                    check, "m", "base", 1L, required = true, firstTranscript = true,
                    resumeFromMs = 2_520_000L
                )
            )
        )
        assertTrue(promoted(JobQueue.Job(check, "m", "base", 1L, required = true)))
        // RecordingService: an audio-only recording's transcript, the
        // automatic check, and either waiting for a charger.
        assertFalse(
            promoted(JobQueue.Job(check, "m", "base", 1L, firstTranscript = true))
        )
        assertFalse(promoted(JobQueue.Job(check, "m", "base", 1L)))
        assertFalse(
            promoted(JobQueue.Job(check, "m", "base", 1L, chargingOnly = true))
        )
        // ImportService's requeue after an unplug: required, on a charger.
        assertFalse(
            promoted(
                JobQueue.chargingRequeue(
                    "m", "base", 1L, firstTranscript = true, language = "", resumeFromMs = 60_000L
                )
            )
        )
    }

    @Test
    fun oneFileIsAPlainHundredStepBar() {
        val p = LiveUpdates.progress(42)
        assertEquals(42, p.value)
        assertEquals(100, p.max)
        assertFalse(p.indeterminate)
        assertTrue(p.segments.isEmpty())
        assertEquals(42, p.percent)
    }

    @Test
    fun nothingDoneYetIsIndeterminateWithNoChipText() {
        val p = LiveUpdates.progress(0)
        assertTrue(p.indeterminate)
        assertNull(p.percent)
    }

    @Test
    fun percentIsClamped() {
        assertEquals(100, LiveUpdates.progress(140).value)
        assertEquals(0, LiveUpdates.progress(-5).value)
        assertEquals(100, LiveUpdates.progress(100).percent)
    }

    @Test
    fun aBatchSpansEveryFileWithOneSegmentEach() {
        val p = LiveUpdates.progress(40, batchIndex = 2, batchSize = 5)
        assertEquals(List(5) { LiveUpdates.SEGMENT_LENGTH }, p.segments)
        assertEquals(500, p.max)
        assertEquals(p.max, p.segments.sum())
        assertEquals(140, p.value)
        assertFalse(p.indeterminate)
        // 140 of 500.
        assertEquals(28, p.percent)
    }

    @Test
    fun theNextFileOfABatchStartsWhereTheLastEnded() {
        // A file at 0% is not "nothing done" when files before it are.
        val second = LiveUpdates.progress(0, batchIndex = 2, batchSize = 3)
        assertEquals(100, second.value)
        assertFalse(second.indeterminate)
        assertEquals(33, second.percent)
        val first = LiveUpdates.progress(0, batchIndex = 1, batchSize = 3)
        assertTrue(first.indeterminate)
        assertNull(first.percent)
        val last = LiveUpdates.progress(100, batchIndex = 3, batchSize = 3)
        assertEquals(last.max, last.value)
        assertEquals(100, last.percent)
    }

    @Test
    fun aBatchOfTenStillGetsOneSegmentPerFile() {
        val p = LiveUpdates.progress(50, batchIndex = 10, batchSize = LiveUpdates.MAX_SEGMENTS)
        assertEquals(LiveUpdates.MAX_SEGMENTS, p.segments.size)
        assertEquals(1000, p.max)
        assertEquals(p.max, p.segments.sum())
        assertEquals(950, p.value)
    }

    @Test
    fun aBatchOfMoreThanTenIsOneUndividedSegmentOfTheSameLength() {
        // The platform merges more than ten into one anyway, logging a
        // warning per extra segment on every update; the max must stay the
        // batch's, which an empty list would turn into 100.
        for (size in listOf(11, 15, JobQueue.MAX_IMPORTS)) {
            val p = LiveUpdates.progress(40, batchIndex = 3, batchSize = size)
            assertEquals("$size", listOf(size * LiveUpdates.SEGMENT_LENGTH), p.segments)
            assertEquals("$size", size * LiveUpdates.SEGMENT_LENGTH, p.max)
            assertEquals("$size", p.max, p.segments.sum())
            assertEquals("$size", 240, p.value)
            assertTrue("$size", p.segments.size <= LiveUpdates.MAX_SEGMENTS)
        }
        val last = LiveUpdates.progress(100, batchIndex = 20, batchSize = 20)
        assertEquals(last.max, last.value)
        assertEquals(100, last.percent)
        assertEquals(12, LiveUpdates.progress(40, batchIndex = 3, batchSize = 20).percent)
    }

    @Test
    fun noBarHasMoreSegmentsThanThePlatformDraws() {
        for (size in 0..JobQueue.MAX_IMPORTS) {
            for (index in 0..size) {
                val p = LiveUpdates.progress(30, index, size)
                assertTrue("$index of $size", p.segments.size <= LiveUpdates.MAX_SEGMENTS)
                if (p.segments.isNotEmpty()) assertEquals("$index of $size", p.max, p.segments.sum())
                assertTrue("$index of $size", p.value <= p.max)
            }
        }
    }

    @Test
    fun aDismissedBatchStaysDismissedForItsLaterFiles() {
        // Swiped away during file 1 of 5: files 2 to 5 stay ordinary.
        assertTrue(LiveUpdates.startsDismissed(1 to 5, batchIndex = 2, batchSize = 5))
        // Carried on file by file (ImportService moves the record along).
        assertTrue(LiveUpdates.startsDismissed(2 to 5, batchIndex = 3, batchSize = 5))
        // A file that could not be staged is skipped; the next one still is.
        assertTrue(LiveUpdates.startsDismissed(2 to 5, batchIndex = 4, batchSize = 5))
        assertTrue(LiveUpdates.startsDismissed(4 to 5, batchIndex = 5, batchSize = 5))
        assertTrue(LiveUpdates.startsDismissed(1 to 20, batchIndex = 20, batchSize = 20))
    }

    @Test
    fun anythingButALaterFileOfThatBatchAsksAfresh() {
        // Nothing dismissed.
        assertFalse(LiveUpdates.startsDismissed(null, batchIndex = 2, batchSize = 5))
        // A new batch starts over at 1, of any size.
        assertFalse(LiveUpdates.startsDismissed(3 to 5, batchIndex = 1, batchSize = 5))
        assertFalse(LiveUpdates.startsDismissed(3 to 5, batchIndex = 1, batchSize = 3))
        // Another batch's file, or the same file again.
        assertFalse(LiveUpdates.startsDismissed(1 to 5, batchIndex = 2, batchSize = 4))
        assertFalse(LiveUpdates.startsDismissed(2 to 5, batchIndex = 2, batchSize = 5))
        // A file on its own, or a pass over a meeting (no position).
        assertFalse(LiveUpdates.startsDismissed(1 to 5, batchIndex = 0, batchSize = 0))
        // Dismissed on a run that was not one of a batch.
        assertFalse(LiveUpdates.startsDismissed(0 to 0, batchIndex = 2, batchSize = 5))
        assertFalse(LiveUpdates.startsDismissed(0 to 0, batchIndex = 0, batchSize = 0))
        assertFalse(LiveUpdates.startsDismissed(1 to 1, batchIndex = 2, batchSize = 1))
    }

    @Test
    fun noSegmentsWhereTheTextShowsNoPosition() {
        // The same rule as the "File 2 of 5" line (ImportBatch.showsPosition).
        for ((index, size) in listOf(0 to 0, 1 to 1, 0 to 4, 5 to 4)) {
            val p = LiveUpdates.progress(30, index, size)
            assertTrue("$index of $size", p.segments.isEmpty())
            assertEquals(100, p.max)
            assertEquals(30, p.value)
        }
    }

    @Test
    fun theRecordingChipRunsTheClockWhileRecording() {
        assertEquals(RecordingChip.ELAPSED, LiveUpdates.recordingChip(NotificationLine.RECORDING, false))
        assertEquals(
            RecordingChip.ELAPSED,
            LiveUpdates.recordingChip(NotificationLine.DEVICE_AUDIO_LIVE, false)
        )
    }

    @Test
    fun theRecordingChipSaysWhyTheClockStoppedOrNeedsALook() {
        assertEquals(RecordingChip.PAUSED, LiveUpdates.recordingChip(NotificationLine.PAUSED, false))
        assertEquals(RecordingChip.NO_MIC, LiveUpdates.recordingChip(NotificationLine.MIC_NO_SIGNAL, false))
        assertEquals(
            RecordingChip.ENDED,
            LiveUpdates.recordingChip(NotificationLine.DEVICE_AUDIO_ENDED, false)
        )
        // A microphone capture that stopped runs no clock in the chip either.
        assertEquals(
            RecordingChip.ENDED,
            LiveUpdates.recordingChip(NotificationLine.CAPTURE_ENDED, false)
        )
        for (line in NotificationLine.entries) {
            assertEquals("$line", RecordingChip.SAVING, LiveUpdates.recordingChip(line, true))
        }
    }

    @Test
    fun theLockScreenMarkGoesOnlyForALiveUpdateThatCanHappen() {
        for (requested in listOf(true, false)) {
            for (canPost in listOf(true, false)) {
                for (promotable in listOf(true, false)) {
                    assertEquals(
                        "requested=$requested canPost=$canPost promotable=$promotable",
                        !(requested && canPost && promotable),
                        LiveUpdates.recordingKeepsLockScreenMark(requested, canPost, promotable)
                    )
                }
            }
        }
    }

    // --- Resources ---------------------------------------------------------

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun source(path: String): File {
        val candidates = listOf("src/main/$path", "app/src/main/$path", "android/app/src/main/$path")
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    private fun strings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/values/strings_liveupdates.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    /** As Android formats it: %% is a literal percent sign. */
    private fun format(template: String, vararg args: Any): String =
        String.format(Locale.US, template, *args)

    @Test
    fun chipTextsFitTheChipWhole() {
        val all = strings()
        val chips = all.filterKeys { it.startsWith("liveupdates_chip_") }
        assertEquals(5, chips.size)
        for ((name, text) in chips) {
            val widest = if (name == "liveupdates_chip_percent") format(text, 100) else text
            assertTrue("$name '$widest' is ${widest.length} characters", widest.length <= 6)
        }
    }

    @Test
    fun percentChipReadsAsAPercentage() {
        val template = strings().getValue("liveupdates_chip_percent")
        assertEquals("0%", format(template, 0))
        assertEquals("42%", format(template, LiveUpdates.progress(42).percent!!))
        assertEquals("28%", format(template, LiveUpdates.progress(40, 2, 5).percent!!))
        assertEquals("100%", format(template, 100))
    }

    @Test
    fun manifestDeclaresThePromotionPermission() {
        val manifest = source("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
    }
}
