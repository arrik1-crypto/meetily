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
 * On-device OCR for attached photos (ML Kit's bundled Latin recognizer — no
 * network, no Play-services download). Extracted text is stored on the
 * meeting so whiteboard shots become searchable and feed LLM summaries.
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
     * Extracts text from [file]. [onResult] gets the recognised text, an
     * empty string when recognition worked but found no text, or null when
     * the image could not be read or recognition failed — which is worth
     * retrying later, where "no text" is not. Callback arrives on the main
     * thread.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extract(context: Context, file: File, onResult: (String?) -> Unit) {
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
