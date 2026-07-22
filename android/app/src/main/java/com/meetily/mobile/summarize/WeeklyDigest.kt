package com.meetily.mobile.summarize

import com.meetily.mobile.data.Meeting

/**
 * "Your week in meetings": selects the last 7 days, compiles a fully local
 * digest (meetings, open action items by owner, starred highlights), and
 * packs per-meeting context blocks for the optional LLM version. Pure
 * Kotlin — unit-tested.
 */
object WeeklyDigest {

    const val WINDOW_DAYS = 7

    fun weekMeetings(
        all: List<Meeting>,
        nowMs: Long,
        days: Int = WINDOW_DAYS
    ): List<Meeting> {
        val from = nowMs - days * 86_400_000L
        return all.filter { it.createdAtMs in from..nowMs }
            .sortedBy { it.createdAtMs }
    }

    /** (owner or null for unassigned) -> open tasks with their meeting title. */
    fun openActionItems(meetings: List<Meeting>): Map<String?, List<Pair<String, String>>> =
        meetings.flatMap { meeting ->
            meeting.actionItems
                .filter { !it.done && it.task.isNotBlank() }
                .map { item -> item.owner?.trim()?.ifBlank { null } to (item.task to meeting.title) }
        }.groupBy({ it.first }, { it.second })

    /** Offline digest text; [formatDate] renders a createdAtMs timestamp. */
    fun buildLocal(
        meetings: List<Meeting>,
        unassignedLabel: String,
        formatDate: (Long) -> String
    ): String = buildString {
        val words = meetings.sumOf { m ->
            m.transcriptText().split(Regex("\\s+")).count { it.isNotBlank() }
        }
        append("MEETINGS (").append(meetings.size).append(", ~")
            .append(words).append(" words)\n")
        for (m in meetings) {
            append("• ").append(m.title)
            append(" — ").append(formatDate(m.createdAtMs)).append('\n')
            val line = m.summary.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: m.segments.firstOrNull()?.text
            if (!line.isNullOrBlank()) {
                append("   ").append(line.take(160)).append('\n')
            }
        }

        val open = openActionItems(meetings)
        if (open.isNotEmpty()) {
            append("\nOPEN ACTION ITEMS\n")
            for ((owner, items) in open) {
                append(owner ?: unassignedLabel).append(":\n")
                for ((task, meetingTitle) in items) {
                    append("  – ").append(task.take(160))
                    append(" (").append(meetingTitle).append(")\n")
                }
            }
        }

        val highlights = meetings.flatMap { m ->
            m.highlightedTexts().map { it to m.title }
        }
        if (highlights.isNotEmpty()) {
            append("\nHIGHLIGHTS\n")
            for ((text, meetingTitle) in highlights.take(12)) {
                append("★ ").append(text.take(200))
                append(" (").append(meetingTitle).append(")\n")
            }
        }
    }

    /** Per-meeting (label, content) blocks for the LLM digest. */
    fun contextBlocks(
        meetings: List<Meeting>,
        formatDate: (Long) -> String
    ): List<Pair<String, String>> = meetings.map { m ->
        val label = "${m.title} — ${formatDate(m.createdAtMs)}"
        val content = buildString {
            if (m.attendees.isNotEmpty()) {
                append("Attendees: ").append(m.attendeesText()).append('\n')
            }
            if (m.tags.isNotEmpty()) {
                append("Tags: ").append(m.tags.joinToString(", ")).append('\n')
            }
            if (m.summary.isNotBlank()) {
                append("Summary:\n").append(m.summary.take(4_000)).append('\n')
            } else {
                append("Transcript excerpt:\n")
                append(m.transcriptTextWithSpeakers().take(2_000)).append('\n')
            }
            val open = m.actionItems.filter { !it.done && it.task.isNotBlank() }
            if (open.isNotEmpty()) {
                append("Open action items:\n")
                for (item in open) {
                    append("- ").append(item.task)
                    item.owner?.let { append(" (").append(it).append(')') }
                    append('\n')
                }
            }
            val stars = m.highlightedTexts()
            if (stars.isNotEmpty()) {
                append("Highlighted moments:\n")
                for (star in stars.take(6)) append("- ").append(star.take(200)).append('\n')
            }
        }
        label to content
    }
}
