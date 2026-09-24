package com.meetily.mobile

import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.AtomicJson
import com.meetily.mobile.data.Attachment
import com.meetily.mobile.data.BackupManager
import com.meetily.mobile.data.LightJson
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.SafeFiles
import com.meetily.mobile.data.TranscriptSegment
import com.meetily.mobile.data.WordStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library's light listings, the path check every stored file name goes
 * through, and the restore gate built on it.
 */
class LibraryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun sample(): Meeting =
        Meeting(id = "m1", title = "Team \"standup\" \\ 3/14", createdAtMs = 42L).apply {
            notes = "line one\nline two ünïcode"
            summary = "Summary {with} [brackets], and \"quotes\""
            tags.add("client-x")
            starred = true
            audioFile = "m1.aac"
            actionItems.add(ActionItem("Send deck", "Ana", false, 99L))
            segments.add(
                TranscriptSegment(
                    timestampMs = 1L,
                    text = "hello [world] {x}",
                    speaker = "Ana",
                    audioMs = 500L,
                    words = listOf(WordStamp(0L, "hello"), WordStamp(10L, "\"world\""))
                )
            )
            segments.add(TranscriptSegment(timestampMs = 2L, text = "no words here"))
        }

    @Test
    fun lightParse_keepsEverythingButTheWordTimings() {
        val meeting = sample()
        val light = Meeting.fromJson(
            LightJson.parse(meeting.toJson().toString(), null, setOf("words"))
        )
        // Word timings are the only difference from a full round trip.
        val expected = Meeting.fromJson(meeting.toJson())
        for (i in expected.segments.indices) {
            expected.segments[i] = expected.segments[i].copy(words = null)
        }
        assertEquals(expected.toJson().toString(), light.toJson().toString())
        assertNull(light.segments[0].words)
        assertEquals("hello [world] {x}", light.segments[0].text)
        assertEquals(500L, light.segments[0].audioMs)
    }

    @Test
    fun lightParse_withTopLevelFieldsReadsOnlyThose() {
        val light = Meeting.fromJson(
            LightJson.parse(
                sample().toJson().toString(),
                setOf("id", "title", "createdAtMs", "actionItems"),
                setOf("words")
            )
        )
        assertEquals("m1", light.id)
        assertEquals("Team \"standup\" \\ 3/14", light.title)
        assertEquals(42L, light.createdAtMs)
        assertEquals(99L, light.actionItems.single().remindAtMs)
        assertTrue(light.segments.isEmpty())
        assertEquals("", light.summary)
        assertFalse(light.starred)
    }

    @Test
    fun lightParse_rejectsTruncatedFilesLikeAFullParse() {
        val text = sample().toJson().toString()
        var failures = 0
        // Cut inside the word arrays, inside strings and at the very end.
        for (cut in listOf(text.length - 1, text.length / 2, text.indexOf("words") + 12)) {
            try {
                LightJson.parse(text.substring(0, cut), null, setOf("words"))
            } catch (_: org.json.JSONException) {
                failures++
            }
        }
        assertEquals(3, failures)
    }

    @Test
    fun safeFiles_acceptsOnlyPlainNames() {
        assertTrue(SafeFiles.isPlainName("m1.json"))
        assertTrue(SafeFiles.isPlainName("abc_123.jpg"))
        assertFalse(SafeFiles.isPlainName(null))
        assertFalse(SafeFiles.isPlainName(""))
        assertFalse(SafeFiles.isPlainName("."))
        assertFalse(SafeFiles.isPlainName(".."))
        assertFalse(SafeFiles.isPlainName("../voice_profiles.json"))
        assertFalse(SafeFiles.isPlainName("a/b"))
        assertFalse(SafeFiles.isPlainName("a\\b"))
        assertFalse(SafeFiles.isPlainName("a\u0000b"))
    }

    @Test
    fun safeFiles_childNeverLeavesTheDirectory() {
        val dir = folder.newFolder("audio")
        assertEquals(File(dir, "m1.aac"), SafeFiles.child(dir, "m1.aac"))
        val escaped = SafeFiles.child(dir, "../voice_profiles.json")
        assertEquals(dir.canonicalPath, escaped.canonicalFile.parentFile!!.canonicalPath)
        assertFalse(escaped.exists())
    }

    @Test
    fun restore_rejectsMeetingsThatNameFilesOutsideTheirStores() {
        assertTrue(BackupManager.isRestorable(sample(), "m1.json"))
        // The id must be the file it arrived in.
        assertFalse(BackupManager.isRestorable(sample(), "other.json"))
        val badId = Meeting(id = "../voice_profiles", title = "x", createdAtMs = 1L)
        assertFalse(BackupManager.isRestorable(badId, "voice_profiles.json"))
        assertFalse(
            BackupManager.isRestorable(sample().apply { audioFile = "../voice_profiles.json" }, "m1.json")
        )
        assertFalse(
            BackupManager.isRestorable(sample().apply { photos.add("../llm-models/x.gguf") }, "m1.json")
        )
        assertFalse(
            BackupManager.isRestorable(
                sample().apply { attachmentsList.add(Attachment("..", "x")) }, "m1.json"
            )
        )
    }

    @Test
    fun atomicJson_countsWritesPerFile() {
        val dir = folder.root
        val file = File(dir, "g.json")
        val before = AtomicJson.generation(file)
        AtomicJson.write(dir, "g.json", "{}")
        val after = AtomicJson.generation(file)
        assertNotEquals(before, after)
        // A write to another file is not a write to this one.
        AtomicJson.write(dir, "h.json", "{}")
        assertEquals(after, AtomicJson.generation(file))
    }
}
