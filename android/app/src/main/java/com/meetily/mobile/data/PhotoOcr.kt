package com.meetily.mobile.data

import android.content.Context
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device OCR for attached photos (ML Kit's bundled Latin recognizer, so no
 * Play-services model download). Extracted text is stored on the meeting so
 * whiteboard shots become searchable and feed LLM summaries.
 *
 * The photo and its text never leave the phone, but the SDK is not silent:
 * ML Kit logs device, app and performance diagnostics to Google through its
 * own transport (MLKitLoggingOptions with enableFirelog=true in the shipped
 * dex), per Google's ML Kit data disclosure. The meeting screen says so under
 * Photos (privacy_ocr_disclosure), and so does the privacy policy. Anything
 * written about this class must not claim "no network".
 */
object PhotoOcr {

    /**
     * Longest side handed to the recognizer. Whiteboard text stays legible
     * well below a camera's 12 MP, which decoded in full is about 48 MB of
     * pixels per photo.
     */
    private const val MAX_SIDE_PX = 2048

    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * One photo at a time, off the main thread. Queuing every photo of a
     * meeting at once kept all their decoded bitmaps alive together.
     */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "photo-ocr").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Photos queued or being read, and the NativeWork tag that spans them.
     * One tag per run rather than per photo: a backfill reads a meeting's
     * photos back to back, and the summary call is one the system may
     * throttle.
     */
    private val workLock = Any()
    private var inFlight = 0
    private var workToken = 0

    /** The recognizer's tag in the process state summary; not a file. */
    private const val WORK_KEY = "mlkit-latin"

    /**
     * Extracts text from [file]. [onResult] gets the recognised text, an
     * empty string when recognition worked but found no text, or null when
     * the image could not be read or recognition failed — which is worth
     * retrying later, where "no text" is not. Callback arrives on the main
     * thread.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extract(context: Context, file: File, onResult: (String?) -> Unit) {
        synchronized(workLock) {
            if (inFlight++ == 0) {
                workToken = com.meetily.mobile.diag.NativeWork.begin(
                    com.meetily.mobile.diag.NativeJob.OCR, WORK_KEY
                )
            }
        }
        worker.execute {
            val text = try {
                val bitmap = PhotoStore.decodeSampled(file, MAX_SIDE_PX)
                if (bitmap == null) {
                    null
                } else {
                    // decodeSampled ignores EXIF, so the rotation is passed on.
                    val image = InputImage.fromBitmap(bitmap, rotationDegrees(file))
                    Tasks.await(client.process(image)).text.trim()
                }
            } catch (_: Exception) {
                null
            } finally {
                synchronized(workLock) {
                    if (--inFlight == 0) {
                        com.meetily.mobile.diag.NativeWork.end(workToken)
                        workToken = 0
                    }
                }
            }
            main.post { onResult(text) }
        }
    }

    private fun rotationDegrees(file: File): Int = try {
        when (
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
