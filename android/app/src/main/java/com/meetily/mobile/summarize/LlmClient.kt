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
