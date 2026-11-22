package com.meetily.mobile

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import java.util.concurrent.Executor

/**
 * Keeps the meeting screen's read of its file across a rotation (see
 * RetainedLoad for the rules).
 *
 * The worker holds this model and the store, never a screen, so a screen
 * rotated away or closed mid-read is released at once, and the result goes
 * to whichever screen is attached when it lands. Cleared with the screen
 * for good (back, finish), after which a read still running is dropped.
 */
class MeetingLoadModel : ViewModel() {

    val load = RetainedLoad<Meeting, Meeting>()

    private val main = Handler(Looper.getMainLooper())

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
        val ticket = load.attach(id, listener) ?: return
        val onHeader: ((Meeting) -> Unit)? = if (wantHeader) {
            { header -> main.post { load.header(ticket, header) } }
        } else {
            null
        }
        executor.execute {
            val read = try {
                store.loadStaged(id, onHeader)
            } catch (_: Throwable) {
                // Out of memory on a huge file, say: the screen says it
                // cannot open the meeting rather than waiting for good.
                null
            }
            main.post { load.finish(ticket, read) }
        }
    }

    override fun onCleared() {
        load.clear()
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
