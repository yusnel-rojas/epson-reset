package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.usb.RawPrinterChannel
import nl.redlabs.epsonreset.usb.UsbPrintTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An endpoint pipe that records what was written and hands back a scripted reply, optionally chunked. */
private class FakeEndpoint(private val reply: ByteArray = ByteArray(0), private val chunk: Int = Int.MAX_VALUE) :
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

class UsbPrintTransportTest {

    /**
     * The framing decision, and the difference from the spooler path: this handle reaches the real
     * endpoints, so every packet — handshake, channel open, credit, D4-framed data — goes out
     * byte-for-byte as generated, exactly as over libusb. Nothing is stripped or swallowed.
     */
    @Test
    fun `every packet goes out byte-for-byte, handshake and credit included`() {
        val endpoint = FakeEndpoint()
        val transport = UsbPrintTransport(endpoint)

        val sequence = SequenceGenerator.handshake() +
            SequenceGenerator.creditPair() +
            listOf(SequenceGenerator.writePacket(13898, 28, 0, "Nbsjcbzb"))
        for (packet in sequence) assertTrue(transport.send(packet))

        assertEquals(sequence.size, endpoint.written.size)
        sequence.zip(endpoint.written).forEach { (sent, seen) -> assertContentEquals(sent, seen) }
    }

    @Test
    fun `a short write reports failure`() {
        val truncating = object : RawPrinterChannel {
            override fun write(data: ByteArray) = data.size - 1
            override fun read(buffer: ByteArray) = 0
            override fun close() {}
        }

        assertFalse(UsbPrintTransport(truncating).send(SequenceGenerator.statusPacket()))
    }

    @Test
    fun `a read reply drains back and parses to its address and value`() {
        val reply = "@BDC PS\r\nEE:001C19;".toByteArray(Charsets.ISO_8859_1)
        val transport = UsbPrintTransport(FakeEndpoint(reply))

        transport.send(SequenceGenerator.readPacket(13898, 28))
        val drained = transport.drain()

        assertContentEquals(reply, drained)
        assertEquals(listOf(0x1C to 0x19), CounterReader.parseReplies(drained))
    }

    @Test
    fun `a write acknowledgement reads as OK`() {
        val ack = "||:42:OK;".toByteArray(Charsets.ISO_8859_1)
        val transport = UsbPrintTransport(FakeEndpoint(ack))

        transport.send(SequenceGenerator.writePacket(13898, 28, 0, "Nbsjcbzb"))
        assertTrue(Executor.isWriteOkAck(transport.drain()))
    }

    /** The endpoint hands data back in bus-sized pieces; drain must reassemble, not stop short. */
    @Test
    fun `a reply split across several reads is reassembled`() {
        val reply = "@BDC PS\r\nEE:001C19;".toByteArray(Charsets.ISO_8859_1)
        val transport = UsbPrintTransport(FakeEndpoint(reply, chunk = 4))

        transport.send(SequenceGenerator.readPacket(13898, 28))
        assertContentEquals(reply, transport.drain())
    }

    @Test
    fun `a silent printer drains empty`() {
        assertEquals(0, UsbPrintTransport(FakeEndpoint()).drain().size)
    }

    @Test
    fun `close closes the channel once`() {
        val endpoint = FakeEndpoint()
        val transport = UsbPrintTransport(endpoint)

        transport.close()
        transport.close()
        assertTrue(endpoint.closed)
    }

    // Interface selection over usbprint.sys device paths. The instance segments below are
    // synthetic, not real hardware serials.

    @Test
    fun `a single non-composite Epson printer is picked`() {
        val path = "\\\\?\\usb#vid_04b8&pid_1142#583959593030353733#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"
        assertEquals(path, UsbPrintTransport.pickEpsonInterface(listOf(path)))
    }

    /** On a composite device (printer + scanner) the engine is the lowest `mi_` interface. */
    @Test
    fun `the lowest interface of a composite device wins`() {
        val engine = "\\\\?\\usb#vid_04b8&pid_08a1&mi_00#7&1a2b3c4d&0&0000#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"
        val other = "\\\\?\\usb#vid_04b8&pid_08a1&mi_01#7&1a2b3c4d&0&0001#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"

        assertEquals(engine, UsbPrintTransport.pickEpsonInterface(listOf(other, engine)))
    }

    @Test
    fun `other vendors are not opened, and Epson is matched case-insensitively`() {
        val hp = "\\\\?\\usb#vid_03f0&pid_0053#cn12345#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"
        val epson = "\\\\?\\USB#VID_04B8&PID_1142#583959593030353733#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"

        assertNull(UsbPrintTransport.pickEpsonInterface(listOf(hp)))
        assertEquals(epson, UsbPrintTransport.pickEpsonInterface(listOf(hp, epson)))
        assertNull(UsbPrintTransport.pickEpsonInterface(emptyList()))
    }

    @Test
    fun `device path tokens parse for diagnostics`() {
        val composite = "\\\\?\\usb#vid_04b8&pid_08a1&mi_01#7&1a2b3c4d&0&0001#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"
        val plain = "\\\\?\\usb#vid_04b8&pid_1142#583959593030353733#{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"

        assertEquals(1, UsbPrintTransport.interfaceIndex(composite))
        assertNull(UsbPrintTransport.interfaceIndex(plain))
        assertEquals("08a1", UsbPrintTransport.pidOf(composite))
        assertEquals("1142", UsbPrintTransport.pidOf(plain))
    }
}
