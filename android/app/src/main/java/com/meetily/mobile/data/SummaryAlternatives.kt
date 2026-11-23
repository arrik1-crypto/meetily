package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A summary kept beside the active one: the text one template produced, with
 * what wrote it. [template] is the template key, null for a summary saved
 * before the template was recorded; [language] the language the run asked
 * for (OutputLanguage.target), null when it left the choice to the model or
 * nobody knows; [createdAtMs] when it was written, 0 when unknown.
 */
data class SavedSummary(
    val template: String?,
    val model: String?,
    val language: String?,
    val text: String,
    val createdAtMs: Long,
    val stale: Boolean = false
)

/**
 * Several summaries per meeting, one per template. Pure — unit-tested.
 *
 * The meeting's top-level summary fields ([Meeting.summary],
 * [Meeting.summaryTemplate], [Meeting.summaryModel], [Meeting.summaryStale],
 * [Meeting.summaryAtMs], [Meeting.summaryLanguage]) stay the ACTIVE summary,
 * so every reader (exports, share, Ask, Digest, the brief, an older version
 * of the app restoring a backup) keeps reading the one shown. The others
 * wait in [Meeting.summaries], at most one per template and never one for
 * the active summary's template.
 *
 * - A run with another template keeps the active summary as an alternative
 *   under its template; a run with a template that already has one replaces
 *   it ([adoptRun]).
 * - Switching swaps the active summary with an alternative, so the one
 *   being left (with any edit made to it) waits under its own template
 *   ([switchTo]). No model runs.
 * - A transcript change that makes the active summary out of date makes
 *   every alternative out of date too ([markStale]).
 *
 * Action items stay one list on the meeting. They carry the user's ticks
 * and reminders, whose alarms are keyed on the task text, and the
 * follow-ups screen, reminders, task export and search read them from the
 * meeting. Each run refreshes them as it always has (keeping ticks and
 * reminders on the tasks it finds again); switching never touches them, so
 * a switch can neither drop a reminder nor bring back a ticked task. The run
 * that wrote them is recorded with them ([Meeting.actionItemsTemplate],
 * [Meeting.actionItemsModel]; see [actionItemsRun]), because after a switch
 * it is no longer the one that wrote the summary shown.
 */
object SummaryAlternatives {

    /** A template key as stored: trimmed, and null when there is none. */
    fun key(template: String?): String? = template?.trim()?.ifEmpty { null }

    /** The active summary as an entry, or null when the meeting has none. */
    fun active(m: Meeting): SavedSummary? = Fields.of(m).active

    /** True when [m] has any summary: the active one or a kept one. */
    fun hasAny(m: Meeting): Boolean = m.summary.isNotBlank() || m.summaries.isNotEmpty()

    /** The alternative kept for [template], if any. */
    fun find(m: Meeting, template: String?): SavedSummary? {
        val k = key(template)
        return m.summaries.firstOrNull { key(it.template) == k }
    }

    /**
     * Takes a finished run's summary as the active one. The summary it
     * replaces is kept under its template unless it is [fresh]'s template,
     * which is a regenerate: one entry per template, so the older text goes.
     * An alternative with [fresh]'s template goes for the same reason.
     */
    fun adoptRun(m: Meeting, fresh: SavedSummary) {
        val freshKey = key(fresh.template)
        val entry = fresh.copy(template = freshKey)
        val current = active(m)
        val rest = m.summaries.filter { key(it.template) != freshKey }.toMutableList()
        if (current != null && current.template != freshKey) rest.add(current)
        Fields.from(entry, rest).applyTo(m)
    }

    /**
     * Shows the alternative kept for [template]: it becomes the active
     * summary and the active one waits under its own template, as it now
     * reads. Returns false when there is no such alternative.
     */
    fun switchTo(m: Meeting, template: String?): Boolean {
        val k = key(template)
        val target = m.summaries.firstOrNull { key(it.template) == k } ?: return false
        val current = active(m)
        val rest = m.summaries.filter { key(it.template) != k }.toMutableList()
        if (current != null) rest.add(current)
        Fields.from(target.copy(template = k), rest).applyTo(m)
        return true
    }

