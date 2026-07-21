package com.meetily.mobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Simple file-backed store: one JSON file per meeting under filesDir/meetings.
 * Everything stays on-device, matching Meetily's privacy-first design.
 */
class MeetingStore(context: Context) {

    private val dir: File = File(context.filesDir, "meetings").apply { mkdirs() }

    fun list(): List<Meeting> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                Meeting.fromJson(JSONObject(file.readText()))
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAtMs }
    }

    fun load(id: String): Meeting? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return try {
            Meeting.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun save(meeting: Meeting) {
        val file = File(dir, "${meeting.id}.json")
        val tmp = File(dir, "${meeting.id}.json.tmp")
        tmp.writeText(meeting.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.writeText(meeting.toJson().toString())
            tmp.delete()
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
