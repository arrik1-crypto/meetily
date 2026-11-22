package com.meetily.mobile.whisper

import java.io.File
import java.io.RandomAccessFile

/**
 * A structural check of an ONNX model file, for the loads in sherpa-onnx
 * that cannot fail cleanly.
 *
 * Since v2.0.0 the recognizer constructors turn a C++ exception into a Java
 * one, but the speaker-embedding extractor's do not (sherpa-onnx v2.0.3,
 * jni/speaker-embedding-extractor.cc). There a file onnxruntime cannot parse
 * throws through the JNI frame and aborts the process, and a parseable one
 * without the "framework" metadata entry leaves the extractor with no model
 * behind it, so the first call on it is a SIGSEGV. Neither is catchable.
 *
 * So the file is walked first as what every ONNX file is, a protobuf
 * ModelProto: each top-level field must have a valid tag and fit in the
 * file, the walk must end exactly at its end, and the graph, the IR version
 * and (for a speaker model) a known "framework" entry must be there. That
 * catches truncated, empty and foreign files without reading the graph:
 * the walk seeks over it.
 *
 * Pure JVM; the byte source is abstract so tests can use arrays.
 */
object OnnxSanity {

    /** What [inspect] found; [problem] is null when the structure is sound. */
    data class Report(
        val problem: String?,
        val irVersion: Long? = null,
        val graphBytes: Long = 0,
        val metadata: Map<String, String> = emptyMap()
    )

    /** Random-access bytes: a file in the app, an array in tests. */
    interface Source {
        val length: Long

        /** Reads up to [len] bytes at [pos] into [buf]; returns the count, or -1 at the end. */
        fun read(pos: Long, buf: ByteArray, len: Int): Int
    }

    class ArraySource(private val bytes: ByteArray) : Source {
        override val length: Long get() = bytes.size.toLong()

