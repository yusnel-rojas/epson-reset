package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.net.EpsonMib
import nl.redlabs.epsonreset.net.Snmp
import nl.redlabs.epsonreset.protocol.FactoryReply
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(text: String): ByteArray =
    text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

class SnmpEncodingTest {

    /**
     * Pinned against a request `snmpget` itself produces for the same OID, byte for byte. This is
     * the layer where a mistake addresses a different OID and silently answers the wrong question.
     */
    @Test
    fun `a get request encodes as BER`() {
        val request = Snmp.encodeGet(
            requestId = 0x11223344,
            community = "public",
            oid = EpsonMib.DEVICE_ID,
        )

        assertEquals(
            "30 30 02 01 00 04 06 70 75 62 6C 69 63 A0 23 02 04 11 22 33 44 02 01 00 02 01 00 " +
                "30 15 30 13 06 0F 2B 06 01 04 01 89 60 01 02 02 01 01 01 01 01 05 00",
            request.hex(),
        )
    }

    /**
     * 1248 needs two bytes and 43 is the merged `1.3`. Command payloads are arbitrary bytes, so
     * every value above 127 takes the multi-byte path — not an edge case here.
     */
    @Test
    fun `oid sub-identifiers above 127 use the continuation form`() {
        val encoded = Snmp.encodeOid(listOf(1, 3, 6, 1, 4, 1, 1248))

        assertEquals("06 07 2B 06 01 04 01 89 60", encoded.hex())
    }

    @Test
    fun `a command payload becomes sub-identifiers, byte values intact`() {
        // The read for address 28 with key 0x364A: || 07 00 4A 36 41 BE A0 1C 00
        val command = hex("7C 7C 07 00 4A 36 41 BE A0 1C 00")

        val oid = EpsonMib.passthroughFor(command)

        assertEquals(EpsonMib.PASSTHROUGH + listOf(124, 124, 7, 0, 74, 54, 65, 190, 160, 28, 0), oid)
        // 190 and 160 are above 127 and must survive the round trip through BER.
        assertTrue(Snmp.encodeOid(oid).hex().contains("81 3E"), "190 should encode as 81 3E")
    }
}

class SnmpDecodingTest {

    /** A GetResponse for [EpsonMib.MODEL], generated to the same encoding a real agent produced. */
    private val captured = hex(
        "30 37 02 01 00 04 06 70 75 62 6C 69 63 A2 2A 02 04 11 22 33 44 02 01 00 02 01 00 " +
            "30 1C 30 1A 06 0F 2B 06 01 04 01 89 60 01 02 02 01 01 01 02 01 04 07 45 54 2D 32 38 32 35",
    )

    @Test
    fun `pulls the value out of a response`() {
        val result = assertIs<Snmp.Result.Ok>(Snmp.decodeResponse(captured))

        assertEquals("ET-2825", result.value.toString(Charsets.ISO_8859_1))
    }

    /** Long-form lengths appear on any real status block, so the header walk must handle them. */
    @Test
    fun `a long form length in the header does not shift the cursor`() {
        val response = hex(
            "30 81 FC 02 01 00 04 06 70 75 62 6C 69 63 A2 81 EE 02 04 11 22 33 44 02 01 00 " +
                "02 01 00 30 81 DF 30 81 DC 06 0F 2B 06 01 04 01 89 60 01 02 02 01 01 01 02 01 " +
                "04 81 C8",
        ) + ByteArray(200) { 0x41 }

        val result = assertIs<Snmp.Result.Ok>(Snmp.decodeResponse(response))
        assertEquals(200, result.value.size)
    }

    @Test
    fun `noSuchName is told apart from an answer`() {
        val response = hex(
            "30 30 02 01 00 04 06 70 75 62 6C 69 63 A2 23 02 04 11 22 33 44 02 01 02 02 01 01 " +
                "30 15 30 13 06 0F 2B 06 01 04 01 89 60 01 02 02 01 01 01 02 01 05 00",
        )

        assertEquals(Snmp.Result.NoSuchObject, Snmp.decodeResponse(response))
    }

