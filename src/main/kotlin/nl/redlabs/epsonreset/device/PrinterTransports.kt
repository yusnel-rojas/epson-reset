package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.usb.LibUsbTransport
import nl.redlabs.epsonreset.usb.UsbPrintTransport
import nl.redlabs.epsonreset.usb.WinspoolTransport

/** Opens whichever transport a printer's [Link] calls for. */
object PrinterTransports {

    sealed interface OpenResult {
        data class Ok(val transport: Transport) : OpenResult

        /** [remedy] is the thing to actually do about it, when there is one. */
        data class Failed(val message: String, val remedy: String?) : OpenResult {
            val detail: String get() = listOfNotNull(message, remedy).joinToString(" ")
        }
    }

    fun open(printer: DetectedPrinter): OpenResult = when (val link = printer.link) {
        is Link.Usb -> when (val opened = LibUsbTransport.open(printer)) {
            is LibUsbTransport.OpenResult.Ok -> OpenResult.Ok(opened.transport)
            is LibUsbTransport.OpenResult.Failed -> OpenResult.Failed(opened.message, opened.remedy)
        }

        // The usbprint.sys device interface as the byte pipe — the printer's real endpoints, full
        // 1284.4, with no libusb and no Zadig. The spooler RAW channel only reaches the print-data
        // service (some firmwares *print* the factory commands), so it is the fallback solely for
        // the odd setup where no usbprint interface is registered at all.
        is Link.WindowsPrinter -> when (val direct = UsbPrintTransport.open(link)) {
            is UsbPrintTransport.OpenResult.Ok -> OpenResult.Ok(direct.transport)
            is UsbPrintTransport.OpenResult.Failed ->
                if (direct.interfaceAbsent) {
                    when (val spooled = WinspoolTransport.open(link)) {
                        is WinspoolTransport.OpenResult.Ok -> OpenResult.Ok(spooled.transport)
                        is WinspoolTransport.OpenResult.Failed ->
                            OpenResult.Failed(spooled.message, spooled.remedy)
                    }
                } else {
                    OpenResult.Failed(direct.message, direct.remedy)
                }
        }

        // Over SNMP, not port 9100: the raw print port accepts commands and answers none of
        // them. See SnmpTransport for the whole story.
        is Link.Network -> when (val opened = SnmpTransport.open(link)) {
            is SnmpTransport.OpenResult.Ok -> OpenResult.Ok(opened.transport)
            is SnmpTransport.OpenResult.Failed -> OpenResult.Failed(opened.message, opened.remedy)
        }
    }
}
