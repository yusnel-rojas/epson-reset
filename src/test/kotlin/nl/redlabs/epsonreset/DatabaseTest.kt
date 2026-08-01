package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.PadKind
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {

    private val db = PrinterDatabase.load()

    @Test
    fun `bundled database loads the full model set`() {
        assertTrue(db.size > 1000, "expected the full database, got ${db.size}")
    }

    @Test
    fun `the bundled data passes the downloaded-data trust boundary`() {
        val text = assertNotNull(PrinterDatabase::class.java.getResourceAsStream("/database.json"))
            .bufferedReader()
            .use { it.readText() }

        assertEquals(db.size, PrinterDatabase.parseDownloaded(text).size)
    }

    @Test
    fun `a known entry parses its keys and pad groups`() {
        val model = assertNotNull(db["PX-7V"])

        assertEquals(1, model.readKey)
        assertEquals("Zvubnpsj", model.writeKey)
        assertEquals("Yutamori", model.writeKey1)
        assertEquals(511, model.memHigh)
        assertEquals(2, model.padGroups.size)

        val platen = model.padGroups.first { it.effectiveKind == PadKind.PLATEN }
        assertEquals(listOf(58, 59, 83, 84, 85, 86), platen.addresses)

        val main = model.padGroups.first { it.effectiveKind == PadKind.MAIN }
        assertEquals(listOf(87, 88, 89, 90), main.addresses)
    }

    @Test
    fun `every model pairs each address with a reset value`() {
        val mismatched = db.models.filter { model ->
            model.padGroups.any { it.addresses.size != it.resetValues.size }
        }
        assertTrue(mismatched.isEmpty(), "unpaired addresses in: ${mismatched.take(5).map { it.name }}")
    }

    @Test
    fun `lookup is case insensitive`() {
        assertNotNull(db["l3150"])
        assertNotNull(db["L3150"])
    }

    @Test
    fun `search ranks exact and prefix hits first`() {
        val results = db.search("L3150")
        assertEquals("L3150", results.first().name)
    }

    @Test
    fun `search returns nothing for a nonsense query`() {
        assertTrue(db.search("zzzznotaprinter").isEmpty())
    }

    @Test
    fun `schema 3 wrapper form parses the same as a bare map`() {
        val wrapped = """
            {"schema_version": 3, "models": {
              "FAKE-1": {"rkey": 2, "wkey": "abc", "pad_groups": [
                {"desc": "Platen Pad Counter", "kind": "platen", "addresses": [1,2], "reset": [0,0]}
              ]}
            }}
        """.trimIndent()

        val parsed = PrinterDatabase.parse(wrapped)
        val model = assertNotNull(parsed["FAKE-1"])
        assertEquals(2, model.readKey)
        assertTrue(model.isPlatenOnly)
    }

    @Test
    fun `legacy flat addresses become a single implicit group`() {
        val legacy = """{"OLD-1": {"wkey": "k", "addresses": [5,6,7], "reset": [0]}}"""
        val model = assertNotNull(PrinterDatabase.parse(legacy)["OLD-1"])

        assertEquals(1, model.padGroups.size)
        assertEquals(listOf(5, 6, 7), model.padGroups[0].addresses)
        // Short reset arrays pad with zeros so no address is left without a value.
        assertEquals(listOf(0, 0, 0), model.padGroups[0].resetValues)
    }

    @Test
    fun `platen only detection drives the main-waste-box warning`() {
        val platenOnly = """
            {"P": {"wkey": "k", "pad_groups": [
              {"desc": "Platen Pad Counter", "kind": "platen", "addresses": [1], "reset": [0]}]}}
        """.trimIndent()
        assertTrue(assertNotNull(PrinterDatabase.parse(platenOnly)["P"]).isPlatenOnly)

        val both = """
            {"B": {"wkey": "k", "pad_groups": [
              {"desc": "Platen Pad Counter", "kind": "platen", "addresses": [1], "reset": [0]},
              {"desc": "Main Pad Counter", "kind": "main", "addresses": [2], "reset": [0]}]}}
        """.trimIndent()
        assertTrue(!assertNotNull(PrinterDatabase.parse(both)["B"]).isPlatenOnly)
    }

    @Test
    fun `a downloaded database is checked without weakening the legacy parser`() {
        val valid = """
            {"ONE": {"rkey": 1, "mem_high": 255, "pad_groups": [
              {"kind": "main", "addresses": [1, 2], "reset": [0, 94]}
            ]}}
        """.trimIndent()

        assertEquals(1, PrinterDatabase.parseDownloaded(valid, minimumModels = 1).size)
        assertFailsWith<IllegalArgumentException> { PrinterDatabase.parseDownloaded(valid) }

        // The ordinary parser still accepts this old shape and pads its reset values.
        assertNotNull(PrinterDatabase.parse("""{"OLD": {"addresses": [1,2], "reset": [0]}}""")["OLD"])
    }

    @Test
    fun `a downloaded database rejects unsafe addresses values and structure`() {
        val mismatch = """{"ONE":{"pad_groups":[{"addresses":[1,2],"reset":[0]}]}}"""
        val outOfBounds =
            """{"ONE":{"mem_high":10,"pad_groups":[{"addresses":[11],"reset":[0]}]}}"""
        val invalidByte = """{"ONE":{"pad_groups":[{"addresses":[1],"reset":[256]}]}}"""
        val duplicate = """{"ONE":{"pad_groups":[{"addresses":[1,1],"reset":[0,0]}]}}"""

        listOf(mismatch, outOfBounds, invalidByte, duplicate).forEach { text ->
            assertFailsWith<IllegalArgumentException> {
                PrinterDatabase.parseDownloaded(text, minimumModels = 1)
            }
        }
    }

    @Test
    fun `a rejected download leaves the previous cache untouched`() {
        val dir = createTempDirectory("database-cache-test").toFile()
        val target = File(dir, "database.json").apply { writeText("working copy") }
        val invalid = """{"ONE":{"pad_groups":[{"addresses":[1],"reset":[999]}]}}"""

        assertFailsWith<IllegalArgumentException> {
            PrinterDatabase.cacheDownloaded(invalid, target, minimumModels = 1)
        }

        assertEquals("working copy", target.readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a valid download atomically replaces the cache`() {
        val target = File(createTempDirectory("database-cache-test").toFile(), "database.json")
        val valid = """{"ONE":{"pad_groups":[{"addresses":[1],"reset":[0]}]}}"""

        val parsed = PrinterDatabase.cacheDownloaded(valid, target, minimumModels = 1)

        assertEquals(1, parsed.size)
        assertEquals(valid, target.readText())
    }
}

class DeviceMatcherTest {

    private val db = PrinterDatabase.load()

    private fun device(product: String?) = DetectedPrinter(
        link = Link.Usb(
            busNumber = 1,
            deviceAddress = 4,
            interfaceNumber = 1,
            endpointIn = 0x81.toByte(),
            endpointOut = 0x02,
            isPrinterClass = true,
        ),
        manufacturer = "EPSON",
        product = product,
        serial = "X4KP0219",
        productId = 0x1169,
    )

    @Test
    fun `strips vendor and marketing words`() {
        assertEquals("L3150", DeviceMatcher.normalise("EPSON L3150 Series"))
        assertEquals("XP-245", DeviceMatcher.normalise("EPSON XP-245 Series"))
        assertEquals("SX130", DeviceMatcher.normalise("EPSON Stylus SX130"))
    }

    @Test
    fun `resolves a typical descriptor string to its database entry`() {
        val matched = DeviceMatcher.match(device("EPSON L3150 Series"), db)

        assertEquals("L3150", matched.model?.name)
        assertEquals(MatchedPrinter.Confidence.EXACT, matched.confidence)
    }

    /**
     * The load-bearing case. L310 and L3100 are one family name apart and read with different keys,
     * so a printer that will only say "L310 Series" has not said which of them it is.
     */
    @Test
    fun `a family name whose members disagree is not treated as an identification`() {
        val matched = DeviceMatcher.match(device("EPSON L310 Series"), db)

        assertEquals(MatchedPrinter.Confidence.CLASS_ONLY, matched.confidence)
        assertTrue(matched.candidates.map { it.name }.containsAll(listOf("L310", "L3100")))
        assertTrue(
            matched.candidates.map { it.readKey }.distinct().size > 1,
            "a candidate list that agrees on the read key would not be worth asking about",
        )
    }

    /** The common case, which must stay free: eight L311x entries, one recipe between them. */
    @Test
    fun `a family whose members share a recipe resolves without asking`() {
        val matched = DeviceMatcher.match(device("EPSON L3110 Series"), db)

        assertEquals(MatchedPrinter.Confidence.EXACT, matched.confidence)
        assertEquals("L3110", matched.model?.name)
        assertTrue(matched.candidates.isEmpty())
    }

    /** A unit answering for itself is taken at its word, even where its family would not be. */
    @Test
    fun `a model naming itself is not second-guessed by its family`() {
        val matched = DeviceMatcher.match(device("EPSON L3100"), db)

        assertEquals(MatchedPrinter.Confidence.EXACT, matched.confidence)
        assertEquals("L3100", matched.model?.name)
    }

    @Test
    fun `a missing product string is reported rather than guessed`() {
        val matched = DeviceMatcher.match(device(null), db)

        assertNull(matched.model)
        assertEquals(MatchedPrinter.Confidence.NONE, matched.confidence)
    }

    @Test
    fun `an unrecognisable name does not fall back to an arbitrary model`() {
        val matched = DeviceMatcher.match(device("EPSON Totally Made Up 9999"), db)
        assertEquals(MatchedPrinter.Confidence.NONE, matched.confidence)
    }

    /** The prefix is what keeps a bus address and a host:port from ever colliding. */
    @Test
    fun `device identity is stable across rescans`() {
        assertEquals("usb:1:4", device("EPSON L3150 Series").id)
        assertEquals("net:192.168.1.50:161", Link.Network("192.168.1.50").id)
    }
}
