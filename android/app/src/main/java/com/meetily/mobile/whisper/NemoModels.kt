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

/**
 * Byte-weighted progress across a model's files, deduped to whole percent.
 *
 * Every other downloader in the app keeps a `lastPercent` guard so a large
 * file reports at most 101 times; the NeMo path used to report once per
 * socket read, which flooded the main thread (each report rebuilds the
 * foreground notification) and made the UI crawl while a 600 MB Parakeet
 * or Nemotron model downloaded. Emitting `null` means "nothing changed —
 * don't tell anyone". Pure (no Android, no I/O) — unit-tested.
 */
class NemoProgressAggregator(totalBytes: Long) {

    private val total = totalBytes.coerceAtLeast(1L)
    private var doneBytes = 0L
    private var lastPercent = -1

    /** Percent to report for the in-flight file, or null when unchanged. */
    fun onBytes(read: Long, nominalBytes: Long): Int? =
        emit(
            ((doneBytes + read.coerceAtMost(nominalBytes)) * 100 / total)
                .toInt().coerceIn(0, 99)
        )

    /** Percent to report once a file is complete, or null when unchanged. */
    fun onFileDone(nominalBytes: Long): Int? {
        doneBytes += nominalBytes
        return emit((doneBytes * 100 / total).toInt().coerceIn(0, 100))
    }

    private fun emit(percent: Int): Int? {
        if (percent == lastPercent) return null
        lastPercent = percent
        return percent
    }
}

data class NemoModel(
    val key: String,
    val displayName: String,
    /** Hugging Face repo holding the sherpa-onnx export. */
    val repo: String,
    val files: List<NemoFile>,
    /** Streaming (OnlineRecognizer) vs offline (OfflineRecognizer). */
    val streaming: Boolean,
    val englishOnly: Boolean,
    /**
     * The checkpoint takes a language-ID prompt, so each stream must be told
     * which language to decode (see [languageOption]). Off for every model
     * that shipped before Nemotron 3.5: those have no language-tag tokens,
     * and setting the option on them would be a change with nothing to gain.
     */
    val languagePrompt: Boolean = false,
    /**
     * Superseded, and offered only to installs that already downloaded it.
     * Removing the entry outright would strand those users: their selected
     * model key would stop resolving and fall through to the whisper family.
     */
    val legacy: Boolean = false
) {
    val totalMb: Int get() = files.sumOf { it.sizeMb }

    /**
     * Value for sherpa-onnx's per-stream `"language"` option, or null when
     * the model takes no language prompt. "auto" is an explicit, documented
     * value for these exports — not the absence of a setting.
     */
    val languageOption: String?
        get() = if (!languagePrompt) null else if (englishOnly) "en" else "auto"

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

    /**
     * Nemotron 3.5's parts, read off the published repo rather than assumed
     * from its English sibling — the multilingual vocabulary makes the
     * decoder and joiner several times larger, and the encoder is bigger
     * too. Sizes only drive the progress bar and the "MB" label; the .ok
     * sidecars decide completeness (see fileComplete).
     */
    private val NEMOTRON_35_INT8 = listOf(
        NemoFile("encoder.int8.onnx", 658),
        NemoFile("decoder.int8.onnx", 15),
        NemoFile("joiner.int8.onnx", 10),
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
        // Supersedes nemotron-en: same 600M cache-aware FastConformer-RNNT,
        // plus language-ID prompt conditioning over 40 language-locales. It
        // is the only fast path the app has for anything outside Parakeet
        // v3's 25 European languages — Japanese and Korean (both in NVIDIA's
        // transcription-ready tier) and Mandarin (broad-coverage: usable
        // untuned, but not in the top tier) otherwise fall through to
        // Whisper turbo, which costs ~3 minutes of compute per minute of
        // audio against Parakeet's ~0.36.
        //
        // 1120 ms is the largest published chunk size. Chunks reach here
        // already VAD-cut, so nothing downstream is latency-bound, and a
        // larger chunk means fewer encoder invocations per second of audio.
        NemoModel(
            key = "nemotron-3.5",
            displayName = "Nemotron 3.5 Streaming 0.6B (40 locales)",
            repo = "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-" +
                "1120ms-int8-2026-06-11",
            files = NEMOTRON_35_INT8,
            streaming = true,
            englishOnly = false,
            languagePrompt = true
        ),
        NemoModel(
            key = "nemotron-en",
            displayName = "Nemotron Streaming 0.6B (English)",
            repo = "csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
            files = TRANSDUCER_INT8,
            streaming = true,
            englishOnly = true,
            legacy = true
        )
    )

    fun byKeyOrNull(key: String): NemoModel? = ALL.firstOrNull { it.key == key }

    /**
     * Models worth showing in a picker: everything current, plus any legacy
     * model this install actually has on disk. A legacy model stays usable
     * and deletable for whoever downloaded it, and is invisible to everyone
     * else — so it costs nothing to a new install and strands no old one.
     */
    fun offered(context: Context): List<NemoModel> =
        offered { isDownloaded(context, it) }

    /** Context-free core of [offered], so the stranding rule is unit-testable. */
    internal fun offered(downloaded: (NemoModel) -> Boolean): List<NemoModel> =
        ALL.filter { !it.legacy || downloaded(it) }

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
     *
     * Progress is deduped by [NemoProgressAggregator]: reporting on every
     * socket read (as this did before) floods the main thread with
     * notification rebuilds and makes the foreground UI crawl.
     */
    fun download(
        context: Context,
        model: NemoModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        val dir = dir(context, model)
        val progress = NemoProgressAggregator(model.totalMb * 1024L * 1024L)
        for (file in model.files) {
            val target = File(dir, file.name)
            val nominal = file.sizeMb * 1024L * 1024L
            if (fileComplete(dir, file)) {
                progress.onFileDone(nominal)?.let(onProgress)
                continue
            }
            downloadOne(model.urlFor(file), target, cancelled) { read ->
                progress.onBytes(read, nominal)?.let(onProgress)
            }
            // Record the verified byte count so completeness never depends on
            // the nominal-size guess again (see fileComplete).
            okFile(dir, file).writeText(target.length().toString())
            progress.onFileDone(nominal)?.let(onProgress)
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