    /**
     * Deletes the alternative kept for [template]. The active summary is
     * never deleted this way. Returns false when there was none.
     */
    fun delete(m: Meeting, template: String?): Boolean {
        val k = key(template)
        val kept = m.summaries.filter { key(it.template) != k }
        if (kept.size == m.summaries.size) return false
        m.summaries = kept.toMutableList()
        return true
    }

    /**
     * Marks every summary of [m] out of date: the active one when there is
     * one, and each alternative. For a transcript change that would have
     * marked the active summary stale on its own. Returns whether anything
     * changed, so a caller can skip a write.
     */
    fun markStale(m: Meeting): Boolean {
        var changed = false
        if (m.summary.isNotBlank() && !m.summaryStale) {
            m.summaryStale = true
            changed = true
        }
        if (m.summaries.any { !it.stale }) {
            m.summaries = m.summaries.map { it.copy(stale = true) }.toMutableList()
            changed = true
        }
        return changed
    }

    /** One template that has a summary, as the summary card lists it. */
    data class Version(
        val template: String?,
        val active: Boolean,
        val stale: Boolean,
        val language: String?,
        val createdAtMs: Long
    )

    /**
     * Every summary of [m], in a fixed order that does not depend on which
     * one is active: the order of [catalogue] (the template keys as the
     * picker lists them), then templates it does not know (a deleted custom
     * template, a summary with none recorded), newest first. A tap that
     * switches never moves the pills under the user's finger.
     */
    fun versions(m: Meeting, catalogue: List<String>): List<Version> {
        val all = ArrayList<Version>()
        active(m)?.let { all.add(version(it, active = true)) }
        for (saved in m.summaries) all.add(version(saved, active = false))
        val order = HashMap<String, Int>()
        for ((index, k) in catalogue.withIndex()) order.putIfAbsent(k, index)
        return all.sortedWith(
            compareBy<Version> { v -> v.template?.let { order[it] } ?: Int.MAX_VALUE }
                .thenByDescending { it.createdAtMs }
                .thenBy { it.template ?: "" }
        )
    }

    private fun version(saved: SavedSummary, active: Boolean) =
        Version(key(saved.template), active, saved.stale, saved.language, saved.createdAtMs)

    /**
     * Whether a summary's language is worth naming beside its template: only
     * when the meeting's summaries are not all in one known language.
     */
    fun namesLanguages(versions: List<Version>): Boolean =
        versions.mapNotNull { it.language }.distinct().size > 1

    /** A summary run as recorded: its template key and its engine and model (AiProvenance). */
    data class Run(val template: String?, val model: String?)

    /**
     * Which run wrote [m]'s action items: as the run recorded it, or, for
     * items from before that was recorded, the summary shown's template and
     * model, since one run wrote both then.
     */
    fun actionItemsRun(m: Meeting): Run =
        if (m.actionItemsTemplate.isNullOrBlank() && m.actionItemsModel.isNullOrBlank()) {
            Run(key(m.summaryTemplate), m.summaryModel?.trim()?.ifEmpty { null })
        } else {
            Run(key(m.actionItemsTemplate), m.actionItemsModel?.trim()?.ifEmpty { null })
        }

    /**
     * True when [m] has action items that another run wrote than the
     * summary shown: the user switched since. Such items carry their own
     * run's notices and are reported under it. One entry per template, so
     * the run is told apart by its template (and model).
     */
    fun actionItemsFromAnotherRun(m: Meeting): Boolean {
        if (m.actionItems.isEmpty()) return false
        val run = actionItemsRun(m)
        return run.template != key(m.summaryTemplate) ||
            run.model != m.summaryModel?.trim()?.ifEmpty { null }
    }

    // --- Storage -------------------------------------------------------------

