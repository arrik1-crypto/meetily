package com.meetily.mobile.whisper

import android.content.Context
import com.meetily.mobile.data.DeviceFit
import com.meetily.mobile.data.ResumableDownload
import com.meetily.mobile.security.ModelIntegrity
import java.io.File

data class DiarizationModel(
    val key: String,
    val displayName: String,
    /** The upstream release asset, which [url] fetches. */
    val fileName: String,
    val approxSizeMb: Int,
    /** Pinned SHA-256, when known; see [ModelIntegrity.verify]. */
    val sha256: String? = null,
    /**
     * Name on disk. Differs from [fileName] only for an entry re-keyed after
     * upstream replaced its asset in place: the size check in
     * [DiarizationModels.isDownloaded] would otherwise take the old key's
     * file, a different graph, for the new one.
     */
    val localName: String = fileName,
    /**
     * Superseded, and offered only to installs that already have it (see
     * [DiarizationModels.offered]); [successor] is the key that replaced it.
     * Its voice profiles carry its key, so switching to the successor
     * re-embeds them like any other model change.
     */
    val legacy: Boolean = false,
    val successor: String? = null
) {
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-recongition-models/$fileName" // (typo is real in upstream tag)

    /**
     * Estimated memory needs, by the transcription formula: it runs on the
     * same onnxruntime (DeviceFit.transcriptionWorkingSetMb).
     */
    val fitNeed: DeviceFit.Need
        get() = DeviceFit.speakerIdNeed(key, approxSizeMb)
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
        // Upstream replaced this asset in place on 2026-11-19 with a
        // re-export whose segment pooling no longer depends on the input
        // length modulo 2 s (sherpa-onnx issue 4201). Same URL, different
        // graph, so it is a new key with its own file: embeddings from the
        // two never meet in one voice profile.
        DiarizationModel(
            "campplus-zh-en-v2", "CAM++ (English + Chinese)",
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", 27,
            localName = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced-2026-11-19.onnx"
        ),
        DiarizationModel(
            "campplus-zh-en", "CAM++ (English + Chinese, earlier export)",
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", 28,
            legacy = true,
            successor = "campplus-zh-en-v2"
        )
    )

    fun byKey(key: String): DiarizationModel =
        ALL.firstOrNull { it.key == key } ?: ALL.first()

    /**
     * Models worth showing in a picker: everything current, plus any legacy
     * model this install already has. Same rule as NemoModels.offered.
     */
    fun offered(context: Context): List<DiarizationModel> =
        offered { isDownloaded(context, it) }

    /** Context-free core of [offered], unit-tested. */
    internal fun offered(downloaded: (DiarizationModel) -> Boolean): List<DiarizationModel> =
        ALL.filter { !it.legacy || downloaded(it) }

    /**
     * The key to select instead of [selected], or null to keep it: a legacy
     * model that is not on this phone (a restored backup, or a deleted file)
     * cannot be downloaded again, so its successor takes over. One that is
     * on the phone keeps working, matched to the profiles made with it.
     */
    internal fun replacementFor(selected: String, downloaded: (DiarizationModel) -> Boolean): String? {
        val model = ALL.firstOrNull { it.key == selected } ?: return null
        if (!model.legacy || downloaded(model)) return null
        return model.successor?.takeIf { s -> ALL.any { it.key == s && !it.legacy } }
    }

    /** Applies [replacementFor] to the stored selection. One stat; call off the main thread. */
    fun retireLegacySelection(context: Context) {
        val settings = com.meetily.mobile.data.AppSettings(context)
        replacementFor(settings.diarizationModel) { isDownloaded(context, it) }
            ?.let { settings.diarizationModel = it }
    }

    fun dir(context: Context): File =
        File(context.filesDir, "diarization-models").apply { mkdirs() }

    fun fileFor(context: Context, model: DiarizationModel): File =
        File(dir(context), model.localName)

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
