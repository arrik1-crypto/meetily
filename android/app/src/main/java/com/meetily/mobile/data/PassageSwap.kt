package com.meetily.mobile.data

/**
 * How a passage-removal run puts its new audio in place of the recording,
 * and how a run that died part-way is cleaned up. Pure, so the decisions
 * are unit-tested; PassageService does the file work.
 *
 * The new audio is written as a part file in a work folder beside the
 * recordings ([WORK_DIR]: inside the audio folder, so a rename into place
 * never crosses a file system, and a folder of its own, which backups and
 * restores, reading only the audio folder's own files, leave out). Once it
 * reads back as whole ([verify]), a marker naming the recording and the new
 * file is written, the part file is renamed to the new file's name, and the
 * meeting is saved pointing at it with its lines removed. That save is the
 * moment of the swap: before it, the recording is untouched and the new file
 * is thrown away; after it, the recording (with its waveform, its clips and
 * its clear-voice copy, all of which hold the passage) is deleted. The new
 * file always has a name of its own ([newAudioName]), so the recording is
 * never overwritten while anything still points at it.
 */
object PassageSwap {

    /** The work folder, inside the audio folder. */
    const val WORK_DIR = "passage"

    /** Added to a recording's name, with a number, for the file that replaces it. */
    private const val EDIT_TAG = "-edit"

    /** The new audio's extension: AAC-LC in ADTS frames, as live recordings are. */
    const val EXTENSION = "aac"

    /** The part file of the run on [meetingId], in [WORK_DIR]. */
    fun partName(meetingId: String): String = "$meetingId.part"

    /** The probe the codec's delay is measured with (PassageAudio.probe), in [WORK_DIR]. */
    fun probeName(meetingId: String): String = "$meetingId.probe.$EXTENSION"

    /** The marker of a swap under way on [meetingId], in [WORK_DIR]. */
    fun markerName(meetingId: String): String = "$meetingId$MARKER_SUFFIX"

    const val MARKER_SUFFIX = ".swap"

    /** The meeting a [markerName] belongs to, or null for any other name. */
    fun meetingOfMarker(name: String): String? =
        name.takeIf { it.endsWith(MARKER_SUFFIX) && it.length > MARKER_SUFFIX.length }
            ?.removeSuffix(MARKER_SUFFIX)

    /**
     * The name the new audio of a meeting whose recording is [audioName]
     * takes: the recording's name without its extension and without an
     * earlier edit number, then the next edit number, as .aac. Never the
     * recording's own name, so the two never stand for each other.
     * "m1.aac" becomes "m1-edit1.aac", which becomes "m1-edit2.aac";
     * "m1.mp3" becomes "m1-edit1.aac".
     */
    fun newAudioName(audioName: String): String {
        val stem = audioName.substringBeforeLast('.')
        val tagAt = stem.lastIndexOf(EDIT_TAG)
        val number = if (tagAt > 0) stem.substring(tagAt + EDIT_TAG.length).toIntOrNull() else null
        val base = if (number != null && number > 0) stem.substring(0, tagAt) else stem
        val next = (number ?: 0) + 1
        return "$base$EDIT_TAG$next.$EXTENSION"
    }

    /** What reading the new file back found. */
    data class Facts(
        val mime: String?,
        val sampleRate: Int,
        val channels: Int,
        /** The container's length, or -1 when it states none. */
        val durationMs: Long
    )

    enum class Verdict { OK, NOT_AUDIO, WRONG_FORMAT, WRONG_LENGTH }

    /**
     * Whether the new file, as read back ([facts], null when it could not
     * be opened), is fit to replace a recording of [expectedMs] that was
     * encoded at [sampleRate] with [channels]: AAC at that rate and channel
     * count, and as long as the recording, give or take [toleranceMs]
     * (the encoder pads its last frame, and the recording's own length is
     * only known to a frame). A file that states no length passes on its
     * format alone; the decoder then reads it to its end.
     */
    fun verify(facts: Facts?, expectedMs: Long, sampleRate: Int, channels: Int): Verdict {
        if (facts == null || facts.mime != "audio/mp4a-latm") return Verdict.NOT_AUDIO
        if (facts.sampleRate != sampleRate || facts.channels != channels) return Verdict.WRONG_FORMAT
        if (facts.durationMs >= 0L && expectedMs > 0L &&
            kotlin.math.abs(facts.durationMs - expectedMs) > toleranceMs(sampleRate)
        ) {
            return Verdict.WRONG_LENGTH
        }
        return Verdict.OK
    }

    /**
     * How far apart the two lengths may be: three AAC frames (the decoder's
     * priming, the encoder's padding and rounding) plus 50 ms.
     */
    fun toleranceMs(sampleRate: Int): Long = 3L * 1024L * 1000L / sampleRate.coerceAtLeast(1) + 50L

    /**
     * What to do with the files of a swap that a process left unfinished
     * (its marker is still there): the meeting's stored audio now is
     * [storedAudio], or the meeting is gone ([exists] false); the marker
     * names the recording [oldName] and the new file [newName].
     */
    enum class Leftover {
        /** The swap never happened: the recording stays, the new file goes. */
        DELETE_NEW,

        /** The swap happened: the recording (which holds the passage) goes. */
        DELETE_OLD,

        /** The meeting points at neither: both go. */
        DELETE_BOTH,

        /**
         * The meeting is not in the library: it may be in Recently deleted,
         * pointing at either file, so neither is touched here; deleting it
         * for good deletes the one it points at.
         */
        KEEP_BOTH
    }

    fun leftover(exists: Boolean, storedAudio: String?, oldName: String, newName: String): Leftover = when {
        !exists -> Leftover.KEEP_BOTH
        storedAudio == oldName -> Leftover.DELETE_NEW
        storedAudio == newName -> Leftover.DELETE_OLD
        else -> Leftover.DELETE_BOTH
    }

    /**
     * Whether the run may swap: the stored meeting still points at the
     * recording [oldName] the run read. Anything else (the audio removed,
     * replaced or the meeting deleted meanwhile) keeps the stored meeting
     * as it is and throws the new file away.
     */
    fun maySwap(exists: Boolean, storedAudio: String?, oldName: String): Boolean =
        exists && storedAudio == oldName
}
