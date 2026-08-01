package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.EscpRemote
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.usb.RawPrinterChannel
import nl.redlabs.epsonreset.usb.WindowsPrinterScanner
import nl.redlabs.epsonreset.usb.WinspoolTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A spooler pipe that records what was written and hands back a scripted reply, optionally chunked. */
private class FakeChannel(private val reply: ByteArray = ByteArray(0), private val chunk: Int = Int.MAX_VALUE) :
    RawPrinterChannel {
    val written = mutableListOf<ByteArray>()
    var closed = false
        private set

    private var pos = 0

    override fun write(data: ByteArray): Int {
        written += data.copyOf()
        return data.size
    }

    override fun read(buffer: ByteArray): Int {
        if (pos >= reply.size) return 0
        val n = minOf(chunk, buffer.size, reply.size - pos)
        System.arraycopy(reply, pos, buffer, 0, n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
    }
}

class WinspoolTransportTest {

    /**
     * The framing decision: the spooler RAW channel is the printer's print-data service, not the
     * 1284.4 control socket, so a data packet is sent as just its ESC/P factory command with the D4
     * frame stripped — the same bytes the SNMP passthrough sends.
     */
    @Test
    fun `a data packet is sent as its ESC-P command, D4 framing stripped`() {
        val channel = FakeChannel()
        val transport = WinspoolTransport(channel, drainTimeoutMs = 200)

        val packet = SequenceGenerator.writePacket(13898, 28, 0, "Nbsjcbzb")
        assertTrue(transport.send(packet))

        assertEquals(1, channel.written.size)
        assertContentEquals(EscpRemote.remoteCommandOf(packet), channel.written.single())
    }

    /** 1284.4 channel-open and credit packets are meaningless on the print-data service — dropped. */
    @Test
    fun `handshake and credit packets are swallowed, not written`() {
        val channel = FakeChannel()
        val transport = WinspoolTransport(channel, drainTimeoutMs = 200)

        for (packet in SequenceGenerator.handshake() + SequenceGenerator.creditPair()) {
            assertTrue(transport.send(packet))
        }

        assertTrue(channel.written.isEmpty())
    }

    @Test
    fun `a read reply drains back and parses to its address and value`() {
        val reply = "@BDC PS\r\nEE:001C19;".toByteArray(Charsets.ISO_8859_1)
        val transport = WinspoolTransport(FakeChannel(reply), drainTimeoutMs = 500)

        transport.send(SequenceGenerator.readPacket(13898, 28))
        val drained = transport.drain()

        assertContentEquals(reply, drained)
        assertEquals(listOf(0x1C to 0x19), CounterReader.parseReplies(drained))
    }

    @Test
    fun `a write acknowledgement reads as OK`() {
        val ack = "||:42:OK;".toByteArray(Charsets.ISO_8859_1)
        val transport = WinspoolTransport(FakeChannel(ack), drainTimeoutMs = 500)

        transport.send(SequenceGenerator.writePacket(13898, 28, 0, "Nbsjcbzb"))
        val drained = transport.drain()

        assertTrue(Executor.isWriteOkAck(drained))
        assertFalse(Executor.isWriteNgAck(drained))
    }

    /** The back-channel can dribble in; drain must reassemble it, not stop at the first short read. */
    @Test
    fun `a reply split across several reads is reassembled`() {
        val reply = "@BDC PS\r\nEE:001C19;".toByteArray(Charsets.ISO_8859_1)
        val transport = WinspoolTransport(FakeChannel(reply, chunk = 4), drainTimeoutMs = 500)

        transport.send(SequenceGenerator.readPacket(13898, 28))
        assertContentEquals(reply, transport.drain())
    }

    @Test
    fun `a silent printer drains empty within the window`() {
        val transport = WinspoolTransport(FakeChannel(ByteArray(0)), drainTimeoutMs = 150)
        assertEquals(0, transport.drain().size)
    }

    @Test
    fun `close closes the channel once`() {
        val channel = FakeChannel()
        val transport = WinspoolTransport(channel)

        transport.close()
        transport.close()
        assertTrue(channel.closed)
    }

    @Test
    fun `the scanner keeps Epson USB queues and drops network and non-Epson ones`() {
        fun q(name: String, port: String?, driver: String? = null) = WindowsPrinterScanner.Queue(name, port, driver)

        assertTrue(WindowsPrinterScanner.isEpsonUsb(q("EPSON ET-2820 Series", "USB001", "EPSON ET-2820 Series")))
        assertTrue(WindowsPrinterScanner.isEpsonUsb(q("EPSON XP-4100 Series", "ESDPRT001")))

        // Same Epson, but reached over the network — the SNMP path owns those.
        assertFalse(WindowsPrinterScanner.isEpsonUsb(q("EPSON ET-2820 Series", "WSD-1a2b3c")))
        assertFalse(WindowsPrinterScanner.isEpsonUsb(q("EPSON ET-2820 Series", "192.168.1.50")))

        // Not an Epson at all.
        assertFalse(WindowsPrinterScanner.isEpsonUsb(q("Some Laser 400", "USB002", "Some Laser 400")))
    }
}
