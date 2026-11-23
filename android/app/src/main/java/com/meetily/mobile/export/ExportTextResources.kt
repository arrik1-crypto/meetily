package com.meetily.mobile.export

import android.content.Context
import com.meetily.mobile.R

/** [ExportText] from strings_exporttext.xml, in the app's current language. */
object ExportTextResources {

    fun load(context: Context): ExportText = ExportText(
        summary = context.getString(R.string.exporttext_summary),
        actionItems = context.getString(R.string.exporttext_action_items),
        notes = context.getString(R.string.exporttext_notes),
        transcript = context.getString(R.string.exporttext_transcript),
        date = context.getString(R.string.exporttext_date),
        duration = context.getString(R.string.exporttext_duration),
        attendees = context.getString(R.string.exporttext_attendees),
        tags = context.getString(R.string.exporttext_tags),
        untitled = context.getString(R.string.exporttext_untitled),
        // Read without arguments, so the %1$d stays for ExportText.count.
        photosMarkdown = context.getString(R.string.exporttext_photos_markdown),
        photosWord = context.getString(R.string.exporttext_photos_word),
        fromMeeting = context.getString(R.string.exporttext_from_meeting)
    )
}
