package com.meetily.mobile.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns action items into portable formats: an iCalendar file of VTODOs
 * (importable by task and calendar apps) and a plain-text checklist for the
 * share sheet. No accounts, no OAuth — files and text any app can take.
 * Pure Kotlin — unit-tested.
 */
object TaskExport {

    data class Item(
        val task: String,
        val owner: String?,
        val dueMs: Long?,
        val meetingTitle: String?
    )

    fun text(items: List<Item>): String = buildString {
        for (item in items) {
            append("- [ ] ").append(item.task)
            if (!item.owner.isNullOrBlank()) {
                append(" — ").append(item.owner)
            }
            if (!item.meetingTitle.isNullOrBlank()) {
                append(" (").append(item.meetingTitle).append(')')
            }
            append('\n')
        }
    }.trimEnd('\n')

    /**
     * RFC 5545 VCALENDAR with one VTODO per item. [nowMs] feeds DTSTAMP and
     * the deterministic UIDs, so callers control time (testable).
     */
    fun ics(items: List<Item>, nowMs: Long): String {
        val stamp = utc(nowMs)
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Recap//Action items//EN"
        )
        for ((index, item) in items.withIndex()) {
            val summary = buildString {
                append(item.task)
                if (!item.owner.isNullOrBlank()) {
                    append(" (").append(item.owner).append(')')
                }
            }
            lines.add("BEGIN:VTODO")
            lines.add("UID:recap-$nowMs-$index-${(item.task.hashCode().toLong() and 0xffffffffL)}@meetily.mobile")
            lines.add("DTSTAMP:$stamp")
            lines.add(fold("SUMMARY:" + escape(summary)))
            if (!item.meetingTitle.isNullOrBlank()) {
                lines.add(
                    fold("DESCRIPTION:" + escape("From meeting: " + item.meetingTitle))
                )
            }
            if (item.dueMs != null) {
                lines.add("DUE:" + utc(item.dueMs))
            }
            lines.add("STATUS:NEEDS-ACTION")
            lines.add("END:VTODO")
        }
        lines.add("END:VCALENDAR")
        return lines.joinToString("\r\n") + "\r\n"
    }

    /** TEXT value escaping per RFC 5545: backslash, semicolon, comma, newline. */
    fun escape(value: String): String =
        value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")

    /**
     * Folds long content lines; continuations start with a space. RFC 5545
     * measures the 75-octet limit in UTF-8 OCTETS, so this walks code points
     * (never splitting a surrogate pair) and counts encoded bytes.
     */
    fun fold(line: String): String {
        val sb = StringBuilder()
        var octets = 0
        var first = true
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val cpOctets = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            val limit = if (first) 74 else 73
            if (octets + cpOctets > limit) {
                sb.append("\r\n ")
                first = false
                octets = 0
            }
            sb.appendCodePoint(codePoint)
            octets += cpOctets
            index += charCount
        }
        return sb.toString()
    }

    private fun utc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
