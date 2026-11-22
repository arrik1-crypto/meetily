package com.meetily.mobile

import android.content.Context
import androidx.annotation.WorkerThread
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.JobQueue
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.StorageUsage
import com.meetily.mobile.data.StorageUsage.Family
import com.meetily.mobile.data.StorageUsage.Kind
import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.TranscriptionModels
import com.meetily.mobile.whisper.WhisperModels
import java.io.File

/**
 * Measures what the Storage screen shows: bytes per area (StorageUsage),
 * free space, the meetings with the largest recordings, and the downloaded
 * models nothing uses. Walks every private directory and stats every file,
 * so worker threads only.
 */
object StorageScan {

    data class Report(
        val totals: Map<StorageUsage.Area, Long>,
        val freeBytes: Long,
        val largest: List<StorageUsage.MeetingAudio>,
        val unusedModels: List<StorageUsage.ModelOnDisk>
    ) {
        val totalBytes: Long get() = totals.values.sum()
    }

    @WorkerThread
    fun run(context: Context): Report {
        val entries = ArrayList<StorageUsage.Entry>()
        walk(context.filesDir, StorageUsage.Root.FILES, entries)
        walk(context.noBackupFilesDir, StorageUsage.Root.NO_BACKUP, entries)
        walk(context.cacheDir, StorageUsage.Root.CACHE, entries)
        walk(context.codeCacheDir, StorageUsage.Root.CODE_CACHE, entries)
        return Report(
            totals = StorageUsage.totals(entries),
            // The Batch 2 free-space helper: allocatable bytes (counting the
            // caches Android would clear), or usable space when the volume
            // cannot be queried.
            freeBytes = ModelSpaceCheck.measure(context).availableBytes.coerceAtLeast(0L),
            largest = StorageUsage.largestByAudio(meetingAudio(context)),
            unusedModels = StorageUsage.unusedModels(modelsOnDisk(context), modelsInUse(context))
        )
    }

    private fun walk(root: File?, kind: StorageUsage.Root, out: MutableList<StorageUsage.Entry>) {
        if (root == null || !root.isDirectory) return
        val base = root.absolutePath
        root.walkTopDown().onFail { _, _ -> }.forEach { file ->
            if (file.isFile) {
                val relative = file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
                out.add(StorageUsage.Entry(kind, relative.replace(File.separatorChar, '/'), file.length()))
            }
        }
    }

    /** Every meeting's kept audio, with only the fields that takes read. */
    private fun meetingAudio(context: Context): List<StorageUsage.MeetingAudio> =
        MeetingStore(context).listPartial(MeetingStore.HEADER_FIELDS + "audioFile")
            .mapNotNull { meeting ->
                val name = meeting.audioFile ?: return@mapNotNull null
                StorageUsage.MeetingAudio(
                    meeting.id, meeting.title, AudioStore.fileFor(context, name).length()
                )
            }

    /**
     * Every catalogue model with files on disk, whether or not complete: a
     * partial download takes room too. NeMo models are directories, looked
     * up without NemoModels.dir, which would create one per model.
     */
    @WorkerThread
    fun modelsOnDisk(context: Context): List<StorageUsage.ModelOnDisk> {
        val out = ArrayList<StorageUsage.ModelOnDisk>()
        for (model in WhisperModels.ALL) {
            val bytes = WhisperModels.fileFor(context, model).length()
            if (bytes > 0L) out.add(StorageUsage.ModelOnDisk(Family.WHISPER, model.key, model.displayName, bytes))
        }
        val nemoRoot = File(context.filesDir, "nemo-models")
        for (model in NemoModels.ALL) {
            val dir = File(nemoRoot, model.key)
            if (!dir.isDirectory) continue
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (bytes > 0L) out.add(StorageUsage.ModelOnDisk(Family.NEMO, model.key, model.displayName, bytes))
        }
        for (model in DiarizationModels.ALL) {
            val bytes = DiarizationModels.fileFor(context, model).length()
            if (bytes > 0L) out.add(StorageUsage.ModelOnDisk(Family.SPEAKER, model.key, model.displayName, bytes))
        }
        for (model in LocalLlmModels.ALL) {
            val bytes = LocalLlmModels.fileFor(context, model).length()
            if (bytes > 0L) out.add(StorageUsage.ModelOnDisk(Family.LLM, model.key, model.displayName, bytes))
        }
        return out
    }

