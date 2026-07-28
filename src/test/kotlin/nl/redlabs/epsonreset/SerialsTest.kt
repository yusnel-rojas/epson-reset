package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.device.Serials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The serials are stand-ins, but the *shape* is the one an ET-2820 reports: a descriptor that
 * hex-encodes only its first eight characters and writes the last two literally. That is the case
 * the earlier fixture missed — it assumed the descriptor was hex the whole way through, so it
 * agreed with itself and never reproduced the mismatch seen on a real desk.
 */
class SerialsTest {

    /** The descriptor's spelling: hex for `QWER0123`, then `45` written plainly. */
    private val usbReported = "515745523031323345"

    /** What the same printer answers over SNMP. */
    private val netReported = "QWER012345"

    /** A descriptor that *is* hex the whole way through — the reading [Serials.canonical] assumes. */
    private val usbUniform = "51574552303132333435"

    @Test
    fun `the two links of one ET-2820 are recognised as one printer`() {
        assertTrue(Serials.same(usbReported, netReported))
        assertTrue(Serials.same(netReported, usbReported), "the answer cannot depend on the order")
    }

    @Test
    fun `a uniformly hex encoded descriptor still matches its network serial`() {
        assertTrue(Serials.same(usbUniform, netReported))
        assertEquals(netReported, Serials.canonical(usbUniform))
    }

    /**
     * The reading that made this a bug: taken as uniform hex the trailing `45` is one byte, `0x45`,
     * which is `E`. [Serials.canonical] still answers that, because on its own the descriptor gives
     * no reason to prefer either reading — the fix is that matching no longer depends on the guess.
     */
    @Test
    fun `the misreading is still what a lone descriptor canonicalises to`() {
        assertEquals("QWER0123E", Serials.canonical(usbReported))
        assertTrue("QWER012345" in Serials.readings(usbReported))
        assertTrue("QWER0123E" in Serials.readings(usbReported))
        assertTrue(usbReported in Serials.readings(usbReported), "the raw spelling is always a reading")
    }

    @Test
    fun `splits that would decode less than half the string are not offered`() {
        // "QWER" + "3031323345" would be one such split: four decoded characters carrying ten
        // literal ones is not evidence of anything.
        assertFalse(Serials.readings(usbReported).any { it.startsWith("QWER3031") })
        assertTrue(Serials.readings(usbReported).all { it.length <= usbReported.length })
    }

    /** Different printers must not be joined, least of all ones from the same batch. */
    @Test
    fun `serials sharing a long prefix are still different printers`() {
        assertFalse(Serials.same(usbReported, "QWER012346"))
        assertFalse(Serials.same("QWER012345", "QWER012346"))
        assertFalse(Serials.same("51574552303132333435", "51574552303132333436"))
    }

    @Test
    fun `a serial that only looks like hex is left exactly as it came`() {
        assertEquals("12345678", Serials.canonical("12345678"))
        assertEquals("X4KP0219", Serials.canonical("X4KP0219"))
        assertEquals("ABCD", Serials.canonical("ABCD"))
        assertNull(Serials.canonical(null))
        assertNull(Serials.canonical("   "))
    }

    @Test
    fun `a missing serial never identifies anything`() {
        assertFalse(Serials.same(null, netReported))
        assertFalse(Serials.same(netReported, null))
        assertFalse(Serials.same(null, null))
        assertFalse(Serials.same("  ", netReported))
        assertEquals(emptySet(), Serials.readings(null))
    }

    @Test
    fun `a decimal serial is not mistaken for an encoding of control characters`() {
        // Decodes to bytes below 0x20, which PLAUSIBLE rejects, so it stays a decimal serial.
        assertEquals("12345678", Serials.canonical("12345678"))
        assertTrue(Serials.same("12345678", "12345678"))
        assertFalse(Serials.same("12345678", "87654321"))
    }
}
