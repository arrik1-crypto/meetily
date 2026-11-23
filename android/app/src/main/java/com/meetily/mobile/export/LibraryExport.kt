package com.meetily.mobile.export

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Export library as Markdown": one readable .md file per meeting, for a
 * notes app or a plain folder.
 *
 * Each file is YAML front matter (title, date, duration, attendees, tags,
 * language, id) followed by exactly what the meeting's own Markdown export
 * writes (MeetingExporter.markdown): summary with its fixed notices, action
 * items, notes and transcript. Every scalar in the front matter is a
 * double-quoted YAML string with everything that could end or bend it
 * escaped, so no title can turn into extra keys.
 *
 * File names are "2026-11-22 Title.md": the meeting's local date, then its
 * title with what file systems and notes apps reject removed, unique within
 * the folder whatever the case (Android's shared storage, FAT and exFAT all
 * ignore case). Pure Kotlin, unit-tested; LibraryExportRunner does the I/O.
 */
object LibraryExport {

    /** Longest title part of a file name, in characters, before the date and ".md". */
    const val MAX_TITLE_CHARS = 80

    /**
     * The whole Markdown file for [meeting]: front matter, then the meeting's
     * Markdown, with [text]'s headings. The front matter's keys are
     * machine-read and stay as they are in every language.
     */
    fun document(
        meeting: Meeting,
        notices: List<String>,
        zone: ZoneId,
        text: ExportText = ExportText()
    ): String = frontMatter(meeting, zone) + "\n" + MeetingExporter.markdown(meeting, notices, text)

    /** "---", the meeting's fields one per line, "---". */
    fun frontMatter(meeting: Meeting, zone: ZoneId): String = buildString {
        append("---\n")
        append("title: ").append(yamlString(meeting.title)).append('\n')
        append("date: ").append(yamlString(ExportFacts.isoDateTime(meeting.createdAtMs, zone))).append('\n')
        val duration = ExportFacts.durationMs(meeting)
        append("duration: ")
            .append(if (duration == null) "null" else yamlString(ExportFacts.durationLabel(duration)))
            .append('\n')
        yamlList("attendees", meeting.attendees)
        yamlList("tags", meeting.tags)
        val language = meeting.transcriptLanguage()
        append("language: ").append(if (language == null) "null" else yamlString(language)).append('\n')
        // Translated transcripts are English; say what was spoken.
        if (meeting.translated) {
            MeetingLanguage.normalize(meeting.language)?.let {
                append("translated_from: ").append(yamlString(it)).append('\n')
            }
        }
        append("id: ").append(yamlString(meeting.id)).append('\n')
        append("---\n")
    }

    private fun StringBuilder.yamlList(key: String, values: List<String>) {
        val items = values.filter { it.isNotBlank() }
        append(key).append(':')
        if (items.isEmpty()) {
            append(" []\n")
            return
        }
        append('\n')
        for (item in items) append("  - ").append(yamlString(item)).append('\n')
    }

    /**
     * [value] as a YAML double-quoted scalar. Backslash and quote are
     * escaped; line breaks (including YAML's own U+0085, U+2028, U+2029),
     * tabs, other control characters, the byte order mark, U+FFFE and
     * U+FFFF become escape sequences; an unpaired surrogate, which no
     * encoding can carry, is dropped. Everything else is written as is.
     */
    fun yamlString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (Character.isHighSurrogate(ch) && i + 1 < value.length &&
                Character.isLowSurrogate(value[i + 1])
            ) {
                out.append(ch).append(value[i + 1])
                i += 2
                continue
            }
            when {
                Character.isSurrogate(ch) -> Unit
                ch == '\\' -> out.append("\\\\")
                ch == '"' -> out.append("\\\"")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch.code < 0x20 || ch.code in 0x7F..0x9F ||
                    ch == ' ' || ch == ' ' ||
                    ch == '﻿' || ch == '￾' || ch == '￿' ->
                    out.append(String.format(Locale.US, "\\u%04X", ch.code))
                else -> out.append(ch)
            }
            i++
        }
        out.append('"')
        return out.toString()
    }

    /**
     * "2026-11-22 Title.md" for [meeting], unique among [taken] (lowercase
     * names already used in the folder), which it is added to.
     */
    fun fileName(meeting: Meeting, zone: ZoneId, taken: MutableSet<String>): String {
        val day = DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(meeting.createdAtMs).atZone(zone)
        )
        val title = sanitize(meeting.title)
        val name = uniqueName(if (title.isEmpty()) day else "$day $title", ".md", taken)
        taken.add(name.lowercase(Locale.ROOT))
        return name
    }

    /**
     * [title] as part of a file name. Gone: characters Windows, FAT and
     * exFAT refuse (\ / : * ? " < > |), control characters, and the ones
     * notes apps read as link syntax (# ^ [ ]); whitespace runs collapse to
     * one space; leading and trailing dots and spaces go (Windows drops
     * them, and a leading dot hides the file); at most [MAX_TITLE_CHARS]
     * characters, never cutting a character in two. Empty when nothing
     * usable is left.
     */
    fun sanitize(title: String): String {
        val out = StringBuilder(title.length)
        var i = 0
        while (i < title.length) {
            val ch = title[i]
            if (Character.isHighSurrogate(ch) && i + 1 < title.length &&
                Character.isLowSurrogate(title[i + 1])
            ) {
                out.append(ch).append(title[i + 1])
                i += 2
                continue
            }
            when {
                Character.isSurrogate(ch) -> Unit
                ch.isWhitespace() || ch.code < 0x20 || ch.code in 0x7F..0x9F -> out.append(' ')
                ch in FORBIDDEN -> out.append(' ')
                else -> out.append(ch)
            }
            i++
        }
        val name = out.toString().replace(SPACES, " ").trim(' ', '.')
        // Most file systems allow 255 bytes of UTF-8 per name; a title in a
        // script that takes three or four bytes a character reaches that
        // well before MAX_TITLE_CHARS.
        var cut = 0
        var bytes = 0
        while (cut < name.length && cut < MAX_TITLE_CHARS) {
            val point = name.codePointAt(cut)
            val size = Character.charCount(point)
            val utf8 = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            if (bytes + utf8 > MAX_TITLE_BYTES || cut + size > MAX_TITLE_CHARS) break
            bytes += utf8
            cut += size
        }
        return if (cut == name.length) name else name.substring(0, cut).trim(' ', '.')
    }

    /** The title part's limit in UTF-8 bytes, leaving room for the date, a counter and ".md". */
    private const val MAX_TITLE_BYTES = 200

    /**
     * [stem] + [extension], or "[stem] (2)" + [extension] and so on: the
     * first not in [taken], compared without case.
     */
    fun uniqueName(stem: String, extension: String, taken: Set<String>): String {
        val first = stem + extension
        if (first.lowercase(Locale.ROOT) !in taken) return first
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$extension"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
            n++
        }
    }

    private const val FORBIDDEN = "\\/:*?\"<>|#^[]"
    private val SPACES = Regex(" {2,}")
}
