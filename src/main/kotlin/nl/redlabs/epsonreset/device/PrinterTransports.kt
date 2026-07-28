package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.usb.LibUsbTransport

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

        // Over SNMP, not port 9100: the raw print port accepts commands and answers none of
        // them. See SnmpTransport for the whole story.
        is Link.Network -> when (val opened = SnmpTransport.open(link)) {
            is SnmpTransport.OpenResult.Ok -> OpenResult.Ok(opened.transport)
            is SnmpTransport.OpenResult.Failed -> OpenResult.Failed(opened.message, opened.remedy)
        }
    }
}
