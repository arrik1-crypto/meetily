package com.meetily.mobile

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge on every screen, the same way on API 26 to 36.
 *
 * An app targeting SDK 35 is drawn under the status and navigation bars on
 * Android 15, and from SDK 36 the theme opt-out is ignored. Rather than look
 * different depending on whether that enforcement applies, every activity
 * goes edge-to-edge on purpose and pads the views that would otherwise sit
 * under a system bar, a display cutout or the keyboard.
 *
 * Call [setUp] once per activity, straight after setContentView. It cannot
 * be one global hook: ActivityLifecycleCallbacks.onActivityPostCreated only
 * exists from API 29.
 *
 * Who takes which inset:
 * - The layout root takes the left and right insets (landscape, cutouts)
 *   and the top one. Every toolbar in the app shares the root's background,
 *   so padding the root looks the same as padding the toolbar, without
 *   squeezing a toolbar whose height is fixed at actionBarSize.
 * - The bottom inset goes to the root when the screen's own content ends in
 *   a button or nothing at all ([Scope.root] with bottom = true). Otherwise
 *   it goes to the view on the bottom edge: a bar ([Scope.bar]), whose
 *   background then runs on under the navigation bar, or a scrolling view
 *   ([Scope.list]), which stops clipping to its padding so its last item can
 *   scroll clear of the bar.
 * - The keyboard always goes to the root. An edge-to-edge window is not
 *   resized for the IME, so the root shrinks the layout itself, as
 *   adjustResize used to. A bottom view drops its navigation-bar padding
 *   while the keyboard covers that bar, so the two never add up.
 *
 * Each padded view keeps the padding it had before its first inset in a
 * view tag and always works from that, so a later dispatch (rotation, the
 * keyboard, a bar appearing) replaces the inset instead of adding to it.
 */
object EdgeToEdge {

    internal const val LEFT = 1
    internal const val TOP = 2
    internal const val RIGHT = 4
    internal const val BOTTOM = 8

    /** A view's padding, or a set of insets, as four plain ints. */
    internal data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Turns edge-to-edge on for [activity] and registers the views named in
     * [configure]. Transparent bars, with icon colours that follow the day or
     * night edition; on API 26-28 the navigation bar keeps a scrim, because
     * those versions cannot enforce contrast over app content themselves.
     */
    fun setUp(activity: ComponentActivity, configure: Scope.() -> Unit = {}) {
        activity.enableEdgeToEdge()
        Scope(activity).configure()
    }

    class Scope internal constructor(private val activity: Activity) {

        /**
         * The layout root: clear of the bars on the left, top and right and
         * of the keyboard at the bottom. With [bottom] it also keeps clear of
         * the navigation bar, for screens with nothing on the bottom edge
         * that scrolls or has its own background.
         */
        fun root(bottom: Boolean = true) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val root = content.getChildAt(0) ?: return
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                EdgeToEdge.padRoot(view, insets, bottom)
                insets
            }
        }

        /** A bar or panel on the bottom edge, padded above the navigation bar. */
        fun bar(id: Int) {
            bottomEdge(id, scrolls = false)
        }

        /** A scrolling view on the bottom edge: its content scrolls on under the bar. */
        fun list(id: Int) {
            bottomEdge(id, scrolls = true)
        }

        private fun bottomEdge(id: Int, scrolls: Boolean) {
            val target = activity.findViewById<View>(id) ?: return
            if (scrolls) {
                (target as? ViewGroup)?.clipToPadding = false
            }
            ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
                EdgeToEdge.padBottomEdge(view, insets)
                insets
            }
        }
    }

    /**
     * Pads a layout root as [Scope.root] does. For a screen that already
     * listens to insets on its root for something else (RecordingActivity
     * watches the keyboard): a view holds only one listener, so that one has
     * to call this itself.
     */
    fun padRoot(root: View, insets: WindowInsetsCompat, bottom: Boolean = true) {
        val sides = LEFT or TOP or RIGHT or (if (bottom) BOTTOM else 0)
        setPaddingIfChanged(
            root,
            rootPadding(basePadding(root), bars(insets), imeBottom(insets), sides)
        )
    }

    private fun padBottomEdge(view: View, insets: WindowInsetsCompat) {
        setPaddingIfChanged(
            view,
            bottomPadding(basePadding(view), bars(insets).bottom, imeBottom(insets))
        )
    }

    /**
     * The root's padding: its own, plus the bars on [sides], plus at the
     * bottom the keyboard or the navigation bar, whichever is taller. The
     * keyboard is measured from the bottom of the window, so it already
     * covers the navigation bar; adding the two would leave a gap.
     */
    internal fun rootPadding(base: Edges, bars: Edges, imeBottom: Int, sides: Int): Edges {
        fun on(side: Int, inset: Int) = if ((sides and side) != 0) inset else 0
        return Edges(
            left = base.left + on(LEFT, bars.left),
            top = base.top + on(TOP, bars.top),
            right = base.right + on(RIGHT, bars.right),
            bottom = base.bottom + maxOf(imeBottom, on(BOTTOM, bars.bottom))
        )
    }

    /**
     * Padding for a view on the bottom edge, under a root that takes the
     * keyboard: the navigation bar, less whatever of it the keyboard (and so
     * the root's padding) already covers.
     */
    internal fun bottomPadding(base: Edges, barsBottom: Int, imeBottom: Int): Edges =
        base.copy(bottom = base.bottom + maxOf(0, barsBottom - imeBottom))

    /** System bars (with the caption bar) and display cutouts, side by side. */
    private fun bars(insets: WindowInsetsCompat): Edges {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        return Edges(bars.left, bars.top, bars.right, bars.bottom)
    }

    private fun imeBottom(insets: WindowInsetsCompat): Int =
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

    /**
     * The padding [view] had before any inset was added, read on the first
     * dispatch (by then the view is attached and start/end padding resolves
     * to the right side for RTL) and kept in a tag from then on.
     */
    private fun basePadding(view: View): Edges {
        (view.getTag(R.id.edgeBasePadding) as? Edges)?.let { return it }
        val base = Edges(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.setTag(R.id.edgeBasePadding, base)
        return base
    }

    private fun setPaddingIfChanged(view: View, padding: Edges) {
        if (view.paddingLeft == padding.left && view.paddingTop == padding.top &&
            view.paddingRight == padding.right && view.paddingBottom == padding.bottom
        ) {
            return
        }
        view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }
}
