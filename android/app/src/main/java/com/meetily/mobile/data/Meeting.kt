package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** One word's start offset (ms) within its segment's audio chunk. */
data class WordStamp(
    val ms: Long,
    val text: String
)

/**
 * One occurrence of a calendar event, copied at the moment the user linked
 * it. [eventId] is kept for provenance only and is never re-resolved.
 *
 * There is deliberately no way to say "the series". A follow-up follows from
 * one specific earlier conversation, not from every Monday standup, so the
 * ambiguous choice simply cannot be expressed.
 */
data class FollowsEvent(
    val title: String,
    val beginMs: Long,
    val eventId: Long
)

data class TranscriptSegment(
    val timestampMs: Long,
    val text: String,
    val speaker: String? = null,
    val highlighted: Boolean = false,
    /** Acoustic diarization cluster ("Speaker N") when no name is known. */
    val clusterId: Int? = null,
    /** Offset into the meeting's audio file, for tap-to-play seek. */
    val audioMs: Long? = null,
    /** Word timings for word-level tap-to-seek; dropped when text is edited. */
    val words: List<WordStamp>? = null
)

data class QaEntry(
    val question: String,
    val answer: String
)

data class ActionItem(
    val task: String,
    val owner: String? = null,
    val done: Boolean = false,
    /** When set, a reminder notification fires at this wall-clock time. */
    val remindAtMs: Long? = null
)

/** A file attached to a meeting: stored filename + original display name. */
data class Attachment(
    val file: String,
    val name: String
)

/**
 * A topical chapter within the transcript. Anchored by wall-clock start time
 * (not segment index) so transcript edits and splits can't orphan it: the
 * chapter renders above the first segment at or after [startMs].
 */
data class Chapter(
    val title: String,
    val startMs: Long
)

