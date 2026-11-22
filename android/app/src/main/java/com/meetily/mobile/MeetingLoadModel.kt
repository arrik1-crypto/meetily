package com.meetily.mobile

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.OpenMeetings
import java.util.concurrent.Executor

/**
 * Keeps the meeting screen's read of its file across a rotation (see
 * RetainedLoad for the rules).
 *
 * The worker holds this model and the store, never a screen, so a screen
 * rotated away or closed mid-read is released at once, and the result goes
 * to whichever screen is attached when it lands. Closed with the screen for
 * good (back, finish: [close], then onCleared), after which a read still
 * running is dropped and one not yet past its next stage stops there
 * (MeetingStore.loadStaged). A rotation does not clear the model, so a read
 * it keeps carries on.
 */
class MeetingLoadModel : ViewModel() {

    val load = RetainedLoad<Meeting, Meeting>()

    private val main = Handler(Looper.getMainLooper())

    /**
     * Set once the screen has gone for good. The read runs on the worker
     * every meeting screen reads and saves on, so a closed screen's read is
     * stopped rather than finished: the next screen opened would otherwise
     * wait behind its whole parse before even its own title could be read.
     */
    @Volatile
    private var cleared = false

    /**
     * The meeting this screen has open (OpenMeetings), from its first open
     * until the screen goes for good, so the automatic clean-up leaves a
     * meeting being read or edited alone. Held here rather than by the
     * screen so a rotation never lets go of it, even for a moment.
     */
    private var held: String? = null

    /** The worker the screen saves on; the hold is let go behind its saves. */
    private var heldSaves: Executor? = null

    /**
     * Attaches [listener] to the read of meeting [id], starting the read on
     * [executor] unless one is already running. With [wantHeader] the
     * listener first gets a display-only copy with the title and date, read
     * from the start of the file. Main thread only.
     */
    fun open(
        id: String,
        store: MeetingStore,
        executor: Executor,
        wantHeader: Boolean,
        listener: RetainedLoad.Listener<Meeting, Meeting>
    ) {
        if (held == null && !cleared) {
            OpenMeetings.hold(id)
            held = id
            heldSaves = executor
        }
        val ticket = load.attach(id, listener) ?: return
        val onHeader: ((Meeting) -> Unit)? = if (wantHeader) {
            { header -> main.post { load.header(ticket, header) } }
        } else {
            null
        }
        executor.execute {
            // Still queued behind other work when the screen closed.
            if (cleared) return@execute
            val read = try {
                store.loadStaged(id, onHeader) { cleared }
            } catch (_: Throwable) {
                // Out of memory on a huge file, say: the screen says it
                // cannot open the meeting rather than waiting for good.
                null
            }
            if (cleared) return@execute
            main.post { load.finish(ticket, read) }
        }
    }

    /**
     * The screen is going for good (finishing). Stops its read at once
     * rather than at onCleared, which comes only once the screen it returns
     * to has settled: a parse that has not reached its next stage by then
     * is not done at all. Main thread only.
     */
    fun close() {
        cleared = true
        load.clear()
    }

    override fun onCleared() {
        close()
        val id = held ?: return
        held = null
        // Behind the saves the screen queued on its way out (onPause, onStop),
        // which run on that same worker: until they land, the stored meeting
        // is older than what was edited on screen.
        val saves = heldSaves
        heldSaves = null
        try {
            saves?.execute { OpenMeetings.release(id) } ?: OpenMeetings.release(id)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            OpenMeetings.release(id)
        }
    }

    companion object {
        /** Builds the model directly, without the default factory's reflection. */
        val FACTORY: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MeetingLoadModel() as T
        }
    }
}
