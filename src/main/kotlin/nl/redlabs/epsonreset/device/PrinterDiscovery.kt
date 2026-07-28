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
    fun scan(includeNetwork: Boolean = true, browseTimeoutMs: Long = 2500): Result {
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

        return Result(usbPrinters + discovered + saved, usb, outcome)
    }

    private fun fromService(service: MdnsDiscovery.Service) = identified(
        link = Link.Network(service.host, service.port),
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
        val identity = SnmpTransport.identify(link.host)

        return DetectedPrinter(
            link = link,
            manufacturer = manufacturer,
            product = identity?.model ?: fallbackProduct,
            serial = identity?.serial,
            accessNote = if (identity == null && fallbackProduct == null) noteWhenAnonymous else null,
        )
    }
}
