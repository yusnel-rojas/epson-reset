package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.ModelChoices
import nl.redlabs.epsonreset.net.NetworkAddress
import nl.redlabs.epsonreset.net.SavedPrinters
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkAddressTest {

    @Test
    fun `a bare address gets the SNMP port, which is the only one anything dials`() {
        val link = assertNotNull(NetworkAddress.parse("192.168.1.50"))

        assertEquals("192.168.1.50", link.host)
        assertEquals(161, link.port)
    }

    @Test
    fun `an explicit port is kept`() {
        assertEquals(9101, assertNotNull(NetworkAddress.parse("192.168.1.50:9101")).port)
    }

    /**
     * 9100 is the raw printing port. It is what a printer advertises, what its status page shows,
     * and what this field used to be filled with by default — and nothing in this app has ever
     * connected to it. Reading it as an instruction to ask SNMP there would break every saved
     * address written before the port meant anything.
     */
    @Test
    fun `the raw printing port is read as unset rather than as an SNMP port`() {
        assertEquals(161, assertNotNull(NetworkAddress.parse("192.168.1.50:9100")).port)
        assertEquals(161, assertNotNull(SavedPrinters.parse("192.168.1.50:9100\n").single().link).port)
    }

    /** The address a user can actually find is the one in their browser's bar. */
    @Test
    fun `a pasted admin url reduces to host and default port`() {
        val link = assertNotNull(NetworkAddress.parse("http://192.168.1.50/PRESENTATION/HTML/TOP/INDEX.HTML"))

        assertEquals("192.168.1.50", link.host)
        assertEquals(161, link.port)
    }

    @Test
    fun `hostnames work and trailing dots do not survive`() {
        assertEquals("printer.local", assertNotNull(NetworkAddress.parse("printer.local.")).host)
    }

    @Test
    fun `bracketed ipv6 keeps its colons`() {
        val link = assertNotNull(NetworkAddress.parse("[fe80::1]:9101"))

        assertEquals("fe80::1", link.host)
        assertEquals(9101, link.port)
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

class ModelChoicesTest {

    private fun file() = createTempFile("model-choices", ".txt").toFile().apply { delete() }

    private val choice = ModelChoices.Choice("X4KP0219", "EPSON L310 Series", "L3100")

    @Test
    fun `a pinned choice comes back for the printer that made it`() {
        val f = file()
        ModelChoices.pin(f, choice)

        assertEquals("L3100", ModelChoices.lookup(f, listOf("X4KP0219"), "EPSON L310 Series"))
    }

    /** Serial first, but a printer that will not give one is still worth remembering by connection. */
    @Test
    fun `any of the printer's keys will find the choice`() {
        val f = file()
        ModelChoices.pin(f, ModelChoices.Choice("usb:1:4", "EPSON L310 Series", "L310"))

        assertEquals("L310", ModelChoices.lookup(f, listOf("X4KP0219", "usb:1:4"), "EPSON L310 Series"))
    }

    /**
     * The reason the reported name is stored alongside: swap the printer on that USB port and the
     * old answer is about a different machine, so it must not be applied to this one.
     */
    @Test
    fun `a choice does not survive the printer reporting something else`() {
        val f = file()
        ModelChoices.pin(f, choice)

        assertNull(ModelChoices.lookup(f, listOf("X4KP0219"), "EPSON L355 Series"))
    }

    @Test
    fun `choosing again replaces the earlier answer`() {
        val f = file()
        ModelChoices.pin(f, choice)
        ModelChoices.pin(f, choice.copy(model = "L3106"))

        assertEquals(listOf("L3106"), ModelChoices.load(f).map { it.model })
    }

    @Test
    fun `forgetting a choice leaves nothing behind`() {
        val f = file()
        ModelChoices.pin(f, choice)
        ModelChoices.forget(f, "X4KP0219")

        assertNull(ModelChoices.lookup(f, listOf("X4KP0219"), "EPSON L310 Series"))
    }

    @Test
    fun `a missing file is an empty list rather than a failure`() {
        assertTrue(ModelChoices.load(file()).isEmpty())
    }

    /** Hand-edited is the expected case for this file too. */
    @Test
    fun `comments and short lines are skipped`() {
        val entries = ModelChoices.parse(
            """
            # a comment

            X4KP0219	EPSON L310 Series	L3100
            NOSERIAL	only two fields
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertEquals("L3100", entries.single().model)
    }

    @Test
    fun `format round trips through parse`() {
        val entries = listOf(choice, ModelChoices.Choice("usb:1:4", "EPSON R200 Series", "R2000"))

        assertEquals(entries, ModelChoices.parse(ModelChoices.format(entries)))
    }
}
