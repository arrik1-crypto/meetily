package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingLanguage
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.data.TranscriptResume
import com.meetily.mobile.data.TranscriptSegment
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Imports an audio file into a Meeting: streaming decode → silence-split
 * chunks → Whisper transcription (+ optional speaker diarization), saving
 * incrementally so a cancelled or crashed import keeps its partial
 * transcript, with where it stops so a later pass can carry on from there
 * instead of starting over (TranscriptResume). Blocking; call from a worker
 * thread.
 */
class AudioFileImporter(
    private val context: Context,
    private val settings: AppSettings
) {
    class ImportException(message: String) : RuntimeException(message)

    /**
     * Outcome of an import. [stoppedEarly] carries the error message when
     * decoding or transcription died mid-file and the partial transcript was
     * kept; [truncated] is also true when the decoder consumed meaningfully
     * less audio than the container claims to hold.
     */
    class Result(
        val meetingId: String,
        val coveredMs: Long,
        val totalMs: Long,
        val stoppedEarly: String?,
        /** Where this pass began: 0, or where it continued a stopped one from. */
        val startedFromMs: Long = 0L,
        /**
         * Where the words now stop when the pass did not finish, for a later
         * pass to continue from (TranscriptResume); null when it finished.
         */
        val resumeFromMs: Long? = null,
        /**
         * The meeting this pass wrote into already had lines: a summary of
         * them is marked out of date rather than replaced (ImportService).
         */
        val hadWords: Boolean = false
    ) {
        val truncated: Boolean
            get() = stoppedEarly != null ||
                (totalMs > 0 && coveredMs < totalMs - 30_000)
    }

    private val sampleRate = AudioFileDecoder.TARGET_RATE
    private val minChunkSec = 1.6f
    private val maxChunkSec = 28f
    private val endSilenceSec = 0.8f
    private val silenceRms = 0.008f
    private val minSpeechRms = 0.012f
    private val frameSize = sampleRate / 10 // 100 ms

    /**
     * Returns the created meeting and its audio coverage (also for cancelled
     * or mid-file-failed imports, which keep whatever was transcribed so
     * far). Throws [ImportException] only when nothing could be processed.
     */
    fun import(
        uri: Uri,
        title: String,
        sourceName: String = title,
        /** Model for THIS run; null = the default from Settings. */
        modelKey: String? = null,
        /**
         * Set to run a second pass over an existing meeting's saved audio.
         * Nothing is written to that meeting: the result is staged in
         * [TranscriptDraft] for the user to compare and accept.
         */
        recheckMeetingId: String? = null,
        /**
         * True when [recheckMeetingId] names a recording that has NO
         * transcript yet — an audio-only capture whose words this run is
         * producing for the first time. It is anchored to that meeting like a
         * recheck, but its result belongs IN the meeting, not staged beside
         * it for comparison against nothing.
         */
        firstTranscript: Boolean = false,
        /**
         * A copy already staged in AudioStore by the import queue, or a
         * video's sound remuxed there (VideoAudioExtractor). Renamed into
         * place rather than copied again — it is the same bytes.
         */
        adoptFile: String? = null,
        /**
         * Spoken language for THIS run: null uses Settings, "auto" detects
         * even when Settings pins one, a code pins it. See MeetingLanguage.
         */
        language: String? = null,
        /**
         * Asked once a staged pass ([recheckMeetingId], not [firstTranscript])
         * has been stopped: true keeps its partial draft, with where it
         * stopped, for a later pass of the same model to continue
         * (ImportService: the charger came out). Otherwise it is deleted.
         */
        keepPartialCheck: () -> Boolean = { false },
        /**
         * Recheck only: decode the meeting's clear-voice copy instead of its
         * recording (ClearVoice), as the user chose. The words still point
         * into the recording, which the copy keeps the timeline of.
         */
        clearVoice: Boolean = false,
        /** Fires as soon as the target meeting id is known. */
        onMeetingCreated: ((String) -> Unit)? = null,
        /** [detail] is a human-readable "how far, how much longer" line. */
        onProgress: (Int, String) -> Unit,
        /** True while a recording is live, so this run takes fewer cores. */
        recordingActive: () -> Boolean = { false },
        cancelled: () -> Boolean
    ): Result {
        val selectedKey = modelKey ?: settings.whisperModel
        /**
         * Re-read per batch, not fixed at start. A recording that begins
         * after the import does adds its own threads on top; sizing once, on
         * an idle phone, leaves this run holding cores the UI thread needs.
         * whisper.cpp takes n_threads per call, so this costs nothing.
         */
        fun nThreads(): Int =
            com.meetily.mobile.data.HeavyWork.batchThreads(recordingActive())
        val startedAtMs = System.currentTimeMillis()

        val store = MeetingStore(context)
        // A recheck re-transcribes a meeting that already exists. Validate it
        // BEFORE loading any model: everything below allocates native memory
        // that only the try/finally further down releases, so a throw between
        // here and there would leak a whole Whisper context.
        val recheckTarget = recheckMeetingId?.let { store.load(it) }
        if (recheckMeetingId != null) {
            if (recheckTarget == null) {
                throw ImportException("That meeting is no longer available")
            }
            if (recheckTarget.audioFile.isNullOrBlank()) {
                throw ImportException("No audio was kept for this meeting")
            }
            if (clearVoice && AudioStore.clearVoiceCopy(
                    context, recheckTarget.audioFile, recheckTarget.clearVoiceFile
                ) == null
            ) {
                throw ImportException("The clear-voice copy is no longer available")
            }
        }
        val recheck = recheckTarget != null
        // Lines were there before this pass: a continuation's kept ones, or
        // the ones a start-over replaces.
        val hadWords = recheckTarget?.segments?.isNotEmpty() == true
        /*
         * Whether the result is STAGED for review rather than written.
         *
         * These were the same flag, which is what broke the default recording
         * mode: with live transcription off a recording finishes with no
         * words, its transcription is queued through the same path a recheck
         * uses, and every batch went to TranscriptDraft. The meeting stayed
         * empty, the auto-summary saw zero segments and never ran, and the
         * user was asked to review their only transcript against nothing —
         * where "keep the current one" deleted it.
         */
        val stageOnly = recheck && !firstTranscript
        /*
         * Continuing a pass that stopped part-way instead of starting over
         * (TranscriptResume). Where it stopped is stored with its words: on
         * the meeting for its own transcript, in the draft for a staged
         * check. Only the model that made them carries on, in the language
         * they were made in, and for a check only on the audio they were
         * made from (the recording, or the clear-voice copy the user chose);
         * anything else starts from the beginning.
         */
        val draft = if (stageOnly) recheckTarget?.let { TranscriptDraft.load(context, it.id) } else null
        val continueFromMs: Long? = when {
            recheckTarget == null -> null
            stageOnly -> draft?.takeIf { !it.complete && it.clearVoice == clearVoice }?.let {
                TranscriptResume.continueFrom(
                    it.resumeMs, it.modelKey, selectedKey, it.language, language
                )
            }
            else -> TranscriptResume.continueFrom(
                recheckTarget.transcriptResumeMs, recheckTarget.transcriptModel,
                selectedKey, recheckTarget.language, language
            )
        }
        val storedLanguage = if (stageOnly) draft?.language else recheckTarget?.language
        val storedTranslated =
            if (stageOnly) draft?.translated == true else recheckTarget?.translated == true
        // The seam: 0 for a pass from the beginning.
        val startMs = continueFromMs ?: 0L
        val seamSample = TranscriptResume.sampleAt(startMs, sampleRate)
        // Null for auto. Whisper starts from it; Nemotron 3.5 is told it.
        val pinned = MeetingLanguage.pinFor(
            if (continueFromMs != null) {
                TranscriptResume.languageFor(storedLanguage, language)
            } else {
                language
            },
            settings.spokenLanguage
        )

        // NeMo path (Parakeet/Nemotron via sherpa-onnx) or whisper.cpp.
        val nemoModel = NemoModels.byKeyOrNull(selectedKey)
        var nemoEngine: NemoEngine? = null
        var contextPtr = 0L
        // NativeWork tag for the whisper context (NemoEngine and
        // SherpaEmbedder tag themselves); ended with it in the finally below.
        var whisperWork = 0
        // var, not val: set to the detected language after the first batch
        // so "auto" is paid for once rather than on every call.
        var whisperLanguage = "en"
        var translate = false
        if (nemoModel != null) {
            if (!NemoModels.isRuntimeAvailable()) {
                throw ImportException("Speech runtime unavailable on this device")
            }
            nemoEngine = NemoEngine.create(context, nemoModel, nThreads(), pinned)
                ?: throw ImportException("Could not load the ${nemoModel.displayName} model")
        } else {
            val model = WhisperModels.byKey(selectedKey)
            if (!WhisperModels.isRuntimeAvailable()) {
                throw ImportException("Whisper runtime unavailable on this device")
            }
            if (!WhisperModels.isDownloaded(context, model)) {
                throw ImportException("Whisper model not downloaded")
            }
            whisperWork = com.meetily.mobile.diag.NativeWork.begin(
                com.meetily.mobile.diag.NativeJob.TRANSCRIBE, model.key
            )
            contextPtr = WhisperBridge.initContext(
                WhisperModels.fileFor(context, model).absolutePath
            )
            if (contextPtr == 0L) {
                com.meetily.mobile.diag.NativeWork.end(whisperWork)
                throw ImportException("Could not load the Whisper model")
            }
            whisperLanguage = MeetingLanguage.whisperArgument(pinned, model.englishOnly)
            // A continuation translates (or not) as the words before it did.
            val wantsTranslation =
                if (continueFromMs != null) storedTranslated else settings.whisperTranslate
            translate = wantsTranslation && !model.englishOnly
        }

        var embedder: SherpaEmbedder? = null
        var clusterer: SpeakerClusterer? = null
        var diarizeKey: String? = null
        if (settings.diarizationEnabled) {
            val dModel = DiarizationModels.byKey(settings.diarizationModel)
            if (DiarizationModels.isDownloaded(context, dModel)) {
                embedder = SherpaEmbedder.create(
                    DiarizationModels.fileFor(context, dModel).absolutePath, dModel.key
                )
                if (embedder != null) {
                    clusterer = SpeakerClusterer()
                    diarizeKey = dModel.key
                }
            }
        }

        // A recheck stays anchored to the meeting it is checking, so the two
        // passes line up on the same audio; nothing is written to that
        // meeting until the user accepts the result.
        // Timestamps are anchored so the imported meeting reads as having
        // just ended (base + in-file offset); refined once duration is known.
        /*
         * Container duration, probed from the SOURCE before the meeting is
         * built.
         *
         * The anchor has to be correct in the constructor: createdAtMs is a
         * val, and every segment timestamp is derived from it. It used to be
         * corrected at the END of the run instead, shifting timestamps that
         * batches had already written to disk — and the meeting screen tracks
         * how far it has seen BY TIMESTAMP, so a screen opened mid-import
         * held a high-water mark matching nothing on disk and its next save
         * overwrote the finished transcript with its stale partial copy.
         *
         * A second probe further down reads the same value for progress and
         * the result; this one exists only because it has to happen earlier.
         */
        val probedMs = if (recheckTarget != null) -1L else runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: -1L
            } finally {
                mmr.release()
            }
        }.getOrDefault(-1L)

        var baseMs = if (recheckTarget != null) {
            // Share the stored transcript's clock exactly. An import
            // re-anchors its segments once the duration is known but leaves
            // createdAtMs at the moment the import started, so the two are
            // not the same instant. Deriving the base from a segment that
            // carries both stamps is the only way accepted lines land in
            // order among the ones they sit between.
            recheckTarget.segments.firstOrNull { it.audioMs != null }
                ?.let { it.timestampMs - (it.audioMs ?: 0L) }
                ?: recheckTarget.createdAtMs
        } else if (probedMs > 0) {
            // Reads as having just ended, and correct from the first write.
            System.currentTimeMillis() - probedMs
        } else {
            System.currentTimeMillis()
        }
        val meeting = Meeting(
            id = recheckTarget?.id ?: UUID.randomUUID().toString(),
            title = recheckTarget?.title ?: title,
            createdAtMs = baseMs
        )
        // Recorded so a later accuracy check can say which model produced the
        // transcript it is comparing against, and offer a different one.
        meeting.transcriptModel = selectedKey
        // What was spoken, as far as this run knows it yet: the pin, or for
        // Whisper on auto nothing until the first batch has been detected.
        meeting.language = if (nemoModel != null) {
            MeetingLanguage.nemoRecorded(pinned, nemoModel.englishOnly, nemoModel.languagePrompt)
        } else {
            MeetingLanguage.whisperRecorded(whisperLanguage)
        }
        meeting.translated = translate
        if (continueFromMs != null) {
            // The lines before the seam are kept as they are, edits included;
            // any after it are made again by this pass.
            val stored = if (stageOnly) draft?.segments.orEmpty() else recheckTarget?.segments.orEmpty()
            meeting.segments.addAll(TranscriptResume.prefix(stored, startMs, baseMs))
        }
        onMeetingCreated?.invoke(meeting.id)

        // Set by the first persist(). The end-of-run re-anchor below must not
        // move timestamps that are already on disk.
        var persisted = false
        var lastPersistAt = 0L
        // Where the words stop while this pass is unfinished, for the next
        // one to continue from. Replaced below once the chunker it reads
        // from exists.
        var resumePointMs: () -> Long = { startMs }
        // What the last save recorded as that point; null once complete.
        var savedResumeMs: Long? = continueFromMs
        // How this pass relabels the speakers of the lines before its seam,
        // applied to what is stored there at each save (TranscriptResume.Relabel).
        var relabel = TranscriptResume.Relabel()

        /**
         * Where transcribed segments land. A normal import owns its meeting
         * and saves into it incrementally; a recheck must not touch the
         * stored meeting at all, so it stages instead.
         */
        fun persist(complete: Boolean) {
            persisted = true
            lastPersistAt = android.os.SystemClock.elapsedRealtime()
            // Saved with every batch, not only at a stop, so a pass killed
            // with the process can be continued too.
            val unfinishedAt = if (complete) null else resumePointMs()
            savedResumeMs = unfinishedAt
            if (stageOnly) {
                TranscriptDraft.save(
                    context, meeting.id, selectedKey, meeting.segments, complete,
                    meeting.language, meeting.translated, unfinishedAt,
                    clearVoice = clearVoice
                )
            } else {
                meeting.transcriptResumeMs = unfinishedAt
                // Not a plain save: the meeting is visible and editable in
                // the library for the whole run, so anything the user changes
                // meanwhile has to survive the next batch. That includes the
                // lines a continuation keeps: what is stored before its seam
                // stays, edits included, and only this pass's lines replace.
                store.saveTranscription(
                    meeting,
                    if (seamSample > 0L) TranscriptResume.Seam(startMs, baseMs, relabel) else null
                )
            }
        }

        /**
         * Mid-run save, throttled. Every save rewrites (and for a meeting,
         * re-reads) the whole transcript, so saving per batch made the IO of a
         * long run grow with the square of its length — on NeMo, every few
         * seconds of audio. The first batch still lands at once so the
         * meeting shows words early; completion, cancel and failure always
         * save, so only a hard process kill can lose one interval.
         */
        fun persistProgress() {
            if (persisted &&
                android.os.SystemClock.elapsedRealtime() - lastPersistAt < PERSIST_INTERVAL_MS
            ) return
            persist(complete = false)
        }

        if (recheck) {
            meeting.audioFile = recheckTarget?.audioFile
        } else {
            // Keep a copy of the source audio so playback and later
            // re-transcription work on imported meetings too. Decoding also
            // runs from this copy: the caller's content-URI grant can be
            // revoked once the sharing activity goes away, but our own file
            // cannot.
            // Built outside the try (it is only a path) so a copy that dies
            // part-way can be removed: nothing else ever points at it. An
            // adopted file names the format: a video's sound is staged as
            // .m4a or the like (VideoAudio), whatever the video was called.
            val audioCopy = AudioStore.newImportFile(context, meeting.id, adoptFile ?: sourceName)
            try {
                val staged = adoptFile?.let { AudioStore.fileFor(context, it) }
                if (staged != null && staged.length() > 0 && staged.renameTo(audioCopy)) {
                    // Queued import: the bytes are already ours.
                    meeting.audioFile = audioCopy.name
                } else {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        audioCopy.outputStream().use { input.copyTo(it) }
                    }
                    if (audioCopy.length() > 0) {
                        meeting.audioFile = audioCopy.name
                    } else {
                        audioCopy.delete()
                    }
                }
            } catch (_: Exception) {
                if (meeting.audioFile == null) audioCopy.delete()
                meeting.audioFile = null
            }
        }
        // Checked above to exist, when asked for; it plays and decodes on
        // the recording's timeline.
        val clearCopy = if (recheck && clearVoice) {
            AudioStore.clearVoiceCopy(context, recheckTarget?.audioFile, recheckTarget?.clearVoiceFile)
        } else {
            null
        }
        val decodeUri = clearCopy?.let { Uri.fromFile(it) } ?: meeting.audioFile?.let {
            Uri.fromFile(AudioStore.fileFor(context, it))
        } ?: uri

        // Container-reported length, probed up front so a mid-file failure
        // can still report how much of the recording was covered.
        var totalMs = -1L
        try {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, decodeUri)
                totalMs = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: -1L
            } finally {
                mmr.release()
            }
        } catch (_: Exception) {
        }

        // Chunker state (same splitting rules as live recording).
        // Allocated once and filled in place: growing a fresh array per 100 ms
        // frame copied the whole chunk every time, quadratic in its length.
        val chunk = FloatArray((maxChunkSec * sampleRate).toInt() + frameSize)
        var chunkLen = 0
        var silenceRun = 0f
        var chunkPeakRms = 0f
        // Absolute samples: a continuation counts from the seam, so every
        // line it makes is already where it is in the file.
        var consumedSamples = seamSample
        var chunkStartSample = seamSample
        var pending = FloatArray(0) // partial frame carry-over
        // Kept so the decoder's own progress ticks can carry the last
        // "how much longer" line rather than blanking it between batches.
        var lastDetail = ""

        // Speech gate (Silero VAD, see SpeechGate), opened at the top of the
        // try below and freed in its finally. Null when the setting is off or
        // the detector could not be opened: then every chunk that passes the
        // RMS check is transcribed, exactly as before.
        var vad: SileroVad? = null
        var speech: SpeechGateRun? = null
        // A skipped chunk reaches no batch and no engine call, the two places
        // that refresh the progress line, so a long stretch with no speech
        // refreshes it from here, at most this often.
        var lastSkipReportAt = 0L

        /** One cut chunk, waiting to be sent to Whisper as part of a batch. */
        class Part(val audio: FloatArray, val startSample: Long, val clusterId: Int?)

        val batch = mutableListOf<Part>()
        var batchSamples = 0

        // The last chunk the speech gate skipped, while it can still be
        // reached by the padding of speech at the start of the next one
        // (see transcribeChunk). Its clusterId is unused.
        var heldSkip: Part? = null

        // Where this pass could be continued from if it stopped now
        // (TranscriptResume): the cuts made at silence, and the chunks before
        // them not yet done. inFlightFrom is the chunk (and any held one
        // before it) transcribeChunk is on, flushingFrom a batch handed to
        // Whisper, abandonedFrom the first chunk a stop left untranscribed.
        // The first two are cleared only once their work is done, so a throw
        // leaves them counted as unfinished.
        val cuts = TranscriptResume.Cuts(seamSample)
        var inFlightFrom: Long? = null
        var flushingFrom: Long? = null
        var abandonedFrom: Long? = null
        fun abandon(from: Long) {
            abandonedFrom = minOf(abandonedFrom ?: from, from)
        }
        resumePointMs = {
            val frontier = TranscriptResume.frontier(
                listOf(
                    batch.firstOrNull()?.startSample, heldSkip?.startSample,
                    inFlightFrom, flushingFrom, abandonedFrom
                ),
                if (chunkLen > 0) chunkStartSample else consumedSamples
            )
            TranscriptResume.msAt(cuts.pointFor(frontier), sampleRate)
        }
        // Lines this pass added (a continuation keeps others from before).
        var added = 0

        fun clusterFor(audio: FloatArray): Int? = try {
            val c = clusterer
            val e = embedder
            if (c != null && e != null) {
                c.assign(
                    if (audio.size >= MIN_EMBED_SAMPLES) {
                        e.embed(EmbedWindow.centre(audio, EmbedWindow.MAX_CHUNK_SAMPLES))
                    } else null
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

        fun addSegment(
            text: String,
            startSample: Long,
            words: List<com.meetily.mobile.data.WordStamp>,
            clusterId: Int?
        ) {
            if (text.isBlank() || isNoise(text)) return
            // Never before the seam: those lines are the kept ones.
            if (startSample < seamSample) return
            added++
            meeting.segments.add(
                TranscriptSegment(
                    timestampMs = baseMs + startSample * 1000 / sampleRate,
                    text = text.trim(),
                    speaker = null,
                    clusterId = clusterId,
                    audioMs = if (meeting.audioFile != null) {
                        startSample * 1000 / sampleRate
                    } else null,
                    words = if (meeting.audioFile != null && words.isNotEmpty()) {
                        words
                    } else null
                )
            )
        }

        /** "Continuing from 42:10", ahead of [detail] when there is one. */
        fun continuingLine(detail: String): String {
            val at = TranscriptResume.clock(startMs)
            return if (detail.isBlank()) {
                context.getString(com.meetily.mobile.R.string.resume_detail_start, at)
            } else {
                context.getString(com.meetily.mobile.R.string.resume_detail, at, detail)
            }
        }

        /** Progress line: how much audio is done, and how much longer. */
        fun reportProgress() {
            val doneMs = consumedSamples * 1000 / sampleRate
            val percent = if (totalMs > 0) {
                ((doneMs * 100) / totalMs).toInt().coerceIn(0, 100)
            } else {
                0
            }
            // Measured over what THIS pass has done: a continuation that
            // starts at 42 minutes has not transcribed 42 minutes in its
            // first second.
            val left = ImportEta.remainingMinutes(
                doneMs - startMs, totalMs - startMs, System.currentTimeMillis() - startedAtMs
            )
            lastDetail = when {
                left != null -> context.getString(
                    com.meetily.mobile.R.string.import_detail_eta,
                    minutesOf(doneMs), minutesOf(totalMs), left.toInt()
                )
                totalMs > 0 -> context.getString(
                    com.meetily.mobile.R.string.import_detail,
                    minutesOf(doneMs), minutesOf(totalMs)
                )
                else -> ""
            }
            if (startMs > 0L) lastDetail = continuingLine(lastDetail)
            onProgress(percent, lastDetail)
        }

        /**
         * Sends the accumulated chunks to Whisper as ONE call.
         *
         * Whisper encodes a full 30-second window however little audio it is
         * handed, so a 6-second chunk costs as much as a 28-second one. Left
         * per-chunk, a long meeting pays hundreds of full encoder passes for
         * a few seconds of speech each. Batching to one window cuts that by
         * roughly the ratio of chunk length to window length — and Whisper is
         * more accurate with more context, not less, so nothing is traded
         * away for it. Word timings put the per-chunk segments back together.
         */
        fun flushBatch() {
            // After a cancel a batch is a whole encoder window nobody wants.
            if (batch.isEmpty() || cancelled()) return
            val parts = batch.toList()
            batch.clear()
            batchSamples = 0
            flushingFrom = parts.first().startSample

            val total = parts.sumOf { it.audio.size }
            val buffer = FloatArray(maxOf(total, sampleRate * 12 / 10))
            val spans = ArrayList<BatchSplit.Part>(parts.size)
            var at = 0
            for (part in parts) {
                System.arraycopy(part.audio, 0, buffer, at, part.audio.size)
                spans.add(
                    BatchSplit.Part(
                        at.toLong() * 1000 / sampleRate,
                        part.audio.size.toLong() * 1000 / sampleRate
                    )
                )
                at += part.audio.size
            }

            // Abortable: one batch is a whole encoder window, up to a minute
            // on a large model, and a cancel or an unplug should not have
            // to wait it out at full CPU.
            val raw = WhisperBridge.abortable(contextPtr, cancelled) {
                WhisperBridge.transcribeWords(
                    contextPtr, buffer, whisperLanguage, nThreads(), translate,
                    Vocab.promptFor(settings.customVocab)
                )
            }
            // A stop that landed during the call cut it short: these chunks
            // are left for the pass that continues this one.
            if (cancelled()) abandon(parts.first().startSample)
            // "auto" costs a whole extra encoder pass per call, so pay it
            // once and pin the answer for the rest of the file. Whisper is
            // also better placed to judge from the first real speech than
            // from every later batch of spliced, silence-stripped audio.
            if (whisperLanguage == MeetingLanguage.AUTO) {
                WhisperBridge.lastLanguage(contextPtr)
                    ?.takeIf { it.isNotBlank() && it != MeetingLanguage.AUTO }
                    ?.let {
                        whisperLanguage = it
                        // Saved with the words from here on.
                        meeting.language = MeetingLanguage.whisperRecorded(it)
                    }
            }
            val (text, words) = WhisperBridge.parseWords(raw)
            if (words.isNotEmpty() && parts.size > 1) {
                val perPart = BatchSplit.split(spans, words)
                for (i in parts.indices) {
                    val partWords = perPart[i]
                    if (partWords.isEmpty()) continue
                    addSegment(
                        WhisperBridge.joinWords(partWords),
                        parts[i].startSample,
                        partWords,
                        parts[i].clusterId
                    )
                }
            } else if (words.isNotEmpty()) {
                addSegment(text, parts.first().startSample, words, parts.first().clusterId)
            } else if (!cancelled()) {
                // An aborted call also lands here with nothing, and must not
                // be retried: that would spend the window the stop just saved.
                //
                // No word timings came back. parseWords derives the text FROM
                // the words, so this also means no text — and the old code
                // simply dropped the batch here. Pre-batching that lost one
                // chunk; now it would lose up to 28 seconds of the meeting,
                // silently. Fall back to the plain path, which asks for text
                // without timings and carries the same one-encode guarantee.
                val plain = WhisperBridge.transcribe(
                    contextPtr, buffer, whisperLanguage, nThreads(), translate,
                    Vocab.promptFor(settings.customVocab)
                )?.trim().orEmpty()
                if (plain.isNotBlank()) {
                    addSegment(plain, parts.first().startSample, emptyList(), parts.first().clusterId)
                }
            }
            flushingFrom = null
            persistProgress()
            reportProgress()
        }

        /** Speaker label, then the engine: a chunk the gate kept. */
        fun recognise(audio: FloatArray, startSample: Long) {
            val clusterId = clusterFor(audio)
            val engine = nemoEngine
            if (engine == null) {
                // Whisper: batch up to one encoder window.
                if (batchSamples > 0 && batchSamples + audio.size > BATCH_LIMIT_SAMPLES) {
                    flushBatch()
                }
                batch.add(Part(audio, startSample, clusterId))
                batchSamples += audio.size
                if (batchSamples >= BATCH_LIMIT_SAMPLES) flushBatch()
                return
            }
            // NeMo: cost scales with the audio actually given, so batching
            // buys nothing and would only coarsen the segments.
            val padded = if (audio.size < sampleRate * 12 / 10) {
                audio.copyOf(sampleRate * 12 / 10)
            } else {
                audio
            }
            val (text, words) = engine.transcribe(padded) ?: ("" to emptyList())
            addSegment(text, startSample, words, clusterId)
            persistProgress()
            reportProgress()
        }

        /**
         * [endsQuiet] is true when the chunk was cut after [endSilenceSec]
         * below the silence floor, false when it was cut at the length limit
         * in sound.
         */
        fun transcribeChunk(audio: FloatArray, startSample: Long, endsQuiet: Boolean) {
            // A cut can still fire from frames already in hand when the
            // cancel lands; it must not start an embedding or engine call.
            if (cancelled()) {
                abandon(heldSkip?.startSample ?: startSample)
                return
            }
            // No speech in it: no embedding, no engine call, no segment. A
            // kept chunk goes on unchanged at the same start sample, so its
            // timestamps and word timings match a run with the gate off.
            if (speech?.keep(audio, startSample) == false) {
                // Speech that starts in the next chunk is padded back into
                // this one, but is only found once that chunk is scored, so
                // a chunk cut at the length limit waits for it. One cut in
                // silence ends in at least endSilenceSec below the floor,
                // longer than the padding, so it has nothing to wait for.
                heldSkip = if (endsQuiet) null else Part(audio, startSample, null)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSkipReportAt >= SKIP_REPORT_INTERVAL_MS) {
                    lastSkipReportAt = now
                    reportProgress()
                }
                return
            }
            val held = heldSkip
            heldSkip = null
            if (held != null) {
                val heldEnd = held.startSample + held.audio.size
                if (heldEnd == startSample && speech?.sendsHeld(held.startSample, heldEnd) == true) {
                    // Sent first and whole, at its own start sample, just as
                    // with the gate off. Also when the gate failed on this
                    // chunk: failing open must not drop the held one.
                    recognise(held.audio, held.startSample)
                }
            }
            recognise(audio, startSample)
        }

        fun cutChunk() {
            if (chunkLen == 0) return
            // A copy: a Whisper batch holds on to it while the buffer refills.
            val audio = chunk.copyOf(chunkLen)
            val peak = chunkPeakRms
            val start = chunkStartSample
            val endsQuiet = silenceRun >= endSilenceSec
            chunkLen = 0
            silenceRun = 0f
            chunkPeakRms = 0f
            chunkStartSample = consumedSamples
            val end = consumedSamples
            if (peak < minSpeechRms) {
                // The next chunk cannot follow on from a held one now.
                heldSkip = null
                // Nothing in it reached the gate, which starts afresh after
                // the gap, so a pass may continue from its end.
                cuts.quietCut(end)
                return
            }
            // A held chunk is always the one just before this.
            inFlightFrom = heldSkip?.startSample ?: start
            transcribeChunk(audio, start, endsQuiet)
            inFlightFrom = null
            // Cut at silence with nothing of the gate's reaching past it: a
            // pass may continue from here once everything before is done.
            if (endsQuiet && speech?.restsAt(end) != false) cuts.quietCut(end)
        }

        fun onFrame(frame: FloatArray) {
            val rms = rmsOf(frame)
            chunkPeakRms = max(chunkPeakRms, rms)
            silenceRun = if (rms < silenceRms) silenceRun + 0.1f else 0f
            if (chunkLen == 0) chunkStartSample = consumedSamples
            System.arraycopy(frame, 0, chunk, chunkLen, frame.size)
            chunkLen += frame.size
            consumedSamples += frame.size
            val chunkSec = chunkLen.toFloat() / sampleRate
            if ((chunkSec >= minChunkSec && silenceRun >= endSilenceSec) ||
                chunkSec >= maxChunkSec
            ) {
                cutChunk()
            }
        }

        // Loudness bars for the Audio tab, gathered from the decode that is
        // happening anyway.
        //
        // Without this the tab starts its OWN full decode the first time it
        // is opened, on an ungated thread — which on a fresh recording means
        // competing with this very pass for the same cores. The visible
        // result was a waveform that stayed flat for a whole playback and
        // then quietly appeared once transcription finished.
        val loudness = com.meetily.mobile.data.Waveform.collector()

        /**
         * Speakers across the seam of a continuation: the voice of every line
         * before it is embedded again, in order, ahead of everything this
         * pass adds, so the whole recording is clustered once, together, as
         * one uninterrupted pass would have done it, and mergePass and the
         * voice profiles below see all of it. Decodes from the start to the
         * last window. Returns each window's cluster, or null when stopped or
         * failed.
         */
        fun clusterPrefix(windows: List<TranscriptResume.Window>): Array<Int?>? {
            val out = arrayOfNulls<Int>(windows.size)
            if (windows.isEmpty()) return out
            var next = 0
            var position = 0L
            var from = 0L
            var until = 0L
            var buffer = FloatArray(0)
            var filled = 0
            fun open() {
                val w = windows[next]
                from = TranscriptResume.sampleAt(w.startMs, sampleRate)
                until = TranscriptResume.sampleAt(w.endMs, sampleRate)
                buffer = FloatArray((until - from).coerceAtLeast(0L).toInt())
                filled = 0
            }
            fun close() {
                out[next] = clusterFor(if (filled == buffer.size) buffer else buffer.copyOf(filled))
                next++
                if (next < windows.size) open()
            }
            open()
            try {
                AudioFileDecoder.decode(
                    context,
                    decodeUri,
                    onPcm = { pcm ->
                        val at = position
                        position += pcm.size
                        var i = 0
                        while (next < windows.size && i < pcm.size) {
                            val abs = at + i
                            if (abs >= until) {
                                close()
                                continue
                            }
                            if (abs < from) {
                                i += minOf(from - abs, (pcm.size - i).toLong()).toInt()
                                continue
                            }
                            val n = minOf(until - abs, (pcm.size - i).toLong()).toInt()
                            System.arraycopy(pcm, i, buffer, filled, n)
                            filled += n
                            i += n
                            if (at + i >= until) close()
                        }
                    },
                    onProgress = {},
                    cancelled = { cancelled() || next >= windows.size }
                )
            } catch (_: Throwable) {
                return null
            }
            if (cancelled()) return null
            // The file ended before the last windows: they take what they got.
            while (next < windows.size) close()
            return out
        }

        try {
            if (settings.vadSpeechGate) {
                vad = runCatching { SileroVad.open(context) }.getOrNull()
                speech = vad?.newRun()
            }
            if (seamSample > 0L) {
                val windows = if (clusterer != null) {
                    TranscriptResume.voiceWindows(meeting.segments, startMs)
                } else {
                    emptyList()
                }
                if (windows.isNotEmpty()) {
                    val at = if (totalMs > 0) {
                        ((startMs * 100) / totalMs).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    onProgress(
                        at,
                        context.getString(
                            com.meetily.mobile.R.string.resume_detail_voices,
                            TranscriptResume.clock(startMs)
                        )
                    )
                    val ids = clusterPrefix(windows)
                    if (ids == null) {
                        // Stopped or failed: the kept lines keep their
                        // clusters, and nothing is clustered against them.
                        clusterer = null
                    } else {
                        for ((n, w) in windows.withIndex()) {
                            for (i in w.segments) {
                                meeting.segments[i] = meeting.segments[i].copy(clusterId = ids[n])
                            }
                        }
                        // The same, for the stored lines each save keeps.
                        relabel = relabel.copy(
                            spans = windows.mapIndexed { n, w ->
                                TranscriptResume.Relabel.Span(w.startMs, w.endMs, ids[n])
                            }
                        )
                    }
                }
                reportProgress()
            }
            // Where decoded audio sits in the file, counted from the seek;
            // only a continuation reads it.
            var position = 0L
            // A seek lands where its timestamps say for every format but MP3,
            // which is decoded from the top with the audio before the seam
            // thrown away (TranscriptResume.seekable).
            val seek = seamSample > 0L &&
                TranscriptResume.seekable(AudioFileDecoder.audioMime(context, decodeUri))
            fun decodeFrom(seekUs: Long): Long = AudioFileDecoder.decode(
                context,
                decodeUri,
                onPcm = { raw ->
                    // A continuation starts at the seam, to the sample.
                    val pcm = if (seamSample > 0L) {
                        val chunkStart = position
                        position += raw.size
                        val skip = TranscriptResume.skipBefore(chunkStart, raw.size, seamSample)
                        when {
                            skip >= raw.size -> return@decode
                            skip > 0 -> raw.copyOfRange(skip, raw.size)
                            else -> raw
                        }
                    } else {
                        loudness.add(raw)
                        raw
                    }
                    // Re-frame into fixed 100 ms windows for the RMS logic.
                    var data = pcm
                    if (pending.isNotEmpty()) {
                        val merged = pending.copyOf(pending.size + pcm.size)
                        System.arraycopy(pcm, 0, merged, pending.size, pcm.size)
                        data = merged
                        pending = FloatArray(0)
                    }
                    var offset = 0
                    while (data.size - offset >= frameSize) {
                        onFrame(data.copyOfRange(offset, offset + frameSize))
                        offset += frameSize
                    }
                    if (offset < data.size) {
                        pending = data.copyOfRange(offset, data.size)
                    }
                },
                // The decoder runs in lockstep with transcription (each PCM
                // chunk is transcribed inside the callback), so its file
                // position is a fair proxy for the bar. The detail line
                // comes from the batches, which is where the time goes.
                onProgress = { p -> onProgress(p, lastDetail) },
                cancelled = cancelled,
                startUs = seekUs,
                onStartUs = if (seekUs > 0L) {
                    { us: Long ->
                        val at = us * sampleRate / 1_000_000L
                        // Landed past the seam: the audio between would never
                        // be transcribed, and every line after it would be
                        // stamped early by the gap.
                        if (at > seamSample) throw SeekOvershoot()
                        position = at
                    }
                } else {
                    null
                }
            )
            val durationMs = if (seek) {
                try {
                    decodeFrom(startMs * 1000L)
                } catch (_: SeekOvershoot) {
                    // Thrown before the first PCM: nothing has been framed.
                    // From the top instead, discarding up to the seam, as
                    // for MP3.
                    position = 0L
                    decodeFrom(0L)
                }
            } else {
                decodeFrom(0L)
            }

            // Cache the bars now the whole file has been through. Best
            // effort: the Audio tab still computes them itself if this did
            // not run — a cancelled import, a continuation (which saw only
            // the end), or audio kept outside the app.
            // decode() returns normally on a cancel, so check here: a prefix
            // would be stretched across the whole card, and on a recheck it
            // would replace a correct sidecar.
            if (!cancelled() && seamSample == 0L) {
                meeting.audioFile?.let { name ->
                    runCatching {
                        com.meetily.mobile.data.Waveform.saveFrom(context, name, loudness)
                    }
                }
            }

            if (!recheck && durationMs > 0 && !persisted) {
                // Fallback only: the container gave no duration up front, so
                // the anchor could not be set before decoding. Safe here
                // precisely because nothing has been written yet — see the
                // up-front anchor above for why shifting after a write is
                // what corrupted finished transcripts.
                val newBase = System.currentTimeMillis() - durationMs
                val shift = newBase - baseMs
                baseMs = newBase
                for (i in meeting.segments.indices) {
                    val s = meeting.segments[i]
                    meeting.segments[i] = s.copy(timestampMs = s.timestampMs + shift)
                }
            }
            // Whatever is still batched has to go through before the run is
            // called complete, or the tail of every import is dropped. Not
            // after a cancel though: the last chunk can force a whole encoder
            // window (or a NeMo pass and an embedding), which would keep the
            // phone busy long after the user or an unplug asked it to stop.
            if (!cancelled()) {
                if (pending.isNotEmpty()) {
                    onFrame(pending.copyOf(min(pending.size, frameSize)))
                    pending = FloatArray(0)
                }
                cutChunk()
                flushBatch()
            }

            // Fuse clusters the online pass kept apart, then persist.
            val remap = clusterer?.mergePass().orEmpty()
            if (remap.isNotEmpty()) {
                for (i in meeting.segments.indices) {
                    val s = meeting.segments[i]
                    val to = s.clusterId?.let { remap[it] } ?: continue
                    meeting.segments[i] = s.copy(clusterId = to)
                }
                relabel = relabel.copy(merged = remap)
            }
            // Name clusters whose voices match saved profiles — after first
            // rolling profiles from other speaker models over to this one
            // (re-embedding their banked audio while the extractor is live).
            val c = clusterer
            if (c != null) {
                val e = embedder
                val dk = diarizeKey
                // After a cancel, name from what is stored rather than spend
                // a rollover's worth of embeddings the user asked not to wait on.
                val profiles = if (dk != null && e != null && !cancelled()) {
                    VoiceProfileStore.reembedForModel(context, dk, cancelled) { e.embed(it) }
                } else {
                    VoiceProfileStore.load(context)
                }
                if (profiles.isNotEmpty()) {
                    val names = mutableMapOf<Int, String>()
                    for (id in c.clusterIds()) {
                        VoiceProfileStore.match(
                            profiles, c.centroidOf(id), model = dk
                        )?.let { names[id] = it }
                    }
                    if (names.isNotEmpty()) {
                        for (i in meeting.segments.indices) {
                            val s = meeting.segments[i]
                            val name = s.clusterId?.let { names[it] } ?: continue
                            if (s.speaker.isNullOrBlank()) {
                                meeting.segments[i] = s.copy(speaker = name)
                            }
                        }
                        relabel = relabel.copy(names = names)
                    }
                }
            }
            if (seamSample > 0L) {
                // A name given to a voice before the seam carries over it.
                val named = TranscriptResume.carryNames(meeting.segments, startMs, baseMs)
                for (i in named.indices) {
                    if (named[i] !== meeting.segments[i]) meeting.segments[i] = named[i]
                }
            }
            // A cancelled run must not leave a draft looking finished: half a
            // second pass would read as "the new model went silent here".
            // A continuation stopped before it saved anything leaves the
            // stored words, and where they stop, exactly as they were.
            if (seamSample == 0L || persisted || !cancelled()) {
                persist(complete = !cancelled())
            }
            // A cancelled recheck drops its draft, unless it is kept to be
            // continued; a cancelled FIRST pass keeps every word it managed,
            // exactly as a plain import does.
            // Deleting here used to discard the whole transcription of a long
            // meeting when the run was cancelled or hit the foreground-service
            // time limit.
            if (stageOnly && cancelled() && !keepPartialCheck()) {
                TranscriptDraft.delete(context, meeting.id)
            }
            // New words under a summary of the part that was there before,
            // or of the lines a start-over replaced: say it is out of date,
            // as an accepted accuracy check does. ImportService leaves such
            // a summary in place rather than starting a new one over it.
            if ((seamSample > 0L || hadWords) && !stageOnly && added > 0) {
                runCatching {
                    store.mutateIf(meeting.id) { m ->
                        if (m.summary.isNotBlank() && !m.summaryStale) {
                            m.summaryStale = true
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            if (durationMs > 0) totalMs = durationMs
            return Result(
                meeting.id, consumedSamples * 1000 / sampleRate, totalMs, null,
                startMs, savedResumeMs, hadWords
            )
        } catch (e: AudioFileDecoder.UnsupportedAudioException) {
            if (stageOnly) {
                TranscriptDraft.delete(context, meeting.id)
            } else if (firstTranscript) {
                // The meeting is the user's recording and its audio is the
                // only copy. Keep both; the manual re-transcribe route stays.
            } else if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
            }
            throw ImportException(e.message ?: "unsupported audio")
        } catch (e: Throwable) {
            if (stageOnly) {
                // Half a transcript is useless for a comparison, and the
                // stored one is untouched either way — drop the draft.
                TranscriptDraft.delete(context, meeting.id)
                throw ImportException(e.message ?: "decode failed")
            }
            if (firstTranscript) {
                // Never delete the recording or its audio here: unlike an
                // import, this meeting existed before the run and the audio
                // is irreplaceable. Keep the words reached, including any the
                // throttled mid-run save had not written yet, with where they
                // stop for a pass to continue from. A continuation that had
                // saved nothing leaves what is stored alone.
                if (meeting.segments.isNotEmpty() && (seamSample == 0L || persisted)) {
                    runCatching { persist(complete = false) }
                }
                throw ImportException(e.message ?: "decode failed")
            }
            if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
                throw ImportException(e.message ?: "decode failed")
            }
            // Partial transcript exists — keep it rather than fail the whole
            // import silently; the caller surfaces how far it got. Saved
            // with where it stops, so the rest can be continued later.
            persist(complete = false)
            return Result(
                meeting.id,
                consumedSamples * 1000 / sampleRate,
                totalMs,
                e.message ?: "decode failed mid-file",
                startMs,
                savedResumeMs,
                hadWords
            )
        } finally {
            if (contextPtr != 0L) WhisperBridge.freeContext(contextPtr)
            com.meetily.mobile.diag.NativeWork.end(whisperWork)
            nemoEngine?.release()
            embedder?.release()
            vad?.release()
        }
    }

    /** A seek that landed after the seam; see the decode in [import]. */
    private class SeekOvershoot : RuntimeException()

    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")"))
    }

    private fun rmsOf(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0
        for (x in buffer) sum += x.toDouble() * x
        return sqrt(sum / buffer.size).toFloat()
    }

    /** Whole minutes, rounded, floored at 1 so short files never read "0". */
    private fun minutesOf(ms: Long): Int =
        (((ms + 30_000L) / 60_000L).coerceAtLeast(1L)).toInt()

    companion object {
        private const val MIN_EMBED_SAMPLES = 24_000 // 1.5 s at 16 kHz

        /** Least time between mid-run transcript saves. */
        private const val PERSIST_INTERVAL_MS = 30_000L

        /** Least time between progress refreshes from chunks the gate skips. */
        private const val SKIP_REPORT_INTERVAL_MS = 2_000L

        /**
         * One Whisper encoder window, less a margin. Whisper's analysis
         * window is 30 s and it pads whatever it is given up to that, so
         * this is the largest batch that stays inside a single pass.
         */
        private const val BATCH_LIMIT_SAMPLES = AudioFileDecoder.TARGET_RATE * 28
    }
}
