package com.meetily.mobile.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * NVIDIA NeMo speech models (Parakeet TDT, Nemotron streaming) served
 * through the bundled sherpa-onnx runtime. Unlike whisper's single ggml
 * file, each model is a directory of ONNX parts downloaded individually
 * from the sherpa-onnx conversions on Hugging Face.
 */
data class NemoFile(val name: String, val sizeMb: Int)

data class NemoModel(
    val key: String,
    val displayName: String,
    /** Hugging Face repo holding the sherpa-onnx export. */
    val repo: String,
    val files: List<NemoFile>,
    /** Streaming (OnlineRecognizer) vs offline (OfflineRecognizer). */
    val streaming: Boolean,
    val englishOnly: Boolean
) {
    val totalMb: Int get() = files.sumOf { it.sizeMb }

    fun urlFor(file: NemoFile): String =
        "https://huggingface.co/$repo/resolve/main/${file.name}"
}

object NemoModels {

    private val TRANSDUCER_INT8 = listOf(
        NemoFile("encoder.int8.onnx", 622),
        NemoFile("decoder.int8.onnx", 7),
        NemoFile("joiner.int8.onnx", 2),
        NemoFile("tokens.txt", 1)
    )

    val ALL: List<NemoModel> = listOf(
        NemoModel(
            key = "parakeet-tdt-v2",
            displayName = "Parakeet TDT 0.6B v2 (English, imports)",
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            files = TRANSDUCER_INT8,
            streaming = false,
            englishOnly = true
        ),
        NemoModel(
            key = "parakeet-tdt-v3",
            displayName = "Parakeet TDT 0.6B v3 (25 languages)",
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            files = TRANSDUCER_INT8,
            streaming = false,
            englishOnly = false
        ),
        NemoModel(
            key = "nemotron-en",
            displayName = "Nemotron Streaming 0.6B (English)",
            repo = "csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
            files = TRANSDUCER_INT8,
            streaming = true,
            englishOnly = true
        )
    )

    fun byKeyOrNull(key: String): NemoModel? = ALL.firstOrNull { it.key == key }

    fun dir(context: Context, model: NemoModel): File =
        File(File(context.filesDir, "nemo-models"), model.key).apply { mkdirs() }

    /** True when the sherpa-onnx native runtime loads on this device. */
    fun isRuntimeAvailable(): Boolean = try {
        System.loadLibrary("sherpa-onnx-jni")
        true
    } catch (_: Throwable) {
        // UnsatisfiedLinkError on unsupported ABIs; already-loaded is fine
        // (loadLibrary is idempotent and doesn't throw when loaded).
        false
    }

    fun isDownloaded(context: Context, model: NemoModel): Boolean {
        val dir = dir(context, model)
        return model.files.all { file -> fileComplete(dir, file) }
    }

    private fun okFile(dir: File, file: NemoFile): File = File(dir, file.name + ".ok")

    /**
     * A file counts as complete when its .ok sidecar (written only after a
     * download finished and matched the server's Content-Length) records the
     * file's current byte count. The nominal-size heuristic remains as a
     * fallback so installs that downloaded before sidecars existed are not
     * asked to re-download — but sidecars are authoritative, because the
     * hard-coded nominal sizes can drift from what upstream actually serves.
     */
    private fun fileComplete(dir: File, file: NemoFile): Boolean {
        val f = File(dir, file.name)
        if (!f.exists() || f.length() == 0L) return false
        val ok = okFile(dir, file)
        if (ok.exists()) {
            val recorded = ok.readText().trim().toLongOrNull()
            if (recorded != null && recorded == f.length()) return true
        }
        // Legacy fallback (pre-sidecar installs): tiny files just need to be
        // non-empty; ONNX parts must be at least ~85% of nominal size.
        return if (file.sizeMb <= 1) {
            f.length() > 0
        } else {
            f.length() > file.sizeMb * 1024L * 1024L * 85 / 100
        }
    }

    fun delete(context: Context, model: NemoModel) {
        dir(context, model).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        File(context.filesDir, "nemo-models").deleteRecursively()
    }

    fun cleanPartials(context: Context) {
        File(context.filesDir, "nemo-models").walkTopDown()
            .filter { it.isFile && it.name.contains(".part-") }
            .forEach { it.delete() }
    }

    /**
     * Blocking multi-file download with aggregate progress (0..100 weighted
     * by nominal sizes). Call from a worker thread. Files already complete
     * are skipped, so an interrupted download resumes at file granularity.
     */
    fun download(
        context: Context,
        model: NemoModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        val dir = dir(context, model)
        val totalBytes = model.totalMb * 1024L * 1024L
        var doneBytes = 0L
        for (file in model.files) {
            val target = File(dir, file.name)
            val nominal = file.sizeMb * 1024L * 1024L
            if (fileComplete(dir, file)) {
                doneBytes += nominal
                onProgress((doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100))
                continue
            }
            downloadOne(model.urlFor(file), target, cancelled) { read ->
                val overall = doneBytes + read.coerceAtMost(nominal)
                onProgress((overall * 100 / totalBytes).toInt().coerceIn(0, 99))
            }
            // Record the verified byte count so completeness never depends on
            // the nominal-size guess again (see fileComplete).
            okFile(dir, file).writeText(target.length().toString())
            doneBytes += nominal
            onProgress((doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100))
        }
    }

    private fun downloadOne(
        url: String,
        target: File,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        val partial = File(target.absolutePath + ".part-" + System.nanoTime())
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                throw RuntimeException("HTTP $status while downloading ${target.name}")
            }
            val expected = connection.contentLengthLong
            var received = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) {
                            throw InterruptedException("Download cancelled")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        onBytes(received)
                    }
                }
            }
            if (expected > 0 && received != expected) {
                throw RuntimeException(
                    "Truncated download for ${target.name}: got $received of $expected bytes"
                )
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
