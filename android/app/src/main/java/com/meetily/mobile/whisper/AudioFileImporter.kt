package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.TranscriptDraft
import com.meetily.mobile.data.TranscriptSegment
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Imports an audio file into a Meeting: streaming decode → silence-split
 * chunks → Whisper transcription (+ optional speaker diarization), saving
 * incrementally so a cancelled or crashed import keeps its partial
 * transcript. Blocking; call from a worker thread.
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
        val stoppedEarly: String?
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
         * A copy already staged in AudioStore by the import queue. Renamed
         * into place rather than copied again — it is the same bytes.
         */
        adoptFile: String? = null,
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
        }
        val recheck = recheckTarget != null

        // NeMo path (Parakeet/Nemotron via sherpa-onnx) or whisper.cpp.
        val nemoModel = NemoModels.byKeyOrNull(selectedKey)
        var nemoEngine: NemoEngine? = null
        var contextPtr = 0L
        // var, not val: set to the detected language after the first batch
        // so "auto" is paid for once rather than on every call.
        var language = "en"
        var translate = false
        if (nemoModel != null) {
            if (!NemoModels.isRuntimeAvailable()) {
                throw ImportException("Speech runtime unavailable on this device")
            }
            nemoEngine = NemoEngine.create(context, nemoModel, nThreads())
                ?: throw ImportException("Could not load the ${nemoModel.displayName} model")
        } else {
            val model = WhisperModels.byKey(selectedKey)
            if (!WhisperModels.isRuntimeAvailable()) {
                throw ImportException("Whisper runtime unavailable on this device")
            }
            if (!WhisperModels.isDownloaded(context, model)) {
                throw ImportException("Whisper model not downloaded")
            }
            contextPtr = WhisperBridge.initContext(
                WhisperModels.fileFor(context, model).absolutePath
            )
            if (contextPtr == 0L) {
                throw ImportException("Could not load the Whisper model")
            }
            language = if (model.englishOnly) "en" else "auto"
            translate = settings.whisperTranslate && !model.englishOnly
        }

        var embedder: SherpaEmbedder? = null
        var clusterer: SpeakerClusterer? = null
        var diarizeKey: String? = null
        if (settings.diarizationEnabled) {
            val dModel = DiarizationModels.byKey(settings.diarizationModel)
            if (DiarizationModels.isDownloaded(context, dModel)) {
                embedder = SherpaEmbedder.create(
                    DiarizationModels.fileFor(context, dModel).absolutePath
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
        onMeetingCreated?.invoke(meeting.id)

        /**
         * Where transcribed segments land. A normal import owns its meeting
         * and saves into it incrementally; a recheck must not touch the
         * stored meeting at all, so it stages instead.
         */
        fun persist(complete: Boolean) {
            if (recheck) {
                TranscriptDraft.save(
                    context, meeting.id, selectedKey, meeting.segments, complete
                )
            } else {
                store.save(meeting)
            }
        }

        if (recheck) {
            meeting.audioFile = recheckTarget?.audioFile
        } else {
            // Keep a copy of the source audio so playback and later
            // re-transcription work on imported meetings too. Decoding also
            // runs from this copy: the caller's content-URI grant can be
            // revoked once the sharing activity goes away, but our own file
            // cannot.
            try {
                val audioCopy = AudioStore.newImportFile(context, meeting.id, sourceName)
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
                meeting.audioFile = null
            }
        }
        val decodeUri = meeting.audioFile?.let {
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
        var chunk = FloatArray(0)
        var silenceRun = 0f
        var chunkPeakRms = 0f
        var consumedSamples = 0L
        var chunkStartSample = 0L
        var pending = FloatArray(0) // partial frame carry-over
        // Kept so the decoder's own progress ticks can carry the last
        // "how much longer" line rather than blanking it between batches.
        var lastDetail = ""

        /** One cut chunk, waiting to be sent to Whisper as part of a batch. */
        class Part(val audio: FloatArray, val startSample: Long, val clusterId: Int?)

        val batch = mutableListOf<Part>()
        var batchSamples = 0

        fun clusterFor(audio: FloatArray): Int? = try {
            val c = clusterer
            val e = embedder
            if (c != null && e != null) {
                c.assign(if (audio.size >= MIN_EMBED_SAMPLES) e.embed(audio) else null)
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

        /** Progress line: how much audio is done, and how much longer. */
        fun reportProgress() {
            val doneMs = consumedSamples * 1000 / sampleRate
            val percent = if (totalMs > 0) {
                ((doneMs * 100) / totalMs).toInt().coerceIn(0, 100)
            } else {
                0
            }
            val left = ImportEta.remainingMinutes(
                doneMs, totalMs, System.currentTimeMillis() - startedAtMs
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
            if (batch.isEmpty()) return
            val parts = batch.toList()
            batch.clear()
            batchSamples = 0

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

            val raw = WhisperBridge.transcribeWords(
                contextPtr, buffer, language, nThreads(), translate,
                Vocab.promptFor(settings.customVocab)
            )
            // "auto" costs a whole extra encoder pass per call, so pay it
            // once and pin the answer for the rest of the file. Whisper is
            // also better placed to judge from the first real speech than
            // from every later batch of spliced, silence-stripped audio.
            if (language == "auto") {
                WhisperBridge.lastLanguage(contextPtr)
                    ?.takeIf { it.isNotBlank() && it != "auto" }
                    ?.let { language = it }
            }
            val (text, words) = WhisperBridge.parseWords(raw)
            if (words.isNotEmpty() && parts.size > 1) {
                val perPart = BatchSplit.split(spans, words)
                for (i in parts.indices) {
                    val partWords = perPart[i]
                    if (partWords.isEmpty()) continue
                    addSegment(
                        partWords.joinToString(" ") { it.text },
                        parts[i].startSample,
                        partWords,
                        parts[i].clusterId
                    )
                }
            } else if (words.isNotEmpty()) {
                addSegment(text, parts.first().startSample, words, parts.first().clusterId)
            } else {
                // No word timings came back. parseWords derives the text FROM
                // the words, so this also means no text — and the old code
                // simply dropped the batch here. Pre-batching that lost one
                // chunk; now it would lose up to 28 seconds of the meeting,
                // silently. Fall back to the plain path, which asks for text
                // without timings and carries the same one-encode guarantee.
                val plain = WhisperBridge.transcribe(
                    contextPtr, buffer, language, nThreads(), translate,
                    Vocab.promptFor(settings.customVocab)
                )?.trim().orEmpty()
                if (plain.isNotBlank()) {
                    addSegment(plain, parts.first().startSample, emptyList(), parts.first().clusterId)
                }
            }
            persist(complete = false)
            reportProgress()
        }

        fun transcribeChunk(audio: FloatArray, startSample: Long) {
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
            persist(complete = false)
            reportProgress()
        }

        fun cutChunk() {
            if (chunk.isEmpty()) return
            val audio = chunk
            val peak = chunkPeakRms
            val start = chunkStartSample
            chunk = FloatArray(0)
            silenceRun = 0f
            chunkPeakRms = 0f
            chunkStartSample = consumedSamples
            if (peak < minSpeechRms) return
            transcribeChunk(audio, start)
        }

        fun onFrame(frame: FloatArray) {
            val rms = rmsOf(frame)
            chunkPeakRms = max(chunkPeakRms, rms)
            silenceRun = if (rms < silenceRms) silenceRun + 0.1f else 0f
            if (chunk.isEmpty()) chunkStartSample = consumedSamples
            val grown = chunk.copyOf(chunk.size + frame.size)
            System.arraycopy(frame, 0, grown, chunk.size, frame.size)
            chunk = grown
            consumedSamples += frame.size
            val chunkSec = chunk.size.toFloat() / sampleRate
            if ((chunkSec >= minChunkSec && silenceRun >= endSilenceSec) ||
                chunkSec >= maxChunkSec
            ) {
                cutChunk()
            }
        }

        try {
            val durationMs = AudioFileDecoder.decode(
                context,
                decodeUri,
                onPcm = { pcm ->
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
                cancelled = cancelled
            )
            if (!recheck && durationMs > 0) {
                // Re-anchor so the meeting reads as ending "now". A recheck
                // stays on the original meeting's clock — the two passes have
                // to line up on the same audio.
                val newBase = System.currentTimeMillis() - durationMs
                val shift = newBase - baseMs
                baseMs = newBase
                for (i in meeting.segments.indices) {
                    val s = meeting.segments[i]
                    meeting.segments[i] = s.copy(timestampMs = s.timestampMs + shift)
                }
            }
            if (pending.isNotEmpty()) {
                onFrame(pending.copyOf(min(pending.size, frameSize)))
                pending = FloatArray(0)
            }
            cutChunk()
            // Whatever is still batched has to go through before the run is
            // called complete, or the tail of every import is dropped. Not
            // after a cancel though: a batch is a whole encoder window, so
            // flushing one would keep the phone busy for a minute after the
            // user asked it to stop.
            if (!cancelled()) flushBatch()

            // Fuse clusters the online pass kept apart, then persist.
            val remap = clusterer?.mergePass().orEmpty()
            if (remap.isNotEmpty()) {
                for (i in meeting.segments.indices) {
                    val s = meeting.segments[i]
                    val to = s.clusterId?.let { remap[it] } ?: continue
                    meeting.segments[i] = s.copy(clusterId = to)
                }
            }
            // Name clusters whose voices match saved profiles — after first
            // rolling profiles from other speaker models over to this one
            // (re-embedding their banked audio while the extractor is live).
            val c = clusterer
            if (c != null) {
                val e = embedder
                val dk = diarizeKey
                val profiles = if (dk != null && e != null) {
                    VoiceProfileStore.reembedForModel(context, dk) { e.embed(it) }
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
                    }
                }
            }
            // A cancelled run must not leave a draft looking finished: half a
            // second pass would read as "the new model went silent here".
            persist(complete = !cancelled())
            if (recheck && cancelled()) TranscriptDraft.delete(context, meeting.id)
            if (durationMs > 0) totalMs = durationMs
            return Result(
                meeting.id, consumedSamples * 1000 / sampleRate, totalMs, null
            )
        } catch (e: AudioFileDecoder.UnsupportedAudioException) {
            if (recheck) {
                TranscriptDraft.delete(context, meeting.id)
            } else if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
            }
            throw ImportException(e.message ?: "unsupported audio")
        } catch (e: Throwable) {
            if (recheck) {
                // Half a transcript is useless for a comparison, and the
                // stored one is untouched either way — drop the draft.
                TranscriptDraft.delete(context, meeting.id)
                throw ImportException(e.message ?: "decode failed")
            }
            if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
                throw ImportException(e.message ?: "decode failed")
            }
            // Partial transcript exists — keep it rather than fail the whole
            // import silently; the caller surfaces how far it got.
            store.save(meeting)
            return Result(
                meeting.id,
                consumedSamples * 1000 / sampleRate,
                totalMs,
                e.message ?: "decode failed mid-file"
            )
        } finally {
            if (contextPtr != 0L) WhisperBridge.freeContext(contextPtr)
            nemoEngine?.release()
            embedder?.release()
        }
    }

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

        /**
         * One Whisper encoder window, less a margin. Whisper's analysis
         * window is 30 s and it pads whatever it is given up to that, so
         * this is the largest batch that stays inside a single pass.
         */
        private const val BATCH_LIMIT_SAMPLES = AudioFileDecoder.TARGET_RATE * 28
    }
}
