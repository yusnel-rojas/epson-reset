package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.net.MdnsDiscovery
import nl.redlabs.epsonreset.net.SavedPrinters
import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.usb.UsbPrinterScanner
import nl.redlabs.epsonreset.usb.WindowsPrinterScanner

/** Reachable printers plus remembered network addresses, from every source, in one list. */
object PrinterDiscovery {

    data class Result(
        val printers: List<DetectedPrinter>,
        val usb: UsbPrinterScanner.ScanResult,
        val network: NetworkOutcome,
    ) {
        // Both the libusb link and the Windows-spooler link are "the USB printer" to a caller.
        val usbPrinters: List<DetectedPrinter> get() = printers.filterNot { it.link is Link.Network }
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
        val usb = scanUsb()
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

    /**
     * The USB source, platform-chosen. On Windows the printer's own driver is the default pipe
     * (no libusb, no Zadig); libusb is the fallback only when the spooler turns up no Epson — a
     * printer that was rebound to WinUSB with Zadig has left the spooler anyway, so the two never
     * list the same unit twice. Everywhere else this is just the libusb scan.
     */
    private fun scanUsb(): UsbPrinterScanner.ScanResult {
        if (System.getProperty("os.name").lowercase().contains("win")) {
            val spooled = WindowsPrinterScanner.scan()
            if (spooled is WindowsPrinterScanner.ScanResult.Ok && spooled.printers.isNotEmpty()) {
                return UsbPrinterScanner.ScanResult.Ok(spooled.printers)
            }
        }
        return UsbPrinterScanner.scan()
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
        advertisedNow = true,
    )

    private fun fromSaved(entry: SavedPrinters.Saved) = identified(
        link = entry.link,
        fallbackProduct = entry.product,
        manufacturer = null,
        advertisedNow = false,
    )

    /** Asks the printer itself before falling back to what advertised it. */
    private fun identified(
        link: Link.Network,
        fallbackProduct: String?,
        manufacturer: String?,
        advertisedNow: Boolean,
    ): DetectedPrinter = identified(
        link = link,
        fallbackProduct = fallbackProduct,
        manufacturer = manufacturer,
        advertisedNow = advertisedNow,
        identity = SnmpTransport.identify(link.host, port = link.port),
    )

    /** Pure half of network discovery, visible to tests so remembered presence cannot regress. */
    internal fun identified(
        link: Link.Network,
        fallbackProduct: String?,
        manufacturer: String?,
        advertisedNow: Boolean,
        identity: SnmpTransport.Companion.Identity?,
    ): DetectedPrinter {
        val reachable = advertisedNow || identity != null

        return DetectedPrinter(
            link = link,
            manufacturer = manufacturer,
            product = identity?.model ?: fallbackProduct,
            serial = identity?.serial,
            accessNote = if (reachable) null else "Saved address did not answer this scan.",
            reachable = reachable,
        )
    }
}
