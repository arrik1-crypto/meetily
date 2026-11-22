package com.meetily.mobile

import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingMerge
import com.meetily.mobile.diag.AiReportFormat
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.summarize.AiDisclaimers
import com.meetily.mobile.summarize.AiDisclaimers.Category
import com.meetily.mobile.summarize.AiProvenance
import com.meetily.mobile.summarize.SummaryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSafetyTest {

    private val clinical = "AI-generated draft for review by a qualified clinician. Not a " +
        "medical record."
    private val legal = "AI-generated draft for review by a qualified lawyer. Not legal advice."

    // --- Template classification ----------------------------------------------

    @Test
    fun everyBuiltInTemplateHasAnExplicitDecision() {
        // A new template must be classified on purpose, not default to "none".
        assertEquals(
            SummaryTemplates.ALL.map { it.key }.toSet(),
            AiDisclaimers.TEMPLATE_CATEGORIES.keys
        )
    }

    @Test
    fun clinicalAndLegalTemplatesAreClassified() {
        assertEquals(Category.CLINICAL, AiDisclaimers.forTemplate("soap"))
        assertEquals(Category.CLINICAL, AiDisclaimers.forTemplate("patient_recap"))
        assertEquals(Category.LEGAL, AiDisclaimers.forTemplate("legal_consult"))
        assertEquals(Category.LEGAL, AiDisclaimers.forTemplate("matter"))
        for (key in listOf("general", "actions", "email", "interview", "exec_summary")) {
            assertEquals("unexpected notice for $key", null, AiDisclaimers.forTemplate(key))
        }
        assertEquals(null, AiDisclaimers.forTemplate("custom_1700000000000"))
        assertEquals(null, AiDisclaimers.forTemplate(null))
    }

    @Test
    fun medGemmaIsClinicalWhateverTheTemplate() {
        assertEquals(Category.CLINICAL, AiDisclaimers.forModel("local:medgemma-4b"))
        assertEquals(Category.CLINICAL, AiDisclaimers.forModel("endpoint:MedGemma:4b"))
        assertEquals(null, AiDisclaimers.forModel("local:qwen2.5-1.5b"))
        assertEquals(null, AiDisclaimers.forModel(null))
        assertEquals(
            setOf(Category.CLINICAL),
            AiDisclaimers.categories("Overview", "general", "local:medgemma-4b")
        )
        // Both, clinical first.
        assertEquals(
            listOf(Category.CLINICAL, Category.LEGAL),
            AiDisclaimers.categories("Memo", "legal_consult", "local:medgemma-4b").toList()
        )
    }

    @Test
    fun aBlankSummaryNeedsNoNotice() {
        assertTrue(AiDisclaimers.categories("  ", "soap", "local:medgemma-4b").isEmpty())
    }

    @Test
    fun aRecordedTemplateWinsOverTheText() {
        // Recorded as general: SOAP-looking text does not override it.
        val soapText = "## Subjective\nx\n## Objective\ny\n## Assessment\nz\n## Plan\nw"
        assertTrue(AiDisclaimers.categories(soapText, "general", "local:qwen2.5-1.5b").isEmpty())
    }

    @Test
    fun legacySummariesAreRecognisedFromTheirText() {
        val soap = "## Subjective\nCough for a week.\n\n**Objective**\nTemp 38.\n\n" +
            "3. Assessment\nLikely viral.\n\n- Plan: fluids."
        assertEquals(setOf(Category.CLINICAL), AiDisclaimers.categories(soap, null, null))
        assertEquals(
            setOf(Category.CLINICAL),
            AiDisclaimers.categories("Draft note, not a medical record.", null, null)
        )
        assertEquals(
            setOf(Category.LEGAL),
            AiDisclaimers.categories("Memo. This is not legal advice.", null, null)
        )
        assertTrue(
            AiDisclaimers.categories(
                "## Overview\nWe planned the objective for Q3.\n## Decisions\nShip it.",
                null, null
            ).isEmpty()
        )
    }

    @Test
    fun legacyPatientRecapsAreRecognisedFromTheirHeadings() {
        val recap = "## What was discussed\nYour knee pain.\n\n## Medication changes\n" +
            "- Start ibuprofen 400 mg.\n\n## Tests ordered\n- X-ray of the left knee.\n\n" +
            "## Warning signs to watch for\n- Swelling or fever."
        assertEquals(setOf(Category.CLINICAL), AiDisclaimers.categories(recap, null, null))
        // One matching section is not enough.
        assertTrue(
            AiDisclaimers.categories(
                "## Overview\nBudget review.\n## Medications budget\nFlat.", null, null
            ).isEmpty()
        )
    }

    @Test
    fun legacyMatterNotesAreRecognisedFromTheirHeadings() {
        val matter = "**Parties and roles**\n- Acme, claimant\n\n**Chronology**\n" +
            "- 3 March: contract signed\n\n**Evidence and documents**\n- The contract\n\n" +
            "**Strategy decisions**\n- File in April"
        assertEquals(setOf(Category.LEGAL), AiDisclaimers.categories(matter, null, null))
        assertTrue(
            AiDisclaimers.categories(
                "## Strategy\nGrow in Q3.\n## Evidence\nSales are up.", null, null
            ).isEmpty()
        )
    }

    @Test
    fun legacyLegalConsultationsAreRecognisedFromTheirHeadings() {
        val consult = "## Client's situation\nA tenant.\n\n## Legal issues identified\n" +
            "- Whether the lease ended.\n\n## Advice and options discussed\n" +
            "- Negotiate first.\n\n## Information still needed\n- The lease itself."
        assertEquals(setOf(Category.LEGAL), AiDisclaimers.categories(consult, null, null))
        // Advice and options open ordinary bullets too; together they count once.
        assertTrue(
            AiDisclaimers.categories(
                "## Venue\n- Options: the hall or the park.\n- Advice from finance: the hall.",
                null, null
            ).isEmpty()
        )
    }

    @Test
    fun unionCoversEveryMeetingOnce() {
        val a = Meeting(id = "a", title = "A", createdAtMs = 1L, summary = "s").apply {
            summaryTemplate = "soap"
        }
        val b = Meeting(id = "b", title = "B", createdAtMs = 2L, summary = "s").apply {
            summaryTemplate = "matter"
        }
        val c = Meeting(id = "c", title = "C", createdAtMs = 3L, summary = "s").apply {
            summaryTemplate = "patient_recap"
        }
        val none = Meeting(id = "d", title = "D", createdAtMs = 4L, summary = "s").apply {
            summaryTemplate = "general"
        }
        assertEquals(
            listOf(Category.CLINICAL, Category.LEGAL),
            AiDisclaimers.union(listOf(b, a, c, none)).toList()
        )
        assertTrue(AiDisclaimers.union(listOf(none)).isEmpty())
    }

    // --- Placing the notice -----------------------------------------------------

    @Test
    fun appendAddsEachNoticeOnce() {
        val out = AiDisclaimers.appendTo("Summary text\n", listOf(clinical, legal, clinical, ""))
        assertEquals("Summary text\n\n$clinical\n\n$legal", out)
    }

    @Test
    fun aNoticeAlreadyCarriedIsNotRepeated() {
        val carried = "Summary.\n\n" + clinical.uppercase().replace(" ", "\n  ")
        assertEquals(carried, AiDisclaimers.appendTo(carried, listOf(clinical)))
        assertEquals(carried, AiDisclaimers.prependTo(carried, listOf(clinical)))
        // Applying twice changes nothing the second time.
        val once = AiDisclaimers.appendTo("Brief", listOf(legal))
        assertEquals(once, AiDisclaimers.appendTo(once, listOf(legal)))
    }

    @Test
    fun noNoticesLeaveTheTextAlone() {
        assertEquals("  text  ", AiDisclaimers.appendTo("  text  ", emptyList()))
        assertEquals("  text  ", AiDisclaimers.prependTo("  text  ", emptyList()))
    }

    @Test
    fun prependPutsTheNoticeFirst() {
        assertEquals(
            "$clinical\n\nThe summary",
            AiDisclaimers.prependTo("\nThe summary", listOf(clinical))
        )
    }

    @Test
    fun markdownExportCarriesTheNoticeUnderTheSummaryHeading() {
        val meeting = Meeting(id = "m", title = "Visit", createdAtMs = 1L, summary = "S body")
        val md = MeetingExporter.markdown(meeting, listOf(clinical))
        val heading = md.indexOf("## Summary")
        val notice = md.indexOf("> $clinical")
        val body = md.indexOf("S body")
        assertTrue(heading in 0 until notice)
        assertTrue(notice < body)
        assertEquals(1, Regex(Regex.escape(clinical)).findAll(md).count())
    }

    @Test
    fun markdownExportDoesNotRepeatANoticeTheSummaryHas() {
        val meeting = Meeting(
            id = "m", title = "Visit", createdAtMs = 1L, summary = "S body\n\n$clinical"
        )
        val md = MeetingExporter.markdown(meeting, listOf(clinical))
        assertEquals(1, Regex(Regex.escape(clinical)).findAll(md).count())
        assertFalse(md.contains("> $clinical"))
    }

    @Test
    fun markdownExportWithoutASummaryHasNoNotice() {
        val meeting = Meeting(id = "m", title = "Visit", createdAtMs = 1L)
        assertFalse(MeetingExporter.markdown(meeting, listOf(clinical)).contains(clinical))
    }

    // --- Provenance on the meeting ------------------------------------------------

    @Test
    fun provenanceRoundTripsAndIsOmittedWhenUnknown() {
        val meeting = Meeting(id = "m", title = "T", createdAtMs = 1L, summary = "s").apply {
            summaryTemplate = "soap"
            summaryModel = "local:medgemma-4b"
        }
        val restored = Meeting.fromJson(meeting.toJson())
        assertEquals("soap", restored.summaryTemplate)
        assertEquals("local:medgemma-4b", restored.summaryModel)

        val plain = Meeting(id = "p", title = "T", createdAtMs = 1L).toJson()
        assertFalse(plain.has("summaryTemplate"))
        assertFalse(plain.has("summaryModel"))
        val legacy = Meeting.fromJson(plain)
        assertEquals(null, legacy.summaryTemplate)
        assertEquals(null, legacy.summaryModel)
    }

    @Test
    fun aNewSummaryOnDiskBringsItsTemplateAndModel() {
        val base = Meeting(id = "m", title = "T", createdAtMs = 1L, summary = "S1").apply {
            summaryTemplate = "general"
            summaryModel = "local:qwen2.5-1.5b"
        }
        val ours = MeetingMerge.snapshot(base).apply { starred = true }
        val disk = MeetingMerge.snapshot(base).apply {
            summary = "S2"
            summaryTemplate = "soap"
            summaryModel = "local:medgemma-4b"
        }
        val merged = MeetingMerge.mergeScreenEdits(base, ours, disk, Long.MIN_VALUE)
        assertEquals("S2", merged.summary)
        assertEquals("soap", merged.summaryTemplate)
        assertEquals("local:medgemma-4b", merged.summaryModel)
        assertTrue(merged.starred)
    }

    @Test
    fun modelKeysNameTheEngineButNeverTheEndpoint() {
        assertEquals(
            "local:medgemma-4b",
            AiProvenance.modelKey(true, true, "medgemma-4b", "llama3.1")
        )
        assertEquals("endpoint:llama3.1", AiProvenance.modelKey(true, false, "x", "llama3.1"))
        assertEquals(AiProvenance.EXTRACTIVE, AiProvenance.modelKey(false, true, "x", "y"))
        assertEquals("endpoint:?", AiProvenance.modelKey(true, false, "x", ""))
    }

    // --- Report email -------------------------------------------------------------

    private fun report(text: String?, recorded: Boolean = true) = AiReportFormat.Report(
        surface = "meeting_summary",
        reason = AiReportFormat.Reason.OFFENSIVE,
        comment = "  ",
        appVersion = "3.14.0 (109)",
        androidVersion = "15 (SDK 35)",
        modelKey = "local:qwen2.5-1.5b",
        modelRecorded = recorded,
        templateKey = null,
        generatedText = text
    )

    @Test
    fun reportWithoutTheTextSendsNoMeetingContent() {
        val body = AiReportFormat.body(report(null))
        assertTrue(body.contains("Reason: offensive_or_harmful"))
        assertTrue(body.contains("Where: meeting_summary"))
        assertTrue(body.contains("Comment: (none)"))
        assertTrue(body.contains("App: Recap 3.14.0 (109)"))
        assertTrue(body.contains("Model: local:qwen2.5-1.5b\n"))
        assertTrue(body.contains("Template: none"))
        assertTrue(body.contains("Generated text: not included."))
        assertFalse(body.contains("--- Generated text"))
    }

    @Test
    fun reportIncludesTheTextOnlyWhenGivenAndCapsIt() {
        val body = AiReportFormat.body(report("The patient said X."))
        assertTrue(body.contains("--- Generated text (included by the user) ---\nThe patient said X."))

        val long = "a".repeat(AiReportFormat.TEXT_CAP + 50)
        val capped = AiReportFormat.body(report(long))
        assertFalse(capped.contains(long))
        assertTrue(capped.contains("[cut at ${AiReportFormat.TEXT_CAP} characters]"))
    }

    @Test
    fun reportSaysWhenTheModelIsOnlyTheCurrentSetting() {
        val body = AiReportFormat.body(report(null, recorded = false))
        assertTrue(body.contains("(current setting, not recorded with this text)"))
    }

    @Test
    fun mailtoLinkEncodesSubjectAndBodyWithCrlfLineBreaks() {
        val uri = AiReportFormat.mailtoUri(
            "support@example.com", "Report: a&b", "line one\nline two é"
        )
        assertEquals(
            "mailto:support@example.com?subject=Report%3A%20a%26b" +
                "&body=line%20one%0D%0Aline%20two%20%C3%A9",
            uri
        )
        assertEquals("AZaz09-._~", AiReportFormat.encode("AZaz09-._~"))
        assertEquals("%2B%3D%3F%23", AiReportFormat.encode("+=?#"))
    }
}
