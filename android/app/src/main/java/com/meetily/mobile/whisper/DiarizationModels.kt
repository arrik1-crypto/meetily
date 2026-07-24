package com.meetily.mobile.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class DiarizationModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val approxSizeMb: Int
) {
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-recongition-models/$fileName" // (typo is real in upstream tag)
}

object DiarizationModels {

    // Ordered by recommendation: first entry is the fallback for unknown keys.
    val ALL: List<DiarizationModel> = listOf(
        DiarizationModel(
            "resnet34-en", "WeSpeaker ResNet34 (English, recommended)",
            "wespeaker_en_voxceleb_resnet34_LM.onnx", 26
        ),
        DiarizationModel(
            "titanet-large-en", "TitaNet large (English, most accurate)",
            "nemo_en_titanet_large.onnx", 97
        ),
        DiarizationModel(
            "titanet-en", "TitaNet small (English)",
            "nemo_en_titanet_small.onnx", 40
        ),
        DiarizationModel(
            "campplus-zh-en", "CAM++ (English + Chinese)",
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", 28
        )
    )

    fun byKey(key: String): DiarizationModel =
        ALL.firstOrNull { it.key == key } ?: ALL.first()

    fun dir(context: Context): File =
        File(context.filesDir, "diarization-models").apply { mkdirs() }

    fun fileFor(context: Context, model: DiarizationModel): File =
        File(dir(context), model.fileName)

    fun isDownloaded(context: Context, model: DiarizationModel): Boolean {
        // Lenient sanity check (sizes vary across upstream re-exports):
        // anything above a few MB is a plausibly complete model file.
        val file = fileFor(context, model)
        return file.exists() && file.length() > 4L * 1024 * 1024
    }

    fun delete(context: Context, model: DiarizationModel) {
        fileFor(context, model).delete()
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun cleanPartials(context: Context) {
        dir(context).listFiles { f -> f.name.contains(".part-") }?.forEach { it.delete() }
    }

    /** Blocking download with 0..100 progress; call from a worker thread. */
    fun download(
        context: Context,
        model: DiarizationModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        val target = fileFor(context, model)
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
