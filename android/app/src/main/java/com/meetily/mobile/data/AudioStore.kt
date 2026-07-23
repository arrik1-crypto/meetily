package com.meetily.mobile.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Meeting audio lives in filesDir/audio, referenced by filename from
 * Meeting.audioFile. Live recordings are ADTS AAC (.aac — playable even if
 * the process dies mid-write); imported meetings keep a copy of the source
 * file in its original container.
 */
object AudioStore {

    // Derived from the installed package so the authority always matches
    // the manifest's ${applicationId}.fileprovider placeholder.
    private fun authority(context: Context) = context.packageName + ".fileprovider"

    fun dir(context: Context): File =
        File(context.filesDir, "audio").apply { mkdirs() }

    fun newRecordingFile(context: Context, meetingId: String): File =
        File(dir(context), "$meetingId.aac")

    /** Import copies keep the source extension so players pick the codec. */
    fun newImportFile(context: Context, meetingId: String, sourceName: String): File {
        val ext = sourceName.substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(5)
            .ifBlank { "bin" }
        return File(dir(context), "$meetingId.$ext")
    }

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    fun exists(context: Context, name: String?): Boolean =
        !name.isNullOrBlank() && fileFor(context, name).length() > 0

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun delete(context: Context, name: String?) {
        if (!name.isNullOrBlank()) fileFor(context, name).delete()
    }
}
