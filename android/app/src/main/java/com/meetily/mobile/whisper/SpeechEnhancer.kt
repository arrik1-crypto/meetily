package com.meetily.mobile.whisper

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.meetily.mobile.data.ClearVoice
import com.meetily.mobile.data.PassageAudio
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Makes a clear-voice copy of a meeting's audio: the bundled GTCRN model
 * through sherpa-onnx's offline speech denoiser, run on the phone.
 *
 * The audio is decoded to 16 kHz mono as it is read (AudioFileDecoder, the
 * same decoder transcription uses, which resamples whatever the file holds),
 * denoised in overlapping 30-second blocks (EnhanceBlocks) and encoded as it
 * comes out (ClearVoiceEncoder), so memory stays flat however long the
 * meeting is. Nothing leaves the phone. One instance per run, on the
 * caller's worker thread; ClearVoiceService owns the run.
 *
 * The copy keeps the recording's timeline: every run first measures how far
 * encoding and decoding a copy moves the sound on this phone ([measureDelay])
 * and the encoder takes that much off the start. A run that cannot measure
 * it fails rather than write a copy whose words are out of step.
 */
class SpeechEnhancer private constructor(
    private val denoiser: OfflineSpeechDenoiser,
    private val workToken: Int
) {

    /** How a run ended. */
    enum class Outcome { DONE, CANCELLED, NOTHING_DECODED, FAILED }

    /**
     * Writes the clear-voice copy of [source] to [target], which is deleted
     * first and again on any outcome but [Outcome.DONE]. [onProgress] gets
     * the decoder's position (0..99, only when the file states its length)
     * and the audio written so far, in ms: as the decoder moves, after each
     * block, and with 100 once the copy is whole. [cancelled] is asked between decoder
     * chunks; a block already running (a few seconds of work) finishes first.
     */
    @WorkerThread
    fun enhance(
        context: Context,
        source: File,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (percent: Int, doneMs: Long) -> Unit
    ): Outcome {
        target.delete()
        val delay = measureDelay(context, cancelled)
        if (cancelled()) return Outcome.CANCELLED
        if (delay == null) {
            logOnce("codec delay not measured")
            return Outcome.FAILED
        }
        val encoder = try {
            ClearVoiceEncoder(target, delay)
        } catch (e: Throwable) {
            logOnce("encoder unavailable (${e.javaClass.simpleName})")
            target.delete()
            return Outcome.FAILED
        }
        var outcome = Outcome.FAILED
        try {
            var percent = 0
            var doneSamples = 0L
            val blocks = EnhanceBlocks(
                denoise = { input -> denoiser.run(input, ClearVoice.SAMPLE_RATE).samples },
                emit = { samples, count ->
                    encoder.write(samples, count)
                    doneSamples += count
                    // Also what keeps the caller's wake lock fresh on a file
                    // that states no length, where the decoder reports none.
                    onProgress(percent, doneSamples * 1000L / ClearVoice.SAMPLE_RATE)
                }
            )
            AudioFileDecoder.decode(
                context,
                Uri.fromFile(source),
                onPcm = { chunk -> blocks.push(chunk) },
                onProgress = { p ->
                    // 100 is kept for the end: the last block is still to run.
                    percent = p.coerceAtMost(99)
                    onProgress(percent, doneSamples * 1000L / ClearVoice.SAMPLE_RATE)
                },
                cancelled = cancelled
            )
            outcome = when {
                cancelled() -> Outcome.CANCELLED
                blocks.received == 0L -> Outcome.NOTHING_DECODED
                else -> {
                    blocks.finish()
                    if (cancelled()) {
                        Outcome.CANCELLED
                    } else if (encoder.finish()) {
                        onProgress(100, doneSamples * 1000L / ClearVoice.SAMPLE_RATE)
                        Outcome.DONE
                    } else {
                        logOnce("encoder produced nothing")
                        Outcome.FAILED
                    }
                }
            }
        } catch (e: Throwable) {
            // Shape only: never a path, a title or anything from the meeting.
            logOnce("run failed (${e.javaClass.simpleName})")
            outcome = Outcome.FAILED
        } finally {
            encoder.release()
            if (outcome != Outcome.DONE) target.delete()
        }
        return outcome
    }

    /**
     * How many samples encoding a copy (ClearVoiceEncoder) and decoding it
     * again with the decoder transcription uses (AudioFileDecoder) delay the
     * sound, as PassageSilencer measures its own encoder: PassageAudio.probe
     * is encoded into a small file in the cache directory, read back and
     * matched against itself. Null when that fails or is cancelled. The probe
     * is a made-up sound, nothing from a meeting, and is deleted either way.
     */
    private fun measureDelay(context: Context, cancelled: () -> Boolean): Int? {
        val probe = File(context.cacheDir, PROBE_FILE)
        return try {
            probe.delete()
            val sent = PassageAudio.probe(ClearVoice.SAMPLE_RATE)
            val encoder = ClearVoiceEncoder(probe)
            try {
                encoder.write(sent, sent.size)
                if (!encoder.finish()) return null
            } finally {
                encoder.release()
            }
            var back = FloatArray(sent.size * 2)
            var filled = 0
            AudioFileDecoder.decode(
                context,
                Uri.fromFile(probe),
                onPcm = { chunk ->
                    if (filled + chunk.size > back.size) {
                        back = back.copyOf(maxOf(back.size * 2, filled + chunk.size))
                    }
                    chunk.copyInto(back, filled)
                    filled += chunk.size
                },
                onProgress = {},
                cancelled = cancelled
            )
            if (cancelled()) null else PassageAudio.delayOf(sent, back.copyOf(filled))
        } catch (e: Throwable) {
            logOnce("delay probe failed (${e.javaClass.simpleName})")
            null
        } finally {
            probe.delete()
        }
    }

    /** Frees the native denoiser. Call once, when the run is over. */
    fun release() {
        try {
            denoiser.release()
        } catch (_: Throwable) {
        }
        com.meetily.mobile.diag.NativeWork.end(workToken)
    }

    companion object {
        private const val TAG = "meetily_clearvoice"

        /** The NativeWork key: the model's name, never a path. */
        private const val WORK_KEY = "gtcrn-simple"

        /** Under noBackupFilesDir: a copy of an APK asset has nothing to back up. */
        private const val DIR = "clearvoice"

        /** The delay probe, in cacheDir: one run at a time, so one name. */
        private const val PROBE_FILE = "clearvoice-probe.m4a"

        private val copyLock = Any()
        private val logged = HashSet<String>()

        /** The copied model's digest was checked in this process. */
        @Volatile private var verified = false

        /**
         * Loads the bundled model, or returns null (logged once, by shape).
         * Call off the main thread: the first call copies the model out of
         * the APK and checks its digest, because sherpa-onnx loads a path.
         */
        @WorkerThread
        fun open(context: Context): SpeechEnhancer? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                logOnce("opened on the main thread")
                return null
            }
            val file = synchronized(copyLock) { modelFile(context) } ?: return null
            val work = com.meetily.mobile.diag.NativeWork.begin(
                com.meetily.mobile.diag.NativeJob.ENHANCE, WORK_KEY
            )
            val denoiser = try {
                // newFromFile catches native errors and returns 0, which the
                // vendored constructor turns into IllegalArgumentException.
                OfflineSpeechDenoiser(config = SherpaConfigs.denoiser(file.absolutePath))
            } catch (e: Throwable) {
                // UnsatisfiedLinkError when the sherpa-onnx library is absent.
                com.meetily.mobile.diag.NativeWork.end(work)
                logOnce("model did not load (${e.javaClass.simpleName})")
                return null
            }
            val rate = try {
                denoiser.sampleRate
            } catch (_: Throwable) {
                0
            }
            if (rate != ClearVoice.SAMPLE_RATE) {
                try {
                    denoiser.release()
                } catch (_: Throwable) {
                }
                com.meetily.mobile.diag.NativeWork.end(work)
                logOnce("model rate $rate")
                return null
            }
            return SpeechEnhancer(denoiser, work)
        }

        /**
         * The model as a file in app-private storage: copied from the APK to
         * a temporary file beside it, checked for size, renamed into place,
         * and checked against [ClearVoice.MODEL_SHA256] once per process.
         * sherpa-onnx ends the process on a model without the metadata it
         * reads, so a damaged copy is deleted rather than loaded.
         */
        private fun modelFile(context: Context): File? {
            return try {
                val dir = File(context.noBackupFilesDir, DIR)
                val target = File(dir, ClearVoice.MODEL_ASSET)
                if (!(target.isFile && target.length() == ClearVoice.MODEL_BYTES)) {
                    if (!dir.isDirectory && !dir.mkdirs()) {
                        logOnce("no model directory")
                        return null
                    }
                    // Leftovers: a copy that died part-way, or an older model.
                    dir.listFiles()?.forEach { if (it.name != ClearVoice.MODEL_ASSET) it.delete() }
                    val temp = File.createTempFile("gtcrn", ".part", dir)
                    try {
                        val copied = context.assets.open(ClearVoice.MODEL_ASSET).use { input ->
                            FileOutputStream(temp).use { out ->
                                val n = input.copyTo(out)
                                out.fd.sync()
                                n
                            }
                        }
                        if (copied != ClearVoice.MODEL_BYTES || temp.length() != ClearVoice.MODEL_BYTES) {
                            logOnce("model copy has the wrong size")
                            return null
                        }
                        target.delete()
                        if (!temp.renameTo(target)) {
                            logOnce("model copy could not be renamed")
                            return null
                        }
                    } finally {
                        if (temp.exists()) temp.delete()
                    }
                    verified = false
                }
                if (!verified) {
                    if (sha256(target) != ClearVoice.MODEL_SHA256) {
                        target.delete()
                        logOnce("model copy has the wrong digest")
                        return null
                    }
                    verified = true
                }
                target
            } catch (e: Throwable) {
                logOnce("model copy failed (${e.javaClass.simpleName})")
                null
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Shape only: never a path, a name or anything from a meeting. */
        private fun logOnce(reason: String) {
            val first = synchronized(logged) { logged.add(reason) }
            if (first) Log.w(TAG, "clear voice: $reason")
        }
    }
}
