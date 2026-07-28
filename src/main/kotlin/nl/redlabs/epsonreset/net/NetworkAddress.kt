package nl.redlabs.epsonreset.net

import nl.redlabs.epsonreset.device.Link

/** Parses what someone types into the "add a printer by address" box. */
object NetworkAddress {

    /** Null when there's nothing usable in [input], so the UI can keep the button disabled. */
    fun parse(input: String): Link.Network? {
        var text = input.trim()
        if (text.isEmpty()) return null

        // A pasted admin URL: keep the authority, drop the scheme and everything after the host.
        text = text.substringAfter("://")
        text = text.substringBefore('/')
        if (text.isEmpty()) return null

        // Bracketed IPv6, which has colons of its own and so cannot use the split below.
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val host = text.substring(1, close)
            val port = text.substring(close + 1).removePrefix(":")
            return build(host, port)
        }

        val colon = text.lastIndexOf(':')
        return if (colon < 0) build(text, "") else build(text.substring(0, colon), text.substring(colon + 1))
    }

    private fun build(host: String, port: String): Link.Network? {
        val cleanHost = host.trim().trimEnd('.')
        if (cleanHost.isEmpty() || cleanHost.any { it.isWhitespace() }) return null

        val number = if (port.isBlank()) Link.RAW_PORT else port.trim().toIntOrNull() ?: return null
        if (number !in 1..65535) return null

        return Link.Network(cleanHost, number)
    }

    /** The form [parse] round-trips, and the form [SavedPrinters] writes to disk. */
    fun format(link: Link.Network): String = if (link.port == Link.RAW_PORT) link.host else "${link.host}:${link.port}"
}
