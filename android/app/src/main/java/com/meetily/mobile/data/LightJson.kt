package com.meetily.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses a meeting file into an org.json tree while leaving out the parts a
 * caller does not need, so Meeting.fromJson can still do the decoding.
 *
 * Most of a transcribed meeting's JSON is word timings: every word is its own
 * `[ms,"text"]` array, about nine thousand per recorded hour, and a full parse
 * turns each one into a JSONArray, a boxed Long, a String and a WordStamp. The
 * library list, the follow-ups screen and the reminder re-arm never look at
 * them. Skipped members are walked character by character and never
 * allocated, so the cost of a skipped word array is one pass over its text.
 *
 * Written against JSONTokener rather than android.util.JsonReader so the JVM
 * unit tests exercise the real code. Pure — unit-tested.
 */
internal object LightJson {

    /**
     * [text] as a JSONObject without any member named in [drop], at any depth,
     * and — when [keepTop] is non-null — without top-level members outside it.
     * Throws org.json.JSONException on malformed input, as JSONObject(text) does.
     */
    fun parse(text: String, keepTop: Set<String>?, drop: Set<String>): JSONObject =
        readObject(JSONTokener(text), keepTop, drop)

    private fun readObject(t: JSONTokener, keep: Set<String>?, drop: Set<String>): JSONObject {
        if (t.nextClean() != '{') throw t.syntaxError("Expected {")
        val out = JSONObject()
        if (t.nextClean() == '}') return out
        t.back()
        while (true) {
            if (t.nextClean() != '"') throw t.syntaxError("Expected a key")
            val key = t.nextString('"')
            if (t.nextClean() != ':') throw t.syntaxError("Expected :")
            if (key in drop || (keep != null && key !in keep)) {
                skipValue(t)
            } else {
                out.put(key, readValue(t, drop))
            }
            when (t.nextClean()) {
                ',' -> continue
                '}' -> return out
                else -> throw t.syntaxError("Expected , or }")
            }
        }
    }

    private fun readArray(t: JSONTokener, drop: Set<String>): JSONArray {
        if (t.nextClean() != '[') throw t.syntaxError("Expected [")
        val out = JSONArray()
        if (t.nextClean() == ']') return out
        t.back()
        while (true) {
            out.put(readValue(t, drop))
            when (t.nextClean()) {
                ',' -> continue
                ']' -> return out
                else -> throw t.syntaxError("Expected , or ]")
            }
        }
    }

    private fun readValue(t: JSONTokener, drop: Set<String>): Any {
        val c = t.nextClean()
        t.back()
        return when (c) {
            '{' -> readObject(t, null, drop)
            '[' -> readArray(t, drop)
            // Strings, numbers, true/false/null: the tokenizer's own reading.
            else -> t.nextValue()
        }
    }

    private fun skipValue(t: JSONTokener) {
        when (t.nextClean()) {
            '"' -> skipString(t)
            '{', '[' -> {
                var depth = 1
                while (depth > 0) {
                    when (t.next()) {
                        '"' -> skipString(t)
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                        // next() answers NUL at the end of input; a real NUL
                        // cannot appear here, org.json escapes it.
                        '\u0000' -> throw t.syntaxError("Unterminated value")
                    }
                }
            }
            else -> {
                t.back()
                t.nextValue()
            }
        }
    }

    /** Past the closing quote of a string whose opening quote was just read. */
    private fun skipString(t: JSONTokener) {
        while (true) {
            when (t.next()) {
                '\\' -> t.next()
                '"' -> return
                '\u0000' -> throw t.syntaxError("Unterminated string")
            }
        }
    }
}
