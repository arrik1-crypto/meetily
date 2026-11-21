package com.meetily.mobile

import com.meetily.mobile.EdgeToEdge.BOTTOM
import com.meetily.mobile.EdgeToEdge.Edges
import com.meetily.mobile.EdgeToEdge.LEFT
import com.meetily.mobile.EdgeToEdge.RIGHT
import com.meetily.mobile.EdgeToEdge.TOP
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every screen is drawn under the system bars, so its layout root and the
 * views on its bottom edge pad themselves. The padding must keep the view's
 * own, take each inset once, and never stack the keyboard on top of the
 * navigation bar it already covers.
 */
class EdgeToEdgeTest {

    private val base = Edges(left = 24, top = 14, right = 24, bottom = 8)

    // Portrait with three-button navigation: status bar on top, nav bar below.
    private val portrait = Edges(left = 0, top = 63, right = 0, bottom = 126)

    // Landscape: the navigation bar and a display cutout sit on the sides.
    private val landscape = Edges(left = 80, top = 63, right = 126, bottom = 0)

    private val allSides = LEFT or TOP or RIGHT or BOTTOM
    private val exceptBottom = LEFT or TOP or RIGHT

    @Test
    fun rootAddsTheBarsToItsOwnPadding() {
        assertEquals(
            Edges(24, 77, 24, 134),
            EdgeToEdge.rootPadding(base, portrait, imeBottom = 0, sides = allSides)
        )
    }

    @Test
    fun rootLeavesTheBottomToTheViewOnTheBottomEdge() {
        assertEquals(
            Edges(24, 77, 24, 8),
            EdgeToEdge.rootPadding(base, portrait, imeBottom = 0, sides = exceptBottom)
        )
    }

    @Test
    fun landscapeInsetsGoToTheSides() {
        assertEquals(
            Edges(104, 77, 150, 8),
            EdgeToEdge.rootPadding(base, landscape, imeBottom = 0, sides = allSides)
        )
    }

    @Test
    fun keyboardReplacesTheNavigationBarInsteadOfAddingToIt() {
        // The keyboard inset is measured from the bottom of the window, so it
        // already includes the navigation bar it covers.
        assertEquals(
            Edges(24, 77, 24, 8 + 900),
            EdgeToEdge.rootPadding(base, portrait, imeBottom = 900, sides = allSides)
        )
    }

    @Test
    fun keyboardAlwaysGoesToTheRoot() {
        // Even when the bottom edge belongs to a list or a bar: an
        // edge-to-edge window is no longer resized for the keyboard.
        assertEquals(
            Edges(24, 77, 24, 8 + 900),
            EdgeToEdge.rootPadding(base, portrait, imeBottom = 900, sides = exceptBottom)
        )
    }

    @Test
    fun bottomEdgeViewTakesTheNavigationBar() {
        assertEquals(
            Edges(24, 14, 24, 8 + 126),
            EdgeToEdge.bottomPadding(base, barsBottom = 126, imeBottom = 0)
        )
    }

    @Test
    fun bottomEdgeViewDropsTheNavigationBarUnderTheKeyboard() {
        // The root already lifts it above the keyboard; another nav-bar
        // height on top would leave a gap.
        assertEquals(
            base,
            EdgeToEdge.bottomPadding(base, barsBottom = 126, imeBottom = 900)
        )
    }

    @Test
    fun rootAndBottomEdgeTogetherClearTheTallerOfKeyboardAndBar() {
        for (ime in listOf(0, 40, 126, 900)) {
            val root = EdgeToEdge.rootPadding(base, portrait, ime, exceptBottom)
            val bar = EdgeToEdge.bottomPadding(base, portrait.bottom, ime)
            val added = (root.bottom - base.bottom) + (bar.bottom - base.bottom)
            assertEquals("ime=$ime", maxOf(ime, portrait.bottom), added)
        }
    }

    @Test
    fun redispatchReplacesTheInsetInsteadOfStackingIt() {
        // Keyboard up, keyboard down, rotate and back: every pass starts
        // from the remembered base, so the result depends only on the
        // current insets.
        val first = EdgeToEdge.rootPadding(base, portrait, 0, allSides)
        EdgeToEdge.rootPadding(base, portrait, 900, allSides)
        EdgeToEdge.rootPadding(base, landscape, 0, allSides)
        val again = EdgeToEdge.rootPadding(base, portrait, 0, allSides)
        assertEquals(first, again)
        assertEquals(
            EdgeToEdge.bottomPadding(base, 126, 0),
            EdgeToEdge.bottomPadding(base, 126, 0)
        )
    }

    @Test
    fun noInsetsLeavesThePaddingAlone() {
        val none = Edges(0, 0, 0, 0)
        assertEquals(base, EdgeToEdge.rootPadding(base, none, 0, allSides))
        assertEquals(base, EdgeToEdge.bottomPadding(base, 0, 0))
    }
}
