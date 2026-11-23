package com.meetily.mobile.whisper

/**
 * What to keep of an imported file so that only a video's sound is stored:
 * its audio track remuxed (copied as it is, not re-encoded) into a small
 * audio-only file, or the file as it is when it is not a video or its sound
 * cannot be remuxed. Pure: decided from the MIME types of the file's tracks,
 * so the rules are unit-tested. VideoAudioExtractor does the remux.
 */
object VideoAudio {

    enum class Keep(
        /** Extension of the remuxed file; null when nothing is remuxed. */
        val extension: String?
    ) {
        /** Not a video, or one whose sound cannot be remuxed here: copied whole, as before. */
        WHOLE_FILE(null),

        /** A file with no audio track at all: nothing to transcribe. */
        NO_SOUND(null),

        /** AAC (Zoom, Teams, phone and screen recordings) into MPEG-4 audio, .m4a. */
        M4A("m4a"),

        /** AMR, as older phones record it in .3gp video, into 3GPP. */
        THREE_GPP("3gp"),

        /** Vorbis or Opus, as WebM video carries it, into audio-only WebM. */
        WEBM("webm");

        val remuxes: Boolean get() = extension != null
    }

    /** Android's WebM muxer takes Opus from Android 10 (it took Vorbis long before). */
    const val WEBM_OPUS_MIN_SDK = 29

    /**
     * [trackMimes]: the MIME type of each track, as MediaExtractor reports
     * them; null when the file could not be opened that way, which leaves
     * it to be copied and decoded as before. The first audio track is the
     * one kept, as AudioFileDecoder reads the first. MPEG-4 holds only AAC
     * and AMR audio; MP3, FLAC, AC-3 or PCM in a video keep the whole file.
     */
    fun plan(trackMimes: List<String>?, sdkInt: Int): Keep {
        val tracks = trackMimes?.map { it.lowercase() } ?: return Keep.WHOLE_FILE
        val audio = tracks.firstOrNull { it.startsWith("audio/") } ?: return Keep.NO_SOUND
        if (tracks.none { it.startsWith("video/") }) return Keep.WHOLE_FILE
        return when (audio) {
            "audio/mp4a-latm" -> Keep.M4A
            "audio/3gpp", "audio/amr-wb" -> Keep.THREE_GPP
            "audio/vorbis" -> Keep.WEBM
            "audio/opus" -> if (sdkInt >= WEBM_OPUS_MIN_SDK) Keep.WEBM else Keep.WHOLE_FILE
            else -> Keep.WHOLE_FILE
        }
    }

    /**
     * The name the kept file is stored under: [sourceName] with the remuxed
     * format's extension, so players and the share sheet see what it is.
     * Unchanged when nothing is remuxed.
     */
    fun storedName(sourceName: String, keep: Keep): String {
        val ext = keep.extension ?: return sourceName
        val dot = sourceName.lastIndexOf('.')
        val stem = if (dot > 0) sourceName.substring(0, dot) else sourceName
        return "$stem.$ext"
    }

    /**
     * Remuxed files waiting to be adopted by an import are named with this
     * prefix and the token of the process that made them, so one left by a
     * process that died can be told from one being written now (see
     * [isStale]).
     */
    const val EXTRACT_PREFIX = "extract-"

    /** "extract-<token>-<id>": made by the process whose token is [token]. */
    fun extractName(token: String, id: String): String = "$EXTRACT_PREFIX$token-$id"

    /**
     * Whether [fileName] is a remuxed file a previous process left behind:
     * one with the prefix and any token but [currentToken]. Nothing else
     * in the folder ever matches.
     */
    fun isStale(fileName: String, currentToken: String): Boolean =
        fileName.startsWith(EXTRACT_PREFIX) &&
            !fileName.startsWith(EXTRACT_PREFIX + currentToken + "-")
}