    /**
     * Every summary field of a meeting as one value, exactly as stored: the
     * active summary's six top-level fields and the alternatives. What a
     * merge compares and moves as a unit.
     */
    data class Fields(
        val summary: String,
        val template: String?,
        val model: String?,
        val stale: Boolean,
        val atMs: Long,
        val language: String?,
        val alternatives: List<SavedSummary>
    ) {
        /** The active summary as an entry, or null when there is none. */
        val active: SavedSummary?
            get() = if (summary.isBlank()) {
                null
            } else {
                SavedSummary(key(template), model, language, summary, atMs, stale)
            }

        fun applyTo(m: Meeting) {
            m.summary = summary
            m.summaryTemplate = template
            m.summaryModel = model
            m.summaryStale = stale
            m.summaryAtMs = atMs
            m.summaryLanguage = language
            m.summaries = alternatives.toMutableList()
        }

        companion object {
            fun of(m: Meeting) = Fields(
                m.summary, m.summaryTemplate, m.summaryModel, m.summaryStale,
                m.summaryAtMs, m.summaryLanguage, m.summaries.toList()
            )

            /** [active] (none when null) with [alternatives] tidied by [canonical]. */
            fun from(active: SavedSummary?, alternatives: List<SavedSummary>): Fields {
                val rest = canonical(alternatives, active)
                return if (active == null) {
                    Fields("", null, null, false, 0L, null, rest)
                } else {
                    Fields(
                        active.text, key(active.template), active.model, active.stale,
                        active.createdAtMs, active.language, rest
                    )
                }
            }
        }
    }

    /**
     * [list] as stored: no blank texts, no entry for [active]'s template,
     * one entry per template (the newest), newest first. Every writer goes
     * through this, so two copies holding the same summaries are equal.
     */
    fun canonical(list: List<SavedSummary>, active: SavedSummary?): List<SavedSummary> {
        val activeKey = active?.let { key(it.template) }
        val seen = HashSet<String?>()
        return list
            .asSequence()
            .filter { it.text.isNotBlank() }
            .map { it.copy(template = key(it.template)) }
            .filter { active == null || it.template != activeKey }
            .sortedWith(
                compareByDescending<SavedSummary> { it.createdAtMs }.thenBy { it.template ?: "" }
            )
            .filter { seen.add(it.template) }
            .toList()
    }

    fun toJson(list: List<SavedSummary>): JSONArray {
        val arr = JSONArray()
        for (saved in list) {
            val obj = JSONObject()
            if (saved.template != null) obj.put("template", saved.template)
            if (!saved.model.isNullOrBlank()) obj.put("model", saved.model)
            if (!saved.language.isNullOrBlank()) obj.put("language", saved.language)
            obj.put("text", saved.text)
            if (saved.createdAtMs > 0L) obj.put("createdAtMs", saved.createdAtMs)
            if (saved.stale) obj.put("stale", true)
            arr.put(obj)
        }
        return arr
    }