        override fun read(pos: Long, buf: ByteArray, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len.toLong(), bytes.size - pos).toInt()
            System.arraycopy(bytes, pos.toInt(), buf, 0, n)
            return n
        }
    }

    /**
     * The "framework" values sherpa-onnx's speaker extractor accepts, each
     * with the metadata its model class then reads (v2.0.3,
     * csrc/speaker-embedding-extractor-model.cc and -nemo-model.cc). A
     * missing entry, or an integer below 0, ends the process (_Exit) there.
     */
    private val GENERAL_INTS = listOf("output_dim", "sample_rate", "normalize_samples")
    private val NEMO_INTS =
        listOf("output_dim", "feat_dim", "sample_rate", "window_size_ms", "window_stride_ms")
    val SPEAKER_FRAMEWORKS: Map<String, List<String>> = mapOf(
        "wespeaker" to GENERAL_INTS,
        "3d-speaker" to GENERAL_INTS,
        "nemo" to NEMO_INTS
    )

    /** ModelProto field numbers used here. */
    private const val IR_VERSION = 1
    private const val GRAPH = 7
    private const val METADATA_PROPS = 14

    /** No real model has this many top-level fields; a bound on a hostile file. */
    private const val MAX_FIELDS = 100_000

    /** Metadata entries are short strings; a longer one is not read. */
    private const val MAX_ENTRY_BYTES = 64 * 1024

    /** Null when [file] can be handed to the speaker-embedding extractor, else why not. */
    fun speakerModelProblem(file: File): String? {
        if (!file.isFile) return "missing"
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val source = object : Source {
                    override val length: Long = raf.length()

                    override fun read(pos: Long, buf: ByteArray, len: Int): Int {
                        raf.seek(pos)
                        return raf.read(buf, 0, len)
                    }
                }
                speakerModelProblem(source)
            }
        } catch (e: Exception) {
            // IO or a permission refusal: either way the file is not loadable.
            // The message names the file by its path, which is not logged.
            "unreadable: ${e.message?.replace(file.path, file.name)}"
        }
    }

    fun speakerModelProblem(source: Source): String? {
        val report = inspect(source)
        report.problem?.let { return it }
        val framework = report.metadata["framework"]
            ?: return "no \"framework\" entry in the model metadata"
        val ints = SPEAKER_FRAMEWORKS[framework] ?: return "unsupported framework \"$framework\""
        for (key in ints) {
            val value = report.metadata[key]?.let(::metadataInt)
            if (value == null || value < 0) return "metadata \"$key\" missing or invalid"
        }
        if (report.metadata["language"].isNullOrEmpty()) return "metadata \"language\" missing"
        return null
    }

    /**
     * An integer metadata value as sherpa-onnx reads it (v2.0.3,
     * ToIntOrDefault in csrc/text-utils.cc), or null where it would fall back
     * to its default: trimmed of C whitespace, then of one pair of
     * surrounding double quotes and the whitespace inside them, then one
     * leading '+' (not followed by another sign), then an optional '-' and
     * ASCII digits only, within 32 bits. Neither stricter (a quoted "192"
     * loads there) nor looser (Kotlin's own trim and toIntOrNull accept
     * Unicode spaces and digits, which end the process there).
     */
    internal fun metadataInt(raw: String): Int? {
        var s = raw.trim(::isCSpace)
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            s = s.substring(1, s.length - 1).trim(::isCSpace)
        }
        if (s.startsWith('+')) {
            s = s.substring(1)
            if (s.startsWith('+') || s.startsWith('-')) return null
        }
        val digits = if (s.startsWith('-')) s.substring(1) else s
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val value = s.toLongOrNull() ?: return null
        return if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
    }

    /** std::isspace in the C locale: space, \t, \n, \v, \f and \r. */
    private fun isCSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    fun inspect(source: Source): Report {
        val length = source.length
        if (length <= 0) return Report("empty file")
        val reader = Reader(source)
        var pos = 0L
        var fields = 0
        var irVersion: Long? = null
        var graphBytes = 0L
        val metadata = LinkedHashMap<String, String>()
        while (pos < length) {
            if (++fields > MAX_FIELDS) return Report("too many top-level fields")
            val tag = reader.varint(pos) ?: return Report("bad field tag at byte $pos")
            pos = reader.next
            val field = tag ushr 3
            if (field < 1 || field > 0x1FFFFFFF) return Report("bad field number at byte $pos")
            when ((tag and 7L).toInt()) {
                0 -> {
                    val value = reader.varint(pos) ?: return Report("bad varint at byte $pos")
                    pos = reader.next
                    if (field == IR_VERSION.toLong()) irVersion = value
                }
                1 -> pos += 8
                2 -> {
                    val size = reader.varint(pos) ?: return Report("bad length at byte $pos")
                    pos = reader.next
                    if (size < 0 || size > length - pos) {
                        return Report("field $field runs past the end of the file")
                    }
                    when (field) {
                        GRAPH.toLong() -> graphBytes += size
                        METADATA_PROPS.toLong() ->
                            if (size <= MAX_ENTRY_BYTES) {
                                entry(reader, pos, size.toInt())?.let { (k, v) -> metadata[k] = v }
                            }
                    }
                    pos += size
                }
                5 -> pos += 4
                else -> return Report("unsupported wire type at byte $pos")
            }
            if (pos > length) return Report("truncated")
        }
        if (irVersion == null) return Report("no IR version", null, graphBytes, metadata)
        if (graphBytes <= 0) return Report("no graph", irVersion, graphBytes, metadata)
        return Report(null, irVersion, graphBytes, metadata)
    }

    /** Key and value of a StringStringEntryProto at [pos], or null if malformed. */
    private fun entry(reader: Reader, start: Long, size: Int): Pair<String, String>? {
        val bytes = reader.bytes(start, size) ?: return null
        val inner = Reader(ArraySource(bytes))
        var pos = 0L
        var key: String? = null
        var value = ""
        while (pos < size) {
            val tag = inner.varint(pos) ?: return null
            pos = inner.next
            if ((tag and 7L) != 2L) return null
            val len = inner.varint(pos) ?: return null
            pos = inner.next
            if (len < 0 || len > size - pos) return null
            val text = String(bytes, pos.toInt(), len.toInt(), Charsets.UTF_8)
            when (tag ushr 3) {
                1L -> key = text
                2L -> value = text
            }
            pos += len
        }
        return key?.let { it to value }
    }

    /** Varint reads with a small buffer; [next] is the position after the last read. */
    private class Reader(private val source: Source) {
        var next = 0L
        private val buf = ByteArray(10)

        fun varint(pos: Long): Long? {
            val n = source.read(pos, buf, buf.size)
            if (n <= 0) return null
            var result = 0L
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl (7 * i))
                if (b and 0x80 == 0) {
                    next = pos + i + 1
                    return result
                }
            }
            return null // more than 10 bytes, or cut off by the end of the file
        }

        fun bytes(pos: Long, size: Int): ByteArray? {
            val out = ByteArray(size)
            var done = 0
            while (done < size) {
                val chunk = ByteArray(minOf(8192, size - done))
                val n = source.read(pos + done, chunk, chunk.size)
                if (n <= 0) return null
                System.arraycopy(chunk, 0, out, done, n)
                done += n
            }
            return out
        }
    }
}
