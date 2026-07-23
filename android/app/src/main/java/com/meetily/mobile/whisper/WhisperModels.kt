package com.meetily.mobile.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WhisperModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val sizeMb: Int,
    val englishOnly: Boolean
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperModels {

    val ALL: List<WhisperModel> = listOf(
        WhisperModel("tiny.en", "Tiny (English)", "ggml-tiny.en.bin", 75, true),
        WhisperModel("tiny", "Tiny (multilingual)", "ggml-tiny.bin", 75, false),
        WhisperModel("base.en", "Base (English)", "ggml-base.en.bin", 142, true),
        WhisperModel("base", "Base (multilingual)", "ggml-base.bin", 142, false),
        // Quantized (q5_1/q5_0) models: ~2.5x smaller than full precision
        // with near-identical accuracy — the practical sweet spot on phones.
        WhisperModel(
            "small.en-q5_1", "Small Q (English, compact)",
            "ggml-small.en-q5_1.bin", 181, true
        ),
        WhisperModel(
            "small-q5_1", "Small Q (multilingual, compact)",
            "ggml-small-q5_1.bin", 181, false
        ),
        WhisperModel("small.en", "Small (English, slower)", "ggml-small.en.bin", 466, true),
        WhisperModel("small", "Small (multilingual, slower)", "ggml-small.bin", 466, false),
        // Near-flagship accuracy; realistic for imports/re-transcription,
        // slow for live use on most phones.
        WhisperModel(
            "large-v3-turbo-q5_0", "Large v3 Turbo Q (best accuracy, slow)",
            "ggml-large-v3-turbo-q5_0.bin", 547, false
        )
    )

    fun byKey(key: String): WhisperModel =
        ALL.firstOrNull { it.key == key } ?: ALL.first { it.key == "base.en" }

    fun dir(context: Context): File =
        File(context.filesDir, "whisper-models").apply { mkdirs() }

    fun fileFor(context: Context, model: WhisperModel): File =
        File(dir(context), model.fileName)

    fun isDownloaded(context: Context, model: WhisperModel): Boolean {
        val file = fileFor(context, model)
        // Guard against truncated downloads: expect at least ~90% of nominal size.
        return file.exists() && file.length() > model.sizeMb * 1024L * 1024L * 9 / 10
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Removes leftover .part-* files from interrupted downloads. */
    fun cleanPartials(context: Context) {
        dir(context).listFiles { f -> f.name.contains(".part-") }?.forEach { it.delete() }
    }

    fun isRuntimeAvailable(): Boolean = WhisperBridge.load()

    /**
     * Blocking download with progress callback (0..100). Call from a worker
     * thread. Writes to a .part file and renames on success.
     */
    fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        val target = fileFor(context, model)
        // Unique temp file per invocation so two downloads (e.g. after an
        // activity recreate orphans one) can never interleave writes.
        val partial = File(target.absolutePath + ".part-" + System.nanoTime())
        val connection = URL(model.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                throw RuntimeException("HTTP $status while downloading model")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        if (cancelled()) {
                            throw InterruptedException("Download cancelled")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
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