    /**
     * The alternatives in [arr], tidied as [canonical] does against the
     * active summary [active]. opt* only, and an entry that is not an object
     * is skipped: a throw here would lose the whole meeting
     * (Meeting.fromJson failures are swallowed by MeetingStore.list).
     */
    fun fromJson(arr: JSONArray?, active: SavedSummary?): MutableList<SavedSummary> {
        if (arr == null) return mutableListOf()
        val out = ArrayList<SavedSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                SavedSummary(
                    template = key(obj.optString("template", "")),
                    model = obj.optString("model", "").ifBlank { null },
                    language = MeetingLanguage.normalize(obj.optString("language", "")),
                    text = obj.optString("text", ""),
                    createdAtMs = obj.optLong("createdAtMs", 0L).coerceAtLeast(0L),
                    stale = obj.optBoolean("stale", false)
                )
            )
        }
        return canonical(out, active).toMutableList()
    }

    // --- Merging -------------------------------------------------------------

    /** What [merge] decided: the summary fields, and whether a new summary came from [disk]. */
    data class Merged(val fields: Fields, val newSummary: Boolean)

    /**
     * A three-way merge of the summary fields, for MeetingMerge.mergeScreenEdits
     * and for a screen taking in what its save brought back ([adopt]).
     *
     * [base] is what the screen last knew to be stored, [ours] the screen's
     * copy, [disk] what is stored now. Only a summary run writes a new
     * summary, and only a screen switches, deletes or edits one, so:
     * - A new active summary on [disk] (other text, or another run's stamp)
     *   stays active: it is the one the user just asked for. [newSummary]
     *   then tells the caller to take the action items from [disk] too.
     * - Otherwise the active summary is whichever template the side that
     *   changed it chose, so a switch made here sticks.
     * - Each template's entry is merged on its own, wherever it sits (active
     *   or kept): a newer run's entry wins; otherwise a deletion or an edit
     *   made here wins over the stored one, and the text and the out-of-date
     *   mark are merged separately, so an edit made here and a stale mark
     *   made by a transcription meanwhile both survive.
     */
    fun merge(base: Fields, ours: Fields, disk: Fields): Merged {
        val newSummary = disk.summary != base.summary || !sameRun(disk.active, base.active)
        if (ours == base) return Merged(disk, newSummary)
        if (disk == base) return Merged(ours, false)

        val baseEntries = entries(base)
        val oursEntries = entries(ours)
        val diskEntries = entries(disk)
        val keys = LinkedHashSet<String?>()
        keys.addAll(diskEntries.keys)
        keys.addAll(oursEntries.keys)
        keys.addAll(baseEntries.keys)
        val merged = LinkedHashMap<String?, SavedSummary>()
        for (k in keys) {
            pickEntry(baseEntries[k], oursEntries[k], diskEntries[k])?.let { merged[k] = it }
        }

        val diskRef = disk.active?.let { Ref(it.template) }
        val activeRef = if (newSummary) {
            diskRef
        } else {
            pick(base.active?.let { Ref(it.template) }, ours.active?.let { Ref(it.template) }, diskRef)
        }
        // The chosen one was deleted on the other side: the stored one, if
        // it is still there; otherwise none, and every summary stays kept.
        val active = activeRef?.let { merged[it.key] }
            ?: diskRef?.let { merged[it.key] }
        val rest = merged.values.filter { it !== active }
        return Merged(Fields.from(active, rest), newSummary)
    }

    /**
     * Takes [merged] (what a screen's save left stored) into [live], the
     * screen's copy, keeping what the screen changed in the summaries since
     * it took [ours] to save: a switch or a deletion made while the save was
     * running is not undone. Returns true when [live] changed.
     */
    fun adopt(live: Meeting, ours: Meeting, merged: Meeting): Boolean {
        val now = Fields.of(live)
        val result = merge(Fields.of(ours), now, Fields.of(merged)).fields
        if (result == now) return false
        result.applyTo(live)
        return true
    }

    /** An active-summary choice: which template, null meaning none recorded. */
    private data class Ref(val key: String?)

    private fun <T> pick(b: T, o: T, d: T): T = if (o != b) o else d

    /** The same run's summary: same template, model and time written. */
    private fun sameRun(a: SavedSummary?, b: SavedSummary?): Boolean {
        if (a == null || b == null) return a == b
        return key(a.template) == key(b.template) &&
            a.model == b.model &&
            a.createdAtMs == b.createdAtMs
    }

    /** Every summary in [fields], active or kept, by template. */
    private fun entries(fields: Fields): Map<String?, SavedSummary> {
        val out = LinkedHashMap<String?, SavedSummary>()
        fields.active?.let { out[it.template] = it }
        for (saved in fields.alternatives) {
            val k = key(saved.template)
            if (k !in out) out[k] = saved.copy(template = k)
        }
        return out
    }

    private fun pickEntry(b: SavedSummary?, o: SavedSummary?, d: SavedSummary?): SavedSummary? {
        // A run wrote this template again (or for the first time) on disk.
        if (d != null && (b == null || !sameRun(d, b))) return d
        // Only a run makes an entry; kept for completeness, ours if it did.
        if (o != null && (b == null || !sameRun(o, b))) return o
        if (b == null) return null
        // Deleted here: stays deleted, whatever disk did to the same run.
        if (o == null) return null
        // Deleted on disk: gone unless it was edited here.
        if (d == null) return if (o == b) null else o
        return d.copy(
            text = pick(b.text, o.text, d.text),
            stale = pick(b.stale, o.stale, d.stale)
        )
    }
}
