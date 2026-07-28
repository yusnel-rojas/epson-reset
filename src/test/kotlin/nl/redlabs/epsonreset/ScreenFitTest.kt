package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.prefs.Preferences
import nl.redlabs.epsonreset.prefs.ScreenFit
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenFitTest {

    private val primary = Rectangle(0, 0, 1920, 1080)

    /** A second display to the left, which is where negative coordinates come from. */
    private val secondaryLeft = Rectangle(-1920, 0, 1920, 1080)

    private fun window(x: Int, y: Int, w: Int = 1100, h: Int = 780) = Rectangle(x, y, w, h)

    @Test
    fun `a window well inside the screen is reachable`() {
        assertTrue(ScreenFit.isReachable(window(200, 150), listOf(primary)))
    }

    @Test
    fun `a window hanging off the right edge is still reachable`() {
        assertTrue(ScreenFit.isReachable(window(1800, 100), listOf(primary)))
    }

    @Test
    fun `a window on an unplugged second monitor is not`() {
        assertFalse(ScreenFit.isReachable(window(-1700, 100), listOf(primary)))
    }

    @Test
    fun `the same window is reachable once that monitor is back`() {
        assertTrue(ScreenFit.isReachable(window(-1700, 100), listOf(primary, secondaryLeft)))
    }

    @Test
    fun `a sliver on screen is not enough to grab`() {
        // 20px of the window overlaps — visible, but not something to aim at.
        assertFalse(ScreenFit.isReachable(window(1900, 100), listOf(primary)))
    }

    @Test
    fun `a title bar above the top of the display counts as unreachable`() {
        // Plenty of the window is on screen, but the bar you would drag it by is not.
        assertFalse(ScreenFit.isReachable(window(400, -60), listOf(primary)))
    }

    @Test
    fun `a desktop that shrank leaves the old position off screen`() {
        val was3840Wide = window(2600, 400)

        assertFalse(ScreenFit.isReachable(was3840Wide, listOf(Rectangle(0, 0, 1440, 900))))
        assertTrue(ScreenFit.isReachable(was3840Wide, listOf(Rectangle(0, 0, 3840, 1600))))
    }

    @Test
    fun `positionFor returns the saved position when it still lands somewhere`() {
        val prefs = Preferences(windowX = 200, windowY = 150)

        assertEquals(200 to 150, ScreenFit.positionFor(prefs, listOf(primary)))
    }

    @Test
    fun `positionFor gives up on a position that no longer lands anywhere`() {
        val prefs = Preferences(windowX = -1700, windowY = 100)

        assertNull(ScreenFit.positionFor(prefs, listOf(primary)))
    }

    @Test
    fun `positionFor has nothing to say without a saved position`() {
        assertNull(ScreenFit.positionFor(Preferences(), listOf(primary)))
        assertNull(ScreenFit.positionFor(Preferences(windowX = 10), listOf(primary)))
    }

    @Test
    fun `with no screens to check against the saved position is trusted`() {
        val prefs = Preferences(windowX = -1700, windowY = 100)

        assertEquals(-1700 to 100, ScreenFit.positionFor(prefs, emptyList()))
    }

    @Test
    fun `enumerating real screens never throws, headless or not`() {
        ScreenFit.screens()
    }
}
