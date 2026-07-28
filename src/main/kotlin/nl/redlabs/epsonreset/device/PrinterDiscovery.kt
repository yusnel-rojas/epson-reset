package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.net.MdnsDiscovery
import nl.redlabs.epsonreset.net.SavedPrinters
import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.usb.UsbPrinterScanner

/** Everything reachable, from every source, in one list. */
object PrinterDiscovery {

    data class Result(
        val printers: List<DetectedPrinter>,
        val usb: UsbPrinterScanner.ScanResult,
        val network: NetworkOutcome,
    ) {
        val usbPrinters: List<DetectedPrinter> get() = printers.filter { it.link is Link.Usb }
        val networkPrinters: List<DetectedPrinter> get() = printers.filter { it.link is Link.Network }
    }

    sealed interface NetworkOutcome {
        /** [saved] are hand-added addresses, listed whether or not they answered a browse. */
        data class Ok(val discovered: Int, val saved: Int) : NetworkOutcome

        data class Unavailable(val detail: String, val hint: String) : NetworkOutcome

        data object Skipped : NetworkOutcome
    }

    /** Scans the USB bus, browses for advertised printers, and adds the saved addresses. */
    fun scan(includeNetwork: Boolean = true, browseTimeoutMs: Long = 2500, crossCheck: Boolean = true): Result {
        val usb = UsbPrinterScanner.scan()
        val usbPrinters = (usb as? UsbPrinterScanner.ScanResult.Ok)?.printers.orEmpty()

        if (!includeNetwork) return Result(usbPrinters, usb, NetworkOutcome.Skipped)

        val browsed = MdnsDiscovery.browse(timeoutMs = browseTimeoutMs)
        val discovered = when (browsed) {
            // Offering to write EEPROM keys to another maker's printer is not a mistake worth
            // leaving available, so anything that doesn't say Epson is dropped rather than listed.
            is MdnsDiscovery.BrowseResult.Ok -> browsed.services.filter { it.isEpson }.map(::fromService)
            is MdnsDiscovery.BrowseResult.Unavailable -> emptyList()
        }

        // A saved address the browse already turned up is the same printer, and the browse knows
        // more about it — the saved copy carries only what it was last told.
        val saved = SavedPrinters.load()
            .filterNot { entry -> discovered.any { it.link == entry.link } }
            .map(::fromSaved)

        val outcome = when (browsed) {
            is MdnsDiscovery.BrowseResult.Ok -> NetworkOutcome.Ok(discovered.size, saved.size)
            is MdnsDiscovery.BrowseResult.Unavailable ->
                NetworkOutcome.Unavailable(browsed.detail, browsed.hint)
        }

        val network = discovered + saved
        val onUsb = if (crossCheck) crossChecked(usbPrinters, network) else usbPrinters

        return Result(onUsb + network, usb, outcome)
    }

    /** [usbPrinters], each given the better name its own network entry has, where there is one. */
    fun crossChecked(usbPrinters: List<DetectedPrinter>, network: List<DetectedPrinter>): List<DetectedPrinter> =
        usbPrinters.map { crossChecked(it, network) }

    /**
     * A USB printer that is also on the network can borrow the better of the two names.
     *
     * Over USB the descriptor says `EPSON ET-2820 Series` — a family covering several units, which
     * is all the descriptor ever gives. The same printer answers SNMP with `ET-2825`, the unit. The
     * serial is what proves they are one machine, once both are spelled the same way; see [Serials].
     */
    private fun crossChecked(printer: DetectedPrinter, network: List<DetectedPrinter>): DetectedPrinter {
        // The descriptor's own spelling, not the canonical one: [Serials.same] weighs every
        // reading of both sides, and handing it a string already reduced to one throws away the
        // alternative that the network entry is the one agreeing with.
        val serial = printer.serial?.takeIf { it.isNotBlank() } ?: return printer

        // Only worth borrowing when it answers the question the descriptor left open.
        if (printer.product?.let { DeviceMatcher.namesAClass(it) } != true) return printer

        val peer = network.firstOrNull {
            Serials.same(it.serial, serial) && it.product?.let { name -> !DeviceMatcher.namesAClass(name) } == true
        } ?: return printer

        return printer.copy(crossCheck = DetectedPrinter.CrossCheck(peer.product.orEmpty(), peer.link))
    }

    private fun fromService(service: MdnsDiscovery.Service) = identified(
        // Deliberately not service.port: what is advertised is the raw printing port, and this app
        // talks SNMP. Taking it would record a port nothing dials.
        link = Link.Network(service.host),
        // usb_MDL is the USB model string republished — the same value the USB path matches on,
        // and the same limitation: it names the family, not the unit. SNMP knows better.
        fallbackProduct = service.model,
        manufacturer = service.manufacturer,
    )

    private fun fromSaved(entry: SavedPrinters.Saved) = identified(
        link = entry.link,
        fallbackProduct = entry.product,
        manufacturer = null,
        noteWhenAnonymous =
        "Added by hand and not identified — test the connection, or pick the model below.",
    )

    /** Asks the printer itself before falling back to what advertised it. */
    private fun identified(
        link: Link.Network,
        fallbackProduct: String?,
        manufacturer: String?,
        noteWhenAnonymous: String? = null,
    ): DetectedPrinter {
        val identity = SnmpTransport.identify(link.host, port = link.port)

        return DetectedPrinter(
            link = link,
            manufacturer = manufacturer,
            product = identity?.model ?: fallbackProduct,
            serial = identity?.serial,
            accessNote = if (identity == null && fallbackProduct == null) noteWhenAnonymous else null,
        )
    }
}
