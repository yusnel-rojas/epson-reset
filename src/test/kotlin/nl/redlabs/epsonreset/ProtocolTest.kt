package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Golden packet, derived by hand for the real PX-7V entry
     * (rkey 1, wkey "Zvubnpsj", first platen address 58).
     */
    @Test
    fun `write packet matches the reference byte layout`() {
        val packet = SequenceGenerator.writePacket(
            readKey = 1,
            address = 58,
            value = 0,
            writeKey = "Zvubnpsj",
        )

        val expected = "02 02 00 1A 00 00 7C 7C 10 00 01 00 42 BD 21 3A 00 00 5A 76 75 62 6E 70 73 6A"
        assertEquals(expected, hex(packet))
    }

    @Test
    fun `d4 length is big endian while the inner epson length is little endian`() {
        val packet = SequenceGenerator.writePacket(1, 58, 0, "Zvubnpsj")

        // D4 header length at [2..3], big-endian, counting from the socket bytes.
        val d4Len = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        assertEquals(packet.size, d4Len)

        // Inner Epson frame length at [8..9], little-endian, counting the inner payload only.
        val innerLen = (packet[8].toInt() and 0xFF) or ((packet[9].toInt() and 0xFF) shl 8)
        assertEquals(packet.size - 10, innerLen)
    }

    @Test
    fun `command byte is echoed plain inverted and rotated`() {
        val packet = SequenceGenerator.writePacket(0, 0, 0, "")
        assertEquals(0x42, packet[12].toInt() and 0xFF)
        assertEquals(0xBD, packet[13].toInt() and 0xFF)
        assertEquals(0x21, packet[14].toInt() and 0xFF)
    }

    @Test
    fun `address and value land in the documented positions`() {
        val packet = SequenceGenerator.writePacket(0, 0x1234, 0x5A, "K")
        assertEquals(0x34, packet[15].toInt() and 0xFF)
        assertEquals(0x12, packet[16].toInt() and 0xFF)
        assertEquals(0x5A, packet[17].toInt() and 0xFF)
    }

    @Test
    fun `sequence is three init packets plus a credit pair and write per address`() {
        val model = PrinterModel(
            name = "TEST",
            readKey = 1,
            writeKey = "Zvubnpsj",
            padGroups = listOf(
                PadGroup("Platen Pad Counter", "platen", listOf(58, 59), listOf(0, 0)),
                PadGroup("Main Pad Counter", "main", listOf(87), listOf(0)),
            ),
        )

        val sequence = SequenceGenerator.generate(model)

        assertEquals(3 + 3 * 3, sequence.size)
        assertEquals(3, sequence.count { Executor.isWritePacket(it) })

        // Every write must be immediately preceded by its credit grant/request pair.
        sequence.forEachIndexed { i, packet ->
            if (Executor.isWritePacket(packet)) {
                assertTrue(i >= 2, "write at $i has no room for a credit pair")
                assertFalse(Executor.isWritePacket(sequence[i - 1]))
                assertFalse(Executor.isWritePacket(sequence[i - 2]))
            }
        }
    }

    @Test
    fun `init packets are not mistaken for writes`() {
        val model = PrinterModel(
            name = "TEST",
            writeKey = "K",
            padGroups = listOf(PadGroup("Waste", "main", listOf(1), listOf(0))),
        )
        val sequence = SequenceGenerator.generate(model)

        assertFalse(Executor.isWritePacket(sequence[0]), "EJL init")
        assertFalse(Executor.isWritePacket(sequence[1]), "D4 init")
        assertFalse(Executor.isWritePacket(sequence[2]), "D4 open")
    }

    @Test
    fun `reset values are written verbatim rather than assumed zero`() {
        val model = PrinterModel(
            name = "TEST",
            writeKey = "",
            padGroups = listOf(PadGroup("Waste", "main", listOf(10, 11), listOf(0x00, 0x94))),
        )
        val writes = SequenceGenerator.generate(model).filter { Executor.isWritePacket(it) }

        assertEquals(2, writes.size)
        assertEquals(0x00, writes[0][17].toInt() and 0xFF)
        assertEquals(0x94, writes[1][17].toInt() and 0xFF)
    }

    @Test
    fun `write key is encoded one byte per character`() {
        val packet = SequenceGenerator.writePacket(0, 0, 0, "Yutamori")
        assertContentEquals(
            "Yutamori".toByteArray(Charsets.ISO_8859_1),
            packet.copyOfRange(18, packet.size),
        )
    }
}

