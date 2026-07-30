package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.db.PrinterModel

/** A printer found during discovery, or a remembered network address, plus what is known about it. */
data class DetectedPrinter(
    val link: Link,
    val manufacturer: String? = null,
    val product: String? = null,
    val serial: String? = null,
    /** USB product ID. Null over the network, where there is no such thing. */
    val productId: Int? = null,
    /** Set when identity couldn't be read — usually the OS owning the USB interface. */
    val accessNote: String? = null,
    /** Whether this printer answered the current discovery or a later connection test. */
    val reachable: Boolean = true,
    /**
     * The same printer answering on its other link, and the better name it gave there. Only set
     * where that name is worth having: SNMP names a unit where a USB descriptor names a family.
     */
    val crossCheck: CrossCheck? = null,
) {
    /** A name for this printer from a link other than the one it is listed on. */
    data class CrossCheck(val name: String, val link: Link)

    val id: String get() = link.id

    /**
     * [serial] as the other link would spell it. The raw value is kept as it arrived because it is
     * what the device actually said; this is the form two links can be compared on.
     */
    val canonicalSerial: String? get() = Serials.canonical(serial)

    val displayName: String
        get() = product?.takeIf { it.isNotBlank() }
            ?: productId?.let { "Epson device %04X".format(it) }
            ?: "Epson at ${link.where}"

    val pidHex: String? get() = productId?.let { "0x%04X".format(it) }

    val isNetwork: Boolean get() = link is Link.Network

    /**
     * Where this printer can be reached over SNMP: its own address when it is a network entry, or
     * else the serial-matched peer that [crossCheck] found on the network. This is what lets a
     * USB-connected unit still answer standard-MIB queries, the same way it borrows its model name
     * from that peer. Null when the printer is USB-only with no network twin.
     */
    val snmpLink: Link.Network? get() = link as? Link.Network ?: crossCheck?.link as? Link.Network
}

/**
 * A detected printer paired with the database entry we believe it is.
 *
 * [candidates] is filled only for [Confidence.CLASS_ONLY], where the question is not *whether* we
 * matched but *which of these* the printer is.
 */
data class MatchedPrinter(
    val device: DetectedPrinter,
    val model: PrinterModel?,
    val confidence: Confidence,
    val candidates: List<PrinterModel> = emptyList(),
) {
    enum class Confidence {
        /** Descriptor name resolved to exactly one database entry. */
        EXACT,

        /** Name matched after normalising, or matched a unique prefix. Worth confirming. */
        LIKELY,

        /**
         * The descriptor named a family rather than a unit, and the family's members do not agree on
         * what a reset writes. [model] is one of them, but only the user can say which is theirs.
         */
        CLASS_ONLY,

        /** Nothing usable in the descriptor — the user must pick the model. */
        NONE,
    }
}
