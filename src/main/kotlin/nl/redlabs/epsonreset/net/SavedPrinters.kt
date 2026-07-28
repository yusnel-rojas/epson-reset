package nl.redlabs.epsonreset.net

import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.device.Link

/** Network printers the user added by hand, kept between runs. */
object SavedPrinters {

    /**
     * [product] is what the printer called itself when it was last reached, cached so a saved
     * address resolves to a database entry on the next launch instead of arriving anonymous.
     */
    data class Saved(val link: Link.Network, val product: String? = null)

    fun load(): List<Saved> = runCatching { AppPaths.networkPrinters.takeIf { it.isFile }?.readText() }.getOrNull()
        ?.let { parse(it) }
        ?: emptyList()

    /** Adds or updates [entry] by address. Returns the list as it now stands. */
    fun add(entry: Saved): List<Saved> {
        val current = load()
        val existing = current.firstOrNull { it.link == entry.link }

        // A re-add with nothing learned must not wipe a name we already had.
        val merged = Saved(entry.link, entry.product ?: existing?.product)
        val updated =
            if (existing == null) {
                current + merged
            } else {
                current.map { if (it.link == entry.link) merged else it }
            }

        save(updated)
        return updated
    }

    fun remove(link: Link.Network): List<Saved> {
        val updated = load().filterNot { it.link == link }
        save(updated)
        return updated
    }

    fun save(entries: List<Saved>) {
        runCatching { AppPaths.networkPrinters.writeText(format(entries)) }
    }

    /**
     * One printer per line: an address, then optionally whatever the printer last called itself.
     * Blank lines, `#` comments and unparseable addresses are skipped.
     */
    fun parse(text: String): List<Saved> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val address = line.takeWhile { !it.isWhitespace() }
            val link = NetworkAddress.parse(address) ?: return@mapNotNull null
            Saved(link, line.drop(address.length).trim().takeIf { it.isNotEmpty() })
        }
        .distinctBy { it.link }
        .toList()

    fun format(entries: List<Saved>): String = buildString {
        appendLine("# Epson Reset — network printers added by hand.")
        appendLine("# One per line: address, then optionally the model name, e.g.")
        appendLine("#   192.168.1.50       EPSON ET-2820 Series")
        entries.forEach { entry ->
            append(NetworkAddress.format(entry.link))
            entry.product?.let { append("  ").append(it) }
            appendLine()
        }
    }
}
