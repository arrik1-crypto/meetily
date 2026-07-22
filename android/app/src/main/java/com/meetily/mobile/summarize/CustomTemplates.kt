package com.meetily.mobile.summarize

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined summary templates: a name plus free-form instructions, stored
 * on-device in SharedPreferences as JSON. They appear in the template picker
 * alongside the built-ins.
 */
object CustomTemplates {

    data class Custom(val key: String, val name: String, val instructions: String)

    private const val PREFS = "custom_templates"
    private const val KEY_JSON = "templates_json"

    fun load(context: Context): List<Custom> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Custom>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("key", "")
                val name = obj.optString("name", "")
                val instructions = obj.optString("instructions", "")
                if (key.isNotBlank() && name.isNotBlank() && instructions.isNotBlank()) {
                    list.add(Custom(key, name, instructions))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, name: String, instructions: String): Custom {
        val custom = Custom(
            key = "custom_${System.currentTimeMillis()}",
            name = name.trim(),
            instructions = instructions.trim()
        )
        save(context, load(context) + custom)
        return custom
    }

    fun delete(context: Context, key: String) {
        save(context, load(context).filterNot { it.key == key })
    }

    private fun save(context: Context, templates: List<Custom>) {
        val arr = JSONArray()
        for (t in templates) {
            arr.put(
                JSONObject()
                    .put("key", t.key)
                    .put("name", t.name)
                    .put("instructions", t.instructions)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, arr.toString())
            .apply()
    }
}
