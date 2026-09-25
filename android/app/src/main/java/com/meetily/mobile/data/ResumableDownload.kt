package com.meetily.mobile.data

import com.meetily.mobile.security.ModelIntegrity
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections

/**
 * What the sidecar next to a `.part` file remembers about the response that
 * started it, so a later run can ask for the rest of *the same* file.
 *
 * [url] is the catalogue URL (not the signed CDN URL a redirect led to — that
 * expires), so a catalogue change, e.g. a newly pinned revision, restarts.
 * [total] is the full file length; <= 0 when the server declared none, which
 * makes the part non-resumable (nothing to check a resumed tail against).
 */
data class PartMeta(
    val url: String,
    val etag: String?,
    val lastModified: String?,
    val total: Long
) {
    /**
     * The If-Range validator: a strong ETag, else Last-Modified, else null.
     * A weak ETag (`W/"…"`) is not allowed in If-Range (RFC 9110 §13.1.5).
     */
    val validator: String?
        get() = etag?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            ?: lastModified?.takeIf { it.isNotEmpty() }

    fun format(): String = buildString {
        append("v=1\n")
        append("url=").append(url).append('\n')
        if (etag != null) append("etag=").append(etag).append('\n')
        if (lastModified != null) append("lastModified=").append(lastModified).append('\n')
        append("total=").append(total).append('\n')
    }

    companion object {
        /** Header values are single-line by spec; strip anything that isn't. */
        private fun clean(value: String?): String? =
            value?.replace("\r", "")?.replace("\n", "")?.trim()?.takeIf { it.isNotEmpty() }

        fun of(url: String, etag: String?, lastModified: String?, total: Long): PartMeta =
            PartMeta(url, clean(etag), clean(lastModified), total)

        /** Null for anything unreadable: the caller then simply restarts. */
        fun parse(text: String): PartMeta? {
            val map = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            if (map["v"] != "1") return null
            val url = map["url"]?.takeIf { it.isNotEmpty() } ?: return null
            val total = map["total"]?.toLongOrNull() ?: return null
            return PartMeta(
                url,
                map["etag"]?.takeIf { it.isNotEmpty() },
                map["lastModified"]?.takeIf { it.isNotEmpty() },
                total
            )
        }
    }
}

/** The request to send: [offset] 0 means a plain full download. */
data class RangeRequest(val offset: Long, val headers: Map<String, String>)

/** What to do with a response, given what was asked for. */
sealed class RangeDecision {
    /** 206 for exactly the requested tail: append after [offset] bytes. */
    data class Append(val offset: Long, val total: Long) : RangeDecision()

    /** 200 (range ignored, or If-Range validator mismatch): truncate, write from 0. */
    data class Fresh(val total: Long) : RangeDecision()

    /** Nothing usable in this body (416, or a 206 for the wrong range): discard and re-request whole. */
    object Retry : RangeDecision()

    data class Fail(val status: Int) : RangeDecision()
}

data class ContentRange(val start: Long, val end: Long, val total: Long)

/**
 * The pure half of resuming: which request to make and how to read the
 * answer. No I/O, no Android — unit-tested.
 */
object ResumeLogic {

    private const val IDENTITY = "identity"

    /**
     * Resume only when every piece needed to trust the tail is present: a
     * sidecar for this same URL, a validator for If-Range, a known total
     * that the part hasn't already reached. Otherwise start over.
     *
     * Accept-Encoding is pinned to identity: HttpURLConnection otherwise
     * negotiates gzip and decompresses transparently, which hides
     * Content-Length and makes byte offsets meaningless.
     */
    fun plan(existingBytes: Long, meta: PartMeta?, url: String): RangeRequest {
        val base = mapOf("Accept-Encoding" to IDENTITY)
        if (existingBytes <= 0L || meta == null || meta.url != url) return RangeRequest(0L, base)
        val validator = meta.validator ?: return RangeRequest(0L, base)
        if (meta.total <= 0L || existingBytes >= meta.total) return RangeRequest(0L, base)
        return RangeRequest(
            existingBytes,
            base + mapOf("Range" to "bytes=$existingBytes-", "If-Range" to validator)
        )
    }

