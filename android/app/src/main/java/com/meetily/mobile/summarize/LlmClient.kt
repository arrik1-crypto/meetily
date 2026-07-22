package com.meetily.mobile.summarize

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
        transcript: String,
        notes: String,
        attendees: List<String> = emptyList(),
        highlights: List<String> = emptyList(),
        template: SummaryTemplate = SummaryTemplates.ALL.first()
    ): String {
        val systemPrompt = "You are a meeting assistant. " + template.llmInstructions +
            " Be concise and factual; incorporate the user's own notes and highlighted " +
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

        return chat(baseUrl, apiKey, model, messages)
    }

    fun title(
        baseUrl: String,
        apiKey: String,
        model: String,
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
        return chat(baseUrl, apiKey, model, messages)
            .trim().trim('"', '\'').take(80)
    }

    /**
     * Content-based speaker attribution: asks the LLM to label untagged
     * transcript lines from conversational context (names addressed,
     * self-references, role cues). Already-tagged lines are shown as anchors
     * and must not be relabeled. Returns (lineIndex, speakerName) pairs.
     */
    fun suggestSpeakers(
        baseUrl: String,
        apiKey: String,
        model: String,
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

        val response = chat(baseUrl, apiKey, model, messages)
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

    fun ask(
        baseUrl: String,
        apiKey: String,
        model: String,
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

        return chat(baseUrl, apiKey, model, messages)
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
        return chat(baseUrl, apiKey, model, messages)
    }

    /** Weekly digest across several meetings; blocks as in [askLibrary]. */
    fun digest(
        baseUrl: String,
        apiKey: String,
        model: String,
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
        return chat(baseUrl, apiKey, model, messages)
    }

    private fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray
    ): String {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
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
