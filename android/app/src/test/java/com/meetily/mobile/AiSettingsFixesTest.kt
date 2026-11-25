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

/**
 * The AI settings and gates: the endpoint address nobody typed, series
 * memory for untitled recordings, automatic summaries on low battery, and a
 * reply whose content is JSON null.
 */
class AiSettingsFixesTest {

    /** default_meeting_title with the date left out, as the app passes it. */
    private val defaultTitle = "Meeting "

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
        val untitled = MeetingGroups.normalizeTitle("Meeting Nov 25, 2026, 10:30 AM")
        assertEquals("meeting", untitled)
        assertFalse(MeetingGroups.isSeriesKey(untitled, defaultTitle))
        assertFalse(MeetingGroups.isSeriesKey("", defaultTitle))
        assertTrue(MeetingGroups.isSeriesKey("team standup", defaultTitle))
        // A real title that only starts like the default is still a series.
        assertTrue(
            MeetingGroups.isSeriesKey(
                MeetingGroups.normalizeTitle("Meeting with legal 3/14"), defaultTitle
            )
        )
    }

    @Test
    fun theHomeChipsLeaveUntitledRecordingsOut() {
        val meetings = listOf(
            Meeting(id = "a", title = "Meeting Nov 24, 2026, 9:00 AM", createdAtMs = 3L),
            Meeting(id = "b", title = "Meeting Nov 25, 2026, 10:30 AM", createdAtMs = 4L),
            Meeting(id = "c", title = "Team standup 3/14", createdAtMs = 1L),
            Meeting(id = "d", title = "Team standup 3/21", createdAtMs = 2L)
        )
        assertEquals(
            listOf("team standup"),
            MeetingGroups.series(meetings, defaultTitle = defaultTitle).map { it.key }
        )
        // Without a default title, as before.
        assertEquals(2, MeetingGroups.series(meetings).size)
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
