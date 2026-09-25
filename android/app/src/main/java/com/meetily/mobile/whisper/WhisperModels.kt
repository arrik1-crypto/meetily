package com.meetily.mobile.whisper

import android.content.Context
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.security.ModelIntegrity
import java.io.File

data class WhisperModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val sizeMb: Int,
    val englishOnly: Boolean,
    /** Pinned SHA-256, when known; see [ModelIntegrity.verify]. */
    val sha256: String? = null,
    /**
     * Pinned Hugging Face commit of ggerganov/whisper.cpp, when known; null
     * keeps following `main`. See android/scripts/model_hashes.py.
     */
    val revision: String? = null
) {
    val url: String
        get() = ModelIntegrity.pinRevision(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName",
            revision
        )
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

    fun delete(context: Context, model: WhisperModel) {
        val file = fileFor(context, model)
        file.delete()
        ResumableDownload.discardPartial(file)
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Removes legacy `.part-*` leftovers and week-old resumable `.part`
     * files; a recent `.part` is kept so the next download resumes it.
     */
    fun cleanPartials(context: Context) {
        ResumableDownload.cleanPartials(dir(context))
    }

    fun isRuntimeAvailable(): Boolean = WhisperBridge.load()

    /**
     * Blocking download with progress callback (0..100). Call from a worker
     * thread. Streams to a .part file (resumed if an earlier run was
     * interrupted) and renames on success.
     */
    fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        ResumableDownload.download(
            url = model.url,
            target = fileFor(context, model),
            label = model.fileName,
            expectedSha256 = model.sha256,
            cancelled = cancelled,
            onBytes = ResumableDownload.percentReporter(onProgress)
        )
    }
}
