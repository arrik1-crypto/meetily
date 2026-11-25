package com.meetily.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.meetily.mobile.summarize.AiDisclaimers

/**
 * The on-screen half of AiDisclaimers: the notice banner shown with a
 * summary, and a text-selection hook so text copied or shared out of a
 * generated text carries its notice too.
 */
object DisclaimerViews {

    /**
     * A hidden notice banner placed directly before [anchor] in the same
     * parent, so no existing layout has to change. Fill it with [show].
     */
    fun addBannerBefore(anchor: View): TextView {
        val parent = anchor.parent as ViewGroup
        val banner = LayoutInflater.from(anchor.context)
            .inflate(R.layout.aisafety_disclaimer, parent, false) as TextView
        parent.addView(banner, parent.indexOfChild(anchor))
        return banner
    }

    /** Shows [notices] in [banner], or hides it when there are none. */
    fun show(banner: TextView, notices: List<String>) {
        banner.text = notices.joinToString("\n\n")
        banner.visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Makes Copy and Share in [view]'s text selection add [notices] (read
     * at the moment of copying) to the selected text, unless the selection
     * already contains them. With no notices the system's own handling runs
     * unchanged.
     */
    fun copyWithNotices(view: TextView, notices: () -> List<String>) {
        view.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val id = item.itemId
                if (id != android.R.id.copy && id != android.R.id.shareText) return false
                val extra = notices()
                if (extra.isEmpty()) return false
                val start = minOf(view.selectionStart, view.selectionEnd)
                val end = maxOf(view.selectionStart, view.selectionEnd)
                if (start < 0 || end <= start || end > view.text.length) return false
                val text = AiDisclaimers.appendTo(
                    view.text.subSequence(start, end).toString(), extra
                )
                val handled = try {
                    if (id == android.R.id.copy) {
                        val clipboard = view.context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(null, text))
                        clipboard != null
                    } else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        com.meetily.mobile.export.MeetingShareFile.clearAll(view.context)
                        view.context.startActivity(Intent.createChooser(send, null))
                        true
                    }
                } catch (_: RuntimeException) {
                    false
                }
                if (handled) mode.finish()
                return handled
            }

            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }
}
