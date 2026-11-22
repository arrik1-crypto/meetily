package com.meetily.mobile.data

/**
 * The home list's multi-select: what a selection may have done to it, and
 * the selection bookkeeping itself. Pure, so the rules are unit-tested.
 *
 * Each action is offered for the whole selection and applied to the meetings
 * it is safe for; the confirmation says how many that is and how many are
 * skipped, so nothing is silently left out.
 */
object BulkActions {

    enum class Action { DELETE, REMOVE_AUDIO, ADD_TAG, EXPORT }

    /** What the rules need to know about one selected meeting. */
    data class Item(
        val id: String,
        /** Kept audio is on disk. */
        val hasAudio: Boolean,
        /** It is the meeting being recorded right now. */
        val recording: Boolean,
        /** An import, accuracy check or summary is writing it right now. */
        val busy: Boolean,
        /** A queued or interrupted job, or a staged check, still needs it. */
        val queued: Boolean
    )

    /** The selection split by whether [Action] applies; both keep selection order. */
    data class Split(val eligible: List<String>, val skipped: List<String>)

    /**
     * Whether [action] may be applied to [item].
     *
     * A meeting being written is never deleted or stripped of its audio: the
     * job's next write would put it back, with its files already gone (see
     * MainActivity.isBusy). Removing audio also waits for any job still
     * queued for the meeting, since every such job reads the audio. The
     * recording itself takes no tag and no export: its copy is rewritten
     * every few seconds and is not finished.
     */
    fun eligible(action: Action, item: Item): Boolean = when (action) {
        Action.DELETE -> !item.recording && !item.busy
        Action.REMOVE_AUDIO -> item.hasAudio && !item.recording && !item.busy && !item.queued
        Action.ADD_TAG -> !item.recording
        Action.EXPORT -> !item.recording
    }

    fun split(action: Action, items: List<Item>): Split {
        val (eligible, skipped) = items.partition { eligible(action, it) }
        return Split(eligible.map { it.id }, skipped.map { it.id })
    }

    /**
     * [input] as a tag, or null when there is nothing usable: trimmed, a
     * leading '#' dropped (the list shows tags as #tag), inner whitespace
     * collapsed. Commas and semicolons separate tags wherever tags are typed
     * (Meeting.parseAttendees), so one here would split into two later.
     */
    fun normalizeTag(input: String): String? {
        val tag = input.trim().trimStart('#').trim()
            .replace(Regex("\\s+"), " ")
        if (tag.isEmpty() || tag.any { it == ',' || it == ';' || it == '\n' }) return null
        return tag.take(MAX_TAG_LENGTH)
    }

    /** [tags] with [tag] added, or null when it is already there (case aside). */
    fun withTag(tags: List<String>, tag: String): List<String>? =
        if (tags.any { it.equals(tag, ignoreCase = true) }) null else tags + tag

    private const val MAX_TAG_LENGTH = 40

    /**
     * [fileName] made unique against [taken] for a zip entry: "Standup.md",
     * then "Standup-2.md", "Standup-3.md". Two meetings can share a title
     * and a date, and a zip with duplicate entry names loses one of them on
     * most unzip tools.
     */
    fun uniqueName(fileName: String, taken: Set<String>): String {
        if (fileName !in taken) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 2
        while ("$stem-$n$ext" in taken) n++
        return "$stem-$n$ext"
    }

    // --- Selection bookkeeping --------------------------------------------

    /** [selected] with [id] flipped; insertion order kept. */
    fun toggled(selected: Set<String>, id: String): Set<String> {
        val out = LinkedHashSet(selected)
        if (!out.remove(id)) out.add(id)
        return out
    }

    /**
     * [selected] without meetings the list no longer shows: deleted by the
     * last action or elsewhere, or filtered out by a search or a chip. An
     * action must never reach a meeting the user cannot see is selected, so
     * [shown] is what the list shows now, not the whole library.
     */
    fun pruned(selected: Set<String>, shown: Collection<String>): Set<String> {
        val present = shown.toHashSet()
        return selected.filterTo(LinkedHashSet()) { it in present }
    }

    /** True when every one of [visible] is selected (and there is at least one). */
    fun allSelected(selected: Set<String>, visible: List<String>): Boolean =
        visible.isNotEmpty() && visible.all { it in selected }

    /**
     * "Select all" over [visible]: selects them all, or, when they already
     * are, deselects them. Anything else in [selected] is left as it is.
     */
    fun selectAllToggled(selected: Set<String>, visible: List<String>): Set<String> {
        val out = LinkedHashSet(selected)
        if (allSelected(selected, visible)) out.removeAll(visible.toSet()) else out.addAll(visible)
        return out
    }
}
