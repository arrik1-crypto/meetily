package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.data.SavedSummary
import com.meetily.mobile.data.SummaryAlternatives
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several summaries per meeting, one per template: the top-level fields stay
 * the one shown, the others wait in "summaries", and every writer (a run,
 * a switch, a screen's merge) keeps one entry per template without losing a
 * summary another writer made meanwhile.
 */
class SummaryAlternativesTest {

    private fun meeting() = Meeting(id = "m1", title = "Weekly", createdAtMs = 1L)

    private fun run(template: String?, text: String, at: Long, language: String? = null) =
        SavedSummary(template, "local:qwen", language, text, at)

    /** A meeting showing [active] with [kept] beside it, as a run would leave it. */
    private fun withSummaries(active: SavedSummary, vararg kept: SavedSummary): Meeting {
        val m = meeting()
        for (saved in kept) SummaryAlternatives.adoptRun(m, saved)
        SummaryAlternatives.adoptRun(m, active)
        return m
    }

    // --- JSON ------------------------------------------------------------------

    @Test
    fun json_aMeetingFromBeforeHasOneSummaryAndNothingKept() {
        val legacy = JSONObject()
            .put("id", "old")
            .put("title", "Older")
            .put("createdAtMs", 1L)
            .put("summary", "Old summary")
            .put("summaryTemplate", "standup")
        val restored = Meeting.fromJson(legacy)
        assertEquals("Old summary", restored.summary)
        assertEquals(0L, restored.summaryAtMs)
        assertNull(restored.summaryLanguage)
        assertTrue(restored.summaries.isEmpty())
    }

    @Test
    fun json_writesNothingNewForAMeetingWithOneSummary() {
        val json = meeting().apply { summary = "Only one" }.toJson()
        assertFalse(json.has("summaries"))
        assertFalse(json.has("summaryAtMs"))
        assertFalse(json.has("summaryLanguage"))
    }

    @Test
    fun json_roundTripsTheActiveSummaryAndTheOnesKept() {
        val m = withSummaries(
            run("sales", "Sales text", 300L, language = "de"),
            run("standup", "Standup text", 100L),
            run(null, "Untemplated text", 50L)
        )
        SummaryAlternatives.markStale(m)
        val restored = Meeting.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(m, restored)
        assertEquals("Sales text", restored.summary)
        assertEquals("sales", restored.summaryTemplate)
        assertEquals(300L, restored.summaryAtMs)
        assertEquals("de", restored.summaryLanguage)
        assertEquals(listOf("standup", null), restored.summaries.map { it.template })
        assertTrue(restored.summaries.all { it.stale })
    }

    @Test
    fun json_anOlderReaderStillFindsTheSummaryShown() {
        // What an older app reads: the top-level summary is the active one.
        val m = withSummaries(run("sales", "Sales text", 300L), run("standup", "Standup", 100L))
        val json = m.toJson()
        assertEquals("Sales text", json.getString("summary"))
        assertEquals("sales", json.getString("summaryTemplate"))
        assertEquals(1, json.getJSONArray("summaries").length())
    }

    @Test
    fun json_aBackupCarriesTheKeptSummariesAndRestores() {
        // A backup holds each meeting's file as written, so what restores is
        // exactly what toJson wrote; it must still pass the restore checks.
        val m = withSummaries(run("sales", "Sales", 300L), run("standup", "Standup", 100L))
        val restored = Meeting.fromJson(JSONObject(m.toJson().toString()))
        assertTrue(com.meetily.mobile.data.BackupManager.isRestorable(restored, "m1.json"))
        assertEquals(m.summaries, restored.summaries)
    }

    @Test
    fun json_tidiesAHandEditedListInsteadOfFailing() {
        val arr = JSONArray()
            .put("not an object")
            .put(JSONObject().put("template", "standup").put("text", "older").put("createdAtMs", 1L))
            .put(JSONObject().put("template", "standup").put("text", "newer").put("createdAtMs", 2L))
            .put(JSONObject().put("template", "sales").put("text", "duplicate of the active"))
            .put(JSONObject().put("template", "retro").put("text", "  "))
            .put(JSONObject().put("template", "email").put("text", "kept").put("language", "xx-not"))
        val obj = JSONObject()
            .put("id", "m9")
            .put("title", "T")
            .put("createdAtMs", 1L)
            .put("summary", "Active")
            .put("summaryTemplate", "sales")
            .put("summaries", arr)
        val restored = Meeting.fromJson(obj)
        assertEquals(listOf("standup", "email"), restored.summaries.map { it.template })
        assertEquals("newer", restored.summaries[0].text)
    }

    @Test
    fun json_theHeaderIsStillReadFromTheStartWithSummariesKept() {
        val m = withSummaries(run("sales", "S", 300L), run("standup", "U", 100L))
        val text = m.toJson().toString()
        val header = com.meetily.mobile.data.LightJson.parseLeading(
            text, com.meetily.mobile.data.MeetingStore.HEADER_FIELDS
        )
        assertEquals(1L, header.getLong("createdAtMs"))
        assertEquals("Weekly", header.getString("title"))
    }

    // --- Runs, switching, deleting ---------------------------------------------

    @Test
    fun aRunWithAnotherTemplateKeepsTheShownSummaryUnderItsTemplate() {
        val m = meeting()
        SummaryAlternatives.adoptRun(m, run("standup", "Standup text", 100L))
        SummaryAlternatives.adoptRun(m, run("sales", "Sales text", 200L))
        assertEquals("Sales text", m.summary)
        assertEquals("sales", m.summaryTemplate)
        assertEquals(200L, m.summaryAtMs)
        assertEquals(listOf("Standup text"), m.summaries.map { it.text })
    }

    @Test
    fun regeneratingTheShownTemplateReplacesItAndKeepsTheOthers() {
        val m = withSummaries(run("sales", "Sales v1", 200L), run("standup", "Standup", 100L))
        SummaryAlternatives.adoptRun(m, run("sales", "Sales v2", 300L))
        assertEquals("Sales v2", m.summary)
        assertEquals(listOf("standup"), m.summaries.map { it.template })
    }

    @Test
    fun regeneratingAKeptTemplateReplacesThatEntryAndKeepsTheShownOne() {
        val m = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup v1", 100L))
        SummaryAlternatives.adoptRun(m, run("standup", "Standup v2", 300L))
        assertEquals("Standup v2", m.summary)
        assertEquals(listOf("Sales"), m.summaries.map { it.text })
        assertEquals(1, m.summaries.count { it.template == "sales" })
    }

    @Test
    fun aSummaryFromBeforeTemplatesWereRecordedIsKeptToo() {
        val m = meeting().apply { summary = "Legacy" }
        SummaryAlternatives.adoptRun(m, run("standup", "New", 100L))
        assertEquals(listOf(null), m.summaries.map { it.template })
        assertTrue(SummaryAlternatives.switchTo(m, null))
        assertEquals("Legacy", m.summary)
        assertNull(m.summaryTemplate)
    }

    @Test
    fun switchingSwapsInstantlyAndLeavesTheActionItemsAlone() {
        val m = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        m.actionItems.add(ActionItem("Send deck", done = true, remindAtMs = 5L))
        val items = m.actionItems.toList()
        assertTrue(SummaryAlternatives.switchTo(m, "standup"))
        assertEquals("Standup", m.summary)
        assertEquals("standup", m.summaryTemplate)
        assertEquals(100L, m.summaryAtMs)
        assertEquals(listOf("sales"), m.summaries.map { it.template })
        assertEquals(items, m.actionItems)
    }

    @Test
    fun anEditToTheShownSummaryStaysWithItsTemplateWhenSwitching() {
        val m = withSummaries(run("sales", "Jon called", 200L), run("standup", "Standup", 100L))
        m.summary = "John called"
        SummaryAlternatives.switchTo(m, "standup")
        assertEquals("John called", SummaryAlternatives.find(m, "sales")?.text)
        SummaryAlternatives.switchTo(m, "sales")
        assertEquals("John called", m.summary)
    }

    @Test
    fun switchingToATemplateWithNoSummaryChangesNothing() {
        val m = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val before = MeetingMerge.snapshot(m)
        assertFalse(SummaryAlternatives.switchTo(m, "retro"))
        assertFalse(SummaryAlternatives.switchTo(m, "sales"))
        assertEquals(before, m)
    }

    @Test
    fun deletingRemovesOnlyThatKeptSummaryAndNeverTheShownOne() {
        val m = withSummaries(
            run("sales", "Sales", 300L), run("standup", "Standup", 100L), run("retro", "Retro", 200L)
        )
        assertTrue(SummaryAlternatives.delete(m, "standup"))
        assertEquals(listOf("retro"), m.summaries.map { it.template })
        assertFalse(SummaryAlternatives.delete(m, "sales"))
        assertEquals("Sales", m.summary)
    }

    @Test
    fun aTranscriptChangeMarksEverySummaryOutOfDate() {
        val m = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        assertTrue(SummaryAlternatives.markStale(m))
        assertTrue(m.summaryStale)
        assertTrue(m.summaries.all { it.stale })
        // Already marked: nothing to write.
        assertFalse(SummaryAlternatives.markStale(m))
        // And the mark travels with the summary it belongs to.
        SummaryAlternatives.switchTo(m, "standup")
        assertTrue(m.summaryStale)
    }

    @Test
    fun aMeetingWithNoSummaryIsNotMarked() {
        val m = meeting()
        assertFalse(SummaryAlternatives.markStale(m))
        assertFalse(m.summaryStale)
    }

    @Test
    fun theCardListsTemplatesInTheCatalogueOrderWhicheverIsShown() {
        val m = withSummaries(
            run("sales", "Sales", 300L), run("standup", "Standup", 100L), run("gone", "Old", 200L)
        )
        val catalogue = listOf("general", "standup", "sales")
        val first = SummaryAlternatives.versions(m, catalogue)
        assertEquals(listOf("standup", "sales", "gone"), first.map { it.template })
        assertEquals(listOf(false, true, false), first.map { it.active })
        SummaryAlternatives.switchTo(m, "standup")
        val second = SummaryAlternatives.versions(m, catalogue)
        assertEquals(listOf("standup", "sales", "gone"), second.map { it.template })
        assertEquals(listOf(true, false, false), second.map { it.active })
    }

    @Test
    fun languagesAreNamedOnlyWhenTheSummariesDiffer() {
        val same = withSummaries(run("sales", "S", 2L, "de"), run("standup", "U", 1L, "de"))
        assertFalse(SummaryAlternatives.namesLanguages(SummaryAlternatives.versions(same, emptyList())))
        val mixed = withSummaries(run("sales", "S", 2L, "de"), run("standup", "U", 1L, "en"))
        assertTrue(SummaryAlternatives.namesLanguages(SummaryAlternatives.versions(mixed, emptyList())))
    }

    @Test
    fun aSnapshotHasItsOwnListOfKeptSummaries() {
        val live = withSummaries(run("sales", "S", 2L), run("standup", "U", 1L))
        val copy = MeetingMerge.snapshot(live)
        SummaryAlternatives.delete(live, "standup")
        assertEquals(1, copy.summaries.size)
    }

    // --- Merging a screen's copy with what a run stored meanwhile ---------------

    @Test
    fun aScreenEditToTheShownSummaryAndAQueuedRunBothSurvive() {
        // The screen shows "sales" and fixes a word in it; meanwhile a queued
        // run with "standup" finished and stored the sales summary as kept.
        val base = withSummaries(run("sales", "Jon called", 200L))
        base.actionItems.add(ActionItem("Old task"))
        val ours = MeetingMerge.snapshot(base).apply { summary = "John called" }
        val disk = MeetingMerge.snapshot(base)
        SummaryAlternatives.adoptRun(disk, run("standup", "Standup", 300L))
        disk.actionItems = mutableListOf(ActionItem("New task"))

        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Standup", merged.summary)
        assertEquals("standup", merged.summaryTemplate)
        assertEquals(listOf("John called"), merged.summaries.map { it.text })
        assertEquals(listOf("New task"), merged.actionItems.map { it.task })
    }

    @Test
    fun aSwitchHereLosesToANewRunButNoSummaryIsLost() {
        val base = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val ours = MeetingMerge.snapshot(base).apply { SummaryAlternatives.switchTo(this, "standup") }
        val disk = MeetingMerge.snapshot(base)
        SummaryAlternatives.adoptRun(disk, run("retro", "Retro", 300L))

        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Retro", merged.summary)
        assertEquals(setOf("sales", "standup"), merged.summaries.map { it.template }.toSet())
    }

    @Test
    fun aSwitchHereSticksWhenNothingElseChanged() {
        val base = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val ours = MeetingMerge.snapshot(base).apply { SummaryAlternatives.switchTo(this, "standup") }
        val disk = MeetingMerge.snapshot(base).apply { title = "Renamed elsewhere" }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Standup", merged.summary)
        assertEquals(listOf("sales"), merged.summaries.map { it.template })
        assertEquals("Renamed elsewhere", merged.title)
    }

    @Test
    fun aDeletionHereSticksWhileARunKeepsTheSummaryItReplaced() {
        val base = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val ours = MeetingMerge.snapshot(base).apply { SummaryAlternatives.delete(this, "standup") }
        val disk = MeetingMerge.snapshot(base)
        SummaryAlternatives.adoptRun(disk, run("retro", "Retro", 300L))

        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Retro", merged.summary)
        assertEquals(listOf("sales"), merged.summaries.map { it.template })
    }

    @Test
    fun aRegenerateOfTheShownTemplateWinsOverAnEditMadeHere() {
        val base = withSummaries(run("sales", "Sales v1", 200L))
        val ours = MeetingMerge.snapshot(base).apply { summary = "Sales v1, edited" }
        val disk = MeetingMerge.snapshot(base)
        SummaryAlternatives.adoptRun(disk, run("sales", "Sales v2", 300L))
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("Sales v2", merged.summary)
        assertTrue(merged.summaries.isEmpty())
    }

    @Test
    fun anEditHereAndAStaleMarkStoredMeanwhileBothSurvive() {
        val base = withSummaries(run("sales", "Jon", 200L), run("standup", "Jon too", 100L))
        val ours = MeetingMerge.snapshot(base).apply { summary = "John" }
        val disk = MeetingMerge.snapshot(base).apply { SummaryAlternatives.markStale(this) }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("John", merged.summary)
        assertTrue(merged.summaryStale)
        assertTrue(merged.summaries.all { it.stale })
    }

    @Test
    fun aScreenTakingInItsSaveKeepsASwitchMadeWhileItRan() {
        // The save left a run's new summary stored; the user switched while
        // it was being written. The switch is kept and the new summary too.
        val ours = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val live = MeetingMerge.snapshot(ours).apply { SummaryAlternatives.switchTo(this, "standup") }
        val stored = MeetingMerge.snapshot(ours)
        SummaryAlternatives.adoptRun(stored, run("retro", "Retro", 300L))

        assertTrue(SummaryAlternatives.adopt(live, ours, stored))
        // The run's summary is the one shown; nothing the user had is lost.
        assertEquals("Retro", live.summary)
        assertEquals(setOf("sales", "standup"), live.summaries.map { it.template }.toSet())
    }

    @Test
    fun aScreenTakingInItsSaveKeepsADeletionMadeWhileItRan() {
        val ours = withSummaries(run("sales", "Sales", 200L), run("standup", "Standup", 100L))
        val live = MeetingMerge.snapshot(ours).apply { SummaryAlternatives.delete(this, "standup") }
        val stored = MeetingMerge.snapshot(ours).apply { SummaryAlternatives.markStale(this) }

        assertTrue(SummaryAlternatives.adopt(live, ours, stored))
        assertEquals("Sales", live.summary)
        assertTrue(live.summaryStale)
        assertTrue(live.summaries.isEmpty())
    }

    // --- Home search and the light listing -------------------------------------

    @Test
    fun homeSearchFindsAWordInAKeptSummaryAndQuotesIt() {
        val m = withSummaries(run("standup", "Blocked on review", 200L), run("sales", "Pipeline is strong", 100L))
        val light = Meeting.fromJson(
            com.meetily.mobile.data.LightJson.parse(m.toJson().toString(), null, setOf("words"))
        )
        assertEquals(m.summaries, light.summaries)
        val search = com.meetily.mobile.search.HomeSearch
        val query = search.normalizeQuery("pipeline")
        val indexed = search.index(light)
        assertTrue(search.matches(indexed.blob, query))
        val moment = search.moment(light, indexed, query)
        // Labelled as another summary: the meeting opens on the standup one.
        assertEquals(com.meetily.mobile.search.HomeSearch.Field.OTHER_SUMMARY, moment?.field)
        val shown = search.moment(light, indexed, search.normalizeQuery("review"))
        assertEquals(com.meetily.mobile.search.HomeSearch.Field.SUMMARY, shown?.field)
    }

    // --- Action items and their run's notices ------------------------------------

    private val medication = ActionItem(task = "Start amoxicillin 500 mg three times daily")

    /**
     * General minutes, then a SOAP note whose run wrote the action items, as
     * SummaryService records them.
     */
    private fun generalThenSoap(): Meeting {
        val m = meeting()
        SummaryAlternatives.adoptRun(m, run("general", "Minutes", 100L))
        m.actionItems = mutableListOf(medication)
        m.actionItemsTemplate = "soap"
        m.actionItemsModel = "local:qwen"
        SummaryAlternatives.adoptRun(m, run("soap", "Subjective: cough", 200L))
        return m
    }

    @Test
    fun actionItemsKeepTheirRunsNoticeAfterASwitch() {
        val clinical = com.meetily.mobile.summarize.AiDisclaimers.Category.CLINICAL
        val notices = com.meetily.mobile.summarize.AiDisclaimers
        val m = generalThenSoap()
        assertFalse(SummaryAlternatives.actionItemsFromAnotherRun(m))
        assertEquals(setOf(clinical), notices.categories(m))

        SummaryAlternatives.switchTo(m, "general")
        assertEquals(listOf(medication), m.actionItems)
        assertTrue(SummaryAlternatives.actionItemsFromAnotherRun(m))
        // The minutes need no notice; the SOAP run's items still do, in a
        // share of the items alone, in follow-ups and in whole-meeting outputs.
        assertTrue(notices.summaryCategories(m).isEmpty())
        assertEquals(setOf(clinical), notices.actionItemCategories(m))
        assertEquals(setOf(clinical), notices.categories(m))
        assertEquals(setOf(clinical), notices.unionActionItems(listOf(m)))
        assertEquals(setOf(clinical), notices.union(listOf(m)))
        // Reported under the run that wrote them.
        assertEquals(SummaryAlternatives.Run("soap", "local:qwen"), SummaryAlternatives.actionItemsRun(m))
        assertEquals(
            listOf(SummaryAlternatives.Run("general", "local:qwen"), SummaryAlternatives.Run("soap", "local:qwen")),
            com.meetily.mobile.summarize.QuotedAiText.quotedRuns(listOf(m))
        )

        // Deleting the SOAP note does not take the items' notice with it.
        SummaryAlternatives.delete(m, "soap")
        assertEquals(setOf(clinical), notices.actionItemCategories(m))

        val back = generalThenSoap()
        SummaryAlternatives.switchTo(back, "general")
        SummaryAlternatives.switchTo(back, "soap")
        assertFalse(SummaryAlternatives.actionItemsFromAnotherRun(back))
        assertEquals(
            listOf(SummaryAlternatives.Run("soap", "local:qwen")),
            com.meetily.mobile.summarize.QuotedAiText.quotedRuns(listOf(back))
        )
    }

    @Test
    fun actionItemsFromBeforeTheRunWasRecordedTakeTheSummarysNotice() {
        val legacy = meeting().apply {
            summary = "Plan"
            summaryTemplate = "soap"
            actionItems = mutableListOf(medication)
        }
        assertFalse(SummaryAlternatives.actionItemsFromAnotherRun(legacy))
        assertEquals(
            setOf(com.meetily.mobile.summarize.AiDisclaimers.Category.CLINICAL),
            com.meetily.mobile.summarize.AiDisclaimers.actionItemCategories(legacy)
        )
        // No items, no items' notice.
        val none = generalThenSoap().apply { actionItems = mutableListOf() }
        SummaryAlternatives.switchTo(none, "general")
        assertTrue(com.meetily.mobile.summarize.AiDisclaimers.categories(none).isEmpty())
    }

    @Test
    fun json_theItemsRunRoundTripsAndReachesTheNoticeListing() {
        val m = generalThenSoap()
        SummaryAlternatives.switchTo(m, "general")
        val text = m.toJson().toString()
        val restored = Meeting.fromJson(JSONObject(text))
        assertEquals("soap", restored.actionItemsTemplate)
        assertEquals("local:qwen", restored.actionItemsModel)
        assertEquals(m, restored)
        // What follow-ups reads (MeetingStore.listPartial).
        val keep = com.meetily.mobile.data.MeetingStore.ACTION_FIELDS +
            com.meetily.mobile.data.MeetingStore.NOTICE_FIELDS + "id"
        val partial = Meeting.fromJson(com.meetily.mobile.data.LightJson.parse(text, keep, setOf("words")))
        assertEquals(
            setOf(com.meetily.mobile.summarize.AiDisclaimers.Category.CLINICAL),
            com.meetily.mobile.summarize.AiDisclaimers.unionActionItems(listOf(partial))
        )
        // Nothing new is written for a meeting no run has summarised.
        val plain = meeting().toJson()
        assertFalse(plain.has("actionItemsTemplate"))
        assertFalse(plain.has("actionItemsModel"))
    }

    @Test
    fun mergeCarriesTheItemsRunWithTheItems() {
        // A switch made here while nothing else changed: the items and their
        // run stay as stored.
        val base = generalThenSoap()
        val ours = MeetingMerge.snapshot(base)
        SummaryAlternatives.switchTo(ours, "general")
        val merged = MeetingMerge.mergeScreenEdits(base, ours, MeetingMerge.snapshot(base), Long.MIN_VALUE)
        assertEquals("general", merged.summaryTemplate)
        assertEquals("soap", merged.actionItemsTemplate)
        assertEquals("local:qwen", merged.actionItemsModel)

        // A tick made here while a queued run wrote new items: the run's
        // items and its record win together.
        val before = meeting()
        SummaryAlternatives.adoptRun(before, run("general", "Minutes", 100L))
        before.actionItems = mutableListOf(ActionItem(task = "Send the deck"))
        before.actionItemsTemplate = "general"
        before.actionItemsModel = "local:qwen"
        val screen = MeetingMerge.snapshot(before)
        screen.actionItems[0] = screen.actionItems[0].copy(done = true)
        val disk = MeetingMerge.snapshot(before)
        disk.actionItems = mutableListOf(medication)
        disk.actionItemsTemplate = "soap"
        disk.actionItemsModel = "endpoint:gpt"
        SummaryAlternatives.adoptRun(disk, run("soap", "Subjective: cough", 300L))
        val after = MeetingMerge.mergeScreenEdits(before, screen, disk, Long.MIN_VALUE)
        assertEquals(listOf(medication), after.actionItems)
        assertEquals("soap", after.actionItemsTemplate)
        assertEquals("endpoint:gpt", after.actionItemsModel)
    }

    // --- Find & replace ----------------------------------------------------------

    private fun replaceQuery(find: String, replace: String) =
        com.meetily.mobile.data.FindReplace.Query.of(
            find, replace, com.meetily.mobile.data.FindReplace.Options()
        )

    private fun meetingWithLine(): Meeting =
        withSummaries(run("sales", "Jon called", 200L), run("standup", "Jon is blocked", 100L))
            .apply {
                segments.add(com.meetily.mobile.data.TranscriptSegment(1_000L, "Jon here"))
            }

    @Test
    fun aReplaceInTheSummaryFixesTheKeptOnesTooAndUndoPutsThemBack() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.SUMMARY)
        val q = replaceQuery("Jon", "John")
        val counted = com.meetily.mobile.data.FindReplace.count(
            com.meetily.mobile.data.FindReplace.Source.of(m), q
        )
        assertEquals(2, counted.summary)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, q, area)!!
        assertEquals(2, snapshot.counts.summary)
        assertEquals("John called", m.summary)
        assertEquals("John is blocked", SummaryAlternatives.find(m, "standup")?.text)
        // Only the summaries changed: nothing is out of date.
        assertFalse(m.summaryStale)
        assertFalse(m.summaries.any { it.stale })

        val undone = com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        assertEquals(2, undone.restored)
        assertEquals("Jon called", m.summary)
        assertEquals("Jon is blocked", SummaryAlternatives.find(m, "standup")?.text)
    }

    @Test
    fun aReplaceInTheTranscriptMarksEverySummaryAndUndoUnmarksThem() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.TRANSCRIPT)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        assertTrue(m.summaryStale)
        assertTrue(m.summaries.all { it.stale })
        com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        assertFalse(m.summaryStale)
        assertFalse(m.summaries.any { it.stale })
    }

    @Test
    fun undoFindsEachSummaryAgainAfterASwitch() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.SUMMARY)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        SummaryAlternatives.switchTo(m, "standup")
        val undone = com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        // A switch moves a summary without changing it: each is put back
        // where it now is, the one shown and the one kept.
        assertEquals(0, undone.skipped)
        assertEquals(2, undone.restored)
        assertEquals("standup", m.summaryTemplate)
        assertEquals("Jon is blocked", m.summary)
        assertEquals("Jon called", SummaryAlternatives.find(m, "sales")?.text)
    }

    @Test
    fun undoLeavesASummaryEditedOrWrittenAgainSince() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.SUMMARY)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        // Standup written again (same text as the fix left it), then the
        // sales summary edited after a switch back to it.
        SummaryAlternatives.adoptRun(m, run("standup", "John is blocked", 300L))
        SummaryAlternatives.switchTo(m, "sales")
        m.summary = "John called twice"
        val undone = com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        assertEquals(2, undone.skipped)
        assertEquals("John called twice", m.summary)
        assertEquals("John is blocked", SummaryAlternatives.find(m, "standup")?.text)
    }

    @Test
    fun undoAfterASwitchPutsBackOnlyTheStaleMarksTheReplaceAdded() {
        // Standup kept and already out of date; sales shown and up to date.
        val m = withSummaries(run("standup", "Standup", 100L))
            .apply { segments.add(com.meetily.mobile.data.TranscriptSegment(1_000L, "Jon here")) }
        SummaryAlternatives.markStale(m)
        SummaryAlternatives.adoptRun(m, run("sales", "Sales", 200L))
        assertTrue(SummaryAlternatives.find(m, "standup")!!.stale)
        assertFalse(m.summaryStale)

        val area = setOf(com.meetily.mobile.data.FindReplace.Area.TRANSCRIPT)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        assertTrue(m.summaryStale)
        SummaryAlternatives.switchTo(m, "standup")
        com.meetily.mobile.data.FindReplace.undo(m, snapshot)

        // Standup was out of date before the replace and stays so; sales,
        // which the replace marked, is up to date again although kept now.
        assertEquals("standup", m.summaryTemplate)
        assertTrue(m.summaryStale)
        assertFalse(SummaryAlternatives.find(m, "sales")!!.stale)
    }

    @Test
    fun undoAfterASwitchUnmarksTheKeptSummaryNowShown() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.TRANSCRIPT)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        assertTrue(m.summaryStale)
        assertTrue(m.summaries.all { it.stale })
        SummaryAlternatives.switchTo(m, "standup")
        com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        assertFalse(m.summaryStale)
        assertFalse(m.summaries.any { it.stale })
    }

    @Test
    fun undoKeepsTheStaleMarkOfASummaryWrittenSince() {
        val m = meetingWithLine()
        val area = setOf(com.meetily.mobile.data.FindReplace.Area.TRANSCRIPT)
        val snapshot = com.meetily.mobile.data.FindReplace.apply(m, replaceQuery("Jon", "John"), area)!!
        // A run that started on the old transcript finishes: out of date.
        SummaryAlternatives.adoptRun(m, run("sales", "Sales again", 500L).copy(stale = true))
        com.meetily.mobile.data.FindReplace.undo(m, snapshot)
        assertTrue(m.summaryStale)
        assertFalse(SummaryAlternatives.find(m, "standup")!!.stale)
    }

    @Test
    fun aScreenWithNoChangeTakesWhatIsStored() {
        val ours = withSummaries(run("sales", "Sales", 200L))
        val live = MeetingMerge.snapshot(ours)
        val stored = MeetingMerge.snapshot(ours)
        SummaryAlternatives.adoptRun(stored, run("standup", "Standup", 300L))
        assertTrue(SummaryAlternatives.adopt(live, ours, stored))
        assertEquals(stored, live)
        assertFalse(SummaryAlternatives.adopt(live, MeetingMerge.snapshot(live), stored))
    }
}
