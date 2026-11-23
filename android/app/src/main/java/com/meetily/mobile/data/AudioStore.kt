package com.meetily.mobile.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Meeting audio lives in filesDir/audio, referenced by filename from
 * Meeting.audioFile. Live recordings are ADTS AAC (.aac — playable even if
 * the process dies mid-write); imported meetings keep a copy of the source
 * file in its original container. A clear-voice copy (ClearVoice), when one
 * was made, sits beside its recording as `<name>.clear.m4a`.
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

    // Names come from meeting JSON, which a restored backup controls.
    fun fileFor(context: Context, name: String): File = SafeFiles.child(dir(context), name)

    fun exists(context: Context, name: String?): Boolean =
        !name.isNullOrBlank() && fileFor(context, name).length() > 0

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun delete(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        val file = fileFor(context, name)
        file.delete()
        // Waveform caches the computed bars beside the audio; without this it
        // outlives the recording it describes, with nothing left to reach it.
        File(file.absolutePath + ".peaks").delete()
        // The clear-voice copy is named after the recording it was made from
        // (ClearVoice), so it goes with it: finished, or still being written
        // (the writer then finds its file gone and keeps nothing). Every
        // path that deletes a meeting or its audio comes through here.
        if (!ClearVoice.isCopyName(name)) {
            fileFor(context, ClearVoice.copyNameFor(name)).delete()
            fileFor(context, ClearVoice.partNameFor(name)).delete()
        }
        // A player on this file, or on the copy just deleted, stops.
        AudioDeletions.deleted(name)
    }

    /**
     * The clear-voice copy a meeting with [audioFile] and [clearVoiceFile]
     * may play, when it is on disk; see ClearVoice.copyOf.
     */
    fun clearVoiceCopy(context: Context, audioFile: String?, clearVoiceFile: String?): File? {
        val name = ClearVoice.copyOf(audioFile, clearVoiceFile) ?: return null
        return fileFor(context, name).takeIf { it.length() > 0 }
    }

    /**
     * What [delete] frees for the recording [audioFile]: the recording and
     * the clear-voice copy made from it, referenced or not.
     */
    fun bytesWithCopy(context: Context, audioFile: String?): Long {
        if (audioFile.isNullOrBlank()) return 0L
        return fileFor(context, audioFile).length() +
            fileFor(context, ClearVoice.copyNameFor(audioFile)).length()
    }
}
