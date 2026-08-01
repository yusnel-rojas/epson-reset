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

    /**
     * A printer reached through its own Windows driver via the print spooler — the driverless USB
     * path on Windows, needing no libusb and no Zadig. [queueName] is the installed printer's name,
     * which is also how the spooler is opened.
     */
    data class WindowsPrinter(val queueName: String, val port: String?, val driver: String?) : Link {
        override val id: String get() = "win:$queueName"

        // "USB" to the user: it is the USB printer, just reached through Windows' own driver.
        override val kind: String get() = "USB"
        override val where: String get() = port ?: queueName
    }

    /** [port] is the SNMP port, because SNMP is the only thing this app ever speaks over a network. */
    data class Network(val host: String, val port: Int = SNMP_PORT) : Link {
        override val id: String get() = "net:$host:$port"
        override val kind: String get() = "Network"

        // Just the host at the default port.
        override val where: String get() = if (port == SNMP_PORT) host else "$host:$port"
    }

    companion object {
        /** Where every network read in this app goes: identity, status, counters, the passthrough. */
        const val SNMP_PORT = 161

        /**
         * Raw ("JetDirect") printing — what a printer advertises `_pdl-datastream._tcp` on, and
         * what this field used to be filled with. Nothing here ever connected to it, so a stored
         * 9100 means "never set" rather than "ask SNMP on 9100"; see [nl.redlabs.epsonreset.net.NetworkAddress].
         */
        const val RAW_PORT = 9100
    }
}
