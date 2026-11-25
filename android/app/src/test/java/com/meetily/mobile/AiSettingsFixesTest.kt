package com.meetily.mobile

import com.meetily.mobile.data.AutoSummary
import com.meetily.mobile.data.EndpointExample
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.search.MeetingGroups
import com.meetily.mobile.summarize.LlmClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The AI settings and gates: the endpoint address nobody typed, series
 * memory for untitled recordings, automatic summaries on low battery, and a
 * reply whose content is JSON null.
 */
class AiSettingsFixesTest {

    /** default_meeting_title ("Meeting %1$s") formatted with a date. */
    private val titleFor: (String) -> String = { "Meeting $it" }

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-25 10:30 and 2026-02-25 10:30, UTC. */
    private val nov25 = 1_795_602_600_000L
    private val feb25 = 1_772_015_400_000L
    private val day = 24 * 60 * 60_000L

    private fun untitled(vararg tags: String) =
        MeetingGroups.Untitled(titleFor, tags.map { Locale.forLanguageTag(it) }, utc)

    /** An untitled recording's title, made as RecordingService makes it. */
    private fun recordedTitle(tag: String, atMs: Long): String =
        titleFor(
            DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(tag)
            ).apply { timeZone = utc }.format(Date(atMs))
        )

    @Test
    fun theOldExampleAddressIsClearedOnlyWhereNothingUsesIt() {
        val old = EndpointExample.OLD_DEFAULT
        assertTrue(EndpointExample.shouldClear(old, useLlm = false, engine = "endpoint"))
        assertTrue(EndpointExample.shouldClear(old, useLlm = true, engine = "local"))
        assertTrue(EndpointExample.shouldClear(" $old ", useLlm = false, engine = "endpoint"))
        // In use as an endpoint: it may be the user's own server.
        assertFalse(EndpointExample.shouldClear(old, useLlm = true, engine = "endpoint"))
        // Anything the user typed stays.
        assertFalse(
            EndpointExample.shouldClear(
                "http://192.168.1.20:11434/v1", useLlm = false, engine = "endpoint"
            )
        )
        assertFalse(EndpointExample.shouldClear(null, useLlm = false, engine = "endpoint"))
        assertFalse(EndpointExample.shouldClear("", useLlm = false, engine = "endpoint"))
    }

    @Test
    fun untitledRecordingsAreNotASeries() {
        val english = untitled("en-US")
        assertTrue(english.isUntitled(recordedTitle("en-US", nov25), nov25))
        assertTrue(english.isUntitled("Meeting Nov 25, 2026, 10:30 AM", nov25))
        // Stamped by the recording screen a few minutes before it started.
        assertTrue(english.isUntitled(recordedTitle("en-US", nov25 - 4 * 60_000L), nov25))
        assertEquals(recordedTitle("en-US", nov25), english.defaultTitle(nov25))
        // In the time zone it is given, not the JVM's.
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals(
            titleFor(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)
                    .apply { timeZone = tokyo }.format(Date(nov25))
            ),
            MeetingGroups.Untitled(titleFor, listOf(Locale.US), tokyo).defaultTitle(nov25)
        )
        assertFalse(english.isUntitled("Team standup 3/14", nov25))
        // A real title that only starts like the default is still a series.
        assertFalse(english.isUntitled("Meeting with legal 3/14", nov25))
        assertFalse(english.hasDefaultForm("Meeting with legal 3/14"))
        assertFalse(english.hasDefaultForm("Meeting "))
        assertTrue(english.hasDefaultForm(recordedTitle("en-US", feb25)))
    }

    @Test
    fun untitledRecordingsAreRecognisedInLanguagesWhoseDatesHaveWords() {
        // Normalizing keeps these words: "meeting de nov de", "meeting févr",
        // "meeting feb", "meeting нояб г", "meeting lut". A fixed "meeting"
        // key let them all become series.
        for (tag in listOf("pt-BR", "fr-FR", "es-ES", "ru-RU", "pl-PL", "de-DE", "ja-JP")) {
            val check = untitled(tag)
            for (at in listOf(nov25, feb25, feb25 + 40 * day)) {
                val title = recordedTitle(tag, at)
                assertTrue("$tag: $title", check.isUntitled(title, at))
                assertTrue("$tag: $title", check.isUntitled(title, at + 3 * 60_000L))
                assertTrue("$tag: $title", check.hasDefaultForm(title))
            }
            assertFalse(tag, check.isUntitled("Team standup 3/14", nov25))
            assertFalse(tag, check.isUntitled("Meeting with legal 3/14", nov25))
        }
        // The titles of the review, whatever this JDK's exact punctuation.
        assertTrue(untitled("pt-BR").isUntitled("Meeting 25 de nov. de 2026 10:30", nov25))
        assertTrue(untitled("fr-FR").isUntitled("Meeting 25 févr. 2026, 10:30", feb25))
    }

    @Test
    fun theDeviceLanguageFormatsAndChecksByDefault() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            val title = titleFor(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(nov25))
            )
            assertTrue(MeetingGroups.Untitled(titleFor).isUntitled(title, nov25))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun aTitleFromAnotherOfThePhonesLanguagesIsStillUntitled() {
        // Recorded in French, the phone since switched to English with
        // French still in its list.
        val title = recordedTitle("fr-FR", feb25)
        assertTrue(untitled("en-US", "fr-FR").isUntitled(title, feb25))
        assertTrue(untitled("en-US", "fr-FR").hasDefaultForm(title))
    }

    @Test
    fun aDefaultTitleWithTheDateFirstWorksToo() {
        val check = MeetingGroups.Untitled(
            { "$it meeting" }, listOf(Locale.forLanguageTag("pt-BR")), utc
        )
        val title = check.defaultTitle(nov25)
        assertTrue(title.endsWith(" meeting"))
        assertTrue(check.isUntitled(title, nov25))
        assertTrue(check.hasDefaultForm(title))
        assertFalse(check.isUntitled("Weekly meeting", nov25))
    }

    @Test
    fun theHomeChipsLeaveUntitledRecordingsOut() {
        val portuguese = untitled("pt-BR")
        val meetings = listOf(
            Meeting(id = "a", title = recordedTitle("pt-BR", nov25 - day), createdAtMs = nov25 - day),
            Meeting(id = "b", title = recordedTitle("pt-BR", nov25), createdAtMs = nov25),
            Meeting(id = "e", title = recordedTitle("pt-BR", nov25 + day), createdAtMs = nov25 + day),
            Meeting(id = "c", title = "Team standup 3/14", createdAtMs = 1L),
            Meeting(id = "d", title = "Team standup 3/21", createdAtMs = 2L),
            Meeting(id = "f", title = "Meeting with legal 3/14", createdAtMs = 5L),
            Meeting(id = "g", title = "Meeting with legal 3/21", createdAtMs = 6L)
        )
        assertEquals(
            listOf("meeting with legal", "team standup"),
            MeetingGroups.series(meetings, untitled = portuguese).map { it.key }
        )
        // Without the check, the untitled ones were a series of their own.
        assertEquals(3, MeetingGroups.series(meetings).size)
    }

    @Test
    fun lowBatteryDefersTheAutomaticSummaryToTheCharger() {
        assertFalse(AutoSummary.waitForCharger("end", heavyWorkAllowed = true))
        assertTrue(AutoSummary.waitForCharger("end", heavyWorkAllowed = false))
        assertTrue(AutoSummary.waitForCharger("charging", heavyWorkAllowed = true))
        assertTrue(AutoSummary.waitForCharger("charging", heavyWorkAllowed = false))
    }

    @Test
    fun nullContentIsAnEmptyReplyNotTheWordNull() {
        assertEquals(
            "",
            LlmClient.replyContent(JSONObject("""{"message":{"content":null,"refusal":"no"}}"""))
        )
        assertEquals("", LlmClient.replyContent(JSONObject("""{"message":{}}""")))
        assertEquals("", LlmClient.replyContent(JSONObject("""{"finish_reason":"length"}""")))
        assertEquals(
            "Summary",
            LlmClient.replyContent(JSONObject("""{"message":{"content":"Summary"}}"""))
        )
    }
}