    private val CONTENT_RANGE =
        Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

    /** `bytes <start>-<end>/<total|*>`; total -1 for `*`. Null when malformed. */
    fun parseContentRange(value: String?): ContentRange? {
        if (value == null) return null
        val m = CONTENT_RANGE.find(value) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val totalText = m.groupValues[3]
        val total = if (totalText == "*") -1L else (totalText.toLongOrNull() ?: return null)
        if (end < start || (total > 0L && end >= total)) return null
        return ContentRange(start, end, total)
    }

    /**
     * [contentLength] is the response's own Content-Length (the tail's
     * length on a 206), <= 0 when absent.
     */
    fun decide(
        status: Int,
        requestedOffset: Long,
        contentRange: String?,
        contentLength: Long
    ): RangeDecision {
        if (status == 206) {
            if (requestedOffset <= 0L) return RangeDecision.Retry
            val cr = parseContentRange(contentRange) ?: return RangeDecision.Retry
            if (cr.start != requestedOffset) return RangeDecision.Retry
            val total = when {
                cr.total > 0L -> cr.total
                contentLength > 0L -> requestedOffset + contentLength
                else -> -1L
            }
            // Asked for an open-ended tail; a closed sub-range would leave a hole.
            if (total > 0L && cr.end != total - 1) return RangeDecision.Retry
            if (contentLength > 0L && contentLength != cr.end - cr.start + 1) {
                return RangeDecision.Retry
            }
            return RangeDecision.Append(requestedOffset, total)
        }
        if (status == 416) return RangeDecision.Retry
        if (status in 200..299) return RangeDecision.Fresh(contentLength)
        return RangeDecision.Fail(status)
    }

    /**
     * A 4xx other than timeout/rate-limit won't fix itself by retrying the
     * same URL, so the part it belonged to is worthless.
     */
    fun isPermanentFailure(status: Int): Boolean =
        status in 400..499 && status != 408 && status != 429

    /**
     * Absolute URL for a redirect's Location (which may be relative), or null
     * when there is none or it would downgrade https to http / leave http(s).
     */
    fun resolveRedirect(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val from = try {
            URL(current)
        } catch (_: Exception) {
            return null
        }
        val next = try {
            URL(from, location.trim())
        } catch (_: Exception) {
            return null
        }
        val proto = next.protocol.lowercase()
        if (proto != "https" && proto != "http") return null
        if (from.protocol.equals("https", ignoreCase = true) && proto != "https") return null
        return next.toString()
    }

    /** Legacy `.part-<nanos>` names were never resumable; resumable parts age out. */
    const val STALE_PART_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun shouldSweep(name: String, lastModifiedMs: Long, nowMs: Long): Boolean {
        if (name.contains(".part-")) return true
        val resumable = name.endsWith(ResumableDownload.PART_SUFFIX) ||
            name.endsWith(ResumableDownload.META_SUFFIX) ||
            name.endsWith(ResumableDownload.META_SUFFIX + ".tmp")
        return resumable && nowMs - lastModifiedMs > STALE_PART_MS
    }
}

/** One HTTP response, abstracted so the download core is testable without a network. */
interface RangeResponse : Closeable {
    val status: Int

    /** Content-Length, or <= 0 when absent. */
    val contentLength: Long

    fun header(name: String): String?

    fun body(): InputStream
}

fun interface RangeTransport {
    fun open(url: String, headers: Map<String, String>): RangeResponse
}

/**
 * HttpURLConnection with redirects followed by hand, so Range/If-Range are
 * sent on every hop — Hugging Face answers `/resolve/` with a 302 to its CDN
 * (sometimes a relative Location), and GitHub releases do the same.
 * Relying on automatic following would leave header propagation to the
 * platform's HTTP stack.
 */
object HttpRangeTransport : RangeTransport {

    private const val MAX_REDIRECTS = 10

