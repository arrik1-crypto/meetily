package com.meetily.mobile.llm

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The imported `.litertlm` model for the LiteRT-LM runtime.
 *
 * **Why import rather than download.** Every first-party publisher of this
 * format is licence-gated — `google/gemma-3n-*` and all of
 * `litert-community/*` return 401 to an unauthenticated request. A one-tap
 * download button has no token to send, so it would 401 for every user.
 *
 * The ungated alternatives are individual re-uploaders, not the established
 * quantizers (`unsloth`, `bartowski`) the GGUF catalogue relies on. Since the
 * app verifies downloads by Content-Length alone, pointing it at arbitrary
 * multi-gigabyte re-uploads of unclear provenance is not something a paid,
 * privacy-first app should do. One of them ships safety tuning stripped out.
 *
 * So the user accepts the licence themselves, in a browser, and hands the
 * file over — which is what the gate is for. Storage is a directory of this
 * runtime's own, deliberately NOT `llm-models`: [LocalLlmModels.deleteAll]
 * deletes every file in that directory, so a shared home would let the GGUF
 * picker's "delete all" silently destroy this model too.
 */
object LiteRtModels {

    /** Where Google publishes the format. Opened in a browser, never fetched. */
    const val LICENCE_PAGE = "https://huggingface.co/litert-community"

    const val EXTENSION = ".litertlm"

    /**
     * Smallest plausible model. Anything below this is a stray file or a
     * download that died early — worth rejecting at import, because the
     * native loader mmaps this and a truncated file is a SIGSEGV rather than
     * a catchable exception.
     */
    private const val MIN_PLAUSIBLE_BYTES = 64L * 1024 * 1024

    fun dir(context: Context): File =
        File(context.filesDir, "litert-models").apply { mkdirs() }

    /** The imported model, or null when nothing has been imported. */
    fun current(context: Context): File? =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            ?.maxByOrNull { it.length() }
            ?.takeIf { it.length() >= MIN_PLAUSIBLE_BYTES }

    fun isImported(context: Context): Boolean = current(context) != null

    fun delete(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Leftovers from an import that was cancelled or killed part-way. */
    fun cleanPartials(context: Context) {
        dir(context).listFiles { f -> f.name.endsWith(PARTIAL_SUFFIX) }
            ?.forEach { it.delete() }
    }

    private const val PARTIAL_SUFFIX = ".importing"

    class ImportFailed(message: String) : Exception(message)

    /**
     * Copies the picked document into app storage, reporting 0..100.
     *
     * Copies rather than referencing the Uri: a content:// permission can be
     * revoked, the backing file can be deleted from Downloads, and the
     * runtime needs a real path to mmap. Multi-gigabyte, so call from a
     * worker thread.
     *
     * Writes to a `.importing` file and renames only on success, so a
     * half-copied model is never visible as a usable one.
     */
    fun import(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false }
    ): File {
        val name = displayName.substringAfterLast('/')
            .ifBlank { "model$EXTENSION" }
            .let { if (it.endsWith(EXTENSION)) it else it + EXTENSION }

        // One model at a time: these are gigabytes, and a second one silently
        // filling the disk is worse than replacing the first.
        delete(context)

        val target = File(dir(context), name)
        val partial = File(target.absolutePath + PARTIAL_SUFFIX)

        val total = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L

        // Refuse before spending twenty minutes of copying rather than after.
        if (total in 1 until MIN_PLAUSIBLE_BYTES) {
            throw ImportFailed(
                "That file is only ${total / (1024 * 1024)} MB — too small to be a " +
                    "LiteRT model. Check the download finished."
            )
        }
        val free = dir(context).usableSpace
        if (total > 0 && free in 0 until total) {
            throw ImportFailed(
                "Not enough space: the model needs ${total / (1024 * 1024)} MB and " +
                    "${free / (1024 * 1024)} MB is free."
            )
        }

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportFailed("Could not open that file.")
            input.use { source ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        if (cancelled()) throw InterruptedException("Import cancelled")
                        val n = source.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    // A stream that ends early still "succeeds"; only the
                    // length proves the whole file arrived.
                    if (total > 0 && copied < total) {
                        throw ImportFailed(
                            "The copy stopped early (${copied / (1024 * 1024)} of " +
                                "${total / (1024 * 1024)} MB). Try again."
                        )
                    }
                    if (copied < MIN_PLAUSIBLE_BYTES) {
                        throw ImportFailed(
                            "That file is too small to be a LiteRT model."
                        )
                    }
                }
            }
            if (!partial.renameTo(target)) {
                throw ImportFailed("Could not finish writing the model.")
            }
            return target
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }
}
