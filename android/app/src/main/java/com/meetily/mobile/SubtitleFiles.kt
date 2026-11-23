package com.meetily.mobile

import android.content.Context
import android.net.Uri
import com.meetily.mobile.data.ImportBatch
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.SubtitleImport
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Turns a WebVTT or SRT caption file (a Zoom or Teams transcript) into a
 * meeting of its own: the captions are its transcript, and it has no audio,
 * so there is nothing to play, transcribe again or check. SubtitleImport
 * does the reading; this reads the file and saves the meeting.
 */
object SubtitleFiles {

    sealed class Outcome {
        /** [skipped]: caption blocks whose timings could not be read. */
        class Imported(val meetingId: String, val title: String, val skipped: Int) : Outcome()

        /** [reason] says why, for the user. Nothing was saved. */
        class Failed(val reason: String) : Outcome()
    }

    /** Reads [uri] (named [fileName]) and saves it as a new meeting. Blocking. */
    fun import(context: Context, uri: Uri, fileName: String): Outcome {
        val app = context.applicationContext
        val parsed = try {
            when (val bytes = read(app, uri)) {
                null -> return Outcome.Failed(app.getString(R.string.import_sub_unreadable))
                TOO_LARGE -> SubtitleImport.Result.Failed(SubtitleImport.Problem.TOO_LARGE)
                else -> SubtitleImport.parse(bytes)
            }
        } catch (_: Exception) {
            return Outcome.Failed(app.getString(R.string.import_sub_unreadable))
        }
        return when (parsed) {
            is SubtitleImport.Result.Failed -> Outcome.Failed(reasonFor(app, parsed.problem))
            is SubtitleImport.Result.Ok -> {
                val meeting = SubtitleImport.toMeeting(
                    UUID.randomUUID().toString(),
                    ImportBatch.titleFor(fileName),
                    parsed.cues,
                    System.currentTimeMillis()
                )
                if (MeetingStore(app).save(meeting)) {
                    Outcome.Imported(meeting.id, meeting.title, parsed.skipped)
                } else {
                    Outcome.Failed(app.getString(R.string.import_sub_save_failed))
                }
            }
        }
    }

    fun reasonFor(context: Context, problem: SubtitleImport.Problem): String =
        context.getString(
            when (problem) {
                SubtitleImport.Problem.EMPTY -> R.string.import_sub_empty
                SubtitleImport.Problem.NOT_CAPTIONS -> R.string.import_sub_not_captions
                SubtitleImport.Problem.NO_CUES -> R.string.import_sub_no_cues
                SubtitleImport.Problem.TOO_LARGE -> R.string.import_sub_too_large
            }
        )

    /** Stands for a file over SubtitleImport.MAX_BYTES; never parsed. */
    private val TOO_LARGE = ByteArray(0)

    /** The file's bytes, [TOO_LARGE] past the limit, null when it cannot be opened. */
    private fun read(context: Context, uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                total += n
                if (total > SubtitleImport.MAX_BYTES) return TOO_LARGE
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
