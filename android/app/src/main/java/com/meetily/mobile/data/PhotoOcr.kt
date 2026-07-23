package com.meetily.mobile.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * On-device OCR for attached photos (ML Kit's bundled Latin recognizer — no
 * network, no Play-services download). Extracted text is stored on the
 * meeting so whiteboard shots become searchable and feed LLM summaries.
 */
object PhotoOcr {

    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Extracts text from [file]; [onResult] gets null for unreadable images
     * or ones with no recognizable text. Callback arrives on the main thread.
     */
    fun extract(context: Context, file: File, onResult: (String?) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            client.process(image)
                .addOnSuccessListener { result ->
                    onResult(result.text.trim().ifBlank { null })
                }
                .addOnFailureListener { onResult(null) }
        } catch (_: Exception) {
            onResult(null)
        }
    }
}
