package nl.redlabs.epsonreset.debug

import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.usb.LibUsbTransport
import nl.redlabs.epsonreset.usb.UsbPrinterScanner

/**
 * Hardware experiment: dump every exchange of the D4 handshake and then try several read-command
 * shapes, to find which one the firmware actually answers.
 */
object ReadProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val db = PrinterDatabase.load()
        val modelName = args.firstOrNull() ?: "ET-2825"
        val model = db[modelName] ?: run {
            println("'$modelName' not in database")
            return
        }

        val scan = UsbPrinterScanner.scan()
        val device = (scan as? UsbPrinterScanner.ScanResult.Ok)?.printers?.firstOrNull() ?: run {
            println("No Epson detected ($scan)")
            return
        }
        // The scan only ever yields USB links, and this probe is about USB framing specifically.
        val usb = device.link as Link.Usb
        println(
            "Target: ${device.displayName}  iface=${usb.interfaceNumber} " +
                "in=0x%02X out=0x%02X".format(usb.endpointIn, usb.endpointOut),
        )
        println("Model:  ${model.name}  rkey=${model.readKey} (0x%04X)".format(model.readKey))

        // Generous timeout: the first reply after channel-open can be slow, and a 250 ms drain may
        // simply be giving up too early.
        val opened = LibUsbTransport.open(device, drainTimeoutMs = 1500)
        if (opened is LibUsbTransport.OpenResult.Failed) {
            println("open failed: ${opened.message} ${opened.remedy ?: ""}")
            return
        }

        (opened as LibUsbTransport.OpenResult.Ok).transport.use { t ->
            step(t, "EJL init", SequenceGenerator.handshake()[0])
            val d4init = step(t, "D4 init", SequenceGenerator.handshake()[1])
            val open = step(t, "D4 open channel", SequenceGenerator.handshake()[2])

            println("\n  channel-open ACK recognised: ${Executor.isChannelOpenAck(open)}")
            if (d4init.isEmpty() && open.isEmpty()) {
                println("  !! The printer answered nothing to the handshake — the D4 channel never came up.")
                println("     Everything after this will be silent regardless of read framing.")
            }

            val address = model.padGroups.first().addresses.first()

            println("\n=== Variant A: credit pair, then standard read ===")
            step(t, "credit grant", SequenceGenerator.creditPair()[0])
            step(t, "credit req", SequenceGenerator.creditPair()[1])
            val a = step(t, "read $address", SequenceGenerator.readPacket(model.readKey, address))
            report(a)

            println("\n=== Variant B: read with credit byte = 1 in the D4 header ===")
            step(t, "credit grant", SequenceGenerator.creditPair()[0])
            step(t, "credit req", SequenceGenerator.creditPair()[1])
            val b = step(t, "read $address", withCredit(SequenceGenerator.readPacket(model.readKey, address), 1))
            report(b)

            println("\n=== Variant C: bare read, no credit pair ===")
            val c = step(t, "read $address", SequenceGenerator.readPacket(model.readKey, address))
            report(c)

            println("\n=== Variant D: plain status query (does it talk at all?) ===")
            val d = step(t, "@BDC ST", "@EJL \r\n@BDC ST\r\n".toByteArray(Charsets.ISO_8859_1))
            report(d)
        }
    }

    /** Overrides the D4 header credit byte (offset 4). */
    private fun withCredit(packet: ByteArray, credit: Int): ByteArray = packet.copyOf().also { it[4] = credit.toByte() }

    private fun step(t: Transport, label: String, packet: ByteArray): ByteArray {
        val ok = t.send(packet)
        val reply = t.drain()
        println("\n-> $label (${packet.size} bytes, sent=$ok)")
        println(Executor.hexDump(packet).prependIndent("   "))
        println("<- ${reply.size} bytes")
        println(Executor.hexDump(reply).prependIndent("   "))
        return reply
    }

    private fun report(reply: ByteArray) {
        val parsed = CounterReader.parseReply(reply)
        println("   parsed: ${parsed?.let { "address ${it.first} = 0x%02X".format(it.second) } ?: "no EE: reading"}")
        val text = String(reply, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() || it in " :;@.-" }
        if (text.isNotBlank()) println("   text:   $text")
    }
}
