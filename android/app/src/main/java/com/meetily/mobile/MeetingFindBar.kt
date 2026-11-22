package com.meetily.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.meetily.mobile.search.FindInMeeting
import com.meetily.mobile.search.FindInMeeting.Anchor
import com.meetily.mobile.search.MatchSnippet
import com.meetily.mobile.search.TextFold
import java.util.concurrent.Executors

/**
 * The meeting screen's "Find in meeting" bar (moment_find_bar.xml): a
 * field, "3 of 12", previous and next. Every match is marked in the
 * transcript as its line is drawn (TranscriptLinesAdapter.setFind), and
 * previous and next go through them by the screen's expand-then-scroll path
 * ([Host.findReveal]). Back closes it.
 *
 * Matching is FindInMeeting's, case- and accent-insensitive, and runs off
 * the main thread: a two-hour transcript is folded once per change to it,
 * then searched again per keystroke.
 *
 * This bar only finds. Find & replace is a separate dialog with its own
 * functions.
 */
class MeetingFindBar(
    private val activity: AppCompatActivity,
    private val adapter: TranscriptLinesAdapter,
    private val host: Host
) {

    /** What the bar needs from the meeting screen. Main thread. */
    interface Host {
        /** The transcript's line texts, by segment; null before the meeting is read. */
        fun findTexts(): List<String>?

        /** The first transcript line on screen, where a new search starts. */
        fun findFirstVisibleSegment(): Int

        /**
         * Brings line [segment] on screen: the transcript tab, its chapter
         * expanded, scrolled to. [landing] is opening at a search hit, which
         * also marks the line for a moment and gives it TalkBack's focus.
         */
        fun findReveal(segment: Int, landing: Boolean)

        /** Where line [segment] is, for a screen reader: "0:42:10, Ana". */
        fun findLineLabel(segment: Int): String
    }

    private val bar: View = activity.findViewById(R.id.momentFindBar)
    private val input: EditText = activity.findViewById(R.id.momentFindInput)
    private val count: TextView = activity.findViewById(R.id.momentFindCount)
    private val previousButton: View = activity.findViewById(R.id.momentFindPrevious)
    private val nextButton: View = activity.findViewById(R.id.momentFindNext)
    private val handler = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = bar.visibility == View.VISIBLE

    /** What the open bar is finding, for Find & replace to start from; null while closed. */
    val query: String? get() = if (isOpen) input.text.toString() else null

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    /** Why a search ran, which decides where the bar goes and what is said. */
    private enum class Reason {
        /** The query changed: to the first match from the line on screen, count spoken. */
        TYPED,

        /** Next or previous after the transcript changed: to that match, line spoken. */
        STEP,

        /** Opened at a search hit: to its line, marked and focused. */
        LANDING,

        /** The transcript changed, or the screen was re-created: marks redrawn, nothing moves. */
        QUIET
    }

    private var result = FindInMeeting.Result(emptyList(), capped = false)
    private var current = -1
    private var searchedQuery = ""

    /** The texts [index] was folded from, to tell when the transcript has changed. */
    private var indexTexts: List<String>? = null
    private var index: List<TextFold.Folded>? = null

    /** Bumped per search and on close, so only the newest result is shown. */
    private var generation = 0
    private var released = false
    private var ignoreEdits = false

    /**
     * A landing ([open] with a query) whose search has not come back yet,
     * and the line it goes to when its words are no longer found. Saved
     * with the state, so a screen re-created in between still lands.
     */
    private var landing = false
    private var landingSegment = -1

    private val typed = Runnable { search(Reason.TYPED, anchorFromScreen()) }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        adapter.findMatchColor = ContextCompat.getColor(activity, R.color.moment_find_match)
        adapter.findCurrentColor = ContextCompat.getColor(activity, R.color.moment_find_current)
        adapter.findCurrentTextColor =
            ContextCompat.getColor(activity, R.color.moment_find_current_text)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreEdits) return
                handler.removeCallbacks(typed)
                handler.postDelayed(typed, TYPING_PAUSE_MS)
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                step(forward = true)
                true
            } else {
                false
            }
        }
        previousButton.setOnClickListener { step(forward = false) }
        nextButton.setOnClickListener { step(forward = true) }
        activity.findViewById<View>(R.id.momentFindClose).setOnClickListener { close() }
        setStepping(false)
    }

    /**
     * Shows the bar. With [query], searches for it at once; [landOn] is the
     * line a search hit opened the screen at (-1 for none), which the bar
     * goes to, or to the nearest line still holding the words. Without, the
     * last query comes back selected, to type over.
     */
    fun open(query: String? = null, focusField: Boolean = true, landOn: Int = -1) {
        if (released) return
        bar.visibility = View.VISIBLE
        back.isEnabled = true
        if (query != null) {
            setQuery(query)
            land(landOn)
        } else if (input.text.isNotEmpty()) {
            input.selectAll()
            search(Reason.TYPED, anchorFromScreen())
        }
        if (focusField) {
            input.requestFocus()
            // Posted: the field has to be shown and focused before the
            // keyboard will come up for it.
            input.post {
                if (isOpen && input.hasFocus()) {
                    activity.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    /** Hides the bar and its marks. The query stays, for the next open. */
    fun close() {
        if (!isOpen) return
        handler.removeCallbacks(typed)
        generation++
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        bar.visibility = View.GONE
        back.isEnabled = false
        landing = false
        result = FindInMeeting.Result(emptyList(), capped = false)
        current = -1
        searchedQuery = ""
        // The folded transcript is only worth its memory while finding.
        index = null
        indexTexts = null
        adapter.setFind(null, -1, -1)
        renderCount(Speak.NONE)
    }

    /**
     * The transcript was rendered again: an edit, a late line, an accepted
     * check, a replace. Counts and marks follow it; nothing scrolls.
     */
    fun transcriptChanged() {
        if (!isOpen || searchedQuery.isBlank()) return
        val texts = host.findTexts() ?: return
        if (texts == indexTexts) return
        val at = result.matches.getOrNull(current)
        search(Reason.QUIET, if (at == null) Anchor.AtOrAfter(0, 0) else Anchor.AtOrAfter(at.segment, at.start))
    }

    /** The open bar's query and match, for the screen after a re-creation; null when closed. */
    fun saveState(): Bundle? {
        if (!isOpen) return null
        val at = result.matches.getOrNull(current)
        return Bundle().apply {
            putString(STATE_QUERY, input.text.toString())
            putInt(STATE_SEGMENT, at?.segment ?: -1)
            putInt(STATE_START, at?.start ?: -1)
            putBoolean(STATE_LANDING, landing)
            putInt(STATE_LANDING_SEGMENT, landingSegment)
        }
    }

    /**
     * Opens the bar as [saveState] left it, without moving the list or the
     * keyboard; or, when it was re-created before a landing came back,
     * lands as that would have.
     */
    fun restore(state: Bundle) {
        if (released) return
        bar.visibility = View.VISIBLE
        back.isEnabled = true
        setQuery(state.getString(STATE_QUERY).orEmpty())
        if (state.getBoolean(STATE_LANDING)) {
            land(state.getInt(STATE_LANDING_SEGMENT, -1))
            return
        }
        val segment = state.getInt(STATE_SEGMENT, -1)
        val start = state.getInt(STATE_START, -1)
        search(
            Reason.QUIET,
            if (segment < 0) Anchor.AtOrAfter(0, 0) else Anchor.AtOrAfter(segment, start)
        )
    }

    /** Searches the query in the field and lands on [segment]'s match, or the nearest. */
    private fun land(segment: Int) {
        landing = true
        landingSegment = segment
        search(Reason.LANDING, Anchor.Nearest(segment.coerceAtLeast(0)))
    }

    /** The screen is going: nothing still running may reach it. */
    fun release() {
        released = true
        generation++
        handler.removeCallbacks(typed)
    }

    private fun setQuery(query: String) {
        ignoreEdits = true
        input.setText(query)
        input.setSelection(input.text.length)
        ignoreEdits = false
    }

    private fun anchorFromScreen(): Anchor =
        Anchor.AtOrAfter(host.findFirstVisibleSegment().coerceAtLeast(0), 0)

    /** Next or previous; a query not searched yet is searched first. */
    private fun step(forward: Boolean) {
        if (!isOpen || released) return
        if (input.text.toString() != searchedQuery) {
            search(Reason.TYPED, anchorFromScreen())
            return
        }
        val texts = host.findTexts() ?: return
        val at = result.matches.getOrNull(current)
        if (texts != indexTexts) {
            // Changed since the search: search again, then step from where
            // the bar was, whether or not that match survived.
            val anchor = when {
                at == null -> anchorFromScreen()
                forward -> Anchor.After(at.segment, at.start)
                else -> Anchor.Before(at.segment, at.start)
            }
            search(Reason.STEP, anchor)
            return
        }
        if (result.isEmpty) return
        current = FindInMeeting.step(current, result.matches.size, forward)
        val next = result.matches[current]
        adapter.setFindCurrent(next.segment, next.start)
        host.findReveal(next.segment, landing = false)
        renderCount(Speak.LINE)
    }

    private fun search(reason: Reason, anchor: Anchor) {
        handler.removeCallbacks(typed)
        val texts = host.findTexts() ?: return
        val query = input.text.toString()
        val ticket = ++generation
        val cachedTexts = indexTexts
        val cachedIndex = index
        EXECUTOR.execute {
            val found = try {
                val lines = if (cachedIndex != null && cachedTexts == texts) {
                    cachedIndex
                } else {
                    FindInMeeting.index(texts)
                }
                lines to FindInMeeting.search(lines, query)
            } catch (_: Throwable) {
                null
            }
            handler.post {
                if (released || ticket != generation || found == null || !isOpen) return@post
                indexTexts = texts
                index = found.first
                show(found.second, query, reason, anchor)
            }
        }
    }

    private fun show(found: FindInMeeting.Result, query: String, reason: Reason, anchor: Anchor) {
        result = found
        searchedQuery = query
        current = FindInMeeting.pick(found.matches, anchor)
        val at = found.matches.getOrNull(current)
        val folded = TextFold.query(query)
        adapter.setFind(
            if (folded.isBlank()) null else { text -> FindInMeeting.rangesIn(text, folded) },
            at?.segment ?: -1,
            at?.start ?: -1
        )
        setStepping(!found.isEmpty)
        // Any result settles a landing: one typed over it replaces it.
        landing = false
        when (reason) {
            Reason.TYPED -> {
                at?.let { host.findReveal(it.segment, landing = false) }
                renderCount(Speak.COUNT)
            }
            Reason.STEP -> {
                at?.let { host.findReveal(it.segment, landing = false) }
                renderCount(Speak.LINE)
            }
            Reason.LANDING -> {
                val segment = at?.segment ?: landingSegment
                landingSegment = -1
                if (segment >= 0) host.findReveal(segment, landing = true)
                // The landed line takes TalkBack's focus and is read out;
                // the count saying so too would talk over it.
                renderCount(Speak.NONE)
            }
            Reason.QUIET -> renderCount(Speak.NONE)
        }
    }

    private fun setStepping(enabled: Boolean) {
        for (button in listOf(previousButton, nextButton)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.38f
        }
    }

    private enum class Speak { NONE, COUNT, LINE }

    /**
     * "3 of 12" beside the field. For a screen reader it is a polite live
     * region only for the change being made: the count as a search settles,
     * the count and the line's words on next and previous.
     */
    private fun renderCount(speak: Speak) {
        val total = result.matches.size
        val shown: String
        var spoken: String
        when {
            searchedQuery.isBlank() -> {
                shown = ""
                spoken = ""
            }
            total == 0 -> {
                shown = activity.getString(R.string.moment_find_none)
                spoken = shown
            }
            else -> {
                val position = current + 1
                shown = activity.getString(
                    if (result.capped) R.string.moment_find_count_capped else R.string.moment_find_count,
                    position, total
                )
                spoken = activity.getString(
                    if (result.capped) R.string.moment_find_a11y_position_capped
                    else R.string.moment_find_a11y_position,
                    position, total
                )
            }
        }
        val at = result.matches.getOrNull(current)
        if (speak == Speak.LINE && at != null) {
            val text = indexTexts?.getOrNull(at.segment).orEmpty()
            spoken = activity.getString(
                R.string.moment_find_a11y_line,
                spoken,
                host.findLineLabel(at.segment),
                MatchSnippet.around(text, at.start, at.end).spoken()
            )
        }
        if (speak != Speak.NONE && shown.isNotEmpty()) {
            A11y.announceNextChange(count)
        } else {
            A11y.endAnnouncement(count)
        }
        count.contentDescription = spoken.ifEmpty { null }
        count.text = shown
    }

    companion object {
        /** Quiet time after the last keystroke before the transcript is searched. */
        private const val TYPING_PAUSE_MS = 200L

        private const val STATE_QUERY = "query"
        private const val STATE_SEGMENT = "segment"
        private const val STATE_START = "start"
        private const val STATE_LANDING = "landing"
        private const val STATE_LANDING_SEGMENT = "landing_segment"

        /** One worker for every meeting screen: searches are short and only the newest counts. */
        private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "meeting-find").apply { isDaemon = true }
        }
    }
}
