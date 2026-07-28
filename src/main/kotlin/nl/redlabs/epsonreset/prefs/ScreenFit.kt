package nl.redlabs.epsonreset.prefs

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** Decides whether a remembered window position is still usable. */
object ScreenFit {

    /** Enough of the window to see it and get hold of it. */
    const val MIN_VISIBLE = 96

    /** True when a window at [window] would land somewhere the user can act on. */
    fun isReachable(window: Rectangle, screens: List<Rectangle>): Boolean = screens.any { screen ->
        val overlap = screen.intersection(window)
        !overlap.isEmpty &&
            overlap.width >= MIN_VISIBLE &&
            overlap.height >= MIN_VISIBLE &&
            window.y >= screen.y
    }

    /** Every attached display's bounds; empty when there is no display to ask (headless, CI). */
    fun screens(): List<Rectangle> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
    }.getOrDefault(emptyList())

    /**
     * The saved position if it still lands on a screen, otherwise null for "let the platform
     * decide".
     */
    fun positionFor(prefs: Preferences, screens: List<Rectangle> = screens()): Pair<Int, Int>? {
        val x = prefs.windowX ?: return null
        val y = prefs.windowY ?: return null
        if (screens.isEmpty()) return x to y

        val window = Rectangle(x, y, prefs.windowWidth, prefs.windowHeight)
        return if (isReachable(window, screens)) x to y else null
    }
}
