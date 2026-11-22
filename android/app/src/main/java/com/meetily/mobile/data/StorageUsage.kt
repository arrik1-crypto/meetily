package com.meetily.mobile.data

/**
 * Where Recap's bytes go, for the Storage screen (StorageActivity).
 *
 * The scan (StorageScan) walks the app's private directories and hands each
 * file over as a path relative to the directory it was found under; this
 * decides which area it counts towards and adds them up. Pure, so the
 * mapping and the arithmetic are unit-tested.
 */
object StorageUsage {

    /** The rows of the Storage screen, in the order it shows them. */
    enum class Area {
        AUDIO,
        PHOTOS_ATTACHMENTS,
        TRANSCRIPTS,
        WHISPER_MODELS,
        NEMO_MODELS,
        SPEAKER_MODELS,
        LLM_MODELS,
        CACHES,
        OTHER
    }

    /** Which of the app's private directories a file was found under. */
    enum class Root { FILES, NO_BACKUP, CACHE, CODE_CACHE }

    /** One file: [relativePath] uses '/' and is relative to [root]. */
    data class Entry(val root: Root, val relativePath: String, val bytes: Long)

    /**
     * The area [relativePath] under [root] belongs to.
     *
     * Everything Android may clear on its own (cacheDir, codeCacheDir) is a
     * cache, and so are two things that only look like meeting audio: shared
     * clips (export/ClipExporter keeps the last few) and the waveform's
     * `.peaks` sidecars, both rebuilt from the audio on demand. A file
     * directly in filesDir, or in a directory nothing here knows, is OTHER
     * (voice profiles, crash reports), as is the copy of the bundled speech
     * detector in no-backup storage.
     */
    fun areaFor(root: Root, relativePath: String): Area {
        when (root) {
            Root.CACHE, Root.CODE_CACHE -> return Area.CACHES
            Root.NO_BACKUP -> return Area.OTHER
            Root.FILES -> Unit
        }
        val path = relativePath.replace('\\', '/').trimStart('/')
        val slash = path.indexOf('/')
        // A plain file in filesDir: no directory, so no area of its own.
        if (slash <= 0 || slash == path.length - 1) return Area.OTHER
        return when (path.substring(0, slash)) {
            "audio" -> when {
                path.startsWith("audio/clips/") -> Area.CACHES
                path.endsWith(".peaks") -> Area.CACHES
                else -> Area.AUDIO
            }
            "photos", "attachments" -> Area.PHOTOS_ATTACHMENTS
            "meetings", "transcript-drafts", "speaker-suggestions" -> Area.TRANSCRIPTS
            "whisper-models" -> Area.WHISPER_MODELS
            "nemo-models" -> Area.NEMO_MODELS
            "diarization-models" -> Area.SPEAKER_MODELS
            // litert-models: left by a withdrawn runtime; RecapApp removes it.
            "llm-models", "litert-models" -> Area.LLM_MODELS
            else -> Area.OTHER
        }
    }

    /** Bytes per area, every area present (zero when empty), in [Area] order. */
    fun totals(entries: Iterable<Entry>): Map<Area, Long> {
        val sums = LongArray(Area.values().size)
        for (entry in entries) {
            if (entry.bytes <= 0L) continue
            sums[areaFor(entry.root, entry.relativePath).ordinal] += entry.bytes
        }
        val out = LinkedHashMap<Area, Long>()
        for (area in Area.values()) out[area] = sums[area.ordinal]
        return out
    }

    /** True for the four downloaded-model rows. */
    fun isModels(area: Area): Boolean = area == Area.WHISPER_MODELS ||
        area == Area.NEMO_MODELS || area == Area.SPEAKER_MODELS || area == Area.LLM_MODELS

    /**
     * One meeting's kept audio, for the largest-meetings list.
     * [hasTranscript] false is a recording not transcribed yet, which
     * removing the audio would leave empty (AudioRemoval.confirm says so).
     */
    data class MeetingAudio(
        val id: String,
        val title: String,
        val audioBytes: Long,
        val hasTranscript: Boolean
    )

    /**
     * The [limit] meetings whose audio takes the most room, largest first.
     * Meetings with no audio on disk are left out; equal sizes keep a stable
     * order by id, so the list does not reshuffle between scans.
     */
    fun largestByAudio(meetings: List<MeetingAudio>, limit: Int = 5): List<MeetingAudio> =
        meetings.filter { it.audioBytes > 0L }
            .sortedWith(compareByDescending<MeetingAudio> { it.audioBytes }.thenBy { it.id })
            .take(limit.coerceAtLeast(0))

    /** The model catalogues, each with its own directory. */
    enum class Family { WHISPER, NEMO, SPEAKER, LLM }

    /**
     * Which setting selects a model of a family. Whisper and NeMo keys share
     * one setting (the transcription model), and no key is in both.
     */
    enum class Kind { TRANSCRIPTION, SPEAKER, LLM }

    fun kindOf(family: Family): Kind = when (family) {
        Family.WHISPER, Family.NEMO -> Kind.TRANSCRIPTION
        Family.SPEAKER -> Kind.SPEAKER
        Family.LLM -> Kind.LLM
    }

    fun areaOf(family: Family): Area = when (family) {
        Family.WHISPER -> Area.WHISPER_MODELS
        Family.NEMO -> Area.NEMO_MODELS
        Family.SPEAKER -> Area.SPEAKER_MODELS
        Family.LLM -> Area.LLM_MODELS
    }

    /** A catalogue model with files on disk. */
    data class ModelOnDisk(
        val family: Family,
        val key: String,
        val name: String,
        val bytes: Long
    )

    /**
     * Models on disk that nothing would miss: not the live selection of any
     * kind, not a pick waiting on its download, not the model a queued job
     * or the automatic accuracy check will use. [inUse] holds those keys per
     * kind; the caller decides them (StorageScan). Largest first, since that
     * is the order a user frees space in.
     */
    fun unusedModels(onDisk: List<ModelOnDisk>, inUse: Map<Kind, Set<String>>): List<ModelOnDisk> =
        onDisk.filter { model ->
            model.bytes > 0L && model.key !in inUse[kindOf(model.family)].orEmpty()
        }.sortedWith(compareByDescending<ModelOnDisk> { it.bytes }.thenBy { it.key })
}
