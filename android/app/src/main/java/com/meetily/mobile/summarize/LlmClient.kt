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

    private const val SYSTEM_PROMPT =
        "You are a meeting assistant. Summarize the meeting transcript into: " +
            "1) a short overview paragraph, 2) key discussion points as bullets, " +
            "3) decisions made, 4) action items with owners if mentioned. " +
            "Be concise and factual; incorporate the user's own notes where relevant."

    fun summarize(
        baseUrl: String,
        apiKey: String,
        model: String,
        transcript: String,
        notes: String,
        attendees: List<String> = emptyList()
    ): String {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val userContent = buildString {
            if (attendees.isNotEmpty()) {
                append("Meeting attendees: ")
                append(attendees.joinToString(", "))
                append("\n\n")
            }
            append("Meeting transcript (lines may be prefixed with the speaker's name):\n")
            append(transcript.take(48_000))
            if (notes.isNotBlank()) {
                append("\n\nMy notes during the meeting:\n")
                append(notes.take(8_000))
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", userContent))

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
                throw RuntimeException("LLM returned an empty summary")
            }
            return content.trim()
        } finally {
            connection.disconnect()
        }
    }
}
