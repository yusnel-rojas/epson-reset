package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.net.EpsonMib
import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A printer's SNMP agent, on loopback. */
private class FakeAgent(private val answer: (List<Int>) -> ByteArray?) : AutoCloseable {

    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())

    val link: Link.Network get() = Link.Network(socket.localAddress.hostAddress, 9100)
    val port: Int get() = socket.localPort

    /** Every OID asked for, in order. */
    val asked = mutableListOf<List<Int>>()

    private val thread = Thread {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            val request = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(request) }.getOrElse { return@Thread }

            val packet = request.data.copyOfRange(0, request.length)
            val oid = oidOf(packet) ?: continue
            synchronized(asked) { asked += oid }

            val value = answer(oid)
            val reply = if (value == null) noSuchName(oid) else response(oid, value)
            runCatching { socket.send(DatagramPacket(reply, reply.size, request.address, request.port)) }
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        socket.close()
        thread.interrupt()
    }

    /** Walks the request to the varbind's OID. */
    private fun oidOf(packet: ByteArray): List<Int>? = runCatching {
        var i = 0

        fun length(): Int {
            val first = packet[i++].toInt() and 0xFF
            if (first and 0x80 == 0) return first
            var value = 0
            repeat(first and 0x7F) { value = (value shl 8) or (packet[i++].toInt() and 0xFF) }
            return value
        }

        fun enter() {
            i++
            length()
        } // constructed: step inside
        fun skip() {
            i++
            val n = length()
            i += n
        } // primitive: step over

        enter() // message
        skip() // version
        skip() // community
        enter() // PDU
        skip() // request id
        skip() // error status
        skip() // error index
        enter() // varbind list
        enter() // varbind

        require(packet[i] == 0x06.toByte()) { "expected an OID" }
        i++
        val size = length()
        decodeOid(packet.copyOfRange(i, i + size))
    }.getOrNull()

    private fun decodeOid(body: ByteArray): List<Int> {
        val parts = mutableListOf(body[0].toInt() / 40, body[0].toInt() % 40)
        var value = 0
        for (index in 1 until body.size) {
            val byte = body[index].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F)
            if (byte and 0x80 == 0) {
                parts += value
                value = 0
            }
        }
        return parts
    }

    private fun response(oid: List<Int>, value: ByteArray): ByteArray {
        val varbind = tlv(0x30, encodeOid(oid) + tlv(0x04, value))
        val pdu = tlv(
            0xA2,
            tlv(0x02, byteArrayOf(1)) + tlv(0x02, byteArrayOf(0)) + tlv(0x02, byteArrayOf(0)) +
                tlv(0x30, varbind),
        )
        return tlv(0x30, tlv(0x02, byteArrayOf(0)) + tlv(0x04, "public".toByteArray()) + pdu)
    }

    private fun noSuchName(oid: List<Int>): ByteArray {
        val varbind = tlv(0x30, encodeOid(oid) + tlv(0x05, ByteArray(0)))
        val pdu = tlv(
            0xA2,
            tlv(0x02, byteArrayOf(1)) + tlv(0x02, byteArrayOf(2)) + tlv(0x02, byteArrayOf(1)) +
                tlv(0x30, varbind),
        )
        return tlv(0x30, tlv(0x02, byteArrayOf(0)) + tlv(0x04, "public".toByteArray()) + pdu)
    }

    private fun encodeOid(oid: List<Int>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(oid[0] * 40 + oid[1])
        for (part in oid.drop(2)) {
            if (part < 0x80) {
                body.write(part)
            } else {
                val chunks = ArrayDeque<Int>()
                var remaining = part
                while (remaining > 0) {
                    chunks.addFirst(remaining and 0x7F)
                    remaining = remaining ushr 7
                }
                chunks.forEachIndexed { i, c -> body.write(if (i == chunks.size - 1) c else c or 0x80) }
            }
        }
        return tlv(0x06, body.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        if (content.size < 0x80) {
            out.write(content.size)
        } else {
            out.write(0x81)
            out.write(content.size)
        }
        out.write(content)
        return out.toByteArray()
    }
}

private fun bytes(text: String): ByteArray =
    text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

/** The passthrough prefixes every reply with a status byte, refusals included. */
private fun passthroughReply(payload: String) = byteArrayOf(0) + bytes(payload)

private val readReply = passthroughReply("40 42 44 43 20 50 53 0D 0A 45 45 3A 30 30 31 43 31 39 3B")
private val refusal = passthroughReply("7C 7C 3A 34 31 3A 4E 41 3B 0C")

class SnmpTransportTest {

    private fun open(agent: FakeAgent): SnmpTransport = assertIs<SnmpTransport.OpenResult.Ok>(
        SnmpTransport.open(agent.link, timeoutMs = 400, port = agent.port),
    ).transport

    private val readPacket = SequenceGenerator.readPacket(13898, 28)
    private val writePacket = SequenceGenerator.writePacket(13898, 28, 0, "Nbsjcbzb")

