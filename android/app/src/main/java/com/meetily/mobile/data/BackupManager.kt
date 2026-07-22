package com.meetily.mobile.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-library backup: a single .zip containing every meeting JSON and every
 * photo. Import merges into the current library (existing ids are overwritten).
 * Settings — which can hold LLM API keys — are deliberately excluded.
 */
object BackupManager {

    private const val MEETINGS = "meetings/"
    private const val PHOTOS = "photos/"
    private const val AUDIO = "audio/"

    private const val VOICES = "voice_profiles.json"

    fun export(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            addDir(zip, File(context.filesDir, "meetings"), MEETINGS) { it.endsWith(".json") }
            addDir(zip, File(context.filesDir, "photos"), PHOTOS) { true }
            addDir(zip, File(context.filesDir, "audio"), AUDIO) { true }
            val voices = File(context.filesDir, VOICES)
            if (voices.isFile) {
                zip.putNextEntry(ZipEntry(VOICES))
                voices.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores meetings + photos + audio from a backup zip. Returns the meeting count. */
    fun import(context: Context, input: InputStream): Int {
        val meetingsDir = File(context.filesDir, "meetings").apply { mkdirs() }
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        var restored = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // File(name).name strips any path components, so a crafted
                    // entry like "../../x" cannot escape the target directory.
                    val target = when {
                        name.startsWith(MEETINGS) && name.endsWith(".json") ->
                            File(meetingsDir, File(name).name)
                        name.startsWith(PHOTOS) ->
                            File(photosDir, File(name).name)
                        name.startsWith(AUDIO) ->
                            File(audioDir, File(name).name)
                        name == VOICES ->
                            File(context.filesDir, VOICES)
                        else -> null
                    }
                    if (target != null && isUnder(target.parentFile, target)) {
                        target.outputStream().use { zip.copyTo(it) }
                        if (name.startsWith(MEETINGS)) restored++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return restored
    }

    private fun addDir(
        zip: ZipOutputStream,
        dir: File,
        prefix: String,
        accept: (String) -> Boolean
    ) {
        val files = dir.listFiles { f -> f.isFile && accept(f.name) } ?: return
        for (file in files) {
            zip.putNextEntry(ZipEntry(prefix + file.name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun isUnder(dir: File?, file: File): Boolean {
        if (dir == null) return false
        return file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }
}
