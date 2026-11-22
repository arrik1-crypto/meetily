package com.meetily.mobile

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityViewCommand

/**
 * Accessibility plumbing shared by the screens that build or recycle views
 * in code: touch-target sizing, labelled gestures, and spoken status.
 */
object A11y {

    /** Material's minimum touch target, in dp. */
    const val MIN_TOUCH_DP = 48

    fun minTouchPx(context: Context): Int =
        (MIN_TOUCH_DP * context.resources.displayMetrics.density + 0.5f).toInt()

    /** One labelled action a screen reader can pick from its actions menu. */
    class Action(val label: CharSequence, val run: () -> Unit)

    /**
     * Gives a code-built pill a 48dp touch target without changing how big
     * it looks: [res] is drawn inset from the top and bottom of a taller
     * view, and the padding grows by the same amount so the label stays put.
     * [visualHeightPx] is the pill's old minimum height.
     */
    fun pillTouchTarget(
        view: TextView,
        @DrawableRes res: Int,
        visualHeightPx: Int,
        paddingHorizontalPx: Int,
        paddingVerticalPx: Int
    ) {
        val touch = minTouchPx(view.context)
        val inset = pillInsetPx(touch, visualHeightPx)
        view.background = insetBackground(view.context, res, inset)
        view.setPadding(
            paddingHorizontalPx, paddingVerticalPx + inset,
            paddingHorizontalPx, paddingVerticalPx + inset
        )
        view.minHeight = touch
        view.minWidth = touch
    }

    /**
     * [res] drawn [insetPx] in from the top and bottom. A layer inset, not an
     * InsetDrawable: that reports its insets as padding, which a later
     * setBackground would copy over the view's own.
     */
    private fun insetBackground(context: Context, @DrawableRes res: Int, insetPx: Int): Drawable? {
        val drawable = AppCompatResources.getDrawable(context, res) ?: return null
        if (insetPx <= 0) return drawable
        return LayerDrawable(arrayOf(drawable)).apply {
            setLayerInset(0, 0, insetPx, 0, insetPx)
        }
    }

    /** Names what a tap does, instead of TalkBack's bare "double-tap to activate". */
    fun labelClick(view: View, label: CharSequence) {
        relabel(view, R.id.a11y_click_label, AccessibilityActionCompat.ACTION_CLICK, label)
    }

    /**
     * Names what a long-press does. Without a label it is announced as
     * "double-tap and hold to long press", with no hint of the result.
     */
    fun labelLongClick(view: View, label: CharSequence) {
        relabel(view, R.id.a11y_long_click_label, AccessibilityActionCompat.ACTION_LONG_CLICK, label)
    }

    /**
     * Each change to a view's actions is reported to the screen reader, and
     * recycled rows rebind several times a second while playback follows
     * along, so an unchanged label is left alone.
     */
    private fun relabel(view: View, tag: Int, action: AccessibilityActionCompat, label: CharSequence) {
        val text = label.toString()
        if (view.getTag(tag) == text) return
        view.setTag(tag, text)
        ViewCompat.replaceAccessibilityAction(view, action, label, null)
    }

    /**
     * Replaces the custom actions on [view] with [actions]. Recycled rows
     * call it on every bind, so a line never keeps an action (Share clip,
     * say) that belonged to the line bound there before it. When the labels
     * are the same as last time only the commands behind them change, which
     * the screen reader does not need to hear about.
     */
    fun setActions(view: View, actions: List<Action>) {
        view.setTag(R.id.a11y_custom_actions, actions)
        val labels = actions.map { it.label.toString() }
        if (view.getTag(R.id.a11y_custom_action_labels) == labels) return
        (view.getTag(R.id.a11y_custom_action_ids) as? IntArray)?.forEach {
            ViewCompat.removeAccessibilityAction(view, it)
        }
        val ids = ArrayList<Int>(actions.size)
        for ((index, action) in actions.withIndex()) {
            val id = ViewCompat.addAccessibilityAction(
                view, action.label, AccessibilityViewCommand { host, _ ->
                    // Looked up when performed, so a rebind that kept the
                    // labels still runs the current row's command.
                    val current = (host.getTag(R.id.a11y_custom_actions) as? List<*>)
                        ?.getOrNull(index) as? Action
                    current?.run?.invoke()
                    current != null
                }
            )
            if (id != View.NO_ID) ids.add(id)
        }
        view.setTag(R.id.a11y_custom_action_ids, ids.toIntArray())
        view.setTag(R.id.a11y_custom_action_labels, labels)
    }

    /** Spoken state for a toggle whose on/off is otherwise only a tint. */
    fun setOnOff(view: View, on: Boolean) {
        ViewCompat.setStateDescription(
            view, view.context.getString(if (on) R.string.a11y_on else R.string.a11y_off)
        )
    }

    /**
     * Sets [text] on a status line, and has a screen reader speak it only
     * when [announce] is true (see ProgressAnnouncer and
     * RecordingStatusSpeech for who decides).
     *
     * The line is a polite live region for just that change; any update
     * passed with [announce] false lifts the region first, and a spoken one
     * lifts it again shortly after, so a later re-render of the same view
     * (a progress tick, a refresh on resume) stays silent.
     */
    fun setStatusText(view: TextView, text: CharSequence, announce: Boolean) {
        if (announce) {
            announceNextChange(view)
            view.text = text
        } else {
            endAnnouncement(view)
            if (!TextUtils.equals(view.text, text)) view.text = text
        }
    }

    /**
     * Makes [view] a polite live region for the change the caller is about
     * to make — to its own text, or to any text inside it when it is a
     * container — and lifts the region again shortly after.
     */
    fun announceNextChange(view: View) {
        endAnnouncement(view)
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val reset = Runnable {
            view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            view.setTag(R.id.a11y_live_region_reset, null)
        }
        view.setTag(R.id.a11y_live_region_reset, reset)
        // Long enough for the screen reader to read the region's state back
        // when it handles the change; short enough that nothing else lands
        // in the window.
        view.postDelayed(reset, LIVE_REGION_HOLD_MS)
    }

    /**
     * Lifts the live region [setStatusText] may have left on [view], for a
     * caller about to set text that must not be read out — a whole summary
     * replacing the progress line it shared a view with, say.
     */
    fun endAnnouncement(view: View) {
        val pending = view.getTag(R.id.a11y_live_region_reset) as? Runnable
        if (pending != null) {
            view.removeCallbacks(pending)
            view.setTag(R.id.a11y_live_region_reset, null)
        }
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }

    /**
     * True while a screen reader is exploring by touch (TalkBack and the
     * like), as opposed to Switch Access or no service at all.
     */
    fun isTouchExploring(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    private const val LIVE_REGION_HOLD_MS = 1_500L
}