    @Test
    fun `rubbish is a failure, not an exception`() {
        assertIs<Snmp.Result.Failed>(Snmp.decodeResponse(ByteArray(0)))
        assertIs<Snmp.Result.Failed>(Snmp.decodeResponse(hex("30 05 02 01 00")))
        assertIs<Snmp.Result.Failed>(Snmp.decodeResponse(ByteArray(40) { 0xFF.toByte() }))
    }
}

class EpsonMibTest {

    /** The passthrough prefixes every reply, refusals included, with a status byte. */
    @Test
    fun `the leading status byte is stripped from a reply`() {
        val reply = hex("00 7C 7C 3A 34 31 3A 4E 41 3B 0C")

        assertEquals("7C 7C 3A 34 31 3A 4E 41 3B 0C", EpsonMib.payloadOf(reply).hex())
        assertContentEquals(ByteArray(0), EpsonMib.payloadOf(ByteArray(0)))
    }

    /**
     * The OIDs are what the app actually asks for, so a typo would be a silent wrong answer.
     * Recorded from a walk of a real ET-2825.
     */
    @Test
    fun `the oids are the ones walked off a real printer`() {
        assertEquals("1.3.6.1.4.1.1248.1.2.2.1.1.1.2.1", EpsonMib.MODEL.joinToString("."))
        assertEquals("1.3.6.1.4.1.1248.1.2.2.1.1.1.5.1", EpsonMib.SERIAL.joinToString("."))
        assertEquals("1.3.6.1.4.1.1248.1.2.2.1.1.1.4.1", EpsonMib.STATUS.joinToString("."))
        assertEquals("1.3.6.1.4.1.1248.1.2.2.44.1.1.2.1", EpsonMib.PASSTHROUGH.joinToString("."))
    }
}

class FactoryReplyTest {

    /** Captured from an ET-2825 on firmware 05.24, over SNMP. */
    private val refusedRead = hex("7C 7C 3A 34 31 3A 4E 41 3B 0C")

    @Test
    fun `a NA reply is recognised as a refusal, with the command it names`() {
        assertTrue(FactoryReply.isRefused(refusedRead))
        assertEquals(0x41, FactoryReply.refusedCommand(refusedRead))
        assertTrue(FactoryReply.explain(refusedRead)!!.contains("USB"))
    }

    @Test
    fun `a refused write is told apart from a refused read`() {
        val refusedWrite = "||:42:NA;".toByteArray(Charsets.ISO_8859_1)

        assertEquals(0x42, FactoryReply.refusedCommand(refusedWrite))
        assertTrue(FactoryReply.explain(refusedWrite)!!.contains("nothing was written"))
    }

    /**
     * The distinction that matters most: `NG` means the write key did not match this model, and the
     * model can be corrected. `NA` means the command was declined and nothing can.
     */
    @Test
    fun `an ordinary answer or rejection is not a refusal`() {
        assertFalse(FactoryReply.isRefused("||:42:OK;".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(FactoryReply.isRefused("||:42:NG;".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(FactoryReply.isRefused("@BDC PS\r\nEE:001C19;".toByteArray(Charsets.ISO_8859_1)))
        assertNull(FactoryReply.explain("||:42:NG;".toByteArray(Charsets.ISO_8859_1)))
    }
}

class D4UnwrappingTest {

    /** What the network transport does with every packet the generator hands it. */
    @Test
    fun `the command comes back out of the packet the generator built`() {
        val packet = SequenceGenerator.readPacket(13898, 28)

        val command = nl.redlabs.epsonreset.protocol.EscpRemote.remoteCommandOf(packet)

        assertEquals("7C 7C 07 00 4A 36 41 BE A0 1C 00", command!!.hex())
    }

    @Test
    fun `channel packets have nothing to unwrap`() {
        for (packet in SequenceGenerator.handshake() + SequenceGenerator.creditPair()) {
            assertTrue(nl.redlabs.epsonreset.protocol.EscpRemote.isChannelPacket(packet))
            assertNull(nl.redlabs.epsonreset.protocol.EscpRemote.remoteCommandOf(packet))
        }
    }
}
