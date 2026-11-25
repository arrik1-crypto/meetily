package com.meetily.mobile.data

/**
 * The rules for files handed to Recap together, from the share sheet, an
 * "Open with" or the in-app picker: what each file is, how many fit in the
 * import queue, and what each meeting is called. Pure, so it is unit-tested;
 * ImportActivity and ImportQueue act on it.
 */
object ImportBatch {

    enum class Kind {
        /** Audio, or video whose sound is transcribed. */
        MEDIA,

        /** A WebVTT or SRT caption file: read, not transcribed (SubtitleImport). */
        SUBTITLE,

        /** Something else entirely: an image, a document, a plain text note. */
        UNSUPPORTED
    }

    /**
     * Types offered by the in-app picker. Plain text and generic binary are
     * there because many file providers report a .vtt or .srt as one of
     * those; [kindOf] then goes by the file's extension.
     */
    val PICKER_TYPES = arrayOf(
        "audio/*", "video/*", "text/vtt", "application/x-subrip",
        "text/plain", "application/octet-stream"
    )

    private val SUBTITLE_EXTENSIONS = setOf("vtt", "srt")

    private val SUBTITLE_TYPES = setOf(
        "text/vtt", "application/x-subrip", "text/x-subrip", "application/srt",
        "text/srt", "application/x-srt", "text/x-srt"
    )

    /** Types that say nothing about the content. */
    private val GENERIC_TYPES = setOf("application/octet-stream", "application/unknown", "*/*")

    private val MEDIA_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "wav", "wave", "ogg", "oga", "opus", "flac",
        "amr", "awb", "3gp", "3gpp", "3g2", "mp4", "m4v", "mov", "mkv", "mka",
        "webm", "weba", "ts", "mpeg", "mpg", "mpga", "avi"
    )

    /**
     * What a file is, from the type its provider reports ([mime], may be
     * null) and its display [name]. A .vtt or .srt name wins over the type,
     * since providers often call those text/plain or octet-stream. A type
     * that says nothing (none, or octet-stream) with an unknown extension is
     * tried as media, as before: the decoder is the judge of that.
     */
    fun kindOf(mime: String?, name: String): Kind {
        val ext = extensionOf(name)
        if (ext in SUBTITLE_EXTENSIONS) return Kind.SUBTITLE
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            type in SUBTITLE_TYPES -> Kind.SUBTITLE
            type.startsWith("audio/") || type.startsWith("video/") -> Kind.MEDIA
            type == "application/ogg" || type == "application/mp4" -> Kind.MEDIA
            ext in MEDIA_EXTENSIONS -> Kind.MEDIA
            type.isEmpty() || type in GENERIC_TYPES -> Kind.MEDIA
            else -> Kind.UNSUPPORTED
        }
    }

    /** "Team sync.MP4" gives "mp4"; blank when the name has no extension. */
    fun extensionOf(name: String): String {
        val base = baseName(name)
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return ""
        val ext = base.substring(dot + 1)
        return if (ext.length <= 5 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else ""
    }

    /** The last path segment: some providers report a path rather than a name. */
    private fun baseName(name: String): String =
        name.trim().substringAfterLast('/').substringAfterLast('\\')

    /**
     * Suffixes Zoom and similar tools add before a caption file's extension:
     * "GMT20240115-150212_Recording.transcript.vtt" is the transcript of
     * "GMT20240115-150212_Recording".
     */
    private val CAPTION_SUFFIXES = listOf(".transcript", ".captions", ".caption", ".cc", ".subtitles")

    /**
     * A meeting title from a file name: without its extension (and a
     * caption file's ".transcript" or ".cc"), underscores read as spaces,
     * runs of spaces collapsed. Falls back to the name as given when that
     * leaves nothing.
     */
    fun titleFor(fileName: String): String {
        val base = baseName(fileName)
        var stem = base
        val ext = extensionOf(base)
        if (ext.isNotEmpty()) stem = stem.dropLast(ext.length + 1)
        if (ext in SUBTITLE_EXTENSIONS) {
            CAPTION_SUFFIXES.firstOrNull { stem.endsWith(it, ignoreCase = true) && stem.length > it.length }
                ?.let { stem = stem.dropLast(it.length) }
        }
        val title = stem.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        return title.ifEmpty { fileName.trim() }
    }

    /** Which of a batch go to the queue, by position, and which are left out. */
    data class Admission(val admitted: List<Int>, val leftOut: List<Int>)

    /**
     * Fills the queue's room for imports in the order the files came. The
     * queue holds [max] imports at most (JobQueue.MAX_IMPORTS), counting the
     * [queued] already in it; the rest are left out, never trimmed later.
     */
    fun admit(wanted: Int, queued: Int, max: Int = JobQueue.MAX_IMPORTS): Admission {
        val count = wanted.coerceAtLeast(0)
        val room = (max - queued).coerceIn(0, count)
        return Admission((0 until room).toList(), (room until count).toList())
    }

    /**
     * Names for a summary line: the first [shown], and how many more there
     * are beyond them.
     */
    fun nameList(names: List<String>, shown: Int = 4): Pair<String, Int> {
        val first = names.take(shown.coerceAtLeast(1))
        return first.joinToString(", ") to (names.size - first.size)
    }

    /** Whether a run is one of a batch, so its progress says which file it is. */
    fun showsPosition(index: Int, size: Int): Boolean = size > 1 && index in 1..size

    /**
     * Whether file [index] of [size] offers "Cancel all": one of a batch
     * (an id to find the rest by; none on jobs queued before ids) with
     * files after it. The last file's Cancel already stops everything.
     */
    fun offersCancelAll(batchId: String, index: Int, size: Int): Boolean =
        batchId.isNotBlank() && size > 1 && index in 1 until size
}
