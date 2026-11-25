package com.meetily.mobile.data

/**
 * Ticks an action item off from a list built across meetings: Follow-ups
 * and the pre-meeting brief, whose rows are display copies of many meetings.
 *
 * Both used to load the meeting, change it and save it in two steps on the
 * main thread. Anything written to that meeting in between (a summary
 * finishing, a transcription batch, a re-timing pass) was put back by the
 * older copy, which is the lost update [MeetingStore.mutate] exists to
 * prevent. Blocking: call it on a worker thread.
 */
object CompleteActionItem {

    /**
     * Where the row's item is in [items]: at [index] while that is still the
     * same task, otherwise the first open item with that text (the list may
     * have changed since the row was drawn); -1 when it is gone.
     */
    fun indexIn(items: List<ActionItem>, index: Int, task: String): Int = when {
        index in items.indices && items[index].task == task -> index
        else -> items.indexOfFirst { it.task == task && !it.done }
    }

    /**
     * Marks the item done in meeting [meetingId], under the store's lock.
     * True when it was found with a reminder set and the meeting was
     * written, so the caller cancels that reminder's alarm.
     */
    fun markDone(store: MeetingStore, meetingId: String, index: Int, task: String): Boolean {
        var hadReminder = false
        val written = store.mutateIf(meetingId) { meeting ->
            val at = indexIn(meeting.actionItems, index, task)
            if (at < 0) return@mutateIf false
            val item = meeting.actionItems[at]
            hadReminder = item.remindAtMs != null
            if (item.done) return@mutateIf false
            meeting.actionItems[at] = item.copy(done = true)
            true
        }
        return written && hadReminder
    }
}
