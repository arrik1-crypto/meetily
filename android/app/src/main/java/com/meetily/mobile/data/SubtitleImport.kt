package com.meetily.mobile.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads a WebVTT or SubRip (SRT) caption file into transcript lines for a
 * meeting that has no audio. Zoom saves a cloud recording's transcript as
 * WebVTT with "Name: text" cues, Teams as WebVTT with <v Name> voice tags,
 * and many other tools as SRT.
 *
 * Pure: bytes in, cues and a meeting out, so every edge case is unit-tested.
 * Anything it cannot read comes back as a [Problem] rather than a throw.
 */
object SubtitleImport {

    /**
     * Far more than any meeting's captions (a three-hour Teams transcript is
     * about a megabyte); anything bigger is refused rather than read whole.
     */
    const val MAX_BYTES = 16 * 1024 * 1024

    /** One caption: when it starts and ends (ms), its text, who said it. */
    data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speaker: String? = null
    )

    enum class Problem {
        /** Nothing in the file, or only whitespace. */
        EMPTY,

        /** No caption timings at all: not a WebVTT or SRT file. */
        NOT_CAPTIONS,

        /** Timings were there, but none of them could be read. */
        NO_CUES,

        /** Larger than [MAX_BYTES]. */
        TOO_LARGE
    }

    sealed class Result {
        /** [skipped]: caption blocks whose timings could not be read. */
        data class Ok(val cues: List<Cue>, val skipped: Int) : Result()
        data class Failed(val problem: Problem) : Result()
    }

    // --- Text ----------------------------------------------------------------

    /**
     * The file's text. A byte-order mark says which encoding (and is dropped);
     * otherwise UTF-8, which WebVTT requires, and for an older SRT that is not
     * valid UTF-8, Windows-1252 (Latin-1 where that is not available), which
     * is what such files are nearly always in. UTF-16 without a mark is
     * recognised by its zero bytes.
     */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        val text = when {
            bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && b(0) != 0 && b(1) == 0 -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && b(0) == 0 && b(1) != 0 -> String(bytes, Charsets.UTF_16BE)
            else -> strictUtf8(bytes) ?: String(bytes, legacyCharset())
        }
        return text.removePrefix("\uFEFF")
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun legacyCharset(): Charset = try {
        Charset.forName("windows-1252")
    } catch (_: Exception) {
        Charsets.ISO_8859_1
    }

    // --- Parsing -------------------------------------------------------------

    /** [bytes] as read from the file, at most [MAX_BYTES] + 1 of them. */
    fun parse(bytes: ByteArray): Result {
        if (bytes.size > MAX_BYTES) return Result.Failed(Problem.TOO_LARGE)
        return parse(decode(bytes))
    }

    /**
     * Cues in start order. WebVTT's header, NOTE, STYLE and REGION blocks are
     * passed over; cue identifiers, SRT index lines, cue settings after the
     * end time and SRT position coordinates are ignored; tags are stripped
     * and entities decoded. Lenient where real files are: hours may be left
     * out or have one digit, milliseconds may follow a comma or a full stop
     * and have fewer than three digits, line endings may be CRLF or CR.
     */
    fun parse(text: String): Result {
        val lines = text.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        if (lines.all { it.isBlank() }) return Result.Failed(Problem.EMPTY)
        val vtt = lines.first().trimStart().let {
            it.startsWith("WEBVTT") && (it.length == 6 || it[6] == ' ' || it[6] == '\t')
        }
        val blocks = blocksOf(lines)
        val raw = mutableListOf<Cue>()
        var timed = 0
        var skipped = 0
        for ((n, whole) in blocks.withIndex()) {
            // The header runs to the first blank line and is not a cue,
            // unless a file leaves that line out before its first cue.
            val start = if (vtt && n == 0) {
                val first = whole.indexOfFirst { it.contains("-->") }
                if (first < 0) continue
                whole.drop(first)
            } else {
                whole
            }
            if (vtt && isVttMetadata(start.first())) continue
            // One pass per cue: a block holds several when a file leaves out
            // the blank lines between its cues.
            var rest: List<String>? = start
            while (rest != null) {
                val block: List<String> = rest
                rest = null
                // A cue's timing line comes first, or after an identifier or
                // an SRT index. Arrows further down are text, not a cue; such
                // a block still had a timing, just none that could be read.
                val at = block.take(2).indexOfFirst { it.contains("-->") }
                if (at < 0) {
                    if (block.any { it.contains("-->") }) {
                        timed++
                        skipped++
                    }
                    break
                }
                timed++
                // A later line that reads as a timing starts the next cue
                // (cue text cannot hold "-->"), with the bare number above
                // it as that cue's SRT index.
                val next = (at + 1 until block.size).firstOrNull { timingOf(block[it]) != null }
                val end = when {
                    next == null -> block.size
                    next - 1 > at && isIndexLine(block[next - 1]) -> next - 1
                    else -> next
                }
                if (end < block.size) rest = block.subList(end, block.size)
                val timing = timingOf(block[at])
                if (timing == null) {
                    skipped++
                    continue
                }
                raw.addAll(cuesOf(timing.first, timing.second, block.subList(at + 1, end)))
            }
        }
        // A WebVTT header with nothing under it is an empty caption file.
        if (timed == 0) return Result.Failed(if (vtt) Problem.EMPTY else Problem.NOT_CAPTIONS)
        if (raw.isEmpty() && skipped > 0) return Result.Failed(Problem.NO_CUES)
        if (raw.isEmpty()) return Result.Failed(Problem.EMPTY)
        return Result.Ok(withSpeakerPrefixes(raw).sortedBy { it.startMs }, skipped)
    }

    /** Runs of non-blank lines. */
    private fun blocksOf(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) blocks.add(current)
                current = mutableListOf()
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks
    }

    /** An SRT index: the cue's number, on a line of its own. */
    private fun isIndexLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.length <= 9 && t.all { it in '0'..'9' }
    }

    private fun isVttMetadata(first: String): Boolean {
        val word = first.trim().substringBefore(' ').substringBefore('\t')
        return word == "NOTE" || word == "STYLE" || word == "REGION"
    }

    /** "start --> end [settings]" as (startMs, endMs), or null. */
    internal fun timingOf(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = timestampMs(line.substring(0, arrow).trim()) ?: return null
        val endToken = line.substring(arrow + 3).trim()
            .split(' ', '\t').firstOrNull { it.isNotBlank() } ?: return null
        val end = timestampMs(endToken) ?: return null
        return start to maxOf(end, start)
    }

    private val TIMESTAMP = Regex("""^(?:(\d{1,4}):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$""")

    /**
     * "01:02:03.456", "02:03.456", "1:02:03,456", "00:01:02,5", "00:01:02"
     * as milliseconds; null when it is not a timestamp. Minutes and seconds
     * have to be below 60.
     */
    fun timestampMs(text: String): Long? {
        val m = TIMESTAMP.matchEntire(text.trim()) ?: return null
        val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        if (minutes >= 60 || seconds >= 60) return null
        val fraction = m.groupValues[4]
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    /**
     * The cue's text lines as one cue per voice: WebVTT can name a different
     * speaker on each line (<v Alice>…, then <v Bob>…), and a voice carries
     * on to the following lines until another is named.
     */
    private fun cuesOf(startMs: Long, endMs: Long, payload: List<String>): List<Cue> {
        val out = mutableListOf<Cue>()
        var voice: String? = null
        var text = StringBuilder()
        var textVoice: String? = null
        fun flush() {
            val t = text.toString().trim()
            if (t.isNotEmpty()) out.add(Cue(startMs, endMs, t, textVoice))
            text = StringBuilder()
        }
        for (line in payload) {
            val (named, clean) = cleanLine(line)
            if (named != null && named != voice) {
                flush()
                voice = named
            }
            if (clean.isEmpty()) continue
            if (text.isEmpty()) textVoice = voice
            if (text.isNotEmpty()) text.append(' ')
            text.append(clean)
        }
        flush()
        return out
    }

    private val VOICE = Regex("""<v(?:\.[^\s>]*)?(?:[ \t]+([^>]*))?>""")
    private val TAG = Regex("""</?(?:[A-Za-z]|\d)[^<>]*>""")
    private val ASS_OVERRIDE = Regex("""\{\\[^}]*\}""")
    private val SPACES = Regex("""\s+""")

    /**
     * One text line: the speaker its <v> tag names (null when none), and its
     * text with tags, karaoke timestamps and SRT {\an8}-style overrides
     * removed and entities decoded.
     */
    internal fun cleanLine(line: String): Pair<String?, String> {
        val voice = VOICE.find(line)?.groupValues?.get(1)?.trim()?.let { decodeEntities(it) }
            ?.takeIf { it.isNotBlank() }
        val stripped = ASS_OVERRIDE.replace(TAG.replace(line, ""), "")
        val text = SPACES.replace(decodeEntities(stripped), " ").trim()
        return voice to text
    }

    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);""")

    internal fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") ->
                name.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                "lrm", "rlm" -> ""
                else -> m.value
            }
        }
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    // --- Speakers ------------------------------------------------------------

    /**
     * "Alice Smith: Hello" as ("Alice Smith", "Hello"), the form Zoom's
     * transcripts and many SRT files use; null when the text does not start
     * with something that reads as a name. A name is short (at most 40
     * characters and five words), starts with a letter and has no sentence
     * punctuation in it: a full stop only after a short abbreviation, as in
     * "Dr. Lee".
     */
    fun speakerPrefix(text: String): Pair<String, String>? {
        val colon = text.indexOf(": ")
        if (colon <= 0) return null
        val name = text.substring(0, colon).trim()
        val rest = text.substring(colon + 2).trim()
        if (rest.isEmpty() || name.isEmpty() || name.length > 40) return null
        if (!Character.isLetter(name.codePointAt(0))) return null
        if (name.any { it in "?!;:\"<>{}[]" }) return null
        val words = name.split(' ').filter { it.isNotEmpty() }
        if (words.size > 5) return null
        if (words.dropLast(1).any { it.endsWith('.') && it.length > 4 }) return null
        return name to rest
    }

    /**
     * Reads "Name: text" prefixes as speakers, but only when at least half
     * of the cues without a <v> voice have one. A caption file where one
     * line happens to read "Note: bring the slides" keeps that line whole.
     */
    private fun withSpeakerPrefixes(cues: List<Cue>): List<Cue> {
        val unvoiced = cues.filter { it.speaker == null }
        if (unvoiced.isEmpty()) return cues
        val prefixed = unvoiced.count { speakerPrefix(it.text) != null }
        if (prefixed == 0 || prefixed * 2 < unvoiced.size) return cues
        return cues.map { cue ->
            if (cue.speaker != null) return@map cue
            val (name, rest) = speakerPrefix(cue.text) ?: return@map cue
            cue.copy(text = rest, speaker = name)
        }
    }

    private val GENERIC_SPEAKER = Regex("""^speaker[ _]?(\d{1,3})$""", RegexOption.IGNORE_CASE)

    /**
     * A generic label such as "Speaker 2" as that number: it becomes the
     * app's own "Speaker 2" voice label, which can be renamed for every line
     * at once. Null for a real name.
     */
    fun genericSpeaker(name: String): Int? =
        GENERIC_SPEAKER.matchEntire(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    private val UNKNOWN_SPEAKERS = setOf("unknown", "unknown speaker", "speaker", "unidentified")

    // --- Meeting -------------------------------------------------------------

    /** Where the captions end: the latest cue end, which is the meeting's length. */
    fun durationMs(cues: List<Cue>): Long = cues.maxOfOrNull { maxOf(it.endMs, it.startMs) } ?: 0L

    /**
     * A meeting holding [cues] as its transcript, with no audio. It reads as
     * having ended at [nowMs], as an imported recording does: it starts the
     * captions' length before. Each line's audioMs is its cue start, so line
     * times, subtitle export and chapters follow the captions' own clock.
     * Named speakers become line speakers and attendees; "Speaker 2" becomes
     * voice 2; "Unknown" stays unnamed.
     */
    fun toMeeting(id: String, title: String, cues: List<Cue>, nowMs: Long): Meeting {
        val createdAtMs = nowMs - durationMs(cues)
        val meeting = Meeting(id = id, title = title, createdAtMs = createdAtMs)
        for (cue in cues) {
            val name = cue.speaker?.trim()?.takeIf {
                it.isNotEmpty() && it.lowercase() !in UNKNOWN_SPEAKERS
            }
            val voice = name?.let { genericSpeaker(it) }
            meeting.segments.add(
                TranscriptSegment(
                    timestampMs = createdAtMs + cue.startMs,
                    text = cue.text,
                    speaker = if (voice == null) name else null,
                    clusterId = voice,
                    audioMs = cue.startMs
                )
            )
            if (name != null && voice == null &&
                meeting.attendees.none { it.equals(name, ignoreCase = true) }
            ) {
                meeting.attendees.add(name)
            }
        }
        return meeting
    }
}
