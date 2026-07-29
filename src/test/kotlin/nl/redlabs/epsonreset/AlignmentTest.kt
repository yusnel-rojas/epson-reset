package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.protocol.Alignment
import nl.redlabs.epsonreset.protocol.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden bytes throughout, captured from `escputil --align-head` (gutenprint 5.3.3,
 * `escp2-et2750`) driven through its own prompts against a FIFO — every pass answered `8`, then
 * saved. Four writes came out: patterns, choices, patterns again, save.
 *
 * They are pinned rather than described because this is the one operation here that writes settings
 * into the printer with no way to read the old ones back.
 */
class AlignmentTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private val preamble =
        "00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A 40 45 4A 4C 20 20 20 20 20 0A"

    /** One session per pass, and only the first initialises twice. */
    @Test
    fun `the pattern stream is escputils stream exactly`() {
        assertEquals(
            "$preamble 1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 54 03 00 00 00 00 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 54 03 00 00 01 00 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 54 03 00 00 02 00 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 54 03 00 00 03 00 " +
                "1B 00 00 00 1B 00 0C 1B 00 1B 00",
            hex(Alignment.patterns()),
        )
    }

    /** The chosen pair rides in the fourth parameter byte — `08` here, for every pass. */
    @Test
    fun `the choice stream is escputils stream exactly`() {
        assertEquals(
            "$preamble 1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 41 04 00 00 00 00 08 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 41 04 00 00 01 00 08 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 41 04 00 00 02 00 08 " +
                "1B 00 00 00 1B 00 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 44 41 04 00 00 03 00 08 " +
                "1B 00 00 00 1B 00 0C 1B 00 1B 00",
            hex(Alignment.choices((0..3).associateWith { 8 })),
        )
    }

    /** `SV` carries no parameters at all. */
    @Test
    fun `the save stream is escputils stream exactly`() {
        assertEquals(
            "$preamble 1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 53 56 00 00 " +
                "1B 00 00 00 1B 00 0C 1B 00 1B 00",
            hex(Alignment.save()),
        )
    }

    /** Each pass gets its own choice byte, so a per-pass answer must reach its own command. */
    @Test
    fun `each pass carries its own choice`() {
        val stream = hex(Alignment.choices(mapOf(0 to 1, 1 to 7, 2 to 8, 3 to 15)))

        assertTrue(stream.contains("44 41 04 00 00 00 00 01"))
        assertTrue(stream.contains("44 41 04 00 00 01 00 07"))
        assertTrue(stream.contains("44 41 04 00 00 02 00 08"))
        assertTrue(stream.contains("44 41 04 00 00 03 00 0F"))
    }

    /**
     * A half-answered alignment is refused rather than filled in. Defaulting a missing pass would
     * write a number nobody looked at the paper for, into the one setting here that cannot be read
     * back and restored.
     */
    @Test
    fun `an incomplete set of choices is refused`() {
        val partial = mapOf(0 to 8, 1 to 8)

        assertFalse(Alignment.isComplete(partial))
        assertNotNull(Alignment.problemWith(partial))
        val failure = assertFailsWith<IllegalArgumentException> { Alignment.choices(partial) }
        assertTrue(failure.message!!.contains("pass 3, 4"))
    }

    @Test
    fun `a pair number that is not on the sheet is refused`() {
        val offSheet = (0..3).associateWith { 16 }

        assertFalse(Alignment.isComplete(offSheet))
        assertFailsWith<IllegalArgumentException> { Alignment.choices(offSheet) }
        assertTrue(Alignment.problemWith(offSheet)!!.contains("1–15"))
    }

    @Test
    fun `a complete set in range is accepted`() {
        val complete = (0..3).associateWith { 8 }

        assertTrue(Alignment.isComplete(complete))
        assertNull(Alignment.problemWith(complete))
    }

    /** The same guarantee the rest of this feature carries: none of it can become an EEPROM write. */
    @Test
    fun `no alignment stream is an eeprom write`() {
        for (stream in listOf(Alignment.patterns(), Alignment.choices((0..3).associateWith { 8 }), Alignment.save())) {
            assertFalse(Executor.isWritePacket(stream))
            assertFalse(Executor.isReadPacket(stream))
        }
    }

    /** The warning has to name the thing that makes this different: no undo, no backup. */
    @Test
    fun `the save warning says it cannot be undone`() {
        assertTrue(Alignment.SAVE_WARNING.contains("permanently"))
        assertTrue(Alignment.SAVE_WARNING.contains("no undo"))
    }
}