    @Test
    fun `a read goes out as a passthrough oid and its reply comes back`() {
        FakeAgent { oid ->
            when {
                oid == EpsonMib.DEVICE_ID -> "MFG:EPSON;MDL:ET-2825;".toByteArray()
                oid.take(EpsonMib.PASSTHROUGH.size) == EpsonMib.PASSTHROUGH -> readReply
                else -> null
            }
        }.use { agent ->
            val transport = open(agent)

            assertTrue(transport.send(readPacket))
            assertEquals("@BDC PS\r\nEE:001C19;", String(transport.drain(), Charsets.ISO_8859_1))

            // The command travelled as sub-identifiers, unchanged.
            val commandOid = agent.asked.last().drop(EpsonMib.PASSTHROUGH.size)
            assertEquals(listOf(124, 124, 7, 0, 74, 54, 65, 190, 160, 28, 0), commandOid)
        }
    }

    /**
     * The gate. A write must not leave the machine until this connection has proved the printer
     * answers reads — which is the printer deciding, not a policy switch.
     */
    @Test
    fun `a write is refused before any read has succeeded`() {
        FakeAgent { oid -> if (oid == EpsonMib.DEVICE_ID) "MFG:EPSON;".toByteArray() else readReply }
            .use { agent ->
                val transport = open(agent)

                assertFalse(transport.send(writePacket), "the write was accepted")
                assertTrue(transport.refusedWrite)
                assertFalse(transport.readProven)

                // Only the open-time identity query reached the agent.
                assertEquals(listOf(EpsonMib.DEVICE_ID), agent.asked.toList())
            }
    }

    @Test
    fun `a write is allowed once a read has come back on the same connection`() {
        FakeAgent { oid ->
            if (oid == EpsonMib.DEVICE_ID) "MFG:EPSON;".toByteArray() else readReply
        }.use { agent ->
            val transport = open(agent)

            transport.send(readPacket)
            transport.drain()
            assertTrue(transport.readProven, "the read should have opened the gate")

            assertTrue(transport.send(writePacket))
            assertFalse(transport.refusedWrite)
        }
    }

    /**
     * A printer that declines factory commands never produces the evidence, so the gate stays shut
     * — and it says why rather than reporting a dead wire.
     */
    @Test
    fun `a refused read leaves the gate shut and explains itself`() {
        FakeAgent { oid ->
            if (oid == EpsonMib.DEVICE_ID) "MFG:EPSON;".toByteArray() else refusal
        }.use { agent ->
            val transport = open(agent)

            transport.send(readPacket)
            val reply = transport.drain()

            assertTrue(
                String(reply, Charsets.ISO_8859_1).contains(":41:NA;"),
                "expected the refusal, got " + String(reply, Charsets.ISO_8859_1),
            )
            assertFalse(transport.readProven)
            assertTrue(assertNotNull(transport.refusal).contains("USB"))

            assertFalse(transport.send(writePacket), "a refused printer must not take a write")
        }
    }

    /** Channel and credit packets have no equivalent, so they cost nothing and reach nobody. */
    @Test
    fun `handshake and credit packets are dropped rather than sent`() {
        FakeAgent { oid -> if (oid == EpsonMib.DEVICE_ID) "MFG:EPSON;".toByteArray() else null }
            .use { agent ->
                val transport = open(agent)

                for (packet in SequenceGenerator.handshake() + SequenceGenerator.creditPair()) {
                    assertTrue(transport.send(packet))
                    assertEquals(0, transport.drain().size)
                }

                assertEquals(listOf(EpsonMib.DEVICE_ID), agent.asked.toList())
            }
    }

    @Test
    fun `a device that is not an Epson fails to open, with a reason`() {
        FakeAgent { null }.use { agent ->
            val failed = assertIs<SnmpTransport.OpenResult.Failed>(
                SnmpTransport.open(agent.link, timeoutMs = 400, port = agent.port),
            )

            assertTrue(failed.message.contains("not an Epson"), failed.message)
            assertNotNull(failed.remedy)
        }
    }

    @Test
    fun `an address with no agent times out with a remedy`() {
        val free = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }

        val failed = assertIs<SnmpTransport.OpenResult.Failed>(
            SnmpTransport.open(
                Link.Network(InetAddress.getLoopbackAddress().hostAddress),
                timeoutMs = 200,
                port = free,
            ),
        )

        assertTrue(failed.message.contains("did not answer"), failed.message)
        assertTrue(assertNotNull(failed.remedy).contains("SNMP"))
    }

    /**
     * The reason discovery pays for an SNMP round trip: DNS-SD and the USB descriptor both say
     * `ET-2820 Series`, which is a different database entry from the ET-2825 actually there.
     */
    @Test
    fun `identify prefers the exact model over the marketing name`() {
        FakeAgent { oid ->
            when (oid) {
                EpsonMib.MODEL -> "ET-2825".toByteArray()
                EpsonMib.PRODUCT -> "ET-2820 Series".toByteArray()
                EpsonMib.SERIAL -> "QWER012345".toByteArray()
                else -> null
            }
        }.use { agent ->
            val identity = assertNotNull(
                SnmpTransport.identify(agent.link.host, timeoutMs = 400, port = agent.port),
            )

            assertEquals("ET-2825", identity.model)
            assertEquals("ET-2820 Series", identity.product)
            assertEquals("QWER012345", identity.serial)
        }
    }

    @Test
    fun `identify returns nothing when the device answers none of the oids`() {
        FakeAgent { null }.use { agent ->
            assertNull(SnmpTransport.identify(agent.link.host, timeoutMs = 300, port = agent.port))
        }
    }
}
