package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferencesTest {

    @Test
    fun `a full round trip preserves every field`() {
        val prefs = Preferences(
            windowWidth = 1440,
            windowHeight = 900,
            windowX = 120,
            windowY = 64,
            maximized = true,
            lastModel = "ET-2820",
            lastPrinterId = "network:192.0.2.20",
            logCollapsed = true,
            checkForUpdates = false,
            lastUpdateCheck = 1_700_000_000_000,
            crossCheckOverSnmp = false,
            keepCounterHistory = false,
        )

        assertEquals(prefs, Preferences.parse(Preferences.format(prefs)))
    }

    @Test
    fun `defaults are the first-launch window`() {
        val prefs = Preferences()

        assertEquals(1100, prefs.windowWidth)
        assertEquals(780, prefs.windowHeight)
        assertNull(prefs.windowX)
        assertNull(prefs.windowY)
        assertNull(prefs.lastPrinterId)
        assertTrue(prefs.checkForUpdates)
        // On by default: it costs nothing and answers the question a USB descriptor leaves open.
        assertTrue(prefs.crossCheckOverSnmp)
        assertTrue(prefs.keepCounterHistory)
    }

    @Test
    fun `a missing file's worth of nothing gives defaults`() {
        assertEquals(Preferences(), Preferences.parse("{}"))
    }

    @Test
    fun `text that isn't JSON costs the preferences, not the launch`() {
        assertEquals(Preferences(), Preferences.parse("not json at all"))
        assertEquals(Preferences(), Preferences.parse(""))
        assertEquals(Preferences(), Preferences.parse("[1, 2, 3]"))
    }

    @Test
    fun `one unparseable field takes the default and leaves the rest alone`() {
        val prefs = Preferences.parse(
            """{ "windowWidth": "enormous", "windowHeight": 900, "lastModel": "L3150" }""",
        )

        assertEquals(Preferences.DEFAULT_WIDTH, prefs.windowWidth)
        assertEquals(900, prefs.windowHeight)
        assertEquals("L3150", prefs.lastModel)
    }

    @Test
    fun `a hand-edited number in quotes still counts`() {
        val prefs = Preferences.parse("""{ "windowWidth": "1400", "logCollapsed": "true" }""")

        assertEquals(1400, prefs.windowWidth)
        assertTrue(prefs.logCollapsed)
    }

    @Test
    fun `unknown keys are ignored rather than fatal`() {
        val prefs = Preferences.parse("""{ "windowWidth": 1400, "somethingWeAddedLater": 3 }""")

        assertEquals(1400, prefs.windowWidth)
    }

    @Test
    fun `a window dragged down to a sliver comes back openable`() {
        val prefs = Preferences.parse("""{ "windowWidth": 4, "windowHeight": 1 }""")

        assertEquals(Preferences.MIN_WIDTH, prefs.windowWidth)
        assertEquals(Preferences.MIN_HEIGHT, prefs.windowHeight)
    }

    @Test
    fun `a garbage size is clamped rather than trusted`() {
        val prefs = Preferences.parse("""{ "windowWidth": 999999999, "windowHeight": -20 }""")

        assertEquals(Preferences.MAX_DIMENSION, prefs.windowWidth)
        assertEquals(Preferences.MIN_HEIGHT, prefs.windowHeight)
    }

    @Test
    fun `an absurd position is dropped so the platform places the window`() {
        val prefs = Preferences.parse("""{ "windowX": 90000000, "windowY": 10 }""")

        assertNull(prefs.windowX)
        assertEquals(10, prefs.windowY)
    }

    @Test
    fun `a negative position survives, because a screen can be left of the primary`() {
        val prefs = Preferences.parse("""{ "windowX": -1600, "windowY": -200 }""")

        assertEquals(-1600, prefs.windowX)
        assertEquals(-200, prefs.windowY)
    }

    @Test
    fun `a blank model name is the same as none`() {
        assertNull(Preferences.parse("""{ "lastModel": "   " }""").lastModel)
        assertNull(Preferences(lastModel = "  ").sanitised().lastModel)
        assertNull(Preferences.parse("""{ "lastPrinterId": "   " }""").lastPrinterId)
        assertNull(Preferences(lastPrinterId = "  ").sanitised().lastPrinterId)
    }

    @Test
    fun `a clock that went backwards does not bank a negative check time`() {
        assertEquals(0L, Preferences(lastUpdateCheck = -5).sanitised().lastUpdateCheck)
    }

    @Test
    fun `absent optional fields are omitted rather than written as null`() {
        val text = Preferences.format(Preferences())

        assertTrue("windowX" !in text, text)
        assertTrue("lastModel" !in text, text)
        assertTrue("lastPrinterId" !in text, text)
    }
}
