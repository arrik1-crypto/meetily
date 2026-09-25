package com.meetily.mobile

import com.meetily.mobile.data.PartMeta
import com.meetily.mobile.data.RangeDecision
import com.meetily.mobile.data.RangeResponse
import com.meetily.mobile.data.RangeTransport
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.data.ResumeLogic
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.security.ModelIntegrity
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.WhisperModels
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ResumableDownloadTest {

    private val url = "https://huggingface.co/org/repo/resolve/main/model.bin"

    // --- Pure decision logic ----------------------------------------------

    @Test
    fun planStartsFreshWithoutAUsableSidecar() {
        val meta = PartMeta(url, "\"abc\"", null, 1000)
        assertEquals(0L, ResumeLogic.plan(0, meta, url).offset)
        assertEquals(0L, ResumeLogic.plan(500, null, url).offset)
        assertEquals(0L, ResumeLogic.plan(500, meta.copy(url = "$url?x"), url).offset)
        assertEquals(0L, ResumeLogic.plan(500, meta.copy(total = -1), url).offset)
        assertEquals(0L, ResumeLogic.plan(1000, meta, url).offset)
        // No validator: a tail could come from a different file.
        assertEquals(0L, ResumeLogic.plan(500, meta.copy(etag = null), url).offset)
        assertEquals(0L, ResumeLogic.plan(500, meta.copy(etag = "W/\"weak\""), url).offset)
        val fresh = ResumeLogic.plan(0, meta, url)
        assertNull(fresh.headers["Range"])
        assertEquals("identity", fresh.headers["Accept-Encoding"])
    }

    @Test
    fun planAsksForTheTailWithIfRange() {
        val plan = ResumeLogic.plan(500, PartMeta(url, "\"abc\"", "Mon, 01 Jan 2024 00:00:00 GMT", 1000), url)
        assertEquals(500L, plan.offset)
        assertEquals("bytes=500-", plan.headers["Range"])
        assertEquals("\"abc\"", plan.headers["If-Range"])
        // Weak ETag falls back to Last-Modified.
        val lm = ResumeLogic.plan(500, PartMeta(url, "W/\"abc\"", "Mon, 01 Jan 2024 00:00:00 GMT", 1000), url)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", lm.headers["If-Range"])
    }

    @Test
    fun contentRangeParsing() {
        val cr = ResumeLogic.parseContentRange("bytes 500-999/1000")!!
        assertEquals(500L, cr.start)
        assertEquals(999L, cr.end)
        assertEquals(1000L, cr.total)
        assertEquals(-1L, ResumeLogic.parseContentRange("bytes 0-9/*")!!.total)
        assertNull(ResumeLogic.parseContentRange(null))
        assertNull(ResumeLogic.parseContentRange("bytes 5-4/10"))
        assertNull(ResumeLogic.parseContentRange("bytes 0-10/10"))
        assertNull(ResumeLogic.parseContentRange("items 0-1/2"))
    }

    @Test
    fun decideReadsEachStatus() {
        assertEquals(RangeDecision.Append(500, 1000), ResumeLogic.decide(206, 500, "bytes 500-999/1000", 500))
        assertEquals(RangeDecision.Append(500, 1000), ResumeLogic.decide(206, 500, "bytes 500-999/*", 500))
        assertEquals(RangeDecision.Fresh(1000), ResumeLogic.decide(200, 500, null, 1000))
        assertEquals(RangeDecision.Fresh(1000), ResumeLogic.decide(200, 0, null, 1000))
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(416, 1000, "bytes */1000", 0))
        // Wrong start, closed sub-range, length disagreeing, unasked 206.
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(206, 500, "bytes 400-999/1000", 600))
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(206, 500, "bytes 500-599/1000", 100))
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(206, 500, "bytes 500-999/1000", 400))
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(206, 0, "bytes 0-999/1000", 1000))
        assertEquals(RangeDecision.Retry, ResumeLogic.decide(206, 500, null, 500))
        assertEquals(RangeDecision.Fail(404), ResumeLogic.decide(404, 500, null, 0))
        assertEquals(RangeDecision.Fail(503), ResumeLogic.decide(503, 0, null, 0))
        assertTrue(ResumeLogic.isPermanentFailure(404))
        assertFalse(ResumeLogic.isPermanentFailure(429))
        assertFalse(ResumeLogic.isPermanentFailure(503))
    }

    @Test
    fun redirectsResolveRelativeAndRefuseDowngrade() {
        assertEquals(
            "https://huggingface.co/api/resolve-cache/x",
            ResumeLogic.resolveRedirect(url, "/api/resolve-cache/x")
        )
        assertEquals(
            "https://cdn-lfs.hf.co/a?b=c",
            ResumeLogic.resolveRedirect(url, "https://cdn-lfs.hf.co/a?b=c")
        )
        assertNull(ResumeLogic.resolveRedirect(url, "http://cdn.example/a"))
        assertNull(ResumeLogic.resolveRedirect(url, "ftp://cdn.example/a"))
        assertNull(ResumeLogic.resolveRedirect(url, null))
    }

    @Test
    fun metaRoundTripsAndRejectsGarbage() {
        val meta = PartMeta.of(url, "\"e\r\ntag\"", "Mon, 01 Jan 2024 00:00:00 GMT", 42)
        assertEquals("\"etag\"", meta.etag)
        assertEquals(meta, PartMeta.parse(meta.format()))
        assertEquals(PartMeta(url, null, null, -1), PartMeta.parse(PartMeta(url, null, null, -1).format()))
        assertNull(PartMeta.parse(""))
        assertNull(PartMeta.parse("url=$url\ntotal=1\n"))
        assertNull(PartMeta.parse("v=1\nurl=$url\ntotal=x\n"))
    }

    @Test
    fun sweepKeepsFreshResumablePartsOnly() {
        val now = 10L * ResumeLogic.STALE_PART_MS
        assertTrue(ResumeLogic.shouldSweep("m.bin.part-123", now, now))
        assertFalse(ResumeLogic.shouldSweep("m.bin.part", now - 1000, now))
        assertFalse(ResumeLogic.shouldSweep("m.bin.part.meta", now - 1000, now))
        assertTrue(ResumeLogic.shouldSweep("m.bin.part", now - ResumeLogic.STALE_PART_MS - 1, now))
        assertTrue(ResumeLogic.shouldSweep("m.bin.part.meta", now - ResumeLogic.STALE_PART_MS - 1, now))
        assertFalse(ResumeLogic.shouldSweep("m.bin", 0, now))
        assertFalse(ResumeLogic.shouldSweep("encoder.int8.onnx.ok", 0, now))
    }

    @Test
    fun percentReporterDedupesAndIgnoresUnknownTotals() {
        val out = mutableListOf<Int>()
        val report = ResumableDownload.percentReporter { out.add(it) }
        report(10, -1)
        report(500, 1000)
        report(501, 1000)
        report(1000, 1000)
        assertEquals(listOf(50, 100), out)
    }

    @Test
    fun revisionPinning() {
        assertEquals(url, ModelIntegrity.pinRevision(url, null))
        assertEquals(url, ModelIntegrity.pinRevision(url, " "))
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            "https://huggingface.co/org/repo/resolve/$sha/model.bin",
            ModelIntegrity.pinRevision(url, sha)
        )
        assertFalse(ModelIntegrity.isValidRevision("a/b"))
        assertFalse(ModelIntegrity.isValidRevision(".."))
        assertFalse(ModelIntegrity.isValidRevision(""))
        try {
            ModelIntegrity.pinRevision(url, "../x")
            fail("bad revision accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun cataloguesKeepMainWhenUnpinnedAndPinWhenSet() {
        for (m in LocalLlmModels.ALL) {
            m.revision?.let { assertTrue(m.key, ModelIntegrity.isValidRevision(it)) }
            if (m.revision == null) assertEquals(m.url, m.downloadUrl)
            assertTrue(m.key, m.downloadUrl.startsWith("https://huggingface.co/"))
        }
        for (m in WhisperModels.ALL) {
            m.revision?.let { assertTrue(m.key, ModelIntegrity.isValidRevision(it)) }
            assertTrue(m.key, m.url.contains("/resolve/${m.revision ?: "main"}/${m.fileName}"))
        }
        for (m in NemoModels.ALL) {
            m.revision?.let { assertTrue(m.key, ModelIntegrity.isValidRevision(it)) }
        }
        val whisper = WhisperModels.ALL.first().copy(revision = "abc123")
        assertTrue(whisper.url.endsWith("/resolve/abc123/${whisper.fileName}"))
        val nemo = NemoModels.ALL.first().copy(revision = "abc123")
        val file = nemo.files.first()
        assertTrue(nemo.urlFor(file).endsWith("/resolve/abc123/${file.name}"))
    }

    // --- Download core against a fake server ------------------------------

    private lateinit var dir: File
    private lateinit var target: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("resume").toFile()
        target = File(dir, "model.bin")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val data = ByteArray(10_000) { (it * 31 + 7).toByte() }
    private val dataSha = ModelIntegrity.toHex(ModelIntegrity.newDigest().digest(data))

    private class Resp(
        override val status: Int,
        private val headers: Map<String, String>,
        private val bytes: ByteArray,
        private val failAfter: Int = -1
    ) : RangeResponse {
        override val contentLength: Long get() = headers["Content-Length"]?.toLong() ?: -1L
        override fun header(name: String): String? = headers[name]
        override fun body(): InputStream {
            if (failAfter < 0) return ByteArrayInputStream(bytes)
            val inner = ByteArrayInputStream(bytes)
            return object : InputStream() {
                var sent = 0
                override fun read(): Int {
                    if (sent >= failAfter) throw IOException("connection reset")
                    sent++
                    return inner.read()
                }
            }
        }
        override fun close() {}
    }

    /** Honours Range + If-Range like a CDN; [etag] may change between runs. */
    private inner class FakeServer(
        var etag: String = "\"v1\"",
        var content: ByteArray = data,
        var honourRange: Boolean = true,
        var failAfter: Int = -1
    ) : RangeTransport {
        val requests = mutableListOf<Map<String, String>>()
        override fun open(url: String, headers: Map<String, String>): RangeResponse {
            requests.add(headers)
            val range = headers["Range"]
            val ifRange = headers["If-Range"]
            val fail = failAfter.also { failAfter = -1 }
            if (range != null && honourRange && (ifRange == null || ifRange == etag)) {
                val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (start >= content.size) {
                    return Resp(416, mapOf("Content-Range" to "bytes */${content.size}"), ByteArray(0))
                }
                val tail = content.copyOfRange(start, content.size)
                return Resp(
                    206,
                    mapOf(
                        "ETag" to etag,
                        "Content-Length" to tail.size.toString(),
                        "Content-Range" to "bytes $start-${content.size - 1}/${content.size}"
                    ),
                    tail,
                    fail
                )
            }
            return Resp(
                200,
                mapOf("ETag" to etag, "Content-Length" to content.size.toString()),
                content,
                fail
            )
        }
    }

    private fun run(server: FakeServer, sha: String? = dataSha, progress: MutableList<Long>? = null) =
        ResumableDownload.download(
            url = url,
            target = target,
            label = "model.bin",
            expectedSha256 = sha,
            cancelled = { false },
            onBytes = { received, _ -> progress?.add(received) },
            transport = server
        )

    private fun interruptedRun(server: FakeServer, after: Int) {
        server.failAfter = after
        try {
            run(server)
            fail("expected the fake connection to drop")
        } catch (_: IOException) {
        }
    }

    @Test
    fun interruptedDownloadResumesAndHashesTheWholeFile() {
        val server = FakeServer()
        interruptedRun(server, 4_000)
        assertFalse(target.exists())
        assertEquals(4_000L, ResumableDownload.partFile(target).length())
        assertTrue(ResumableDownload.metaFile(target).exists())

        val progress = mutableListOf<Long>()
        run(server, progress = progress)
        assertEquals("bytes=4000-", server.requests.last()["Range"])
        assertEquals("\"v1\"", server.requests.last()["If-Range"])
        assertArrayEquals(data, target.readBytes())
        assertEquals(4_000L, progress.first())
        assertEquals(10_000L, progress.last())
        assertFalse(ResumableDownload.partFile(target).exists())
        assertFalse(ResumableDownload.metaFile(target).exists())
    }

    @Test
    fun changedUpstreamFileRestartsFromZero() {
        val server = FakeServer()
        interruptedRun(server, 4_000)
        val v2 = ByteArray(8_000) { (it * 17).toByte() }
        server.etag = "\"v2\""
        server.content = v2
        run(server, sha = null)
        assertArrayEquals(v2, target.readBytes())
    }

    @Test
    fun serverIgnoringRangeRestarts() {
        val server = FakeServer()
        interruptedRun(server, 4_000)
        server.honourRange = false
        run(server)
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun unsatisfiableRangeDiscardsAndRetriesWhole() {
        val server = FakeServer()
        interruptedRun(server, 4_000)
        // Server now has a shorter file under the same validator: 416.
        server.content = data.copyOfRange(0, 3_000)
        run(server, sha = null)
        // interrupted run, then the 416 resume attempt, then a whole request
        assertEquals(3, server.requests.size)
        assertEquals("bytes=4000-", server.requests[1]["Range"])
        assertNull(server.requests[2]["Range"])
        assertArrayEquals(data.copyOfRange(0, 3_000), target.readBytes())
    }

    @Test
    fun hashMismatchDiscardsThePart() {
        val server = FakeServer()
        try {
            run(server, sha = "0".repeat(64))
            fail("mismatch accepted")
        } catch (_: ModelIntegrity.IntegrityException) {
        }
        assertFalse(target.exists())
        assertFalse(ResumableDownload.partFile(target).exists())
        assertFalse(ResumableDownload.metaFile(target).exists())
    }

    @Test
    fun corruptedPrefixIsCaughtByTheWholeFileHash() {
        val server = FakeServer()
        interruptedRun(server, 4_000)
        val part = ResumableDownload.partFile(target)
        val bytes = part.readBytes()
        bytes[10] = (bytes[10] + 1).toByte()
        part.writeBytes(bytes)
        try {
            run(server)
            fail("corrupted prefix accepted")
        } catch (_: ModelIntegrity.IntegrityException) {
        }
        assertFalse(part.exists())
    }

    @Test
    fun cancellationKeepsThePartForLater() {
        val server = FakeServer()
        try {
            ResumableDownload.download(
                url, target, "model.bin", dataSha, { true }, { _, _ -> }, server
            )
            fail("cancel ignored")
        } catch (_: InterruptedException) {
        }
        assertTrue(ResumableDownload.metaFile(target).exists())
        run(server)
        assertArrayEquals(data, target.readBytes())
    }
}
