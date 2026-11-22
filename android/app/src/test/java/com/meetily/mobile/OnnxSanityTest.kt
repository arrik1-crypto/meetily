package com.meetily.mobile

import com.meetily.mobile.whisper.OnnxSanity
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural check that stands between a speaker model on disk and
 * sherpa-onnx's extractor constructor, which aborts the process on a file
 * onnxruntime rejects, crashes later on one without "framework" metadata,
 * and exits on one missing the metadata its model class reads.
 */
class OnnxSanityTest {

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun lenField(out: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        varint(out, (field.toLong() shl 3) or 2)
        varint(out, payload.size.toLong())
        out.write(payload)
    }

    private fun entry(key: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        lenField(out, 1, key.toByteArray())
        lenField(out, 2, value.toByteArray())
        return out.toByteArray()
    }

    /** The metadata sherpa-onnx's speaker models carry, by framework. */
    private fun metadata(framework: String?): List<Pair<String, String>> {
        val common = listOf("language" to "English", "output_dim" to "192", "sample_rate" to "16000")
        val own = if (framework == "nemo") {
            listOf("feat_dim" to "80", "window_size_ms" to "25", "window_stride_ms" to "10")
        } else {
            listOf("normalize_samples" to "1")
        }
        return common + own + listOfNotNull(framework?.let { "framework" to it })
    }

    /**
     * A ModelProto laid out as onnx.save writes the sherpa-onnx speaker
     * models: header fields, graph, opset, then the metadata entries.
     */
    private fun model(
        framework: String? = "wespeaker",
        graphBytes: Int = 5000,
        irVersion: Boolean = true,
        meta: List<Pair<String, String>> = metadata(framework)
    ): ByteArray {
        val out = ByteArrayOutputStream()
        if (irVersion) {
            varint(out, (1L shl 3) or 0)
            varint(out, 7)
        }
        lenField(out, 2, "pytorch".toByteArray())
        lenField(out, 3, "2.14.1".toByteArray())
        if (graphBytes > 0) lenField(out, 7, ByteArray(graphBytes) { (it * 31).toByte() })
        val opset = ByteArrayOutputStream()
        varint(opset, (2L shl 3) or 0)
        varint(opset, 13)
        lenField(out, 8, opset.toByteArray())
        for ((k, v) in meta) lenField(out, 14, entry(k, v))
        return out.toByteArray()
    }

    private fun problem(bytes: ByteArray) =
        OnnxSanity.speakerModelProblem(OnnxSanity.ArraySource(bytes))

    @Test
    fun acceptsAWellFormedSpeakerModel() {
        for (framework in listOf("wespeaker", "3d-speaker", "nemo")) {
            assertNull(framework, problem(model(framework)))
        }
        val report = OnnxSanity.inspect(OnnxSanity.ArraySource(model()))
        assertNull(report.problem)
        assertEquals(7L, report.irVersion)
        assertEquals(5000L, report.graphBytes)
        assertEquals("192", report.metadata["output_dim"])
    }

    @Test
    fun rejectsEveryTruncation() {
        // A file cut short anywhere: inside a tag, a length, the graph or a
        // metadata entry, or between fields, which drops a required entry.
        for (framework in listOf("wespeaker", "nemo")) {
            val whole = model(framework)
            for (cut in 1 until whole.size) {
                assertNotNull("$framework cut at $cut", problem(whole.copyOf(cut)))
            }
        }
        assertNotNull(problem(ByteArray(0)))
    }

    @Test
    fun rejectsMissingOrUnknownMetadata() {
        // Without "framework" the native extractor is built with no model
        // behind it, and its first call is a SIGSEGV.
        assertTrue(problem(model(framework = null))!!.contains("framework"))
        assertTrue(problem(model(framework = "pyannote"))!!.contains("pyannote"))
        // Each entry the model class reads ends the process when missing.
        for (framework in listOf("wespeaker", "nemo")) {
            for (dropped in metadata(framework).map { it.first }) {
                val meta = metadata(framework).filter { it.first != dropped }
                assertNotNull("$framework without $dropped", problem(model(framework, meta = meta)))
            }
        }
        val negative = metadata("wespeaker").map {
            if (it.first == "output_dim") it.first to "-1" else it
        }
        assertNotNull(problem(model("wespeaker", meta = negative)))
        // A NeMo model needs its window settings, which WeSpeaker's lack.
        val relabelled = metadata("wespeaker").map {
            if (it.first == "framework") it.first to "nemo" else it
        }
        assertNotNull(problem(model("nemo", meta = relabelled)))
    }

    @Test
    fun rejectsAModelWithoutGraphOrIrVersion() {
        assertNotNull(problem(model(graphBytes = 0)))
        assertNotNull(problem(model(irVersion = false)))
    }

    @Test
    fun rejectsForeignFiles() {
        val notOnnx = listOf(
            "<!DOCTYPE html><html><body>Not Found</body></html>".toByteArray(),
            "{\"error\":\"rate limited\"}".toByteArray(),
            ByteArray(4096), // zeros: field number 0
            ByteArray(4096) { 0xFF.toByte() }, // a varint that never ends
            byteArrayOf(0x0B, 0x00) // wire type 3 (start group)
        )
        for (bytes in notOnnx) {
            assertNotNull(String(bytes.copyOf(minOf(bytes.size, 16))), problem(bytes))
        }
    }

    @Test
    fun rejectsALengthPastTheEnd() {
        val out = ByteArrayOutputStream()
        varint(out, (1L shl 3) or 0)
        varint(out, 7)
        varint(out, (7L shl 3) or 2)
        varint(out, 1L shl 40)
        out.write(ByteArray(100))
        assertNotNull(problem(out.toByteArray()))
    }

    @Test
    fun readsFilesFromDisk() {
        val file = File.createTempFile("onnx-sanity", ".onnx")
        try {
            file.writeBytes(model("3d-speaker"))
            assertNull(OnnxSanity.speakerModelProblem(file))
            file.writeBytes(model("3d-speaker").copyOf(100))
            assertNotNull(OnnxSanity.speakerModelProblem(file))
        } finally {
            file.delete()
        }
        assertNotNull(OnnxSanity.speakerModelProblem(File(file.path + ".missing")))
    }
}