    override fun open(url: String, headers: Map<String, String>): RangeResponse {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val status = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }
            if (status in 300..399 && status != 304) {
                val next = ResumeLogic.resolveRedirect(current, c.getHeaderField("Location"))
                c.disconnect()
                current = next ?: throw IOException("Unfollowable redirect (HTTP $status)")
                return@repeat
            }
            return ConnectionResponse(c, status)
        }
        throw IOException("Too many redirects")
    }

    private class ConnectionResponse(
        private val c: HttpURLConnection,
        override val status: Int
    ) : RangeResponse {
        override val contentLength: Long get() = c.contentLengthLong
        override fun header(name: String): String? = c.getHeaderField(name)
        override fun body(): InputStream = c.inputStream
        override fun close() {
            c.disconnect()
        }
    }
}

/**
 * Model downloads that survive interruption.
 *
 * Bytes stream into `<target>.part` with a small `<target>.part.meta`
 * sidecar (URL, ETag/Last-Modified, total). A later run finding both asks
 * for `Range: bytes=<len>-` with `If-Range`; on 206 it appends, on 200 (the
 * file changed upstream, or the server ignores ranges) it starts over, on
 * 416 it discards and re-requests whole. The SHA-256 always covers the
 * whole file: an existing prefix is re-read into the digest first.
 *
 * A part is kept on network errors, cancellation (which includes the
 * system's foreground-service timeout) and early EOF — those are exactly
 * the interruptions worth resuming — and discarded on an integrity failure
 * or a permanent HTTP error. The file is renamed into place only after
 * [ModelIntegrity.verify] passes.
 */
object ResumableDownload {

    const val PART_SUFFIX = ".part"
    const val META_SUFFIX = ".part.meta"

    /** Parts are no longer unique per run, so two runs on one target must not overlap. */
    private val active: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun partFile(target: File): File = File(target.path + PART_SUFFIX)

    fun metaFile(target: File): File = File(target.path + META_SUFFIX)

    /** Deletes [target]'s resumable part and sidecar, if any. */
    fun discardPartial(target: File) {
        partFile(target).delete()
        metaFile(target).delete()
    }

    /**
     * Sweeps legacy `.part-*` leftovers and resumable parts untouched for
     * [ResumeLogic.STALE_PART_MS]. Callers must not run it while a download
     * is in flight (see SettingsActivity's ModelDownloadService.isRunning guard).
     */
    fun cleanPartials(dir: File, recursive: Boolean = false) {
        val now = System.currentTimeMillis()
        val files: Sequence<File> = if (recursive) {
            dir.walkTopDown().filter { it.isFile }
        } else {
            (dir.listFiles() ?: emptyArray()).asSequence().filter { it.isFile }
        }
        files.filter { ResumeLogic.shouldSweep(it.name, it.lastModified(), now) }
            .toList()
            .forEach { it.delete() }
    }

    /** Adapts byte progress to deduped whole percents (0..100), as the single-file downloaders report. */
    fun percentReporter(onProgress: (Int) -> Unit): (Long, Long) -> Unit {
        var last = -1
        return { received, total ->
            if (total > 0L) {
                val p = ((received * 100) / total).toInt().coerceIn(0, 100)
                if (p != last) {
                    last = p
                    onProgress(p)
                }
            }
        }
    }

    /**
     * Blocking; call from a worker thread. [onBytes] gets (bytes of the
     * whole file on disk so far, full length or <= 0 when unknown) — so a
     * resumed download reports from where it left off, not from zero.
     */
    fun download(
        url: String,
        target: File,
        label: String,
        expectedSha256: String?,
        cancelled: () -> Boolean,
        onBytes: (Long, Long) -> Unit,
        transport: RangeTransport = HttpRangeTransport
    ) {
        val key = target.absolutePath
        if (!active.add(key)) throw IOException("$label is already downloading")
        try {
            target.parentFile?.mkdirs()
            if (attempt(url, target, label, expectedSha256, cancelled, onBytes, transport, true)) {
                return
            }
            // The resume attempt was unusable (416 / wrong range): one clean try.
            if (!attempt(url, target, label, expectedSha256, cancelled, onBytes, transport, false)) {
                throw IOException("Server refused the download of $label")
            }
        } finally {
            active.remove(key)
        }
    }

