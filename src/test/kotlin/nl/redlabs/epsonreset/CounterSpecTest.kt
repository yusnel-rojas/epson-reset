package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CounterSpecTest {

    private val specs = CounterSpecs.loadBundled()

    @Test
    fun `bundled layouts cover the model set`() {
        assertTrue(specs.modelCount > 1000, "expected the full layout set, got ${specs.modelCount}")
    }

    /** The real ET-2820 layout, which is what makes grouped decoding possible at all. */
    @Test
    fun `ET-2820 groups addresses into separate counters`() {
        val layout = assertNotNull(specs["ET-2820"])

        assertEquals(6, layout.size)
        assertEquals(listOf(28, 52, 53, 54, 55, 255), layout[0].addresses)
        assertEquals(listOf(47), layout[1].addresses)
        assertEquals(listOf(48, 49), layout[2].addresses)
        assertEquals(listOf(50, 51), layout[3].addresses)
        assertEquals(listOf(252, 253), layout[4].addresses)
        assertEquals(listOf(254), layout[5].addresses)
    }

    @Test
    fun `the whole family shares one layout`() {
        val a = assertNotNull(specs["ET-2820"])
        val b = assertNotNull(specs["ET-2825"])
        assertEquals(a, b)
    }

    @Test
    fun `lookup is case insensitive`() {
        assertNotNull(specs["et-2825"])
    }

    @Test
    fun `multi-byte counters decode little-endian`() {
        val spec = CounterSpec(listOf(48, 49), "Waste counter")
        // The real bytes read off the ET-2820: 48=0x19, 49=0x0F → 0x0F19.
        assertEquals(0x0F19L, spec.decode(mapOf(48 to 0x19, 49 to 0x0F)))
    }

    @Test
    fun `a single byte counter decodes to that byte`() {
        assertEquals(0xB4L, CounterSpec(listOf(252), "c").decode(mapOf(252 to 0xB4)))
    }

    @Test
    fun `a counter with a missing byte decodes to null rather than a wrong number`() {
        val spec = CounterSpec(listOf(48, 49), "Waste counter")
        assertNull(spec.decode(mapOf(48 to 0x19)))
        assertNull(spec.decode(mapOf(48 to 0x19, 49 to null)))
    }

    @Test
    fun `percentage is only offered when a maximum is declared`() {
        assertNull(CounterSpec(listOf(1), "c").percentOf(100))

        val withMax = CounterSpec(listOf(1), "c", max = 200)
        assertEquals(50.0, withMax.percentOf(100))
    }

    /** The ET-2820's first entry spans 6 addresses and mixes a counter with limit bytes. */
    @Test
    fun `a group too wide to be one integer is not decoded`() {
        val wide = CounterSpec(listOf(28, 52, 53, 54, 55, 255), "Waste counters (?)")

        assertTrue(!wide.isSingleValue)
        assertNull(
            wide.decode(mapOf(28 to 0x08, 52 to 0x6C, 53 to 0x15, 54 to 0x5E, 55 to 0x5E, 255 to 0x5E)),
        )
    }

    @Test
    fun `four bytes is still a plausible counter`() {
        val spec = CounterSpec(listOf(1, 2, 3, 4), "Waste counter")
        assertTrue(spec.isSingleValue)
        assertEquals(0x04030201L, spec.decode(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4)))
    }

    @Test
    fun `uncertain layouts are flagged`() {
        assertTrue(CounterSpec(listOf(1), "Waste counters (?)").isUncertain)
        assertTrue(!CounterSpec(listOf(1), "Waste counter").isUncertain)
    }

    @Test
    fun `an overlay group replaces the bundled layout for that model`() {
        val overlay = """
            {"groups":[{"models":["ET-2825"],"counters":[
              {"addr":[10,11],"desc":"Custom counter","max":5000}]}]}
        """.trimIndent()

        val parsed = CounterSpecs.parseGroups(overlay)
        val layout = assertNotNull(parsed["et-2825"])

        assertEquals(1, layout.size)
        assertEquals(listOf(10, 11), layout[0].addresses)
        assertEquals(5000, layout[0].max)
    }

    /** Calibrations are layered onto bundled layouts by address, adding only a `max`. */
    @Test
    fun `calibrations add a maximum without altering the layout`() {
        val layouts = LinkedHashMap(
            CounterSpecs.parseGroups(
                """{"groups":[{"models":["FAKE-1"],"counters":[
                 {"addr":[48,49],"desc":"Waste counter"},
                 {"addr":[50,51],"desc":"Waste counter"}]}]}""",
            ),
        )

        CounterSpecs.applyCalibrations(
            layouts,
            """{"calibrations":[{"models":["FAKE-1"],"maxima":[
                 {"addr":[48,49],"max":6346},
                 {"addr":[999],"max":1}]}]}""",
        )

        val result = assertNotNull(layouts["fake-1"])
        assertEquals(2, result.size, "layout must not gain or lose counters")
        assertEquals(6346, result[0].max)
        assertNull(result[1].max, "uncalibrated counters stay without a maximum")
    }

    @Test
    fun `the overlay template is valid input to the parser`() {
        val parsed = CounterSpecs.parseGroups(CounterSpecs.overlayTemplate())
        val layout = assertNotNull(parsed["my-model"])

        assertEquals(2, layout.size)
        assertEquals(8450, layout[0].max)
    }
}