class ExecutorTest {

    private val model = PrinterModel(
        name = "TEST",
        readKey = 1,
        writeKey = "Zvubnpsj",
        padGroups = listOf(PadGroup("Waste", "main", listOf(58, 59), listOf(0, 0))),
    )

    @Test
    fun `acknowledged writes report success`() {
        val sequence = SequenceGenerator.generate(model)
        val result = Executor.execute(
            transport = FakeTransport(ackWrites = true),
            sequence = sequence,
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
        )

        assertTrue(result.success, result.error)
        assertEquals(2, result.writesTotal)
        assertEquals(2, result.writesAcknowledged)
        assertEquals(sequence.size, result.packetsSent)
    }

    @Test
    fun `write listener identifies each address as it starts and is acknowledged`() {
        val events = mutableListOf<Triple<Int, Int, Executor.WriteState>>()

        Executor.execute(
            transport = FakeTransport(ackWrites = true),
            sequence = SequenceGenerator.generate(model),
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
            listener = object : Executor.Listener {
                override fun onWrite(address: Int, value: Int, state: Executor.WriteState) {
                    events += Triple(address, value, state)
                }
            },
        )

        assertEquals(
            listOf(
                Triple(58, 0, Executor.WriteState.WRITING),
                Triple(58, 0, Executor.WriteState.ACKNOWLEDGED),
                Triple(59, 0, Executor.WriteState.WRITING),
                Triple(59, 0, Executor.WriteState.ACKNOWLEDGED),
            ),
            events,
        )
    }

    @Test
    fun `a rejecting printer aborts on the first NG instead of retrying`() {
        val transport = FakeTransport(ackWrites = false)
        val result = Executor.execute(
            transport = transport,
            sequence = SequenceGenerator.generate(model),
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
        )

        assertFalse(result.success)
        assertEquals(1, result.writesRejected)
        assertTrue(result.error.contains("REJECTED"), result.error)
        // Aborted at the first write, so the second address was never touched.
        assertEquals(1, result.writesTotal)
    }

    @Test
    fun `a silent printer is a failure rather than a false success`() {
        val silent = object : nl.redlabs.epsonreset.protocol.Transport {
            override fun send(packet: ByteArray) = true
            override fun drain() = ByteArray(0)
        }

        val result = Executor.execute(
            transport = silent,
            sequence = SequenceGenerator.generate(model),
            options = Executor.Options(maxWriteAttempts = 2, interPacketDelayMs = 0, retryDelayMs = 0),
        )

        assertFalse(result.success)
        assertTrue(result.error.contains("not acknowledged"), result.error)
    }

    @Test
    fun `a dead transport fails instead of reporting packets sent`() {
        val dead = object : nl.redlabs.epsonreset.protocol.Transport {
            override fun send(packet: ByteArray) = false
            override fun drain() = ByteArray(0)
        }

        val result = Executor.execute(
            transport = dead,
            sequence = SequenceGenerator.generate(model),
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
        )

        assertFalse(result.success)
        assertEquals(0, result.packetsSent)
        assertTrue(result.error.contains("Transport failure"), result.error)
    }

    @Test
    fun `cancellation stops the run partway`() {
        var seen = 0
        val result = Executor.execute(
            transport = FakeTransport(),
            sequence = SequenceGenerator.generate(model),
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
            isCancelled = { seen++ >= 2 },
        )

        assertFalse(result.success)
        assertTrue(result.error.contains("Cancelled"), result.error)
    }

    @Test
    fun `ack detection keys on the exact tokens`() {
        assertTrue(Executor.isWriteOkAck("||:42:OK;".toByteArray()))
        assertTrue(Executor.isWriteNgAck("noise||:42:NG;more".toByteArray()))
        assertFalse(Executor.isWriteOkAck("||:41:OK;".toByteArray()))
        assertFalse(Executor.isWriteOkAck(ByteArray(0)))
    }

    @Test
    fun `hex dump renders 16 columns with an ascii gutter`() {
        val dump = Executor.hexDump("AB".toByteArray())
        assertTrue(dump.startsWith("41 42 "), dump)
        assertTrue(dump.trimEnd().endsWith("| AB"), dump)
    }
}
