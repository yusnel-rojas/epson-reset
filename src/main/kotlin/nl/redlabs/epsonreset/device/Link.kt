package nl.redlabs.epsonreset.device

/** Where a printer is, and therefore how to open it. */
sealed interface Link {

    /** Stable across rescans, so a selection survives one. */
    val id: String

    /** One word, for the UI. */
    val kind: String

    /** Human-readable location: a bus address or a host:port. */
    val where: String

    data class Usb(
        val busNumber: Int,
        val deviceAddress: Int,
        val interfaceNumber: Int,
        val endpointIn: Byte,
        val endpointOut: Byte,
        val isPrinterClass: Boolean,
    ) : Link {
        /** bus+address is what libusb itself keys on. */
        override val id: String get() = "usb:$busNumber:$deviceAddress"
        override val kind: String get() = "USB"
        override val where: String get() = "bus $busNumber.$deviceAddress"
    }

    data class Network(val host: String, val port: Int = RAW_PORT) : Link {
        override val id: String get() = "net:$host:$port"
        override val kind: String get() = "Network"

        // Just the host at the default port.
        override val where: String get() = if (port == RAW_PORT) host else "$host:$port"
    }

    companion object {
        /** Raw ("JetDirect") printing — the port a printer advertises `_pdl-datastream._tcp` on. */
        const val RAW_PORT = 9100
    }
}
