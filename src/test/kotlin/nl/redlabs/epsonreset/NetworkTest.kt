package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.net.NetworkAddress
import nl.redlabs.epsonreset.net.SavedPrinters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkAddressTest {

    @Test
    fun `a bare address gets the raw printing port`() {
        val link = assertNotNull(NetworkAddress.parse("192.168.1.50"))

        assertEquals("192.168.1.50", link.host)
        assertEquals(9100, link.port)
    }

    @Test
    fun `an explicit port is kept`() {
        assertEquals(9101, assertNotNull(NetworkAddress.parse("192.168.1.50:9101")).port)
    }

    /** The address a user can actually find is the one in their browser's bar. */
    @Test
    fun `a pasted admin url reduces to host and default port`() {
        val link = assertNotNull(NetworkAddress.parse("http://192.168.1.50/PRESENTATION/HTML/TOP/INDEX.HTML"))

        assertEquals("192.168.1.50", link.host)
        assertEquals(9100, link.port)
    }

    @Test
    fun `hostnames work and trailing dots do not survive`() {
        assertEquals("printer.local", assertNotNull(NetworkAddress.parse("printer.local.")).host)
    }

    @Test
    fun `bracketed ipv6 keeps its colons`() {
        val link = assertNotNull(NetworkAddress.parse("[fe80::1]:9100"))

        assertEquals("fe80::1", link.host)
        assertEquals(9100, link.port)
    }

    @Test
    fun `nonsense is rejected rather than turned into a host`() {
        assertNull(NetworkAddress.parse(""))
        assertNull(NetworkAddress.parse("   "))
        assertNull(NetworkAddress.parse("192.168.1.50:not-a-port"))
        assertNull(NetworkAddress.parse("192.168.1.50:70000"))
        assertNull(NetworkAddress.parse("has spaces"))
    }

    @Test
    fun `format round trips through parse`() {
        val explicit = Link.Network("10.0.0.4", 9101)
        val default = Link.Network("10.0.0.4")

        assertEquals("10.0.0.4:9101", NetworkAddress.format(explicit))
        assertEquals("10.0.0.4", NetworkAddress.format(default))
        assertEquals(explicit, NetworkAddress.parse(NetworkAddress.format(explicit)))
        assertEquals(default, NetworkAddress.parse(NetworkAddress.format(default)))
    }
}

class SavedPrintersTest {

    @Test
    fun `parses an address with the name the printer last gave`() {
        val entries = SavedPrinters.parse("192.168.1.50  EPSON ET-2820 Series\n")

        assertEquals(1, entries.size)
        assertEquals("192.168.1.50", entries.single().link.host)
        assertEquals("EPSON ET-2820 Series", entries.single().product)
    }

    @Test
    fun `an address on its own is fine`() {
        assertNull(SavedPrinters.parse("10.0.0.9\n").single().product)
    }

    /** A hand-edited file is the expected case, so one bad line costs that line only. */
    @Test
    fun `comments blanks and unparseable lines are skipped`() {
        val entries = SavedPrinters.parse(
            """
            # a comment

            192.168.1.50
            192.168.1.99:99999   port out of range
            10.0.0.9:9101   EPSON XP-245 Series   # trailing comment
            """.trimIndent(),
        )

        assertEquals(listOf("192.168.1.50", "10.0.0.9"), entries.map { it.link.host })
        assertEquals(9101, entries.last().link.port)
        assertEquals("EPSON XP-245 Series", entries.last().product)
    }

    @Test
    fun `the same address twice is kept once`() {
        assertEquals(1, SavedPrinters.parse("192.168.1.50\n192.168.1.50\n").size)
    }

    @Test
    fun `format round trips through parse`() {
        val entries = listOf(
            SavedPrinters.Saved(Link.Network("192.168.1.50"), "EPSON ET-2820 Series"),
            SavedPrinters.Saved(Link.Network("10.0.0.9", 9101)),
        )

        assertEquals(entries, SavedPrinters.parse(SavedPrinters.format(entries)))
    }
}
