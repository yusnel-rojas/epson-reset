package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusCommandTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * `st` is a plain command, not a factory one: two ASCII letters, a little-endian payload
     * length, then the payload.
     */
    @Test
    fun `status command is st with no factory prefix`() {
        assertEquals("02 02 00 0B 00 00 73 74 01 00 01", hex(SequenceGenerator.statusPacket()))
    }

    @Test
    fun `control commands carry their own two letters`() {
        val packet = SequenceGenerator.controlCommand("di", listOf(0x01))

        assertEquals(0x64, packet[6].toInt() and 0xFF) // 'd'
        assertEquals(0x69, packet[7].toInt() and 0xFF) // 'i'
        assertEquals(0x01, packet[8].toInt() and 0xFF) // payload length, little-endian
        assertEquals(0x00, packet[9].toInt() and 0xFF)
    }

    @Test
    fun `factory commands still frame as the golden write packet`() {
        // The refactor that introduced controlCommand must not have moved the EEPROM bytes.
        assertEquals(
            "02 02 00 1A 00 00 7C 7C 10 00 01 00 42 BD 21 3A 00 00 5A 76 75 62 6E 70 73 6A",
            hex(SequenceGenerator.writePacket(1, 58, 0, "Zvubnpsj")),
        )
    }
}

class StatusParsingTest {

