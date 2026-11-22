package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.summarize.ActionItems
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.OutputLanguage
import com.meetily.mobile.summarize.OutputLanguage.Kind
import com.meetily.mobile.summarize.SummaryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputLanguageTest {

    // --- Which language ------------------------------------------------------

    @Test
    fun target_anExplicitSettingWins() {
        assertEquals("fr", OutputLanguage.target("fr", "de", translated = false))
        assertEquals("en", OutputLanguage.target("en", "de", translated = false))
    }

    @Test
    fun target_sameAsMeetingFollowsTheTranscriptText() {
        assertEquals("de", OutputLanguage.target(OutputLanguage.SAME, "de", translated = false))
        // Translate-to-English made English text, and its summary stays English.
        assertEquals("en", OutputLanguage.target(OutputLanguage.SAME, "de", translated = true))
        assertNull(OutputLanguage.target(OutputLanguage.SAME, null, translated = false))
        // A setting that is not a language behaves as "same".
        assertEquals("de", OutputLanguage.target("bogus value", "de", translated = false))
    }

    // --- The clause ----------------------------------------------------------

    @Test
    fun clause_isEmptyWhereNothingNeedsSaying() {
        for (kind in Kind.values()) {
            assertEquals("", OutputLanguage.clause(kind, null, null))
            assertEquals("", OutputLanguage.clause(kind, null, "de"))
            assertEquals("", OutputLanguage.clause(kind, "en", null))
            assertEquals("", OutputLanguage.clause(kind, "en", "en"))
        }
        // Old meetings and English ones: every prompt exactly as before.
        assertEquals("", OutputLanguage.clauseFor(Kind.SUMMARY, OutputLanguage.SAME, null, false))
        assertEquals("", OutputLanguage.clauseFor(Kind.ANSWER, OutputLanguage.SAME, "en", false))
        assertEquals("", OutputLanguage.clauseFor(Kind.TITLE, OutputLanguage.SAME, "de", true))
    }

    @Test
    fun clause_namesTheLanguageInEnglish() {
        val summary = OutputLanguage.clause(Kind.SUMMARY, "de", "de")
        assertTrue(summary.startsWith(" Write the summary in German"))
        // The JSON tail must survive "write everything in German".
        assertTrue(summary.contains("JSON keys exactly as specified"))
        assertEquals(" Answer in German.", OutputLanguage.clause(Kind.ANSWER, "de", "de"))
        assertEquals(" Write the notes in German.", OutputLanguage.clause(Kind.NOTES, "de", null))
        assertEquals(" Write the title in Japanese.", OutputLanguage.clause(Kind.TITLE, "ja", "ja"))
    }

    @Test
    fun clause_asksForEnglishOnlyWhenTheTranscriptIsNotEnglish() {
        assertEquals(" Answer in English.", OutputLanguage.clause(Kind.ANSWER, "en", "fr"))
        assertEquals(
            " Answer in English.",
            OutputLanguage.clauseFor(Kind.ANSWER, "en", "fr", translated = false)
        )
    }

    // --- Into the summary prompt ---------------------------------------------

    @Test
    fun effective_withoutAClauseIsByteForByteWhatItWas() {
        val general = SummaryTemplates.ALL.first { it.key == "general" }
        val effective = SummaryTemplates.effective(general, "standard", "")
        assertEquals(SummaryTemplates.effective(general, "standard"), effective)
        assertTrue(effective.llmInstructions.endsWith(SummaryTemplates.OUTPUT_RULES))
    }

    @Test
    fun effective_putsTheClauseAfterTheSharedRules() {
        val general = SummaryTemplates.ALL.first { it.key == "general" }
        val clause = OutputLanguage.clause(Kind.SUMMARY, "de", "de")
        val text = SummaryTemplates.effective(general, "brief", clause).llmInstructions
        assertTrue(text.endsWith(SummaryTemplates.OUTPUT_RULES + clause))
        assertTrue(text.startsWith(general.llmInstructions))
    }

    @Test
    fun effective_aVerbatimTemplateKeepsItsTextAndGainsOnlyTheLanguage() {
        val exec = SummaryTemplates.ALL.first { it.key == "exec_summary" }
        val clause = OutputLanguage.clause(Kind.SUMMARY, "fr", "fr")
        val text = SummaryTemplates.effective(exec, "detailed", clause).llmInstructions
        assertEquals(exec.llmInstructions + "\n\n" + clause.trim(), text)
        // No depth clause or shared rules sneak in with it.
        assertFalse(text.contains(SummaryTemplates.depthInstructions("detailed")))
    }

    // --- English-only heuristics ---------------------------------------------

    private val german = listOf(
        "Ich will das Budget bis Freitag fertig haben.",
        "Wir müssen die Zahlen noch einmal prüfen, sagt Anna.",
        "Das Budget für das nächste Quartal ist noch nicht freigegeben.",
        "Die Präsentation für den Vorstand ist am Montag.",
        "Ich will auch die Kosten für die Reise sehen.",
        "Das Team will die Planung für das Quartal abschließen."
    ).joinToString("\n")

    @Test
    fun extractive_listsNoActionItemsFromAnotherLanguage() {
        // German "will" means "wants": the English pattern matches it.
        val unguarded = ExtractiveSummarizer.summarize(german, "")
        assertTrue(unguarded.contains("Possible action items"))
        val guarded = ExtractiveSummarizer.summarize(german, "", englishHeuristics = false)
        assertFalse(guarded.contains("Possible action items"))
        assertTrue(guarded.contains("only picked out automatically from English"))
        assertTrue(guarded.contains("Key points"))
        val actionsOnly = ExtractiveSummarizer.summarize(
            german, "", actionsOnly = true, englishHeuristics = false
        )
        assertFalse(actionsOnly.contains("☐"))
        assertTrue(actionsOnly.contains("only picked out automatically from English"))
    }

    @Test
    fun extractive_englishIsUnchangedByDefault() {
        val english = "We will send the report by Friday.\nThe budget review went well overall today."
        assertEquals(
            ExtractiveSummarizer.summarize(english, "notes"),
            ExtractiveSummarizer.summarize(english, "notes", englishHeuristics = true)
        )
    }

    @Test
    fun extractive_commonWordsStandInForStopwords() {
        val sentences = german.split("\n")
        val common = ExtractiveSummarizer.commonWords(sentences)
        assertTrue("die" in common)
        assertTrue("das" in common)
        // The subject recurs, but not in a third of the sentences.
        assertFalse("präsentation" in common)
        assertTrue(ExtractiveSummarizer.commonWords(sentences.take(3)).isEmpty())
        assertTrue(ExtractiveSummarizer.titleFor(german, englishHeuristics = false).isNotBlank())
    }

    @Test
    fun actionItems_theOfflineFallbackStaysEmptyForAnotherLanguage() {
        val segments = german.split("\n").mapIndexed { i, text ->
            TranscriptSegment(timestampMs = i * 1000L, text = text, speaker = "Anna")
        }
        assertTrue(ActionItems.fromMeetingContent(segments, "", englishHeuristics = true).isNotEmpty())
        assertEquals(
            emptyList<ActionItem>(),
            ActionItems.fromMeetingContent(segments, "- Ich will anrufen", englishHeuristics = false)
        )
    }

    @Test
    fun actionItems_noneInTheMeetingsLanguageIsNotATask() {
        assertTrue(ActionItems.isPlaceholderTask("Keine."))
        assertTrue(ActionItems.isPlaceholderTask("- aucune"))
        assertFalse(ActionItems.isPlaceholderTask("Keine Angst vor dem Budget zeigen"))
    }

    @Test
    fun actionItems_aTranslatedNoneIdentifiedIsNotATask() {
        // "None identified" as the shared rules ask for it, in the summary's language.
        for (text in listOf(
            "Keine identifiziert", "Keine erkannt.", "Aucun identifié", "Aucune action",
            "Ninguno identificado", "Nessuno identificato", "Geen geïdentificeerd",
            "Ingen identifierade", "Nenhum identificado", "Brak", "なし"
        )) {
            assertTrue("expected placeholder: $text", ActionItems.isPlaceholderTask(text))
        }
        val (_, items) = ActionItems.splitLlmOutput(
            "## Aufgaben\nKeine identifiziert\n\n${ActionItems.MARKER} " +
                "[{\"task\":\"Keine identifiziert\",\"owner\":null}]"
        )
        assertEquals(emptyList<ActionItem>(), items)
        // A real task that opens with the same word is longer than a placeholder.
        assertFalse(ActionItems.isPlaceholderTask("Keine Termine im Mai mehr annehmen"))
        assertFalse(ActionItems.isPlaceholderTask("Aucun envoi avant la réponse du juridique"))
    }

    @Test
    fun clause_asksForAnEmptyArrayRatherThanAPlaceholder() {
        assertTrue(
            OutputLanguage.clause(Kind.SUMMARY, "de", "de")
                .endsWith(" If there are no action items, the JSON array must be empty ([]).")
        )
    }
}
