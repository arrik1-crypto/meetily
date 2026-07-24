package com.meetily.mobile.llm

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LocalLlmModel(
    val key: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int
)

/** GGUF chat models for the embedded llama.cpp engine (filesDir/llm-models). */
object LocalLlmModels {

    val ALL: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            "qwen2.5-1.5b",
            "Qwen 2.5 1.5B (recommended)",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1120
        ),
        LocalLlmModel(
            "qwen2.5-0.5b",
            "Qwen 2.5 0.5B (fastest, lighter answers)",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491
        ),
        LocalLlmModel(
            "llama3.2-3b",
            "Llama 3.2 3B (best quality, slower)",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/" +
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2020
        ),
        LocalLlmModel(
            "gemma3-1b",
            "Gemma 3 1B (fast, light)",
            "google_gemma-3-1b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/" +
                "google_gemma-3-1b-it-Q4_K_M.gguf",
            800
        ),
        LocalLlmModel(
            "gemma3-4b",
            "Gemma 3 4B (great quality · 6 GB+ RAM)",
            "google_gemma-3-4b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/" +
                "google_gemma-3-4b-it-Q4_K_M.gguf",
            2490
        ),
        LocalLlmModel(
            "gemma4-e2b",
            "Gemma 4 E2B (newest Gemma · 8 GB+ RAM)",
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/" +
                "gemma-4-E2B-it-Q4_K_M.gguf",
            3110
        ),
        LocalLlmModel(
            "gemma4-e4b",
            "Gemma 4 E4B (excellent · 12 GB+ RAM)",
            "gemma-4-E4B-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/" +
                "gemma-4-E4B-it-Q4_K_M.gguf",
            4980
        ),
        LocalLlmModel(
            "mistral-7b",
            "Mistral 7B v0.3 (flagship phones · 12 GB+ RAM)",
            "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/" +
                "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            4370
        ),
        LocalLlmModel(
            "llama3.1-8b",
            "Llama 3.1 8B (flagship phones · 12 GB+ RAM)",
            "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/" +
                "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            4920
        ),
        LocalLlmModel(
            "qwen3.5-9b",
            "Qwen 3.5 9B (flagship phones · 16 GB RAM)",
            "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF/resolve/main/" +
                "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
            5680
        )
    )

    fun byKey(key: String): LocalLlmModel =
        ALL.firstOrNull { it.key == key } ?: ALL.first()

    fun dir(context: Context): File =
        File(context.filesDir, "llm-models").apply { mkdirs() }

    fun fileFor(context: Context, model: LocalLlmModel): File =
        File(dir(context), model.fileName)

    fun isDownloaded(context: Context, model: LocalLlmModel): Boolean {
        val file = fileFor(context, model)
        return file.exists() && file.length() > model.sizeMb * 1024L * 1024L * 9 / 10
    }

    fun delete(context: Context, model: LocalLlmModel) {
        fileFor(context, model).delete()
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun cleanPartials(context: Context) {
        dir(context).listFiles { f -> f.name.contains(".part-") }?.forEach { it.delete() }
    }

    fun isRuntimeAvailable(): Boolean = LlamaBridge.load()

    /** Blocking download with 0..100 progress; call from a worker thread. */
    fun download(
        context: Context,
        model: LocalLlmModel,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ) {
        val target = fileFor(context, model)
        val partial = File(target.absolutePath + ".part-" + System.nanoTime())
        val connection = URL(model.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                throw RuntimeException("HTTP $status while downloading model")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        if (cancelled()) {
                            throw InterruptedException("Download cancelled")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
