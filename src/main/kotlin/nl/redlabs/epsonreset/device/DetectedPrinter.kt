package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.db.PrinterModel

/** A printer we found and can reach, plus whatever it told us about itself. */
data class DetectedPrinter(
    val link: Link,
    val manufacturer: String? = null,
    val product: String? = null,
    val serial: String? = null,
    /** USB product ID. Null over the network, where there is no such thing. */
    val productId: Int? = null,
    /** Set when identity couldn't be read — usually the OS owning the USB interface. */
    val accessNote: String? = null,
) {
    val id: String get() = link.id

    val displayName: String
        get() = product?.takeIf { it.isNotBlank() }
            ?: productId?.let { "Epson device %04X".format(it) }
            ?: "Epson at ${link.where}"

    val pidHex: String? get() = productId?.let { "0x%04X".format(it) }

    val isNetwork: Boolean get() = link is Link.Network
}

/** A detected printer paired with the database entry we believe it is. */
data class MatchedPrinter(val device: DetectedPrinter, val model: PrinterModel?, val confidence: Confidence) {
    enum class Confidence {
        /** Descriptor name resolved to exactly one database entry. */
        EXACT,

        /** Name matched after normalising, or matched a unique prefix. Worth confirming. */
        LIKELY,

        /** Nothing usable in the descriptor — the user must pick the model. */
        NONE,
    }
}
