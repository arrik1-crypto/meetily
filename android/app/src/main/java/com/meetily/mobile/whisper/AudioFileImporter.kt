package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.AudioStore
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
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

    private val sampleRate = AudioFileDecoder.TARGET_RATE
    private val minChunkSec = 1.6f
    private val maxChunkSec = 28f
    private val endSilenceSec = 0.8f
    private val silenceRms = 0.008f
    private val minSpeechRms = 0.012f
    private val frameSize = sampleRate / 10 // 100 ms

    /**
     * Returns the created meeting id (also for cancelled imports, which keep
     * whatever was transcribed so far). Throws [ImportException] on failure
     * before any audio was processed.
     */
    fun import(
        uri: Uri,
        title: String,
        sourceName: String = title,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): String {
        val model = WhisperModels.byKey(settings.whisperModel)
        if (!WhisperModels.isRuntimeAvailable()) {
            throw ImportException("Whisper runtime unavailable on this device")
        }
        if (!WhisperModels.isDownloaded(context, model)) {
            throw ImportException("Whisper model not downloaded")
        }
        val contextPtr = WhisperBridge.initContext(
            WhisperModels.fileFor(context, model).absolutePath
        )
        if (contextPtr == 0L) {
            throw ImportException("Could not load the Whisper model")
        }
        val language = if (model.englishOnly) "en" else "auto"
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        var embedder: SherpaEmbedder? = null
        var clusterer: SpeakerClusterer? = null
        if (settings.diarizationEnabled) {
            val dModel = DiarizationModels.byKey(settings.diarizationModel)
            if (DiarizationModels.isDownloaded(context, dModel)) {
                embedder = SherpaEmbedder.create(
                    DiarizationModels.fileFor(context, dModel).absolutePath
                )
                if (embedder != null) clusterer = SpeakerClusterer()
            }
        }

        val store = MeetingStore(context)
        // Timestamps are anchored so the imported meeting reads as having
        // just ended (base + in-file offset); refined once duration is known.
        var baseMs = System.currentTimeMillis()
        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAtMs = baseMs
        )

        // Keep a copy of the source audio so playback and later
        // re-transcription work on imported meetings too. Decoding also runs
        // from this copy: the caller's content-URI grant can be revoked once
        // the sharing activity goes away, but our own file cannot.
        try {
            val audioCopy = AudioStore.newImportFile(context, meeting.id, sourceName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                audioCopy.outputStream().use { input.copyTo(it) }
            }
            if (audioCopy.length() > 0) {
                meeting.audioFile = audioCopy.name
            } else {
                audioCopy.delete()
            }
        } catch (_: Exception) {
            meeting.audioFile = null
        }
        val decodeUri = meeting.audioFile?.let {
            Uri.fromFile(AudioStore.fileFor(context, it))
        } ?: uri

        // Chunker state (same splitting rules as live recording).
        var chunk = FloatArray(0)
        var silenceRun = 0f
        var chunkPeakRms = 0f
        var consumedSamples = 0L
        var chunkStartSample = 0L
        var pending = FloatArray(0) // partial frame carry-over

        fun transcribeChunk(audio: FloatArray, startSample: Long) {
            val clusterId = try {
                val c = clusterer
                val e = embedder
                if (c != null && e != null) {
                    c.assign(
                        if (audio.size >= MIN_EMBED_SAMPLES) e.embed(audio) else null
                    )
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
            val padded = if (audio.size < sampleRate * 12 / 10) {
                audio.copyOf(sampleRate * 12 / 10)
            } else {
                audio
            }
            val text = WhisperBridge.transcribe(contextPtr, padded, language, nThreads)
                ?.trim()
                .orEmpty()
            if (text.isBlank() || isNoise(text)) return
            meeting.segments.add(
                TranscriptSegment(
                    timestampMs = baseMs + startSample * 1000 / sampleRate,
                    text = text,
                    speaker = null,
                    clusterId = clusterId,
                    audioMs = if (meeting.audioFile != null) {
                        startSample * 1000 / sampleRate
                    } else null
                )
            )
            store.save(meeting)
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
                onProgress = onProgress,
                cancelled = cancelled
            )
            if (durationMs > 0) {
                // Re-anchor so the meeting reads as ending "now".
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

            // Fuse clusters the online pass kept apart, then persist.
            val remap = clusterer?.mergePass().orEmpty()
            if (remap.isNotEmpty()) {
                for (i in meeting.segments.indices) {
                    val s = meeting.segments[i]
                    val to = s.clusterId?.let { remap[it] } ?: continue
                    meeting.segments[i] = s.copy(clusterId = to)
                }
            }
            store.save(meeting)
            return meeting.id
        } catch (e: AudioFileDecoder.UnsupportedAudioException) {
            if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
            }
            throw ImportException(e.message ?: "unsupported audio")
        } catch (e: Throwable) {
            if (meeting.segments.isEmpty()) {
                store.delete(meeting.id)
                AudioStore.delete(context, meeting.audioFile)
                throw ImportException(e.message ?: "decode failed")
            }
            // Partial transcript exists — keep it rather than fail the whole
            // import at the finish line.
            store.save(meeting)
            return meeting.id
        } finally {
            WhisperBridge.freeContext(contextPtr)
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

    companion object {
        private const val MIN_EMBED_SAMPLES = 24_000 // 1.5 s at 16 kHz
    }
}
