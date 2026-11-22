package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.LightJson
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import com.meetily.mobile.search.HomeSearch
import com.meetily.mobile.search.LibrarySearch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home search box, and the light listing that feeds it and Ask your
 * library: everything either one reads has to survive that listing.
 */
class HomeSearchTest {

    private val now = 1_800_000_000_000L

    private fun rich(): Meeting =
        Meeting(id = "m1", title = "Quarterly Planning", createdAtMs = now - 86_400_000L).apply {
            notes = "Bring the Gantt chart"
            summary = "Agreed the Q3 launch date."
            summaryTemplate = "executive"
            summaryModel = "extractive"
            attendees.add("Ana Lima")
            tags.add("Client-X")
            actionItems.add(ActionItem("Send the Revised Deck", "Priya", false, 99L))
            actionItems.add(ActionItem("Book the venue"))
            photos.add("p1.jpg")
            photos.add("p2.jpg")
            photoTexts["p1.jpg"] = "ROADMAP\nMilestone Kappa — Whiteboard"
            // OCR ran and found nothing.
            photoTexts["p2.jpg"] = ""
            attachmentsList.add(Attachment("a_17.bin", "Budget-2027.XLSX"))
            segments.add(
                TranscriptSegment(
                    timestampMs = now,
                    text = "The Vendor contract is signed",
                    speaker = "Ana",
                    words = listOf(WordStamp(0L, "The"), WordStamp(120L, "Vendor"))
                )
            )
            segments.add(TranscriptSegment(timestampMs = now + 1_000L, text = "no speaker here"))
        }

    private fun matches(meeting: Meeting, typed: String): Boolean =
        HomeSearch.matches(HomeSearch.blobFor(meeting), HomeSearch.normalizeQuery(typed))

    @Test
    fun findsPhotoText() {
        assertTrue(matches(rich(), "roadmap"))
        assertTrue(matches(rich(), "milestone kappa"))
        assertTrue(matches(rich(), "whiteboard"))
    }

    @Test
    fun findsActionItemsAndTheirOwners() {
        assertTrue(matches(rich(), "revised deck"))
        assertTrue(matches(rich(), "priya"))
        assertTrue(matches(rich(), "book the venue"))
    }

    @Test
    fun findsAttachmentNamesButNotStoredFileNames() {
        assertTrue(matches(rich(), "budget-2027.xlsx"))
        assertFalse(matches(rich(), "a_17.bin"))
    }

    @Test
    fun stillFindsEverythingItFoundBefore() {
        val meeting = rich()
        for (query in listOf(
            "quarterly", "gantt", "q3 launch", "ana lima", "client-x",
            "vendor contract", "ana: the vendor", "no speaker here"
        )) {
            assertTrue(query, matches(meeting, query))
        }
    }

    @Test
    fun isCaseInsensitiveBothWays() {
        val blob = HomeSearch.blobFor(rich())
        assertEquals(blob.lowercase(), blob)
        assertEquals("roadmap", HomeSearch.normalizeQuery("  RoadMap \t"))
        assertTrue(matches(rich(), "  ROADMAP "))
        assertTrue(matches(rich(), "PRIYA"))
        assertTrue(matches(rich(), "Budget-2027.xlsx"))
        assertTrue(matches(rich(), "VENDOR"))
    }

    @Test
    fun doesNotMatchAcrossFieldsOrUnrelatedText() {
        assertFalse(matches(rich(), "planning bring"))
        assertFalse(matches(rich(), "kickoff"))
    }

    @Test
    fun blankEntriesAddNothing() {
        val without = rich().apply { photoTexts.remove("p2.jpg") }
        assertEquals(HomeSearch.blobFor(without), HomeSearch.blobFor(rich()))
        val bare = Meeting(id = "e", title = "", createdAtMs = 0L)
        assertEquals("", HomeSearch.blobFor(bare))
    }

    /** As MeetingStore.listLight reads a meeting file. */
    private fun lightCopy(meeting: Meeting): Meeting =
        Meeting.fromJson(LightJson.parse(meeting.toJson().toString(), null, setOf("words")))

    @Test
    fun lightListingKeepsWhatHomeSearchReads() {
        val full = Meeting.fromJson(JSONObject(rich().toJson().toString()))
        val light = lightCopy(rich())
        assertEquals(full.photoTexts, light.photoTexts)
        assertEquals(full.actionItems, light.actionItems)
        assertEquals(full.attachmentsList, light.attachmentsList)
        assertEquals(HomeSearch.blobFor(full), HomeSearch.blobFor(light))
        assertNull(light.segments[0].words)
    }

    @Test
    fun lightListingKeepsWhatAskYourLibraryReads() {
        val full = Meeting.fromJson(JSONObject(rich().toJson().toString()))
        val light = lightCopy(rich())
        // Retrieval: scores and quoted moments come out the same.
        for (question in listOf("vendor contract", "roadmap", "revised deck", "q3 launch")) {
            val fromFull = LibrarySearch.search(listOf(full), question, nowMs = now)
            val fromLight = LibrarySearch.search(listOf(light), question, nowMs = now)
            assertEquals(question, 1, fromLight.size)
            assertEquals(question, fromFull.single().score, fromLight.single().score, 0.0)
            assertEquals(question, fromFull.single().excerpts, fromLight.single().excerpts)
        }
        // The answer's context, its source rows and its fixed notices.
        assertEquals(full.transcriptTextWithSpeakers(), light.transcriptTextWithSpeakers())
        assertEquals(full.title, light.title)
        assertEquals(full.createdAtMs, light.createdAtMs)
        assertEquals(full.summary, light.summary)
        assertEquals(full.summaryTemplate, light.summaryTemplate)
        assertEquals(full.summaryModel, light.summaryModel)
    }
}