    /**
     * The model keys something would miss, per kind: the live selection
     * (resolved the way the catalogues resolve it, so a stale key protects
     * the model actually used), a pick parked behind its download, every
     * download running or queued, every model a queued accuracy check or
     * import names, and — with the automatic accuracy check on — the model
     * that check would pick.
     */
    @WorkerThread
    fun modelsInUse(context: Context): Map<Kind, Set<String>> {
        val settings = AppSettings(context)
        val transcription = HashSet<String>()
        val speaker = HashSet<String>()
        val llm = HashSet<String>()

        transcription += resolveTranscription(settings.whisperModel)
        speaker += DiarizationModels.byKey(settings.diarizationModel).key
        llm += LocalLlmModels.byKey(settings.localLlmModel).key

        fun add(kind: String, key: String) {
            if (key.isBlank()) return
            when (kind) {
                ModelDownloadService.KIND_LLM -> llm += key
                ModelDownloadService.KIND_DIARIZE -> speaker += key
                else -> transcription += key
            }
        }
        for (kind in listOf(
            ModelDownloadService.KIND_WHISPER,
            ModelDownloadService.KIND_DIARIZE,
            ModelDownloadService.KIND_LLM
        )) {
            PendingModelSelection.get(context, kind)?.let { add(kind, it) }
        }
        if (ModelDownloadService.isRunning) {
            add(ModelDownloadService.currentKind, ModelDownloadService.currentKey)
            for ((kind, key) in ModelDownloadService.queuedItems) add(kind, key)
        }
        for (job in JobQueue.load(context)) {
            if (job.kind == JobQueue.KIND_CHECK || job.kind == JobQueue.KIND_IMPORT) {
                if (job.payload.isNotBlank()) transcription += job.payload
            }
        }
        if (settings.autoCheckTranscript) {
            // The same ranking the automatic pass uses, over the complete
            // models on disk. No runtime is loaded just to measure storage,
            // and only models with files are asked about, since asking
            // NemoModels about any other creates its directory.
            val downloaded = modelsOnDisk(context)
                .filter { StorageUsage.kindOf(it.family) == Kind.TRANSCRIPTION }
                .map { it.key }
                .filter { TranscriptionModels.isDownloaded(context, it) }
            TranscriptionModels.rankForCheck(downloaded, settings.whisperModel)
                .firstOrNull()?.let { transcription += it }
        }
        return mapOf(
            Kind.TRANSCRIPTION to transcription,
            Kind.SPEAKER to speaker,
            Kind.LLM to llm
        )
    }

    /** The key [stored] actually runs: unknown keys fall back as WhisperModels.byKey does. */
    private fun resolveTranscription(stored: String): String =
        if (TranscriptionModels.isNemo(stored)) stored else WhisperModels.byKey(stored).key

    /**
     * True while deleting a model could pull it out from under something:
     * a download writing into the model directories, or an engine that may
     * have one open (recording, import or accuracy check, summary).
     */
    fun modelsBusy(): Boolean =
        ModelDownloadService.isRunning || RecordingService.isRunning ||
            ImportService.isRunning || SummaryService.isRunning

    /** How many models a delete removed, and the bytes they held. */
    data class Deleted(val count: Int, val bytes: Long)

    /**
     * Deletes [models] with their catalogue's own delete (which also drops
     * a resumable partial file). Checks again that nothing has started
     * meanwhile (null: nothing was deleted) and that each model is still
     * unused (one picked since is skipped).
     */
    @WorkerThread
    fun deleteModels(context: Context, models: List<StorageUsage.ModelOnDisk>): Deleted? {
        if (modelsBusy()) return null
        val inUse = modelsInUse(context)
        var count = 0
        var freed = 0L
        for (model in models) {
            if (model.key in inUse[StorageUsage.kindOf(model.family)].orEmpty()) continue
            val deleted = when (model.family) {
                Family.WHISPER -> WhisperModels.ALL.firstOrNull { it.key == model.key }?.let {
                    WhisperModels.delete(context, it)
                    true
                }
                Family.NEMO -> NemoModels.byKeyOrNull(model.key)?.let {
                    NemoModels.delete(context, it)
                    true
                }
                Family.SPEAKER -> DiarizationModels.ALL.firstOrNull { it.key == model.key }?.let {
                    DiarizationModels.delete(context, it)
                    true
                }
                Family.LLM -> LocalLlmModels.ALL.firstOrNull { it.key == model.key }?.let {
                    LocalLlmModels.delete(context, it)
                    true
                }
            } ?: false
            if (deleted) {
                count++
                freed += model.bytes
            }
        }
        return Deleted(count, freed)
    }
}
