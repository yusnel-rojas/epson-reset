package nl.redlabs.epsonreset.ui

import java.awt.Desktop
import java.net.URI

/** Handing a URL to the user's browser. */
object Browser {

    fun open(url: String): Boolean = runCatching {
        val desktop = Desktop.getDesktop().takeIf {
            Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE)
        } ?: return@runCatching false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)
}
