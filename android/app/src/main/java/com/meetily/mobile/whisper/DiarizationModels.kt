package com.meetily.mobile.whisper

import android.content.Context
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.security.ModelIntegrity
import java.io.File

data class DiarizationModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val approxSizeMb: Int,
    /** Pinned SHA-256, when known; see [ModelIntegrity.verify]. */
    val sha256: String? = null
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
        // Deeper WeSpeaker ResNets: noticeably better speaker separation than
        // ResNet34/TitaNet at the cost of size and per-segment compute. Their
        // embeddings are not comparable across models, so saved voice
        // profiles need re-enrollment after switching (the profile store
        // skips mismatched embeddings rather than mis-matching them).
        DiarizationModel(
            "resnet293-en", "WeSpeaker ResNet293 (English, most accurate)",
            "wespeaker_en_voxceleb_resnet293_LM.onnx", 109
        ),
        DiarizationModel(
            "resnet152-en", "WeSpeaker ResNet152 (English, high accuracy)",
            "wespeaker_en_voxceleb_resnet152_LM.onnx", 76
        ),
        DiarizationModel(
            "titanet-large-en", "TitaNet large (English)",
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
        val file = fileFor(context, model)
        file.delete()
        ResumableDownload.discardPartial(file)
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Legacy `.part-*` leftovers and week-old resumable parts; see [ResumableDownload]. */
    fun cleanPartials(context: Context) {
        ResumableDownload.cleanPartials(dir(context))
    }

    /**
     * Blocking download with 0..100 progress; call from a worker thread.
     * Resumes an interrupted `.part` when the server supports it.
     */
    fun download(
        context: Context,
        model: DiarizationModel,
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
