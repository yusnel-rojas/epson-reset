package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.net.EpsonMib
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.net.Snmp
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

// ── A tiny BER encoder, shared by the crafted responses and the walkable agent ──────────────────

private object Ber {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
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

    fun oid(parts: List<Int>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(parts[0] * 40 + parts[1])
        for (part in parts.drop(2)) {
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

    /** Minimal signed big-endian, the same shape `snmpget` emits for an INTEGER. */
    fun int(value: Long): ByteArray {
        val out = ArrayDeque<Byte>()
        var r = value
        do {
            out.addFirst((r and 0xFF).toByte())
            r = r shr 8
        } while (r != 0L && r != -1L)
        if (value >= 0 && (out.first().toInt() and 0x80) != 0) out.addFirst(0)
        if (value < 0 && (out.first().toInt() and 0x80) == 0) out.addFirst(0xFF.toByte())
        return out.toByteArray()
    }

    /** A GetResponse carrying one varbind: the given OID, then a value under [valueTag]. */
    fun response(oid: List<Int>, valueTag: Int, value: ByteArray, errorStatus: Int = 0): ByteArray {
        val varbind = tlv(0x30, oid(oid) + tlv(valueTag, value))
        val pdu = tlv(
            0xA2,
            tlv(0x02, byteArrayOf(0x11, 0x22, 0x33, 0x44)) +
                tlv(0x02, int(errorStatus.toLong())) +
                tlv(0x02, byteArrayOf(0)) +
                tlv(0x30, varbind),
        )
        return tlv(0x30, tlv(0x02, byteArrayOf(0)) + tlv(0x04, "public".toByteArray()) + pdu)
    }
}

// ── A synthetic printer, expressed once as a sorted OID → varbind table ──────────────────────────

private data class Varbind(val tag: Int, val value: ByteArray)

private fun intVar(value: Long) = Varbind(0x02, Ber.int(value))
private fun strVar(text: String) = Varbind(0x04, text.toByteArray(Charsets.ISO_8859_1))

/** All the OIDs a conforming inkjet answers for the two values this app reads. Values are synthetic. */
private fun syntheticPrinter(): Map<List<Int>, Varbind> {
    val life = PrinterMib.LIFE_COUNT_COLUMN
    val supplies = PrinterMib.SUPPLIES_ENTRY
    val colorant = PrinterMib.COLORANT_VALUE

    fun supplyCell(column: Int, supplyIndex: Int) = supplies + listOf(column, 1, supplyIndex)

    return buildMap {
        // prtMarkerLifeCount, as a Counter32 so the decoder's type capture is exercised.
        put(life + listOf(1, 1), Varbind(Snmp.COUNTER32, Ber.int(123_456)))

        // Supply 1 — black ink, 42% remaining.
        put(supplyCell(3, 1), intVar(1)) // colorant index
        put(supplyCell(4, 1), intVar(3)) // class: consumed
        put(supplyCell(5, 1), intVar(5)) // type: ink
        put(supplyCell(6, 1), strVar("Black ink"))
        put(supplyCell(8, 1), intVar(100)) // max capacity
        put(supplyCell(9, 1), intVar(42)) // level

        // Supply 2 — waste ink receptacle, 90% full.
        put(supplyCell(4, 2), intVar(4)) // class: receptacle
        put(supplyCell(5, 2), intVar(8)) // type: waste ink
        put(supplyCell(6, 2), strVar("Waste ink pad"))
        put(supplyCell(8, 2), intVar(100))
        put(supplyCell(9, 2), intVar(90))

        // Colorant table: index 1 names the black ink's colour.
        put(colorant + listOf(1, 1), strVar("black"))
    }
}

/** A loopback SNMP agent that serves a fixed table, answering GET and GETNEXT (a real walk). */
private class WalkAgent(table: Map<List<Int>, Varbind>) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val entries = table.entries.sortedWith { a, b -> compareOids(a.key, b.key) }

    val host: String get() = socket.localAddress.hostAddress
    val port: Int get() = socket.localPort

    private val thread = Thread {
        val buffer = ByteArray(4096)
        while (!socket.isClosed) {
            val request = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(request) }.getOrElse { return@Thread }

            val packet = request.data.copyOfRange(0, request.length)
            val (pduTag, oid) = parse(packet) ?: continue

            val reply = when (pduTag) {
                0xA0 -> entries.firstOrNull { it.key == oid }
                    ?.let { Ber.response(it.key, it.value.tag, it.value.value) }
                    ?: Ber.response(oid, 0x05, ByteArray(0), errorStatus = 2)

                0xA1 -> entries.firstOrNull { compareOids(it.key, oid) > 0 }
                    ?.let { Ber.response(it.key, it.value.tag, it.value.value) }
                    ?: Ber.response(oid, 0x82, ByteArray(0)) // endOfMibView

                else -> continue
            }
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

    /** Walks the request PDU to its tag and the varbind's OID. */
    private fun parse(packet: ByteArray): Pair<Int, List<Int>>? = runCatching {
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
        }
        fun skip() {
            i++
            val n = length()
            i += n
        } // n before i, or i += length() drops the length byte

        enter() // message
        skip() // version
        skip() // community
        val pduTag = packet[i].toInt() and 0xFF
        enter() // PDU
        skip() // request id
        skip() // error status
        skip() // error index
        enter() // varbind list
        enter() // varbind
        require(packet[i] == 0x06.toByte()) { "expected an OID" }
        i++
        val size = length()
        pduTag to decodeOid(packet.copyOfRange(i, i + size))
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

    companion object {
        fun compareOids(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val c = a[i].compareTo(b[i])
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }
    }
}

private fun compareOids(a: List<Int>, b: List<Int>) = WalkAgent.compareOids(a, b)

// ── Encoding / decoding, no sockets ─────────────────────────────────────────────────────────────

class GetNextCodecTest {

    /** GETNEXT is a GET with a different PDU tag — and nothing else may drift, or it addresses wrong. */
    @Test
    fun `a getNext request differs from a get only in the pdu tag`() {
        val get = Snmp.encodeGet(0x11223344, "public", EpsonMib.DEVICE_ID)
        val next = Snmp.encodeGetNext(0x11223344, "public", EpsonMib.DEVICE_ID)

        assertEquals(get.size, next.size)
        val differences = get.indices.filter { get[it] != next[it] }
        assertEquals(1, differences.size, "only the PDU tag may differ")
        assertEquals(0xA0, get[differences.single()].toInt() and 0xFF)
        assertEquals(0xA1, next[differences.single()].toInt() and 0xFF)
    }

    /** Reads the returned OID (including the merged first byte and a > 127 sub-id via base-128). */
    @Test
    fun `decode pulls the returned oid and value out of a getNext response`() {
        val oid = PrinterMib.LIFE_COUNT_COLUMN + listOf(1, 1)
        val response = Ber.response(oid, Snmp.COUNTER32, Ber.int(123_456))

        val result = assertIs<Snmp.Next.Ok>(Snmp.decodeNextResponse(response))
        assertEquals(oid, result.oid)
        assertEquals(Snmp.COUNTER32, result.type)
        assertEquals(123_456L, Snmp.intOf(result.value))
    }

    /** The returned OID must round-trip the same base-128 path a private OID takes (1248 > 127). */
    @Test
    fun `a returned oid with a multi-byte sub-identifier decodes intact`() {
        val oid = listOf(1, 3, 6, 1, 4, 1, 1248, 1, 2)
        val response = Ber.response(oid, 0x02, Ber.int(7))

        val result = assertIs<Snmp.Next.Ok>(Snmp.decodeNextResponse(response))
        assertEquals(oid, result.oid)
    }

    @Test
    fun `endOfMibView ends a walk`() {
        val response = Ber.response(PrinterMib.SUPPLIES_ENTRY, 0x82, ByteArray(0))
        assertEquals(Snmp.Next.EndOfMib, Snmp.decodeNextResponse(response))
    }

    @Test
    fun `a v1 noSuchName past the last oid ends a walk`() {
        val response = Ber.response(PrinterMib.SUPPLIES_ENTRY, 0x05, ByteArray(0), errorStatus = 2)
        assertEquals(Snmp.Next.EndOfMib, Snmp.decodeNextResponse(response))
    }

    @Test
    fun `rubbish is a failure, not an exception`() {
        assertIs<Snmp.Next.Failed>(Snmp.decodeNextResponse(ByteArray(0)))
        assertIs<Snmp.Next.Failed>(Snmp.decodeNextResponse(ByteArray(40) { 0xFF.toByte() }))
    }

    @Test
    fun `intOf reads negatives, for the level sentinels`() {
        assertEquals(-2L, Snmp.intOf(byteArrayOf(0xFE.toByte())))
        assertEquals(0L, Snmp.intOf(ByteArray(0)))
        assertEquals(255L, Snmp.intOf(byteArrayOf(0, 0xFF.toByte())))
    }
}

// ── Supply interpretation, independent of any transport ─────────────────────────────────────────

class SupplyTest {

    private fun supply(level: Int?, max: Int? = 100, typeCode: Int? = 5, classCode: Int? = 3) =
        PrinterMib.Supply(1, "s", classCode, typeCode, level, max, null)

    @Test
    fun `a consumable's percent is its remaining fraction`() {
        assertEquals(42, supply(level = 42).percent)
        assertFalse(supply(level = 42).isWaste)
    }

    @Test
    fun `the negative sentinels are not a percentage`() {
        assertNull(supply(level = -2).percent)
        assertEquals("unknown", supply(level = -2).levelNote)
        assertEquals("some remaining", supply(level = -3).levelNote)
        assertNull(supply(level = 50, max = -2).percent)
    }

    @Test
    fun `a low consumable and a full receptacle both warn`() {
        assertTrue(supply(level = 10).isWarn)
        assertFalse(supply(level = 80).isWarn)

        val waste = supply(level = 90, typeCode = 8, classCode = 4)
        assertTrue(waste.isWaste)
        assertTrue(waste.isWarn)
        assertFalse(supply(level = 10, typeCode = 8, classCode = 4).isWarn)
    }

    @Test
    fun `an unknown type is shown by its code, not guessed at`() {
        assertEquals("ink", supply(typeCode = 5, level = 1).typeLabel)
        assertEquals("type 99", supply(typeCode = 99, level = 1).typeLabel)
    }

    @Test
    fun `only plain ink consumables are the rows the ink card already shows`() {
        assertTrue(supply(typeCode = 5, level = 42).isInkConsumable) // ink
        assertTrue(supply(typeCode = 6, level = 42).isInkConsumable) // ink cartridge
        assertFalse(supply(typeCode = 8, classCode = 4, level = 90).isInkConsumable) // waste ink
        assertFalse(supply(typeCode = 20, level = 50).isInkConsumable) // transfer unit — additive
    }
}

// ── The walk, against a loopback agent ──────────────────────────────────────────────────────────

class PrinterMibReadTest {

    @Test
    fun `read returns the lifetime count and both supplies`() {
        WalkAgent(syntheticPrinter()).use { agent ->
            val reading = assertNotNull(
                PrinterMib.read(agent.host, port = agent.port, timeoutMs = 400),
            )

            assertEquals(123_456L, reading.lifeCount)
            assertEquals(2, reading.supplies.size)

            val ink = reading.supplies.first { it.index == 1 }
            assertEquals("Black ink", ink.description)
            assertEquals(5, ink.typeCode)
            assertEquals(42, ink.percent)
            assertEquals("black", ink.colour)
            assertFalse(ink.isWaste)

            val waste = reading.supplies.first { it.index == 2 }
            assertEquals("Waste ink pad", waste.description)
            assertTrue(waste.isWaste)
            assertEquals(90, waste.percent)
            assertTrue(waste.isWarn)
        }
    }

    @Test
    fun `a printer without the standard mib reads as nothing`() {
        WalkAgent(emptyMap()).use { agent ->
            assertNull(PrinterMib.read(agent.host, port = agent.port, timeoutMs = 400))
        }
    }
}
