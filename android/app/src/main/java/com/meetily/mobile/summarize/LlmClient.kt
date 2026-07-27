package com.meetily.mobile.summarize

import com.meetily.mobile.security.EndpointGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenAI-compatible chat-completions client. Works with the same
 * providers the desktop app supports: Ollama (http://<host>:11434/v1),
 * Groq, OpenRouter, or any other /v1/chat/completions endpoint.
 */
object LlmClient {

    fun summarize(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        transcript: String,
        notes: String,
        attendees: List<String> = emptyList(),
        highlights: List<String> = emptyList(),
        template: SummaryTemplate = SummaryTemplates.ALL.first()
    ): String {
        // Length is governed by the template's depth instructions (see
        // SummaryTemplates.effective), so no "be concise" here to fight it.
        val systemPrompt = "You are a meeting assistant. " + template.llmInstructions +
            " Be factual; incorporate the user's own notes and highlighted " +
            "moments where relevant." + ActionItems.LLM_INSTRUCTIONS

        val userContent = buildString {
            if (attendees.isNotEmpty()) {
                append("Meeting attendees: ")
                append(attendees.joinToString(", "))
                append("\n\n")
            }
            if (highlights.isNotEmpty()) {
                append("Moments the user highlighted as important:\n")
                for (h in highlights.take(40)) {
                    append("- ").append(h).append('\n')
                }
                append('\n')
            }
            append("Meeting transcript (lines may be prefixed with the speaker's name):\n")
            append(transcript.take(48_000))
            if (notes.isNotBlank()) {
                append("\n\nMy notes during the meeting:\n")
                append(notes.take(8_000))
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))

        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    fun title(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        transcript: String,
        notes: String
    ): String {
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Generate a concise 3-6 word title for this meeting. " +
                        "Return ONLY the title text: no quotes, no trailing punctuation."
                )
            )
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Transcript:\n" + transcript.take(20_000) +
                        if (notes.isNotBlank()) "\n\nNotes:\n" + notes.take(3_000) else ""
                )
            )
        return chat(baseUrl, apiKey, model, messages, localOnly)
            .trim().trim('"', '\'').take(80)
    }

    /**
     * Content-based speaker attribution: asks the LLM to label untagged
     * transcript lines from conversational context (names addressed,
     * self-references, role cues). Already-tagged lines are shown as anchors
     * and must not be relabeled. Returns (lineIndex, speakerName) pairs.
     */
    /**
     * [suggestSpeakers] across the WHOLE transcript, in windows, with each
     * window's line numbers mapped back to absolute positions. [onWindow]
     * reports (index, total) for progress.
     *
     * A single call truncates the transcript at 48k characters, so on a long
     * meeting only its opening was ever attributed — silently, which is the
     * worst way for a feature to be incomplete. Windows also give the run
     * something real to report progress against.
     *
     * Lines that already carry a speaker travel with their window and keep
     * acting as the anchors the prompt relies on.
     */
    fun suggestSpeakersWindowed(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        lines: List<Pair<String, String?>>,
        attendees: List<String>,
        onWindow: ((Int, Int) -> Unit)? = null
    ): List<Pair<Int, String>> {
        val windows = chapterWindows(lines.map { it.first.length })
        val collected = mutableListOf<Pair<Int, String>>()
        for ((index, range) in windows.withIndex()) {
            onWindow?.invoke(index + 1, windows.size)
            val slice = lines.subList(range.first, range.last + 1)
            val part = suggestSpeakers(baseUrl, apiKey, model, localOnly, slice, attendees)
            for ((line, name) in part) {
                val absolute = range.first + line
                if (absolute in lines.indices) collected.add(absolute to name)
            }
        }
        // Windows are disjoint, so a repeat can only come from a model
        // returning an out-of-range line; first answer wins either way.
        return collected.distinctBy { it.first }
    }

    fun suggestSpeakers(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        lines: List<Pair<String, String?>>,
        attendees: List<String>
    ): List<Pair<Int, String>> {
        val systemPrompt = buildString {
            append(
                "You attribute meeting transcript lines to speakers using conversational " +
                    "context: names people address each other by, self-references like " +
                    "\"I'll take that\", and role cues. Reply with ONLY a JSON array; each " +
                    "element is {\"line\": <0-based line number>, \"speaker\": \"<name>\"}. " +
                    "Include only lines you can attribute with high confidence; skip all " +
                    "others. Lines that already show a speaker in [brackets] are ground " +
                    "truth anchors — never relabel them."
            )
            if (attendees.isNotEmpty()) {
                append(" Use exactly these attendee names where possible: ")
                append(attendees.joinToString(", "))
                append(".")
            }
        }
        val transcript = buildString {
            append("Transcript:\n")
            for ((i, line) in lines.withIndex()) {
                val (text, speaker) = line
                if (speaker.isNullOrBlank()) {
                    append("$i: $text\n")
                } else {
                    append("$i [$speaker]: $text\n")
                }
            }
        }.take(48_000)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", transcript))

        val response = chat(baseUrl, apiKey, model, messages, localOnly, allowMapReduce = false)
        // Anchor on "[{" so prose brackets ("[high-confidence]") can't hijack
        // the extraction; fall back to the first '[' for a bare "[]" answer.
        val start = response.indexOf("[{").takeIf { it >= 0 } ?: response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            val out = mutableListOf<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val index = obj.optInt("line", -1)
                val name = obj.optString("speaker", "").trim()
                if (index >= 0 && name.isNotBlank()) {
                    out.add(index to name)
                }
            }
            out
        } catch (_: Exception) {
            // Malformed model output reads as "no confident suggestions",
            // not as an error.
            emptyList()
        }
    }

    /** Characters per chapter-detection window; sized to fit the on-device budget. */
    const val CHAPTER_WINDOW_CHARS = 11_000

    /**
     * Line ranges to send for chapter detection. A single call truncates to
     * the model's context, so a long meeting would only ever be chaptered at
     * its beginning; windowing covers the whole transcript. Pure — tested.
     */
    fun chapterWindows(
        lineLengths: List<Int>,
        budget: Int = CHAPTER_WINDOW_CHARS
    ): List<IntRange> {
        if (lineLengths.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        for (i in lineLengths.indices) {
            // +8 covers the "123: " prefix and newline added per line.
            val cost = lineLengths[i] + 8
            if (used > 0 && used + cost > budget) {
                out.add(start..(i - 1))
                start = i
                used = 0
            }
            used += cost
        }
        out.add(start..(lineLengths.size - 1))
        return out
    }

    /**
     * Merges per-window chapter marks: sorts by line, drops duplicates and
     * marks closer together than [minGap] lines (window seams produce a
     * spurious chapter at every boundary). Pure — tested.
     */
    fun mergeChapterMarks(
        marks: List<Pair<Int, String>>,
        minGap: Int = 3
    ): List<Pair<Int, String>> {
        val sorted = marks.sortedBy { it.first }
        val out = mutableListOf<Pair<Int, String>>()
        for (mark in sorted) {
            val previous = out.lastOrNull()
            if (previous != null && mark.first - previous.first < minGap) continue
            out.add(mark)
        }
        return out
    }

    /**
     * [chapters] applied across the whole transcript in windows, with each
     * window's line numbers mapped back to absolute positions. [onWindow]
     * reports (index, total) for progress.
     */
    fun chaptersWindowed(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        lines: List<String>,
        onWindow: ((Int, Int) -> Unit)? = null
    ): List<Pair<Int, String>> {
        val windows = chapterWindows(lines.map { it.length })
        val collected = mutableListOf<Pair<Int, String>>()
        for ((index, range) in windows.withIndex()) {
            onWindow?.invoke(index + 1, windows.size)
            val slice = lines.subList(range.first, range.last + 1)
            val part = chapters(baseUrl, apiKey, model, localOnly, slice)
            for ((line, title) in part) {
                val absolute = range.first + line
                if (absolute in lines.indices) collected.add(absolute to title)
            }
        }
        return mergeChapterMarks(collected)
    }

    /**
     * Topic separation: asks the LLM to mark where new topics start in a
     * numbered transcript. Returns (lineIndex, chapterTitle) pairs sorted by
     * line; empty on unusable output (caller falls back to TopicChapters).
     */
    fun chapters(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        lines: List<String>
    ): List<Pair<Int, String>> {
        val systemPrompt =
            "You split a meeting transcript into topical chapters. Reply with " +
                "ONLY a JSON array; each element is {\"line\": <0-based line " +
                "number where the topic starts>, \"title\": \"<2-5 word " +
                "chapter title>\"}. The first chapter must start at line 0. " +
                "Mark only clear topic shifts — typically 2 to 8 chapters for " +
                "a full meeting."
        val transcript = buildString {
            append("Transcript:\n")
            for ((i, line) in lines.withIndex()) {
                append(i).append(": ").append(line).append('\n')
            }
        }.take(48_000)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", transcript))

        val response = chat(baseUrl, apiKey, model, messages, localOnly, allowMapReduce = false)
        val start = response.indexOf("[{").takeIf { it >= 0 } ?: response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(response.substring(start, end + 1))
            val out = mutableListOf<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val index = obj.optInt("line", -1)
                val title = obj.optString("title", "").trim()
                if (index >= 0 && title.isNotBlank()) {
                    out.add(index to title)
                }
            }
            out.sortedBy { it.first }.distinctBy { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun ask(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        transcript: String,
        notes: String,
        summary: String,
        attendees: List<String>,
        history: List<Pair<String, String>>,
        question: String
    ): String {
        val systemPrompt = buildString {
            append(
                "You answer questions about one specific meeting, using ONLY the meeting " +
                    "content below. If the answer is not in the meeting, say so briefly. " +
                    "Be concise.\n\n"
            )
            if (attendees.isNotEmpty()) {
                append("Attendees: ").append(attendees.joinToString(", ")).append("\n\n")
            }
            if (summary.isNotBlank()) {
                append("Summary:\n").append(summary.take(6_000)).append("\n\n")
            }
            append("Transcript (lines may be prefixed with the speaker's name):\n")
            append(transcript.take(40_000))
            if (notes.isNotBlank()) {
                append("\n\nUser's notes:\n").append(notes.take(6_000))
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((q, a) in history.takeLast(3)) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    /**
     * Answers a question against several meetings at once. Each context block
     * is (label, content) where the label carries the meeting title + date;
     * the model is instructed to cite which meeting each claim came from.
     */
    fun askLibrary(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        contextBlocks: List<Pair<String, String>>,
        question: String
    ): String {
        val systemPrompt = buildString {
            append(
                "You answer questions using ONLY the meeting records below. " +
                    "Cite the meeting (by its title and date) for every claim, " +
                    "e.g. (Team sync, Jul 3). If the records don't contain the " +
                    "answer, say so briefly. Be concise.\n"
            )
            val perBlock = (30_000 / contextBlocks.size.coerceAtLeast(1))
                .coerceAtLeast(4_000)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(content.take(perBlock)).append("\n")
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", question))
        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    /**
     * Granola-style note enhancement: expands the user's rough in-meeting
     * notes into complete notes, using the transcript for the details the
     * user didn't have time to type. The user's structure and voice win;
     * the transcript only fills in.
     */
    fun enhanceNotes(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        notes: String,
        transcript: String
    ): String {
        val systemPrompt =
            "You polish a user's rough meeting notes into complete notes, using the " +
                "meeting transcript as context. Keep the user's structure, ordering, " +
                "and voice — expand their bullets, never replace them with your own " +
                "outline. Complete half-sentences, expand abbreviations, and fill in " +
                "the specifics the transcript provides (names, numbers, dates, " +
                "decisions). Where the transcript adds real substance to one of the " +
                "user's points, add a short indented sub-bullet. Keep Markdown " +
                "formatting; preserve checklist lines (- [ ] / - [x]) as checklists. " +
                "Never invent content found in neither the notes nor the transcript. " +
                "Reply with ONLY the enhanced notes."
        val userContent = buildString {
            append("My rough notes:\n")
            append(notes.take(8_000))
            append("\n\nMeeting transcript (lines may be prefixed with the speaker's name):\n")
            append(transcript.take(48_000))
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))
        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    /**
     * Live "catch me up": a terse mid-meeting summary a late joiner can scan.
     * Map-reduce is off and the input pre-trimmed to the most recent stretch,
     * so the on-device engine answers in one pass — this runs while the
     * meeting is still happening and latency matters more than coverage.
     */
    fun catchUp(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        transcript: String
    ): String {
        val systemPrompt =
            "A meeting is in progress and the user needs to catch up fast. From the " +
                "transcript so far, reply with 4-8 short bullets: the CURRENT topic " +
                "first, then key decisions, open questions, and action items so far. " +
                "Terse lines, no preamble, no headings."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Transcript so far (most recent last):\n" + transcript.takeLast(9_000)
                )
            )
        return chat(baseUrl, apiKey, model, messages, localOnly, allowMapReduce = false)
    }

    /**
     * Pre-meeting brief for a recurring series: what happened last time and
     * what to walk in ready for. Blocks as in [askLibrary], newest first.
     */
    fun preBrief(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        seriesName: String,
        contextBlocks: List<Pair<String, String>>
    ): String {
        val systemPrompt = buildString {
            append(
                "You prepare the user for the upcoming \"" + seriesName.take(120) +
                    "\" meeting using records of its past occurrences below. " +
                    "Structure the brief as:\n" +
                    "LAST TIME — the key outcomes of the most recent occurrence.\n" +
                    "OPEN ITEMS — unresolved action items and questions, grouped by owner.\n" +
                    "SUGGESTED AGENDA — 3-5 concrete items to raise today.\n" +
                    "Use only the records; be specific and concise.\n"
            )
            val perBlock = (28_000 / contextBlocks.size.coerceAtLeast(1))
                .coerceAtLeast(3_000)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(content.take(perBlock)).append('\n')
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user")
                    .put("content", "Write my pre-meeting brief.")
            )
        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    /** Weekly digest across several meetings; blocks as in [askLibrary]. */
    fun digest(
        baseUrl: String,
        apiKey: String,
        model: String,
        localOnly: Boolean,
        contextBlocks: List<Pair<String, String>>
    ): String {
        val systemPrompt = buildString {
            append(
                "You write a concise weekly digest of the user's meetings from the " +
                    "records below. Structure it as:\n" +
                    "THEMES — the 2-4 threads that ran through the week.\n" +
                    "DECISIONS — what was decided, citing the meeting title.\n" +
                    "OPEN ACTION ITEMS — grouped by owner.\n" +
                    "WORTH REVISITING — unresolved questions or follow-ups to schedule.\n" +
                    "Use only the records; be specific and skip empty sections.\n"
            )
            val perBlock = (28_000 / contextBlocks.size.coerceAtLeast(1))
                .coerceAtLeast(3_000)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(content.take(perBlock)).append('\n')
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user")
                    .put("content", "Write my weekly meeting digest.")
            )
        return chat(baseUrl, apiKey, model, messages, localOnly)
    }

    private fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        localOnly: Boolean,
        allowMapReduce: Boolean = true
    ): String {
        // On-device engine: no endpoint, no socket — straight to llama.cpp.
        if (com.meetily.mobile.llm.LocalLlm.isSelected()) {
            return com.meetily.mobile.llm.LocalLlm.chat(messages, allowMapReduce)
        }
        val vetted = EndpointGuard.vet(baseUrl, localOnly)
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            // Send to the address the guard approved, not to whatever the name
            // resolves to a second time on the way to the socket. Certificate
            // and hostname verification are untouched — see
            // PinnedAddressSocketFactory.
            //
            // https only. Plain HttpURLConnection exposes no socket factory,
            // so a cleartext endpoint still does its own lookup; what protects
            // it is that vet() re-resolved and refused unless every answer was
            // private, which narrows the window to the moment between that
            // check and the connect rather than closing it. Cleartext is
            // already confined to the private network by the guard's first
            // rule, and the common local setups (an Ollama box at
            // http://192.168.x.y or http://localhost) are IP literals that
            // never touch DNS and so have no window at all.
            if (connection is javax.net.ssl.HttpsURLConnection && vetted.pinned.isNotEmpty()) {
                connection.sslSocketFactory = com.meetily.mobile.security
                    .PinnedAddressSocketFactory(connection.sslSocketFactory, vetted.pinned)
            }
            connection.requestMethod = "POST"
            // EndpointGuard vets the URL that was configured; it cannot vet
            // one the server picks afterwards. HttpURLConnection follows
            // redirects by default, so an approved endpoint could 302 the
            // request — transcript and all — anywhere it liked, and "local
            // only" would silently stop meaning anything. A redirect is not
            // something a chat-completions endpoint needs.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

            if (status !in 200..299) {
                throw RuntimeException("LLM endpoint returned HTTP $status: ${response.take(300)}")
            }

            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
                ?: throw RuntimeException("Unexpected response (no choices): ${response.take(300)}")
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            if (content.isBlank()) {
                throw RuntimeException("LLM returned an empty response")
            }
            return content.trim()
        } finally {
            connection.disconnect()
        }
    }
}
