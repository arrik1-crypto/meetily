package com.meetily.mobile.reminders

import android.content.Context
import com.meetily.mobile.data.CalendarHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * The set of calendar events the app currently has alarms armed for, kept
 * only so the calendar diagnostics screen can show it. Without this, "is a
 * nudge actually armed for my next meeting?" is unanswerable from inside
 * the app, which is what made the Outlook misses hard to pin down.
 */
object NudgeState {

    private const val PREFS = "nudge_state"
    private const val KEY_ARMED = "armed"
    private const val KEY_UPDATED = "updated_at"
    private const val KEY_POSTED = "posted"

    /** Posted-nudge memory horizon; entries older than this are pruned. */
    private const val POSTED_TTL_MS = 6L * 60 * 60 * 1000

    data class Armed(val title: String, val beginMs: Long)

    /**
     * True when a nudge for [key] has already been posted recently.
     *
     * Mandatory companion to the widened acceptance window and the periodic
     * re-arm: without it, a meeting could be nudged more than once, trading
     * "misses nudges" for "spams nudges".
     */
    @Synchronized
    fun alreadyPosted(context: Context, key: String): Boolean =
        postedMap(context).has(key)

    @Synchronized
    fun markPosted(context: Context, key: String) {
        val now = System.currentTimeMillis()
        val map = postedMap(context)
        val out = JSONObject()
        // Prune while rewriting, so this never grows without bound.
        for (existing in map.keys()) {
            val at = map.obj.optLong(existing)
            if (at > 0 && now - at < POSTED_TTL_MS) out.put(existing, at)
        }
        out.put(key, now)
        prefs(context).edit().putString(KEY_POSTED, out.toString()).apply()
    }

    private class PostedMap(val obj: JSONObject) {
        fun has(key: String): Boolean {
            val at = obj.optLong(key)
            return at > 0 && System.currentTimeMillis() - at < POSTED_TTL_MS
        }

        fun keys(): List<String> {
            val out = mutableListOf<String>()
            val it = obj.keys()
            while (it.hasNext()) out.add(it.next())
            return out
        }
    }

    private fun postedMap(context: Context): PostedMap = try {
        PostedMap(JSONObject(prefs(context).getString(KEY_POSTED, "{}") ?: "{}"))
    } catch (_: Exception) {
        PostedMap(JSONObject())
    }

    fun save(context: Context, events: List<CalendarHelper.CalendarEvent>) {
        val arr = JSONArray()
        for (event in events) {
            arr.put(
                JSONObject()
                    .put("title", event.title)
                    .put("begin", event.beginMs)
            )
        }
        prefs(context).edit()
            .putString(KEY_ARMED, arr.toString())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ARMED)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun armed(context: Context): List<Armed> = try {
        val arr = JSONArray(prefs(context).getString(KEY_ARMED, "[]") ?: "[]")
        val out = mutableListOf<Armed>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val title = obj.optString("title").trim()
            val begin = obj.optLong("begin")
            if (title.isNotBlank() && begin > 0) out.add(Armed(title, begin))
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    /** When the armed set was last recomputed (0 = never). */
    fun lastUpdatedMs(context: Context): Long =
        prefs(context).getLong(KEY_UPDATED, 0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
