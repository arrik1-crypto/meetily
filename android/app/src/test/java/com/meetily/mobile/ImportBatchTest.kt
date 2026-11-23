package com.meetily.mobile

import com.meetily.mobile.data.ImportBatch
import com.meetily.mobile.data.ImportBatch.Kind
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.whisper.VideoAudio
import com.meetily.mobile.whisper.VideoAudio.Keep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files handed over together: what each one is, how many fit in the import
 * queue, what each meeting is called, what of a video is kept, and the
 * queued job's batch position.
 */
class ImportBatchTest {

    // --- What a file is ------------------------------------------------------

    @Test
    fun audioAndVideoAreMediaWhateverTheirName() {
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("video/mp4", "GMT20240115-150212_Recording_1920x1080.mp4"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("audio/mpeg", "memo"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("video/webm", "screen.webm"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("application/ogg", "talk.ogg"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("audio/mp4; codecs=mp4a", "a"))
    }

    @Test
    fun captionsAreKnownByTypeOrByExtensionWhenTheTypeSaysLittle() {
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf("text/vtt", "call.vtt"))
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf("text/vtt; charset=utf-8", "document:42"))
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf("application/x-subrip", "1234"))
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf("text/plain", "Weekly sync.vtt"))
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf("application/octet-stream", "talk.SRT"))
        assertEquals(Kind.SUBTITLE, ImportBatch.kindOf(null, "GMT20240115-150212_Recording.transcript.vtt"))
    }

    @Test
    fun aTypeThatSaysNothingIsTriedAsMediaAndOtherFilesAreNot() {
        assertEquals(Kind.MEDIA, ImportBatch.kindOf(null, "1234"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("application/octet-stream", "clip.mov"))
        assertEquals(Kind.MEDIA, ImportBatch.kindOf("text/plain", "meeting.m4a"))
        assertEquals(Kind.UNSUPPORTED, ImportBatch.kindOf("text/plain", "notes.txt"))
        assertEquals(Kind.UNSUPPORTED, ImportBatch.kindOf("application/pdf", "agenda.pdf"))
        assertEquals(Kind.UNSUPPORTED, ImportBatch.kindOf("image/jpeg", "whiteboard.jpg"))
    }

    @Test
    fun extensionsAreShortAndCaseless() {
        assertEquals("mp4", ImportBatch.extensionOf("Team sync.MP4"))
        assertEquals("", ImportBatch.extensionOf("v2.0 notes"))
        assertEquals("", ImportBatch.extensionOf(".hidden"))
        assertEquals("", ImportBatch.extensionOf("trailing."))
        assertEquals("vtt", ImportBatch.extensionOf("primary:Movies/zoom/call.vtt"))
    }

    // --- What each meeting is called -----------------------------------------

    @Test
    fun aMeetingIsNamedFromItsFile() {
        assertEquals("Weekly sync", ImportBatch.titleFor("Weekly sync.mp4"))
        assertEquals("GMT20240115-150212 Recording 1920x1080",
            ImportBatch.titleFor("GMT20240115-150212_Recording_1920x1080.mp4"))
        // Zoom's transcript is named after its recording.
        assertEquals("GMT20240115-150212 Recording",
            ImportBatch.titleFor("GMT20240115-150212_Recording.transcript.vtt"))
        assertEquals("GMT20240115-150212 Recording",
            ImportBatch.titleFor("GMT20240115-150212_Recording.cc.vtt"))
        assertEquals("Project kickoff", ImportBatch.titleFor("Project  kickoff .srt"))
        assertEquals("call", ImportBatch.titleFor("primary:Download/call.m4a"))
        assertEquals("v2.0 notes", ImportBatch.titleFor("v2.0 notes"))
        // Only a caption file's ".transcript" goes.
        assertEquals("talk.transcript", ImportBatch.titleFor("talk.transcript.mp4"))
    }

    @Test
    fun aNameThatLeavesNothingIsKeptAsItIs() {
        assertEquals("___.mp3", ImportBatch.titleFor("___.mp3"))
        assertEquals(".mp4", ImportBatch.titleFor(".mp4"))
    }

    // --- How many fit --------------------------------------------------------

    @Test
    fun theQueueTakesFilesInOrderUpToItsCap() {
        val all = ImportBatch.admit(25, queued = 0)
        assertEquals((0 until 20).toList(), all.admitted)
        assertEquals((20 until 25).toList(), all.leftOut)
        assertEquals(JobQueue.MAX_IMPORTS, all.admitted.size)

        val some = ImportBatch.admit(5, queued = 18)
        assertEquals(listOf(0, 1), some.admitted)
        assertEquals(listOf(2, 3, 4), some.leftOut)
    }

    @Test
    fun aFullQueueTakesNoneAndAnEmptyBatchLeavesNothingOut() {
        assertEquals(listOf(0, 1, 2), ImportBatch.admit(3, queued = 20).leftOut)
        assertTrue(ImportBatch.admit(3, queued = 27).admitted.isEmpty())
        val none = ImportBatch.admit(0, queued = 4)
        assertTrue(none.admitted.isEmpty() && none.leftOut.isEmpty())
        assertEquals(listOf(0, 1, 2), ImportBatch.admit(3, queued = 0).admitted)
        assertEquals(listOf(0), ImportBatch.admit(1, queued = 19).admitted)
    }

    @Test
    fun summariesNameAFewFilesAndCountTheRest() {
        assertEquals("a, b" to 0, ImportBatch.nameList(listOf("a", "b")))
        assertEquals("a, b, c, d" to 2, ImportBatch.nameList(listOf("a", "b", "c", "d", "e", "f")))
        assertEquals("" to 0, ImportBatch.nameList(emptyList()))
    }

    @Test
    fun onlyABatchSaysWhichFileItIs() {
        assertTrue(ImportBatch.showsPosition(2, 5))
        assertFalse(ImportBatch.showsPosition(0, 0))
        assertFalse(ImportBatch.showsPosition(1, 1))
        assertFalse(ImportBatch.showsPosition(6, 5))
    }

    // --- What of a video is kept ---------------------------------------------

    @Test
    fun aVideosAacSoundIsRemuxedToM4a() {
        assertEquals(Keep.M4A, VideoAudio.plan(listOf("video/avc", "audio/mp4a-latm"), 26))
        // The first sound track, as the decoder reads it.
        assertEquals(
            Keep.M4A, VideoAudio.plan(listOf("video/hevc", "audio/mp4a-latm", "audio/ac3"), 34)
        )
        assertEquals(Keep.THREE_GPP, VideoAudio.plan(listOf("video/3gpp", "audio/3gpp"), 26))
        assertEquals(Keep.THREE_GPP, VideoAudio.plan(listOf("audio/amr-wb", "video/mp4v-es"), 26))
    }

    @Test
    fun webmSoundStaysWebmWhereTheMuxerTakesIt() {
        assertEquals(Keep.WEBM, VideoAudio.plan(listOf("video/x-vnd.on2.vp8", "audio/vorbis"), 26))
        assertEquals(Keep.WEBM, VideoAudio.plan(listOf("video/x-vnd.on2.vp9", "audio/opus"), 29))
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("video/x-vnd.on2.vp9", "audio/opus"), 28))
    }

    @Test
    fun soundAnMpeg4FileCannotHoldKeepsTheWholeFile() {
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("video/avc", "audio/mpeg"), 34))
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("video/avc", "audio/raw"), 34))
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("video/avc", "audio/eac3"), 34))
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("video/avc", "audio/flac"), 34))
    }

    @Test
    fun audioFilesAreCopiedAsBeforeAndFilesWithoutSoundAreRefused() {
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("audio/mp4a-latm"), 34))
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(listOf("audio/mpeg"), 34))
        // Could not be opened this way: copied and decoded as before.
        assertEquals(Keep.WHOLE_FILE, VideoAudio.plan(null, 34))
        assertEquals(Keep.NO_SOUND, VideoAudio.plan(listOf("video/avc"), 34))
        assertEquals(Keep.NO_SOUND, VideoAudio.plan(emptyList(), 34))
        assertEquals(Keep.NO_SOUND, VideoAudio.plan(listOf("application/x-subrip", "video/avc"), 34))
        assertFalse(Keep.WHOLE_FILE.remuxes)
        assertFalse(Keep.NO_SOUND.remuxes)
        assertTrue(Keep.M4A.remuxes && Keep.THREE_GPP.remuxes && Keep.WEBM.remuxes)
    }

    @Test
    fun theKeptSoundIsNamedForItsFormat() {
        assertEquals("Weekly sync.m4a", VideoAudio.storedName("Weekly sync.mp4", Keep.M4A))
        assertEquals("screen.webm", VideoAudio.storedName("screen.mkv", Keep.WEBM))
        assertEquals("clip.3gp", VideoAudio.storedName("clip", Keep.THREE_GPP))
        assertEquals("Weekly sync.mp4", VideoAudio.storedName("Weekly sync.mp4", Keep.WHOLE_FILE))
    }

    @Test
    fun onlyAnotherProcesssRemuxedFilesAreStale() {
        val mine = VideoAudio.extractName("aaa111", "id-1") + ".m4a"
        val theirs = VideoAudio.extractName("bbb222", "id-2") + ".m4a"
        assertFalse(VideoAudio.isStale(mine, "aaa111"))
        assertTrue(VideoAudio.isStale(theirs, "aaa111"))
        assertFalse(VideoAudio.isStale("queued-1234.m4a", "aaa111"))
        assertFalse(VideoAudio.isStale("3f2a-meeting.aac", "aaa111"))
        // A token that merely starts the same is not ours.
        assertTrue(VideoAudio.isStale(VideoAudio.extractName("aaa1112", "x"), "aaa111"))
    }

    // --- The queued job ------------------------------------------------------

    @Test
    fun aBatchFileKeepsItsPositionThroughTheQueue() {
        val job = JobQueue.Job(
            JobQueue.KIND_IMPORT, "", "small", queuedAtMs = 9L,
            stagedFile = "queued-1.m4a", sourceName = "Call.mp4", language = "de",
            batchIndex = 2, batchSize = 5
        )
        val single = JobQueue.Job(
            JobQueue.KIND_IMPORT, "", "small", queuedAtMs = 10L,
            stagedFile = "queued-2.m4a", sourceName = "Memo.m4a"
        )
        val jobs = listOf(job, single)
        assertEquals(jobs, JobQueue.decode(JobQueue.encode(jobs)))
        assertFalse(JobQueue.encode(listOf(single)).contains("batch"))
    }

    @Test
    fun anImportQueuedBeforeBatchesIsAFileOnItsOwn() {
        // A row exactly as 3.18.0 wrote it.
        val raw = """[{"kind":"import","meetingId":"","payload":"small","queuedAtMs":5,
            "interrupted":false,"stagedFile":"queued-9.m4a","sourceName":"Call.m4a",
            "chargingOnly":false,"firstTranscript":false,"required":false,"auto":false,
            "language":""}]"""
        val job = JobQueue.decode(raw).single()
        assertEquals(0, job.batchIndex)
        assertEquals(0, job.batchSize)
        assertEquals("queued-9.m4a", job.stagedFile)
        val bad = """[{"kind":"import","meetingId":"","batchIndex":-3,"batchSize":-1}]"""
        assertEquals(0, JobQueue.decode(bad).single().batchSize)
    }
}
