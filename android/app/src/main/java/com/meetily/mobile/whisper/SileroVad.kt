package com.meetily.mobile.whisper

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Silero VAD v6.2.0, bundled in the APK and run on the phone through
 * whisper.cpp's whisper_vad_* API in libmeetily_whisper: the detector behind
 * [SpeechGateRun]. MIT-licensed; listed on the open-source licences screen.
 *
 * It needs only that library, loaded through [WhisperBridge.load] for the
 * CPU build the phone supports, not a Whisper model, so it gates Parakeet
 * and Nemotron as well. Every failure (copying the model, loading the
 * library, creating the context) makes [open] return null and is logged
 * once per process; the caller then transcribes as it would without a gate.
 *
 * One handle per run. [probabilities] and [release] are serialised, so a
 * release from another thread waits for a call in flight instead of freeing
 * the context under it.
 */
class SileroVad private constructor(
    private var handle: Long,
    /** NativeWork tag for the context; ended in [release]. */
    private val workToken: Int
) : SpeechDetector {

    private val lock = Any()

    override fun probabilities(windows: FloatArray, reset: Boolean): FloatArray? {
        synchronized(lock) {
            val h = handle
            if (h == 0L) return null
            return try {
                WhisperBridge.vadDetect(h, windows, reset)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** A gate for one run over this detector; it logs once if it gives up. */
    fun newRun(): SpeechGateRun = SpeechGateRun(this) { reason -> logOnce(reason) }

    /** Frees the native context. Idempotent; blocks on at most one call in flight. */
    fun release() {
        synchronized(lock) {
            val h = handle
            handle = 0L
            if (h != 0L) {
                try {
                    WhisperBridge.vadFree(h)
                } catch (_: Throwable) {
                }
            }
        }
        com.meetily.mobile.diag.NativeWork.end(workToken)
    }

    companion object {
        private const val TAG = "meetily_vad"

        private const val ASSET = SpeechGate.MODEL_ASSET
        private const val ASSET_BYTES = SpeechGate.MODEL_BYTES

        /** The NativeWork key: a model name, never a path. */
        private const val WORK_KEY = "silero-v6.2.0"

        /** Under noBackupFilesDir: a copy of an APK asset has nothing to back up. */
        private const val DIR = "vad"

        private val copyLock = Any()
        private val logged = HashSet<String>()

        /**
         * Opens the bundled model, or returns null. Call off the main thread:
         * the first call copies the model out of the APK, because
         * whisper_vad needs a file path.
         */
        fun open(context: Context): SileroVad? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                logOnce("opened on the main thread")
                return null
            }
            val file = modelFile(context) ?: return null
            if (!WhisperBridge.load()) {
                logOnce("speech library unavailable")
                return null
            }
            // Tagged before the native load, like the model loads, and ended
            // when the context is freed.
            val work = com.meetily.mobile.diag.NativeWork.begin(
                com.meetily.mobile.diag.NativeJob.VAD, WORK_KEY
            )
            val handle = try {
                WhisperBridge.vadInit(file.absolutePath, 1)
            } catch (_: Throwable) {
                0L
            }
            if (handle == 0L) {
                com.meetily.mobile.diag.NativeWork.end(work)
                logOnce("model did not load")
                return null
            }
            return SileroVad(handle, work)
        }

        /**
         * The model as a file in app-private storage, copied from the APK the
         * first time: to a temporary file in the same directory, checked for
         * the full size, then renamed into place, so a copy cut short by a
         * crash or a full disk is never taken for the model.
         */
        private fun modelFile(context: Context): File? {
            synchronized(copyLock) {
                return copyModel(context)
            }
        }

        private fun copyModel(context: Context): File? {
            return try {
                val dir = File(context.noBackupFilesDir, DIR)
                val target = File(dir, ASSET)
                if (target.isFile && target.length() == ASSET_BYTES) return target
                if (!dir.isDirectory && !dir.mkdirs()) {
                    logOnce("no model directory")
                    return null
                }
                // Leftovers: a copy that died part-way, or an older model.
                dir.listFiles()?.forEach { if (it.name != ASSET) it.delete() }
                val temp = File.createTempFile("silero", ".part", dir)
                try {
                    val copied = context.assets.open(ASSET).use { input ->
                        FileOutputStream(temp).use { out ->
                            val n = input.copyTo(out)
                            out.fd.sync()
                            n
                        }
                    }
                    if (copied != ASSET_BYTES || temp.length() != ASSET_BYTES) {
                        logOnce("model copy has the wrong size")
                        return null
                    }
                    if (!temp.renameTo(target)) {
                        logOnce("model copy could not be renamed")
                        return null
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
                target
            } catch (e: Throwable) {
                logOnce("model copy failed (${e.javaClass.simpleName})")
                null
            }
        }

        /** Shape only: never a path, a name or anything from a meeting. */
        private fun logOnce(reason: String) {
            val first = synchronized(logged) { logged.add(reason) }
            if (first) Log.w(TAG, "speech gate off, transcribing every chunk: $reason")
        }
    }
}