data class Meeting(
    val id: String,
    var title: String,
    val createdAtMs: Long,
    val segments: MutableList<TranscriptSegment> = mutableListOf(),
    var notes: String = "",
    /** Pre-enhancement notes, kept so "Enhance notes" is always revertable. */
    var notesOriginal: String = "",
    var summary: String = "",
    var attendees: MutableList<String> = mutableListOf(),
    val qa: MutableList<QaEntry> = mutableListOf(),
    var actionItems: MutableList<ActionItem> = mutableListOf(),
    var photos: MutableList<String> = mutableListOf(),
    /** Filename in AudioStore when the meeting's audio was kept. */
    var audioFile: String? = null,
    /** Free-form labels for filtering the library ("client-x", "1:1"…). */
    var tags: MutableList<String> = mutableListOf(),
    /** Detected topic chapters, ordered by startMs. */
    val chapters: MutableList<Chapter> = mutableListOf(),
    /** On-device OCR text per attached photo (filename → extracted text). */
    val photoTexts: MutableMap<String, String> = mutableMapOf(),
    /** Arbitrary file attachments (see AttachmentStore). */
    val attachmentsList: MutableList<Attachment> = mutableListOf(),
    /** Flagged for follow-up: the meeting is starred in the library list. */
    var starred: Boolean = false,
    /**
     * Key of the transcription model that produced the current segments, when
     * known. Written by imports and by the accuracy check so the second-pass
     * picker can say which model made this transcript.
     */
    var transcriptModel: String? = null,
    /**
     * Set when the transcript changed under an existing summary (an accepted
     * accuracy check), so the screen can say the summary is out of date
     * instead of silently regenerating or silently lying.
     */
    var summaryStale: Boolean = false,
    /**
     * An earlier calendar event this conversation follows on from, asserted
     * by the user and never inferred.
     *
     * A DEAD SNAPSHOT, not a pointer: the calendar is read once, when the
     * link is made, and never dereferenced again. Permission revoked, event
     * deleted, event retitled, provider wiped, phone migrated — this still
     * renders, because rendering never touches a ContentResolver.
     */
    var followsEvent: FollowsEvent? = null
) {
    /** Raw transcript text, no speaker labels (used for snippets, word counts, extractive summary). */
    fun transcriptText(): String =
        segments.joinToString("\n") { it.text }

    /** Transcript with speaker labels where assigned (used for sharing and LLM summaries). */
    fun transcriptTextWithSpeakers(): String =
        segments.joinToString("\n") { seg ->
            val speaker = seg.speaker
            if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
        }

    fun highlightedTexts(): List<String> =
        segments.filter { it.highlighted }.map { seg ->
            val speaker = seg.speaker
            if (speaker.isNullOrBlank()) seg.text else "$speaker: ${seg.text}"
        }

    fun attendeesText(): String = attendees.joinToString(", ")

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("createdAtMs", createdAtMs)
        obj.put("notes", notes)
        if (notesOriginal.isNotEmpty()) {
            obj.put("notesOriginal", notesOriginal)
        }
        obj.put("summary", summary)
        val attendeesArr = JSONArray()
        for (name in attendees) {
            attendeesArr.put(name)
        }
        obj.put("attendees", attendeesArr)
        val arr = JSONArray()
        for (seg in segments) {
            arr.put(segmentToJson(seg))
        }
        obj.put("segments", arr)
        val qaArr = JSONArray()
        for (entry in qa) {
            val q = JSONObject()
            q.put("q", entry.question)
            q.put("a", entry.answer)
            qaArr.put(q)
        }
        obj.put("qa", qaArr)
        val actionsArr = JSONArray()
        for (item in actionItems) {
            val a = JSONObject()
            a.put("task", item.task)
            if (!item.owner.isNullOrBlank()) a.put("owner", item.owner)
            a.put("done", item.done)
            if (item.remindAtMs != null) a.put("remindAt", item.remindAtMs)
            actionsArr.put(a)
        }
        obj.put("actionItems", actionsArr)
        val photosArr = JSONArray()
        for (name in photos) {
            photosArr.put(name)
        }
        obj.put("photos", photosArr)
        if (!audioFile.isNullOrBlank()) {
            obj.put("audioFile", audioFile)
        }
        if (tags.isNotEmpty()) {
            val tagsArr = JSONArray()
            for (tag in tags) tagsArr.put(tag)
            obj.put("tags", tagsArr)
        }
        if (chapters.isNotEmpty()) {
            val chaptersArr = JSONArray()
            for (chapter in chapters) {
                chaptersArr.put(
                    JSONObject().put("title", chapter.title).put("t", chapter.startMs)
                )
            }
            obj.put("chapters", chaptersArr)
        }
        if (photoTexts.isNotEmpty()) {
            val photoTextsObj = JSONObject()
            for ((name, text) in photoTexts) photoTextsObj.put(name, text)
            obj.put("photoTexts", photoTextsObj)
        }
        if (attachmentsList.isNotEmpty()) {
            val attachArr = JSONArray()
            for (attachment in attachmentsList) {
                attachArr.put(
                    JSONObject().put("f", attachment.file).put("n", attachment.name)
                )
            }
            obj.put("attachments", attachArr)
        }
        if (starred) {
            obj.put("starred", true)
        }
        if (!transcriptModel.isNullOrBlank()) {
            obj.put("transcriptModel", transcriptModel)
        }
        if (summaryStale) {
            obj.put("summaryStale", true)
        }
        followsEvent?.let { link ->
            obj.put(
                "followsEvent",
                JSONObject()
                    .put("title", link.title)
                    .put("beginMs", link.beginMs)
                    .put("eventId", link.eventId)
            )
        }
        return obj
    }

    companion object {
        /**
         * Segment (de)serialisation lives here rather than inline so the
         * staged second-pass transcript (TranscriptDraft) writes exactly the
         * same shape a stored meeting does.
         */
        fun segmentToJson(seg: TranscriptSegment): JSONObject {
            val s = JSONObject()
            s.put("t", seg.timestampMs)
            s.put("text", seg.text)
            if (!seg.speaker.isNullOrBlank()) {
                s.put("speaker", seg.speaker)
            }
            if (seg.highlighted) {
                s.put("highlighted", true)
            }
            if (seg.clusterId != null) {
                s.put("cluster", seg.clusterId)
            }
            if (seg.audioMs != null) {
                s.put("audioMs", seg.audioMs)
            }
            val words = seg.words
            if (!words.isNullOrEmpty()) {
                val wordsArr = JSONArray()
                for (word in words) {
                    wordsArr.put(JSONArray().put(word.ms).put(word.text))
                }
                s.put("words", wordsArr)
            }
            return s
        }

        fun segmentFromJson(s: JSONObject): TranscriptSegment {
            val speaker = s.optString("speaker", "")
            return TranscriptSegment(
                timestampMs = s.optLong("t", 0L),
                text = s.optString("text", ""),
                speaker = speaker.ifBlank { null },
                highlighted = s.optBoolean("highlighted", false),
                clusterId = if (s.has("cluster")) s.optInt("cluster") else null,
                audioMs = if (s.has("audioMs")) s.optLong("audioMs") else null,
                words = s.optJSONArray("words")?.let { wordsArr ->
                    val out = mutableListOf<WordStamp>()
                    for (w in 0 until wordsArr.length()) {
                        val pair = wordsArr.optJSONArray(w) ?: continue
                        val text = pair.optString(1, "")
                        if (text.isNotBlank()) {
                            out.add(WordStamp(pair.optLong(0), text))
                        }
                    }
                    out.ifEmpty { null }
                }
            )
        }

        fun parseAttendees(input: String): MutableList<String> =
            input.split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()

        fun fromJson(obj: JSONObject): Meeting {
            val meeting = Meeting(
                id = obj.getString("id"),
                title = obj.optString("title", "Untitled meeting"),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                notes = obj.optString("notes", ""),
                notesOriginal = obj.optString("notesOriginal", ""),
                summary = obj.optString("summary", "")
            )
            val attendeesArr = obj.optJSONArray("attendees") ?: JSONArray()
            for (i in 0 until attendeesArr.length()) {
                val name = attendeesArr.optString(i, "")
                if (name.isNotBlank()) meeting.attendees.add(name)
            }
            val arr = obj.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until arr.length()) {
                meeting.segments.add(segmentFromJson(arr.getJSONObject(i)))
            }
            val qaArr = obj.optJSONArray("qa") ?: JSONArray()
            for (i in 0 until qaArr.length()) {
                val q = qaArr.getJSONObject(i)
                val question = q.optString("q", "")
                val answer = q.optString("a", "")
                if (question.isNotBlank()) {
                    meeting.qa.add(QaEntry(question, answer))
                }
            }
            val actionsArr = obj.optJSONArray("actionItems") ?: JSONArray()
            for (i in 0 until actionsArr.length()) {
                val a = actionsArr.getJSONObject(i)
                val task = a.optString("task", "")
                if (task.isNotBlank()) {
                    val owner = a.optString("owner", "")
                    meeting.actionItems.add(
                        ActionItem(
                            task,
                            owner.ifBlank { null },
                            a.optBoolean("done", false),
                            if (a.has("remindAt")) a.optLong("remindAt") else null
                        )
                    )
                }
            }
            val photosArr = obj.optJSONArray("photos") ?: JSONArray()
            for (i in 0 until photosArr.length()) {
                val name = photosArr.optString(i, "")
                if (name.isNotBlank()) meeting.photos.add(name)
            }
            meeting.audioFile = obj.optString("audioFile", "").ifBlank { null }
            val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tagsArr.length()) {
                val tag = tagsArr.optString(i, "")
                if (tag.isNotBlank()) meeting.tags.add(tag)
            }
            val chaptersArr = obj.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until chaptersArr.length()) {
                val c = chaptersArr.getJSONObject(i)
                val title = c.optString("title", "")
                if (title.isNotBlank()) {
                    meeting.chapters.add(Chapter(title, c.optLong("t", 0L)))
                }
            }
            val photoTextsObj = obj.optJSONObject("photoTexts")
            if (photoTextsObj != null) {
                for (key in photoTextsObj.keys()) {
                    val text = photoTextsObj.optString(key, "")
                    if (text.isNotBlank()) meeting.photoTexts[key] = text
                }
            }
            val attachArr = obj.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until attachArr.length()) {
                val a = attachArr.getJSONObject(i)
                val file = a.optString("f", "")
                if (file.isNotBlank()) {
                    meeting.attachmentsList.add(
                        Attachment(file, a.optString("n", file))
                    )
                }
            }
            // Absent in meetings saved before these fields existed, which is
            // exactly the default either way.
            meeting.starred = obj.optBoolean("starred", false)
            meeting.transcriptModel = obj.optString("transcriptModel", "").ifBlank { null }
            meeting.summaryStale = obj.optBoolean("summaryStale", false)
            // opt* only, and a blank title prunes the whole link to null.
            // A throw here would not cost the link, it would cost the
            // MEETING: fromJson failures are swallowed by mapNotNull in
            // MeetingStore.list(), so the meeting would vanish with nothing
            // logged anywhere.
            obj.optJSONObject("followsEvent")?.let { link ->
                val title = link.optString("title", "")
                if (title.isNotBlank()) {
                    meeting.followsEvent = FollowsEvent(
                        title = title,
                        beginMs = link.optLong("beginMs", 0L),
                        eventId = link.optLong("eventId", 0L)
                    )
                }
            }
            return meeting
        }
    }
}
