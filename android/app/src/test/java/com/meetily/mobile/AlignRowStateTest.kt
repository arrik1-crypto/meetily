package com.meetily.mobile

import com.meetily.mobile.AlignRowState.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings' word re-timing row after a download of its model ends.
 *
 * ModelDownloadService.finishRun runs as one main-thread message: it calls
 * its observer's onDownloadDone (Settings, which forwards an align kind to
 * AlignModelRow.onDone) and only then takes the next queued download or
 * clears isRunning. [Service] plays that order on a fake main looper. A row
 * that drew inside the callback still read the ended download as running
 * and stayed on "Downloading… 0%", so the kept-download state a cancelled
 * or full-disk unpack leaves never showed while Settings was open.
 */
class AlignRowStateTest {

    /** The main looper: messages run in the order they were posted. */
    private val looper = ArrayDeque<() -> Unit>()

    private fun drain() {
        while (looper.isNotEmpty()) looper.removeFirst()()
    }

    /** The parts of ModelDownloadService the row reads, in finishRun's order. */
    private inner class Service {
        var isRunning = false
        var currentKind = ""
        val queue = ArrayDeque<String>()

        /** Settings' observer: an align download's end goes to the row. */
        var onDownloadDone: (kind: String) -> Unit = {}

        fun start(kind: String) {
            isRunning = true
            currentKind = kind
        }

        /** As runDownload's thread: posts finishRun as one message. */
        fun finish(kind: String) {
            looper.addLast {
                onDownloadDone(kind)
                val next = queue.removeFirstOrNull()
                if (next != null) currentKind = next else isRunning = false
            }
        }
    }

    private val service = Service()
    private var downloaded = false
    private var kept = false
    private val drawn = mutableListOf<Shown>()

    private val row = AlignRowState(
        aligning = { service.isRunning && service.currentKind == ModelDownloadService.KIND_ALIGN },
        downloaded = { downloaded },
        kept = { kept },
        later = { block -> looper.addLast(block) },
        draw = { drawn.add(it) }
    )

    init {
        service.onDownloadDone = { kind ->
            if (kind == ModelDownloadService.KIND_ALIGN) row.onDone()
        }
    }

    /** An align download that runs, then ends with [onDisk] already set. */
    private fun downloadAndEnd(onDisk: () -> Unit) {
        service.start(ModelDownloadService.KIND_ALIGN)
        row.render()
        assertEquals(Shown.DOWNLOADING, drawn.last())
        onDisk()
        service.finish(ModelDownloadService.KIND_ALIGN)
        drain()
    }

    @Test
    fun aStoppedOrFullDiskUnpackShowsTheKeptDownload() {
        // Cancel during unpacking, or a write that failed for lack of space:
        // the archive is kept and the model is not unpacked.
        downloadAndEnd { kept = true }
        assertEquals(Shown.KEPT, drawn.last())
        assertEquals(Shown.KEPT, row.shown())
    }

    @Test
    fun aFinishedDownloadShowsTheModelDownloaded() {
        downloadAndEnd { downloaded = true }
        assertEquals(Shown.DOWNLOADED, drawn.last())
    }

    @Test
    fun aFailedOrCancelledDownloadWithNothingKeptOffersTheDownload() {
        downloadAndEnd { }
        assertEquals(Shown.MISSING, drawn.last())
    }

    @Test
    fun unpackingAKeptDownloadEndsWithTheModelDownloaded() {
        // Unpack or delete… → Unpack: the same service run, unpacking only.
        kept = true
        row.render()
        assertEquals(Shown.KEPT, drawn.last())
        downloadAndEnd { kept = false; downloaded = true }
        assertEquals(Shown.DOWNLOADED, drawn.last())
    }

    @Test
    fun nothingIsDrawnWhileTheServiceStillReportsTheEndedDownload() {
        service.start(ModelDownloadService.KIND_ALIGN)
        kept = true
        service.finish(ModelDownloadService.KIND_ALIGN)
        // Run only finishRun's message: the row's draw waits behind it.
        looper.removeFirst()()
        assertTrue(drawn.isEmpty())
        assertEquals(1, looper.size)
        drain()
        assertEquals(listOf(Shown.KEPT), drawn)
    }

    @Test
    fun theModelPickedAgainAfterACancelShowsItsDownload() {
        // A cancelled align download still unwinding does not count as
        // running, so picking the model again queues it; finishRun starts
        // it next.
        service.queue.addLast(ModelDownloadService.KIND_ALIGN)
        downloadAndEnd { kept = true }
        assertEquals(Shown.DOWNLOADING, drawn.last())
    }

    @Test
    fun anotherModelQueuedNextLeavesTheRowOnItsOwnState() {
        service.queue.addLast(ModelDownloadService.KIND_LLM)
        downloadAndEnd { kept = true }
        assertTrue(service.isRunning)
        assertEquals(ModelDownloadService.KIND_LLM, service.currentKind)
        assertEquals(Shown.KEPT, drawn.last())
    }

    @Test
    fun anotherKindEndingDoesNotRedrawTheRow() {
        service.start(ModelDownloadService.KIND_LLM)
        service.finish(ModelDownloadService.KIND_LLM)
        drain()
        assertTrue(drawn.isEmpty())
    }

    @Test
    fun theDownloadIsCheckedBeforeWhatIsOnDisk() {
        downloaded = true
        kept = true
        assertEquals(Shown.DOWNLOADED, row.shown())
        service.start(ModelDownloadService.KIND_ALIGN)
        assertEquals(Shown.DOWNLOADING, row.shown())
        service.start(ModelDownloadService.KIND_LLM)
        assertEquals(Shown.DOWNLOADED, row.shown())
        downloaded = false
        assertEquals(Shown.KEPT, row.shown())
    }
}
