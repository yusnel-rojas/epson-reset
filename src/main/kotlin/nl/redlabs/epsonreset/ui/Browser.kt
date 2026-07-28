package nl.redlabs.epsonreset.ui

import java.awt.Desktop
import java.io.File
import java.net.URI

/** Handing a URL to the user's browser, or a directory to their file manager. */
object Browser {

    /** Opens [dir] in Finder, Explorer, or whatever the desktop uses. */
    fun openDirectory(dir: File): Boolean = runCatching {
        val desktop = Desktop.getDesktop().takeIf {
            Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.OPEN)
        } ?: return@runCatching false
        if (!dir.isDirectory) return@runCatching false
        desktop.open(dir)
        true
    }.getOrDefault(false)

    fun open(url: String): Boolean = runCatching {
        val desktop = Desktop.getDesktop().takeIf {
            Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE)
        } ?: return@runCatching false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)
}