    /** True when the file is in place; false when the caller should retry without resuming. */
    private fun attempt(
        url: String,
        target: File,
        label: String,
        expectedSha256: String?,
        cancelled: () -> Boolean,
        onBytes: (Long, Long) -> Unit,
        transport: RangeTransport,
        allowResume: Boolean
    ): Boolean {
        val part = partFile(target)
        val meta = metaFile(target)
        val existing = if (allowResume && part.isFile) part.length() else 0L
        val plan = ResumeLogic.plan(existing, if (allowResume) readMeta(meta) else null, url)

        val digest = ModelIntegrity.newDigest()
        if (plan.offset > 0L) {
            // Before connecting, so the connection never idles through a
            // multi-GB re-read. Wasted only if the server then answers 200.
            hashPrefix(part, plan.offset, digest, cancelled)
        } else {
            discardPartial(target)
        }

        val response = transport.open(url, plan.headers)
        try {
            val decision = ResumeLogic.decide(
                response.status,
                plan.offset,
                response.header("Content-Range"),
                response.contentLength
            )
            val offset: Long
            val total: Long
            when (decision) {
                is RangeDecision.Append -> {
                    offset = decision.offset
                    total = decision.total
                }
                is RangeDecision.Fresh -> {
                    offset = 0L
                    total = decision.total
                    digest.reset()
                }
                is RangeDecision.Retry -> {
                    discardPartial(target)
                    if (plan.offset == 0L) {
                        throw IOException("HTTP ${response.status} while downloading $label")
                    }
                    return false
                }
                is RangeDecision.Fail -> {
                    if (ResumeLogic.isPermanentFailure(decision.status)) discardPartial(target)
                    throw IOException("HTTP ${decision.status} while downloading $label")
                }
            }

            // Pin the part to exactly what the digest covers, then record the
            // validators before the first new byte: from here on an
            // interruption leaves a resumable part.
            if (offset > 0L) {
                RandomAccessFile(part, "rw").use { it.setLength(offset) }
            } else {
                part.delete()
            }
            writeMeta(
                meta,
                PartMeta.of(url, response.header("ETag"), response.header("Last-Modified"), total)
            )

            var received = offset
            onBytes(received, total)
            FileOutputStream(part, offset > 0L).use { output ->
                response.body().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedException("Download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        onBytes(received, total)
                    }
                }
            }

            if (total > 0L && received < total) {
                // Early EOF without an exception: keep the part, resume next time.
                throw IOException("Download of $label was interrupted at $received of $total bytes")
            }
            try {
                // Before the rename: a file that fails here never reaches the
                // native loader.
                ModelIntegrity.verify(label, received, total, expectedSha256, digest)
            } catch (e: ModelIntegrity.IntegrityException) {
                discardPartial(target)
                throw e
            }
            meta.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return true
        } finally {
            try {
                response.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readMeta(meta: File): PartMeta? = try {
        if (meta.isFile) PartMeta.parse(meta.readText()) else null
    } catch (_: IOException) {
        null
    }

    private fun writeMeta(meta: File, value: PartMeta) {
        val tmp = File(meta.path + ".tmp")
        tmp.writeText(value.format())
        if (!tmp.renameTo(meta)) {
            tmp.copyTo(meta, overwrite = true)
            tmp.delete()
        }
    }

    private fun hashPrefix(
        part: File,
        length: Long,
        digest: MessageDigest,
        cancelled: () -> Boolean
    ) {
        FileInputStream(part).use { input ->
            val buffer = ByteArray(256 * 1024)
            var remaining = length
            while (remaining > 0L) {
                if (cancelled()) throw InterruptedException("Download cancelled")
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Partial download shrank while resuming")
                digest.update(buffer, 0, n)
                remaining -= n
            }
        }
    }
}
