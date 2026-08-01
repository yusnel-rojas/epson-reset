package nl.redlabs.epsonreset.usb

import com.sun.jna.Memory
import com.sun.jna.ptr.IntByReference
import nl.redlabs.epsonreset.Diag
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link

/**
 * Enumerates Epson printers that Windows already has a driver for, through the print spooler. No
 * libusb and no Zadig: the printer stays a normal Windows printer and this reads it over its own
 * driver. Network queues are left out — the SNMP path already covers those.
 */
object WindowsPrinterScanner {

    sealed interface ScanResult {
        data class Ok(val printers: List<DetectedPrinter>) : ScanResult
        data class Failed(val message: String) : ScanResult
    }

    fun scan(): ScanResult {
        val spool = Winspool.instance
            ?: return ScanResult.Failed(Winspool.loadError ?: "winspool.drv could not be loaded")

        return try {
            val queues = enumerate(spool)
            Diag.log { "[DBG] EnumPrinters found ${queues.size} queue(s)" }
            queues.forEach { q ->
                Diag.log { "[DBG]   queue=\"${q.name}\" port=${q.port} driver=${q.driver} epsonUsb=${isEpsonUsb(q)}" }
            }
            ScanResult.Ok(queues.filter(::isEpsonUsb).map(::toPrinter))
        } catch (e: Exception) {
            ScanResult.Failed(e.message ?: e.toString())
        }
    }

    /** One installed queue, reduced to the strings the filter and matcher need. */
    internal data class Queue(val name: String, val port: String?, val driver: String?)

    private fun enumerate(spool: Winspool): List<Queue> {
        val flags = Winspool.PRINTER_ENUM_LOCAL or Winspool.PRINTER_ENUM_CONNECTIONS

        // First call sizes the buffer; the second fills it.
        val needed = IntByReference()
        val returned = IntByReference()
        spool.EnumPrintersW(flags, null, Winspool.LEVEL_2, null, 0, needed, returned)
        if (needed.value <= 0) return emptyList()

        val buffer = Memory(needed.value.toLong())
        if (!spool.EnumPrintersW(flags, null, Winspool.LEVEL_2, buffer, needed.value, needed, returned)) {
            return emptyList()
        }

        val count = returned.value
        if (count <= 0) return emptyList()

        val template = Winspool.PrinterInfo2(buffer)

        @Suppress("UNCHECKED_CAST")
        val entries = template.toArray(count) as Array<Winspool.PrinterInfo2>
        return entries.map { Queue(it.printerName.orEmpty(), it.portName, it.driverName) }
    }

    /**
     * An Epson reachable over USB, not the network. The driver or model name carries "EPSON", and
     * the port is a local USB port (`USB001`, `ESDPRT001`) rather than a network one (`WSD-…`,
     * an IP address, a standard TCP/IP port).
     */
    internal fun isEpsonUsb(q: Queue): Boolean {
        val looksEpson = listOfNotNull(q.name, q.driver).any { it.contains("EPSON", ignoreCase = true) }
        val port = q.port?.uppercase().orEmpty()
        val looksUsb = port.startsWith("USB") || port.startsWith("ESDPRT")
        return looksEpson && looksUsb
    }

    private fun toPrinter(q: Queue) = DetectedPrinter(
        link = Link.WindowsPrinter(queueName = q.name, port = q.port, driver = q.driver),
        // The queue name is the model as Windows knows it — usually the family ("EPSON ET-2820
        // Series"), which DeviceMatcher and the model picker resolve to a unit, the same as USB.
        product = q.name,
        manufacturer = "EPSON".takeIf { q.driver?.contains("EPSON", ignoreCase = true) == true },
    )
}
