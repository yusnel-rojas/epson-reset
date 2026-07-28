package nl.redlabs.epsonreset.debug

import nl.redlabs.epsonreset.protocol.EpsonD4
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.usb.LibUsbTransport
import nl.redlabs.epsonreset.usb.UsbPrinterScanner

/**
 * Hardware experiment: ask the printer for its own maintenance status, instead of computing a
 * percentage against a counter maximum nobody publishes.
 */
object StatusProbe {

    // IEEE 1284.4 transport commands, sent on socket 0.
    private const val CMD_CREDIT = 0x03
    private const val CMD_CREDIT_REQUEST = 0x04
    private const val CMD_GET_SOCKET_ID = 0x09

    @JvmStatic
    fun main(args: Array<String>) {
        val scan = UsbPrinterScanner.scan()
        val device = (scan as? UsbPrinterScanner.ScanResult.Ok)?.printers?.firstOrNull() ?: run {
            println("No Epson detected ($scan)")
            return
        }
        println("Target: ${device.displayName}  ${device.link.where}")

        val opened = LibUsbTransport.open(device, drainTimeoutMs = 1500)
        if (opened is LibUsbTransport.OpenResult.Failed) {
            println("open failed: ${opened.message} ${opened.remedy ?: ""}")
            return
        }

        (opened as LibUsbTransport.OpenResult.Ok).transport.use { t ->
            println("\n===== D4 handshake =====")
            for ((i, packet) in SequenceGenerator.handshake().withIndex()) {
                exchange(t, "handshake $i", packet)
            }

            println("\n===== Socket assignment =====")
            for (name in listOf("EPSON-DATA", "EPSON-CTRL")) {
                exchange(t, "GetSocketID \"$name\"", getSocketId(name))
            }

            println("\n===== Status on the control channel (0x02) =====")
            statusQueries(t, EpsonD4.SOCKET_EPSON_CTRL)
        }
    }

    private fun statusQueries(t: Transport, socket: Int) {
        // Already D4-wrapped by controlCommand(). `st` is the documented one; the rest are
        // cheap to try while the channel is open.
        val queries = listOf(
            "st 01 (status)" to SequenceGenerator.statusPacket(),
            "st 02" to SequenceGenerator.controlCommand("st", listOf(0x02)),
            "ia 01 (ink)" to SequenceGenerator.controlCommand("ia", listOf(0x01)),
            "pm 01" to SequenceGenerator.controlCommand("pm", listOf(0x01)),
            "di 01 (device info)" to SequenceGenerator.controlCommand("di", listOf(0x01)),
        )

        for ((label, packet) in queries) {
            val collected = java.io.ByteArrayOutputStream()

            // Credit first, or the printer has no allowance to answer.
            collected.write(exchange(t, "credit grant", creditGrant(socket), quiet = true))
            collected.write(exchange(t, "credit req", creditRequest(socket), quiet = true))

            collected.write(exchange(t, label, packet))

            // The answer often rides along with a *later* credit exchange rather than arriving on
            // the drain straight after the command — the deferral the EEPROM read already hit.
            repeat(2) {
                collected.write(exchange(t, "nudge grant", creditGrant(socket), quiet = true))
                collected.write(exchange(t, "nudge req", creditRequest(socket), quiet = true))
            }

            interpret(collected.toByteArray())
        }
    }

    /** Transport-layer packet on socket 0: `00 00 <len BE> <credit> <control> <payload>`. */
    private fun transportPacket(payload: ByteArray): ByteArray {
        val total = payload.size + 6
        return byteArrayOf(
            0x00,
            0x00,
            ((total shr 8) and 0xFF).toByte(),
            (total and 0xFF).toByte(),
            0x01,
            0x00,
        ) + payload
    }

    private fun creditGrant(socket: Int) = transportPacket(
        byteArrayOf(CMD_CREDIT.toByte(), socket.toByte(), socket.toByte(), 0x00, 0x01),
    )

    private fun creditRequest(socket: Int) = transportPacket(
        byteArrayOf(
            CMD_CREDIT_REQUEST.toByte(),
            socket.toByte(),
            socket.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00,
            0x01,
        ),
    )

    private fun getSocketId(serviceName: String) = transportPacket(
        byteArrayOf(CMD_GET_SOCKET_ID.toByte()) + serviceName.toByteArray(Charsets.ISO_8859_1),
    )

    private fun exchange(t: Transport, label: String, packet: ByteArray, quiet: Boolean = false): ByteArray {
        val sent = t.send(packet)
        val reply = t.drain()

        if (quiet && !containsBdc(reply)) return reply

        println("\n-> $label (${packet.size} bytes, sent=$sent)")
        println("<- ${reply.size} bytes")
        if (reply.isEmpty()) {
            println("   (no reply)")
        } else {
            println(Executor.hexDump(reply).prependIndent("   "))
        }
        return reply
    }

    private fun containsBdc(reply: ByteArray) = String(reply, Charsets.ISO_8859_1).contains("@BDC")

    /** Surface anything that looks like a status field, especially waste/maintenance. */
    private fun interpret(reply: ByteArray) {
        if (!containsBdc(reply)) {
            println("   >>> no @BDC status block in ${reply.size} bytes")
            return
        }

        val text = String(reply, Charsets.ISO_8859_1)
        println("   >>> " + text.map { if (it.code in 32..126) it else '.' }.joinToString(""))

        Status.parse(reply)?.let { report ->
            println("\n   --- ST2 fields (${report.fields.size}) ---")
            for (f in report.fields) {
                println("   0x%02X %-14s len %-3d  %s".format(f.type, f.name, f.value.size, f.hex))
                if (f.value.size >= 8 && f.value.size % 4 == 0) {
                    println("        as 32-bit LE words: ${f.words32}")
                }
                if (f.ascii.any { it.isLetterOrDigit() }) println("        ascii: ${f.ascii}")
            }
            report.serial?.let { println("   serial: $it") }
            report.inkLevels.takeIf { it.isNotEmpty() }?.let { println("   ink levels: $it") }
        }
    }
}
