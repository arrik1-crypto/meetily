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
 *
 * When the segment carries an auto-detected cluster label ("Speaker 2"), an
 * extra "Name Speaker 2 everywhere…" entry renames the whole cluster at once
 * via [onRenameCluster].
 */
object SpeakerPicker {

    fun show(
        context: Context,
        attendees: List<String>,
        currentSpeaker: String?,
        clusterLabel: String? = null,
        onRenameCluster: ((String) -> Unit)? = null,
        onPlayFrom: (() -> Unit)? = null,
        onEditText: (() -> Unit)? = null,
        onSplit: (() -> Unit)? = null,
        onPicked: (String?) -> Unit
    ) {
        val options = mutableListOf<String>()
        val playIndex: Int
        if (onPlayFrom != null) {
            playIndex = 0
            options.add(context.getString(R.string.play_from_here))
        } else {
            playIndex = -1
        }
        val attendeesStart = options.size
        options.addAll(attendees)
        options.add(context.getString(R.string.someone_else))
        val renameIndex: Int
        if (clusterLabel != null && onRenameCluster != null) {
            renameIndex = options.size
            options.add(context.getString(R.string.rename_cluster_fmt, clusterLabel))
        } else {
            renameIndex = -1
        }
        val editIndex: Int
        if (onEditText != null) {
            editIndex = options.size
            options.add(context.getString(R.string.edit_text_action))
        } else {
            editIndex = -1
        }
        val splitIndex: Int
        if (onSplit != null) {
            splitIndex = options.size
            options.add(context.getString(R.string.split_action))
        } else {
            splitIndex = -1
        }
        val removeIndex: Int
        if (!currentSpeaker.isNullOrBlank()) {
            removeIndex = options.size
            options.add(context.getString(R.string.remove_speaker))
        } else {
            removeIndex = -1
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.assign_speaker_title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == playIndex && onPlayFrom != null -> onPlayFrom()
                    which < attendeesStart + attendees.size ->
                        onPicked(attendees[which - attendeesStart])
                    which == attendeesStart + attendees.size ->
                        promptForName(context) { onPicked(it) }
                    which == renameIndex && onRenameCluster != null ->
                        promptForName(context) { onRenameCluster(it) }
                    which == editIndex && onEditText != null -> onEditText()
                    which == splitIndex && onSplit != null -> onSplit()
                    which == removeIndex -> onPicked(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptForName(context: Context, onName: (String) -> Unit) {
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
                if (name.isNotBlank()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