    /**
     * A `@BDC ST2` reply captured from a real ET-2820 on 2026-07-26, including the D4 framing it
     * arrived in.
     */
    private val captured: ByteArray = (
        "00 00 00 0C 01 00 84 00 02 02 00 00 02 02 00 8B 00 01 " +
            "40 42 44 43 20 53 54 32 0D 0A 79 00 01 01 04 06 02 01 00 0A 03 11 00 04 " +
            "0F 0D 03 01 00 0B 05 03 41 04 02 3F 03 01 42 10 03 01 0A 4E 13 01 01 " +
            "19 0C 00 00 00 00 00 75 6E 6B 6E 6F 77 6E 28 04 FF 01 00 00 " +
            "36 20 FF FF FF FF FF FF FF FF 20 04 00 00 8E 00 00 00 22 00 00 00 " +
            "FF FF FF FF 3F 00 00 00 00 00 00 00 37 05 02 00 00 00 00 " +
            "40 0A 58 58 58 58 30 30 30 30 30 30 4B 01 09 54 08 01 00 00 00 00 00 00 00"
        ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parses the captured block into its fields`() {
        val report = assertNotNull(Status.parse(captured))
        assertEquals(13, report.fields.size)
    }

    /** Field 0x40 is the serial. */
    @Test
    fun `serial decodes from field 0x40`() {
        val report = assertNotNull(Status.parse(captured))
        assertEquals("XXXX000000", report.serial)
    }

    /**
     * The payload length is binary, and 0x0A is a perfectly ordinary value for its low byte — a
     * ten-byte payload, a 2570-byte one.
     */
    @Test
    fun `a payload length of 0x0A is not mistaken for a line terminator`() {
        val serial = "X4KP0219"
        val payload = byteArrayOf(0x40, serial.length.toByte()) +
            serial.toByteArray(Charsets.ISO_8859_1)
        assertEquals(0x0A, payload.size, "this test only bites at a length of exactly 0x0A")

        val block = "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(payload.size.toByte(), 0) + payload

        assertEquals(serial, assertNotNull(Status.parse(block)).serial)
    }

    @Test
    fun `field types and lengths survive the walk`() {
        val report = assertNotNull(Status.parse(captured))

        assertEquals(1, assertNotNull(report[0x01]).value.size)
        assertEquals(13, assertNotNull(report[Status.TYPE_INK]).value.size)
        assertEquals(32, assertNotNull(report[0x36]).value.size)
        assertEquals(10, assertNotNull(report[Status.TYPE_SERIAL]).value.size)
    }

    @Test
    fun `32-byte block decodes to little-endian words`() {
        val report = assertNotNull(Status.parse(captured))
        val words = assertNotNull(report[0x36]).words32

        assertEquals(8, words.size)
        assertEquals(0xFFFFFFFFL, words[0])
        assertEquals(1056L, words[2])
        assertEquals(142L, words[3])
    }

    /** Unknown types must stay unnamed rather than acquire a plausible-sounding label. */
    @Test
    fun `unidentified fields are not given invented names`() {
        val report = assertNotNull(Status.parse(captured))

        assertEquals("unknown", assertNotNull(report[0x36]).name)
        assertEquals("unknown", assertNotNull(report[0x28]).name)
        assertEquals("serial", assertNotNull(report[0x40]).name)
    }

    /**
     * Cross-check against an independent reading of this same printer: Black 11%, Yellow 65%,
     * Magenta 63%, Cyan 66% — so our decode of field 0x0F must agree exactly, colour for colour.
     */
    @Test
    fun `ink levels match the reference tool colour for colour`() {
        val report = assertNotNull(Status.parse(captured))
        val levels = report.inkLevels

        assertEquals(4, levels.size)
        assertEquals(
            mapOf("Black" to 11, "Yellow" to 65, "Magenta" to 63, "Cyan" to 66),
            levels.associate { it.colour to it.percent },
        )
    }

    @Test
    fun `a low cartridge is flagged`() {
        val report = assertNotNull(Status.parse(captured))
        val black = report.inkLevels.first { it.colour == "Black" }

        assertTrue(black.isLow, "black at ${black.percent}% should read as low")
        assertTrue(report.inkLevels.filter { it.colour != "Black" }.none { it.isLow })
    }

    @Test
    fun `an ink field with an implausible width yields nothing rather than noise`() {
        val broken = Status.Field(Status.TYPE_INK, byteArrayOf(0x01, 0x02, 0x03))
        val report = Status.Report(listOf(broken), ByteArray(0))

        assertTrue(report.inkLevels.isEmpty())
    }

    /**
     * The capture is an idle ET-2820, which is what makes it the reference for [Status.STATE_IDLE]:
     * 0x04 is the code a printer that is doing nothing reports, so 0x04 is the code a reset runs
     * on.
     */
    @Test
    fun `the idle capture reports state 0x04 and objects to nothing`() {
        val report = assertNotNull(Status.parse(captured))

        assertEquals(Status.STATE_IDLE, report.state)
        assertNull(report.errorCode, "an idle printer sent no error field at all")
        assertNull(report.writeBlocker)
    }

    private fun blockWith(vararg fields: Pair<Int, Int>): Status.Report {
        val payload = fields.fold(ByteArray(0)) { acc, (type, value) ->
            acc + byteArrayOf(type.toByte(), 1, value.toByte())
        }
        return assertNotNull(
            Status.parse(
                "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(payload.size.toByte(), 0) + payload,
            ),
        )
    }

    @Test
    fun `a busy printer objects to being written to`() {
        val blocker = assertNotNull(blockWith(0x01 to 0x02).writeBlocker)
        assertTrue(blocker.contains("busy"), blocker)
    }

    /**
     * An allow-list, so a code this doesn't have a name for still refuses. Getting this backwards
     * would make every undocumented state read as permission to write.
     */
    @Test
    fun `an unknown state code still blocks`() {
        val blocker = assertNotNull(blockWith(0x01 to 0x7B).writeBlocker)
        assertTrue(blocker.contains("0x7B"), blocker)
    }

    /** The error field colours the message; the state field is what decides the refusal. */
    @Test
    fun `an error state carries the reported error code`() {
        val blocker = assertNotNull(blockWith(0x01 to 0x00, 0x02 to 0x02).writeBlocker)

        assertTrue(blocker.contains("cover open"), blocker)
    }

    @Test
    fun `an unlisted error code is shown raw rather than guessed at`() {
        val blocker = assertNotNull(blockWith(0x01 to 0x00, 0x02 to 0x77).writeBlocker)

        assertTrue(blocker.contains("0x77"), blocker)
    }

    /**
     * Silence is not an objection. A block with no state field is the pre-check behaviour, and
     * manufacturing a refusal out of it would strand any firmware that reports differently.
     */
    @Test
    fun `a block with no state field blocks nothing`() {
        val report = assertNotNull(Status.parse(minimalSerialBlock()))

        assertNull(report.state)
        assertNull(report.writeBlocker)
    }

    private fun minimalSerialBlock(): ByteArray {
        val payload = byteArrayOf(0x40, 4) + "ABCD".toByteArray(Charsets.ISO_8859_1)
        return "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(payload.size.toByte(), 0) + payload
    }

    @Test
    fun `a buffer without a status block yields null`() {
        assertNull(Status.parse(ByteArray(0)))
        assertNull(Status.parse("@BDC PS\r\nEE:001C08;".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `a truncated block stops cleanly instead of overrunning`() {
        val truncated = captured.copyOfRange(0, captured.size - 40)
        val report = assertNotNull(Status.parse(truncated))

        assertTrue(report.fields.isNotEmpty())
        assertTrue(report.fields.size < 13)
    }
}
