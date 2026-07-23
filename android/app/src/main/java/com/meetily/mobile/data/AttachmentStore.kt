package com.meetily.mobile.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Arbitrary file attachments (PDFs, decks, docs…) live in
 * filesDir/attachments, referenced from Meeting.attachmentsList with their
 * original display names.
 */
object AttachmentStore {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    fun dir(context: Context): File =
        File(context.filesDir, "attachments").apply { mkdirs() }

    /** New storage file for [displayName]; the extension is preserved. */
    fun newFile(context: Context, meetingId: String, displayName: String): File {
        val safe = displayName.takeLast(60).replace(Regex("[^\\w.\\-]"), "_")
        return File(dir(context), "${meetingId}_${System.currentTimeMillis()}_$safe")
    }

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun delete(context: Context, name: String) {
        fileFor(context, name).delete()
    }
}
