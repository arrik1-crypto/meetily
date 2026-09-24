package com.meetily.mobile.summarize

import com.meetily.mobile.llm.LocalLlm
import com.meetily.mobile.llm.PromptShaping
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

        val body = transcriptFor(transcript, NETWORK_TRANSCRIPT_CHARS)
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
            append(body)
            if (notes.isNotBlank()) {
                append("\n\nMy notes during the meeting:\n")
                append(notes.take(8_000))
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))

        return chat(baseUrl, apiKey, model, messages, localOnly, payload = body)
    }

    /**
     * The transcript as it should go into a prompt.
     *
     * On-device it is passed WHOLE: LocalLlm map-reduces it (as the named
     * payload) to cover the full meeting in bounded passes. A network
     * endpoint gets a head+tail cut with an omission marker instead of the
     * old head-only `take()`, which silently dropped the end of any meeting
     * over about fifty minutes — the wrap-up, where decisions and owners
     * usually are.
     */
    private fun transcriptFor(transcript: String, networkChars: Int): String =
        if (LocalLlm.isSelected()) transcript
        else PromptShaping.excerpt(transcript, networkChars)

    private const val NETWORK_TRANSCRIPT_CHARS = 48_000

    /**
     * Transcript excerpt for a 3-6 word title. A title needs the gist, not
     * coverage, so no map-reduce: on-device that was several full passes to
     * produce a handful of words.
     */
    private const val TITLE_LOCAL_CHARS = 8_000
    private const val TITLE_NETWORK_CHARS = 20_000

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
                    "Transcript:\n" + PromptShaping.excerpt(
                        transcript,
                        if (LocalLlm.isSelected()) TITLE_LOCAL_CHARS else TITLE_NETWORK_CHARS
                    ) +
                        if (notes.isNotBlank()) "\n\nNotes:\n" + notes.take(3_000) else ""
                )
            )
        return chat(baseUrl, apiKey, model, messages, localOnly, allowMapReduce = false)
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
        // A labeled line is sent as "$i [$speaker]: ", so charge the label
        // too; the +8 in chapterWindows only covers the bare "$i: ".
        val windows = chapterWindows(
            lines.map { (text, speaker) ->
                text.length + (if (speaker.isNullOrBlank()) 0 else speaker.length + 3)
            },
            windowBudget(speakerSystemPrompt(attendees).length)
        )
        val collected = mutableListOf<Pair<Int, String>>()
        for ((index, range) in windows.withIndex()) {
            onWindow?.invoke(index + 1, windows.size)
            val slice = lines.subList(range.first, range.last + 1)
            val part = suggestSpeakers(baseUrl, apiKey, model, localOnly, slice, attendees)
            for ((line, name) in part) {
                // Checked against the WINDOW: the prompt numbered it from 0,
                // so a line past its end is a model error, and shifting it
                // would land it on a real line in the next window — where,
                // coming first, it would also beat that window's own answer.
                if (line in slice.indices) collected.add(range.first + line to name)
            }
        }
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
        val systemPrompt = speakerSystemPrompt(attendees)
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
        val arr = parseJsonArray(response) ?: return emptyList()
        return try {
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

    /**
     * The JSON array in a model reply, or null when there is none.
     *
     * Anchors on "[{" so prose brackets ("[high-confidence]") can't hijack
     * the extraction, falling back to the first '[' for a bare "[]" answer.
     * A reply cut off mid-array by the reply budget has no closing ']'; it
     * is closed after its last complete object, so the attributions that
     * did finish are kept rather than the whole window reading as none.
     */
    private fun parseJsonArray(response: String): JSONArray? {
        val start = response.indexOf("[{").takeIf { it >= 0 } ?: response.indexOf('[')
        if (start < 0) return null
        val end = response.lastIndexOf(']')
        if (end > start) {
            try {
                return JSONArray(response.substring(start, end + 1))
            } catch (_: Exception) {
                // Fall through: the ']' may belong to text inside a cut-off array.
            }
        }
        val lastObject = response.lastIndexOf('}')
        if (lastObject <= start) return null
        return try {
            JSONArray(response.substring(start, lastObject + 1) + "]")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Characters of transcript per speaker/chapter window, given the fixed
     * prompt text that shares the context with it.
     *
     * [CHAPTER_WINDOW_CHARS] suits network models, but it is larger than the
     * on-device engine's whole prompt budget: every window lost its middle
     * fifth to trimming, and those lines were never attributed or chaptered.
     * Map-reduce is off for these calls, so the window itself has to fit —
     * with a margin, because a transcript tokenizes worse than the estimate.
     */
    private fun windowBudget(fixedChars: Int): Int =
        if (LocalLlm.isSelected()) {
            ((LocalLlm.CHAR_BUDGET - fixedChars - 200) * 85 / 100).coerceAtLeast(2_000)
        } else {
            CHAPTER_WINDOW_CHARS
        }

    private fun speakerSystemPrompt(attendees: List<String>): String = buildString {
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

    /** Characters per speaker/chapter window for network models; see [windowBudget]. */
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
        val windows = chapterWindows(
            lines.map { it.length }, windowBudget(CHAPTERS_PROMPT.length)
        )
        val collected = mutableListOf<Pair<Int, String>>()
        for ((index, range) in windows.withIndex()) {
            onWindow?.invoke(index + 1, windows.size)
            val slice = lines.subList(range.first, range.last + 1)
            val part = chapters(baseUrl, apiKey, model, localOnly, slice)
            for ((line, title) in part) {
                // Window-relative, as in suggestSpeakersWindowed.
                if (line in slice.indices) collected.add(range.first + line to title)
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
        val systemPrompt = CHAPTERS_PROMPT
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
        val arr = parseJsonArray(response) ?: return emptyList()
        return try {
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

    private const val CHAPTERS_PROMPT =
        "You split a meeting transcript into topical chapters. Reply with " +
            "ONLY a JSON array; each element is {\"line\": <0-based line " +
            "number where the topic starts>, \"title\": \"<2-5 word " +
            "chapter title>\"}. The first chapter must start at line 0. " +
            "Mark only clear topic shifts — typically 2 to 8 chapters for " +
            "a full meeting."

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
        // On-device the whole transcript is the map-reduce payload (cached per
        // transcript, so follow-up questions skip the condensing), and the
        // summary and notes around it are held shorter: they share a far
        // smaller window with the condensed notes.
        val onDevice = LocalLlm.isSelected()
        val body = transcriptFor(transcript, 40_000)
        val sideChars = if (onDevice) 3_000 else 6_000
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
                append("Summary:\n").append(summary.take(sideChars)).append("\n\n")
            }
            append("Transcript (lines may be prefixed with the speaker's name):\n")
            append(body)
            if (notes.isNotBlank()) {
                append("\n\nUser's notes:\n").append(notes.take(sideChars))
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((q, a) in history.takeLast(3)) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        return chat(baseUrl, apiKey, model, messages, localOnly, payload = body)
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
        val instructions =
            "You answer questions using ONLY the meeting records below. " +
                "Cite the meeting (by its title and date) for every claim, " +
                "e.g. (Team sync, Jul 3). If the records don't contain the " +
                "answer, say so briefly. Be concise.\n"
        val perBlock = perBlockChars(
            contextBlocks, instructions.length + question.length, 30_000, 4_000
        )
        val systemPrompt = buildString {
            append(instructions)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(blockText(content, perBlock)).append("\n")
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
        // Only the transcript is condensable: the user's notes are what is
        // being enhanced, and must reach the final pass verbatim.
        val body = transcriptFor(transcript, NETWORK_TRANSCRIPT_CHARS)
        val userContent = buildString {
            append("My rough notes:\n")
            append(notes.take(8_000))
            append("\n\nMeeting transcript (lines may be prefixed with the speaker's name):\n")
            append(body)
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))
        return chat(baseUrl, apiKey, model, messages, localOnly, payload = body)
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
        val instructions =
            "You prepare the user for the upcoming \"" + seriesName.take(120) +
                "\" meeting using records of its past occurrences below. " +
                "Structure the brief as:\n" +
                "LAST TIME — the key outcomes of the most recent occurrence.\n" +
                "OPEN ITEMS — unresolved action items and questions, grouped by owner.\n" +
                "SUGGESTED AGENDA — 3-5 concrete items to raise today.\n" +
                "Use only the records; be specific and concise.\n"
        val perBlock = perBlockChars(contextBlocks, instructions.length + 40, 28_000, 3_000)
        val systemPrompt = buildString {
            append(instructions)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(blockText(content, perBlock)).append('\n')
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
        val instructions =
            "You write a concise weekly digest of the user's meetings from the " +
                "records below. Structure it as:\n" +
                "THEMES — the 2-4 threads that ran through the week.\n" +
                "DECISIONS — what was decided, citing the meeting title.\n" +
                "OPEN ACTION ITEMS — grouped by owner.\n" +
                "WORTH REVISITING — unresolved questions or follow-ups to schedule.\n" +
                "Use only the records; be specific and skip empty sections.\n"
        val perBlock = perBlockChars(contextBlocks, instructions.length + 40, 28_000, 3_000)
        val systemPrompt = buildString {
            append(instructions)
            for ((label, content) in contextBlocks) {
                append("\n=== MEETING: ").append(label).append(" ===\n")
                append(blockText(content, perBlock)).append('\n')
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

    /**
     * Characters each meeting record may use in a multi-meeting prompt
     * (Ask Library, brief, digest).
     *
     * On-device these prompts are sent in ONE pass with every block cut to
     * fit, rather than map-reduced: condensing treated the instructions, the
     * "=== MEETING ===" labels and the required structure as transcript, so
     * citations and format were lost, and the passes cost minutes. Callers
     * put the densest material (matching moments, the summary) first, so a
     * block's head carries the most per character.
     * [fixedChars] is the instruction and question text sharing the window;
     * the margin allows for a transcript tokenizing worse than the estimate.
     */
    private fun perBlockChars(
        blocks: List<Pair<String, String>>,
        fixedChars: Int,
        networkTotal: Int,
        networkFloor: Int
    ): Int {
        val count = blocks.size.coerceAtLeast(1)
        if (!LocalLlm.isSelected()) {
            return (networkTotal / count).coerceAtLeast(networkFloor)
        }
        val labels = blocks.sumOf { it.first.length + 20 }
        return ((LocalLlm.CHAR_BUDGET * 85 / 100 - fixedChars - labels) / count)
            .coerceAtLeast(300)
    }

    /**
     * One record cut to [perBlock]. On-device the share is small, so it keeps
     * head AND tail: the head carries the summary, the tail the open action
     * items and highlights that a head-only cut would drop first.
     */
    private fun blockText(content: String, perBlock: Int): String =
        if (LocalLlm.isSelected()) PromptShaping.excerpt(content, perBlock)
        else content.take(perBlock)

    private fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        localOnly: Boolean,
        allowMapReduce: Boolean = true,
        /** The transcript text inside [messages]; see LocalLlm.chat. */
        payload: String? = null
    ): String {
        // On-device: no endpoint, no socket. Returns before EndpointGuard
        // because llama.cpp answers over JNI and never opens one.
        if (com.meetily.mobile.llm.LocalLlm.isSelected()) {
            return com.meetily.mobile.llm.LocalLlm.chat(messages, allowMapReduce, payload)
        }
        val vetted = EndpointGuard.vet(baseUrl, localOnly)
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)

        // A constrained endpoint (http, or https with Local-only on) was
        // vetted as private, so it is connected to DIRECTLY. Through the
        // system or Wi-Fi proxy, a cleartext request — transcript and API key
        // — went to the proxy host, which the guard never looked at; and
        // https failed outright, because the pin saw the proxy's address.
        // Cloud endpoints keep the proxy: corporate networks may need it.
        val constrained = vetted.pinned.isNotEmpty()
        val connection = (
            if (constrained) URL(endpoint).openConnection(java.net.Proxy.NO_PROXY)
            else URL(endpoint).openConnection()
            ) as HttpURLConnection
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
            // The request is not streamed, so nothing arrives until the whole
            // reply is written, and this bounds prompt evaluation plus
            // generation together. A CPU-only home server routinely needs
            // more than three minutes for a long meeting; a private endpoint
            // gets the time, while cloud endpoints keep the shorter limit.
            connection.readTimeout = if (constrained) PRIVATE_READ_TIMEOUT_MS else 180_000
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
            // Reasoning models behind some servers put their <think> block
            // in the content itself. Stripped here as on the device path, or
            // the deliberation becomes the saved summary or meeting title.
            if (PromptShaping.thinkingRanOver(content)) {
                throw RuntimeException(
                    "The model spent its whole reply reasoning and never answered"
                )
            }
            val answer = PromptShaping.stripThinking(content)
            if (answer.isBlank()) {
                throw RuntimeException("LLM returned an empty response")
            }
            return answer
        } finally {
            connection.disconnect()
        }
    }

    private const val PRIVATE_READ_TIMEOUT_MS = 15 * 60 * 1000
}