class DecodedCounterTest {

    private val model = PrinterDatabase.load()["ET-2825"]!!
    private val specs = CounterSpecs.loadBundled()["ET-2825"]!!

    @Test
    fun `decodes a real reading set into grouped counters`() {
        // The actual bytes read off the hardware.
        val hardware = mapOf(
            28 to 0x08, 52 to 0x6C, 53 to 0x15, 54 to 0x5E, 55 to 0x5E, 255 to 0x5E,
            47 to 0x00, 48 to 0x19, 49 to 0x0F, 50 to 0x3C, 51 to 0x00,
            252 to 0xB4, 253 to 0x00, 254 to 0x00,
        )
        val readings = hardware.map { (address, value) ->
            CounterReader.Reading(address, value, 0, "Waste")
        }

        val decoded = CounterReader.decode(readings, specs)

        assertEquals(6, decoded.size)
        // The 6-address "(?)" entry is not one integer and must stay undecoded.
        assertNull(decoded.first { it.spec.addresses.size == 6 }.value)
        assertEquals(0L, decoded.first { it.spec.addresses == listOf(47) }.value)
        assertEquals(0x0F19L, decoded.first { it.spec.addresses == listOf(48, 49) }.value)
        assertEquals(0x003CL, decoded.first { it.spec.addresses == listOf(50, 51) }.value)
        assertEquals(0x00B4L, decoded.first { it.spec.addresses == listOf(252, 253) }.value)
        assertEquals(0L, decoded.first { it.spec.addresses == listOf(254) }.value)
    }

    /** The calibrated family now carries maxima, so its counters do report a percentage. */
    @Test
    fun `a calibrated model reports percentages`() {
        val readings = specs.flatMap { it.addresses }.map { CounterReader.Reading(it, 0x10, 0, "g") }
        val decoded = CounterReader.decode(readings, specs)

        assertTrue(decoded.any { it.percent != null }, "ET-2825 is calibrated and should have percentages")
    }

    /** An uncalibrated model must still say "no limit" rather than borrow someone else's. */
    @Test
    fun `an uncalibrated model reports no percentage`() {
        val other = assertNotNull(CounterSpecs.loadBundled()["L3150"])
        val readings = other.flatMap { it.addresses }.map { CounterReader.Reading(it, 0x10, 0, "g") }

        assertTrue(CounterReader.decode(readings, other).all { it.percent == null })
    }

    /**
     * Calibration regression. On 2026-07-26 the reference ET-2820 read 3865 / 60 / 180, measured
     * independently as 60.90% / 1.76% / 13.85%.
     */
    @Test
    fun `calibrated maxima reproduce the reference percentages`() {
        val calibrated = listOf(
            CounterSpec(listOf(48, 49), "Waste counter", max = 6346) to 60.90,
            CounterSpec(listOf(50, 51), "Waste counter", max = 3400) to 1.76,
            CounterSpec(listOf(252, 253), "Waste counter", max = 1300) to 13.85,
        )
        val hardware = mapOf(48 to 0x19, 49 to 0x0F, 50 to 0x3C, 51 to 0x00, 252 to 0xB4, 253 to 0x00)
        val readings = hardware.map { (a, v) -> CounterReader.Reading(a, v, 0, "g") }

        for ((spec, expected) in calibrated) {
            val decoded = CounterReader.decode(readings, listOf(spec)).single()
            val percent = assertNotNull(decoded.percent)
            assertEquals(expected, "%.2f".format(percent).toDouble(), "for addr ${spec.addresses}")
        }
    }

    @Test
    fun `spec addresses outside the pad groups are still read`() {
        val extraSpec = listOf(CounterSpec(listOf(900, 901), "Off-list counter"))
        val report = CounterReader.readAll(FakeTransport(), model, extraSpec)

        assertTrue(report.readings.any { it.address == 900 }, "spec-only address should be sampled")
        assertTrue(report.readings.any { it.address == 901 })
    }
}
