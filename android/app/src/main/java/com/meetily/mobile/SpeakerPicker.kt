package com.meetily.mobile

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * Dialog for tagging a transcript segment with a speaker. Offers the meeting's
 * attendees, a free-form "someone else" entry, and removal of the current tag.
 * The picked name (or null to clear) is delivered via [onPicked].
 */
object SpeakerPicker {

    fun show(
        context: Context,
        attendees: List<String>,
        currentSpeaker: String?,
        onPicked: (String?) -> Unit
    ) {
        val options = mutableListOf<String>()
        options.addAll(attendees)
        options.add(context.getString(R.string.someone_else))
        if (!currentSpeaker.isNullOrBlank()) {
            options.add(context.getString(R.string.remove_speaker))
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.assign_speaker_title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which < attendees.size -> onPicked(attendees[which])
                    which == attendees.size -> promptForName(context, onPicked)
                    else -> onPicked(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptForName(context: Context, onPicked: (String?) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = context.getString(R.string.speaker_name_hint)
        }
        val container = FrameLayout(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.assign_speaker_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) onPicked(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
