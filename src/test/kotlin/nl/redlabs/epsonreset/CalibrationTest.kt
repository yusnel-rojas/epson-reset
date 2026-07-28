package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.Calibration
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The arithmetic that turns an observation into a counter maximum. */
class CalibrationTest {

    private val bundled = CounterSpecs.loadBundled()

    private fun et2820(addresses: List<Int>): CounterSpec {
        val layout = assertNotNull(bundled["ET-2820"])
        return assertNotNull(layout.firstOrNull { it.addresses == addresses })
    }

    private fun measure(spec: CounterSpec, value: Long, basis: Calibration.Basis): Calibration.Measured =
        assertIs<Calibration.Outcome.Ok>(Calibration.measure(spec, value, basis)).measured

    /** Note the trailing zero on `60.90`. */
    @Test
    fun `the committed ET-282x maxima fall out of the observations recorded beside them`() {
        val cases = listOf(
            Triple(listOf(48, 49), 3865L to "60.90", 6346),
            Triple(listOf(50, 51), 60L to "1.76", 3400),
            Triple(listOf(252, 253), 180L to "13.85", 1300),
        )

        for ((addresses, observation, expected) in cases) {
            val (value, percent) = observation
            val spec = et2820(addresses)

            assertEquals(
                expected,
                measure(spec, value, Calibration.Basis.Reference(percent)).max,
                "derived maximum for addr $addresses",
            )
            assertEquals(expected, spec.max, "bundled maximum for addr $addresses")
        }
    }

    /**
     * A percentage is only as precise as its decimals, and the range says so out loud rather than
     * letting a maximum look pinned when it is bracketed.
     */
    @Test
    fun `decimals decide how tightly the maximum is pinned`() {
        val spec = et2820(listOf(48, 49))

        val coarse = measure(spec, 3865, Calibration.Basis.Reference("60.9"))
        assertEquals(6342..6351, coarse.range)
        assertEquals(6342, coarse.max)

        val fine = measure(spec, 3865, Calibration.Basis.Reference("60.90"))
        assertEquals(6346..6346, fine.range)
        assertEquals(6346, fine.max)
    }

    @Test
    fun `a whole-number percentage is flagged as too coarse to trust`() {
        val spec = et2820(listOf(48, 49))

        assertTrue(measure(spec, 3865, Calibration.Basis.Reference("61")).isCoarse)
        assertFalse(measure(spec, 3865, Calibration.Basis.Reference("60.90")).isCoarse)
    }

    /**
     * The strongest observation available, and the only one needing no second tool: the counter has
     * reached the limit, so it *is* the limit. One integer, no window.
     */
    @Test
    fun `service required pins the maximum exactly`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)

        assertEquals(6346, measured.max)
        assertEquals(6346..6346, measured.range)
        assertEquals(100.0, measured.percent)
        assertFalse(measured.isCoarse)
    }

    @Test
    fun `a percentage tolerates the ways people type one`() {
        val spec = et2820(listOf(48, 49))
        val expected = measure(spec, 3865, Calibration.Basis.Reference("60.90")).max

        for (typed in listOf(" 60.90 ", "60.90%", "60,90")) {
            assertEquals(expected, measure(spec, 3865, Calibration.Basis.Reference(typed)).max, typed)
        }
    }

    @Test
    fun `observations that pin nothing are refused rather than rounded`() {
        val spec = et2820(listOf(48, 49))

        // 0 is 0% of every maximum there is.
        assertIs<Calibration.Outcome.Rejected>(
            Calibration.measure(spec, 0, Calibration.Basis.ServiceRequired),
        )
        assertIs<Calibration.Outcome.Rejected>(
            Calibration.measure(spec, 3865, Calibration.Basis.Reference("0")),
        )
        assertIs<Calibration.Outcome.Rejected>(
            Calibration.measure(spec, 3865, Calibration.Basis.Reference("about sixty")),
        )
        assertIs<Calibration.Outcome.Rejected>(
            Calibration.measure(spec, null, Calibration.Basis.ServiceRequired),
        )
    }

    /**
     * A mistyped decimal point turns a plausible reading into an implied maximum of two and a half
     * billion.
     */
    @Test
    fun `a percentage small enough to imply an impossible maximum is refused`() {
        val outcome = Calibration.measure(
            et2820(listOf(48, 49)),
            3865,
            Calibration.Basis.Reference("0.0001"),
        )

        assertContains(assertIs<Calibration.Outcome.Rejected>(outcome).reason, "not a pad counter")
    }

    /** The maximum itself fits; only the far end of the window runs away, and it is clamped. */
    @Test
    fun `a runaway upper bound is clamped rather than wrapped`() {
        val measured = measure(et2820(listOf(48, 49)), 3865, Calibration.Basis.Reference("0.0002"))

        assertEquals(1_546_000_000, measured.max)
        assertEquals(Int.MAX_VALUE, measured.range.last)
    }

    /**
     * The bundled grouping lists some of Epson's limit bytes as counters — they read 0x5E always,
     * and the reset writes 0x5E back. Calibrating one gives a maximum of 94 and a counter stuck at
     * 100%.
     */
    @Test
    fun `a lone byte sitting at the limit marker is flagged`() {
        val marker = measure(et2820(listOf(47)), 94, Calibration.Basis.ServiceRequired)
        assertTrue(marker.looksLikeLimitByte)
        assertEquals(94, marker.max)

        assertFalse(measure(et2820(listOf(47)), 93, Calibration.Basis.ServiceRequired).looksLikeLimitByte)
        // A multi-byte counter that decodes to 94 is a count, not a marker byte.
        assertFalse(measure(et2820(listOf(48, 49)), 94, Calibration.Basis.ServiceRequired).looksLikeLimitByte)

        assertContains(
            Calibration.report(Calibration.Context(model = "ET-2825"), listOf(marker)),
            "Epson's limit marker",
        )
    }

    /** The ET-2820's 6-address entry mixes a counter with limit bytes; it is not one number. */
    @Test
    fun `a group too wide to be one value has no maximum to measure`() {
        val wide = et2820(listOf(28, 52, 53, 54, 55, 255))

        assertIs<Calibration.Outcome.Rejected>(
            Calibration.measure(wide, 3865, Calibration.Basis.ServiceRequired),
        )
    }

    // ---------------------------------------------------------------- against what we hold

    /** Three different things can arrive, and they want doing different things with. */
    @Test
    fun `a measurement is placed against what the app already holds`() {
        val calibrated = et2820(listOf(48, 49))
        val uncalibrated = et2820(listOf(47))

        assertEquals(
            Calibration.Agreement.FILLS_A_GAP,
            measure(uncalibrated, 100, Calibration.Basis.ServiceRequired).agreement,
        )
        assertEquals(
            Calibration.Agreement.CONFIRMS,
            measure(calibrated, 3865, Calibration.Basis.Reference("60.90")).agreement,
        )

        val contradiction = measure(calibrated, 3865, Calibration.Basis.Reference("45.00"))
        assertEquals(Calibration.Agreement.DISAGREES, contradiction.agreement)
        assertEquals(6346, contradiction.existingMax)
        assertContains(Calibration.headline(listOf(contradiction)), "contradicts")
    }

    /**
     * The check that stops a percentage calibrating the wrong counter. A reference tool reading a
     * different count is not reading this counter, whatever its percentage says.
     */
    @Test
    fun `a reference tool reading a different count cannot calibrate this one`() {
        val spec = et2820(listOf(48, 49))

        val mismatch = Calibration.measure(
            spec,
            3865,
            Calibration.Basis.Reference("60.90", reportedValue = "3907"),
        )
        assertContains(
            assertIs<Calibration.Outcome.Rejected>(mismatch).reason,
            "not looking at the same counter",
        )

        // The same count confirms the two agree, and the measurement stands.
        val agreed = measure(spec, 3865, Calibration.Basis.Reference("60.90", reportedValue = "3865"))
        assertEquals(6346, agreed.max)
        assertContains(agreed.basisLabel, "matching this read")
    }

    // ---------------------------------------------------------------- artefacts

    private fun fakeLayouts() = LinkedHashMap(
        CounterSpecs.parseGroups(
            """{"groups":[{"models":["FAKE-1"],"counters":[
                 {"addr":[48,49],"desc":"Waste counter"},
                 {"addr":[50,51],"desc":"Waste counter (platen)"}]}]}""",
        ),
    )

    /**
     * The whole point of the entry: it has to be something the loader already understands, so a
     * contribution reaches the screen by the bundled file's own path rather than a second one.
     */
    @Test
    fun `a generated entry is valid input to the calibration loader`() {
        val layouts = fakeLayouts()
        val measured = measure(
            layouts.getValue("fake-1")[0],
            3865,
            Calibration.Basis.Reference("60.90"),
        )

        val entry = Calibration.entryJson("FAKE-1", listOf(measured), date = "2026-07-28")
        CounterSpecs.applyCalibrations(layouts, Calibration.asCalibrationsFile(entry))

        val result = layouts.getValue("fake-1")
        assertEquals(2, result.size, "a calibration must not add or remove counters")
        assertEquals(6346, result[0].max)
        assertNull(result[1].max, "an uncalibrated counter stays without a maximum")
    }

    /** Only the model measured. A shared EEPROM layout does not prove identical pad capacity. */
    @Test
    fun `an entry claims one model and no relatives`() {
        val measured = measure(et2820(listOf(48, 49)), 3865, Calibration.Basis.Reference("60.90"))
        val entry = Calibration.entryJson("ET-2825", listOf(measured))

        assertTrue(entry.contains(""""models": ["ET-2825"]"""))
        assertFalse(entry.contains("ET-2820"))
    }

    @Test
    fun `a service-required entry records what it was observed at`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val entry = Calibration.entryJson("ET-2825", listOf(measured))

        assertTrue(entry.contains(""""at": "service required""""), entry)
        assertTrue(entry.contains(""""max": 6346"""), entry)
    }

    @Test
    fun `the session apply goes through the same layering the bundled file uses`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val entry = Calibration.entryJson("ET-2825", listOf(measured))

        val applied = bundled.withCalibration(Calibration.asCalibrationsFile(entry))

        assertEquals(6346, assertNotNull(applied["ET-2825"])[2].max)
        // The measured model only — the sibling it shares a layout with keeps the bundled figure.
        assertEquals(6346, assertNotNull(bundled["ET-2820"])[2].max)
        assertEquals(bundled.modelCount, applied.modelCount)
    }

    @Test
    fun `the overlay keeps the whole layout, not just the calibrated counter`() {
        val layouts = fakeLayouts()
        val specs = layouts.getValue("fake-1")
        val measured = measure(specs[0], 3865, Calibration.Basis.Reference("60.90"))

        val parsed = CounterSpecs.parseGroups(Calibration.overlayJson("FAKE-1", specs, listOf(measured)))
        val layout = assertNotNull(parsed["fake-1"])

        // An overlay replaces the model's entry outright, so a dropped counter is a deleted one.
        assertEquals(2, layout.size)
        assertEquals(6346, layout[0].max)
        assertEquals("Waste counter (platen)", layout[1].description)
        assertNull(layout[1].max)
    }

    @Test
    fun `the report carries the measurement and never the serial`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val report = Calibration.report(
            Calibration.Context(
                model = "ET-2825",
                printer = "ET-2820 Series",
                transport = "USB",
                firmware = "05.24",
                appVersion = "dev build",
                inkLevels = listOf("Black" to 11, "Cyan" to 66),
                statusFields = listOf("error" to "00"),
            ),
            listOf(measured),
        )

        assertTrue(report.contains("ET-2825"))
        assertTrue(report.contains("05.24"))
        assertTrue(report.contains("6346"))

        // Read off the same status block, and context for a counter that fills as ink is used.
        assertContains(report, "Ink levels")
        assertContains(report, "| Black | 11% |")

        // What the app was showing before the measurement, so a maintainer can see at a glance
        // whether this fills a gap, confirms a figure, or contradicts one.
        assertContains(report, "app has")

        assertFalse(report.contains("XADA020273"), "a serial has no place in a public issue")
    }

    /** A maximum belongs to one SKU, and 120 models share the ET-2820's layout. */
    @Test
    fun `the report says which unit this was and how big the group around it is`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val report = Calibration.report(
            Calibration.Context(
                model = "ET-2825",
                identifiedAs = "ET-2825",
                layoutOf = "ET-2820 Series",
                sharedLayout = 120,
            ),
            listOf(measured),
        )

        assertContains(report, "Filed against: `ET-2825`")
        assertContains(report, "names itself `ET-2825`")
        assertContains(report, "shared by **120 models**")
        assertContains(report, "splitting the group")
    }

    /** Overriding the firmware's own answer is allowed, and has to be visible to a maintainer. */
    @Test
    fun `filing against a different model than the printer reported is flagged`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val report = Calibration.report(
            Calibration.Context(model = "ET-2821", identifiedAs = "ET-2825"),
            listOf(measured),
        )

        assertContains(report, "filed it as `ET-2821` instead")
    }

    @Test
    fun `the entry's note counts the siblings it is deliberately not claiming`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val entry = Calibration.entryJson("ET-2825", listOf(measured), sharedLayout = 120)

        assertContains(entry, "other 119 models sharing this EEPROM layout")
    }

    /** A counter with nothing on file has to say so, rather than leaving a blank to interpret. */
    @Test
    fun `the report names a missing maximum as missing`() {
        val measured = measure(et2820(listOf(47)), 100, Calibration.Basis.ServiceRequired)
        val report = Calibration.report(Calibration.Context(model = "ET-2825"), listOf(measured))

        assertContains(report, "— none")
        assertContains(report, "no maximum was known")
    }

    // ---------------------------------------------------------------- submission

    @Test
    fun `a normal submission arrives prefilled`() {
        val measured = measure(et2820(listOf(48, 49)), 6346, Calibration.Basis.ServiceRequired)
        val entry = Calibration.entryJson("ET-2825", listOf(measured))
        val evidence = Calibration.report(Calibration.Context(model = "ET-2825"), listOf(measured))

        val submission = Calibration.submission("ET-2825", entry, evidence)

        assertTrue(submission.prefilled)
        assertTrue(submission.url.length <= Calibration.MAX_URL)
        assertTrue(submission.url.startsWith(Calibration.ISSUE_BASE))
        assertTrue(submission.url.contains("template=calibration.yml"))
    }

    /** Evidence is what gets shed first; the JSON is the difference between a submission and an intention. */
    @Test
    fun `an oversized report is dropped before the entry is`() {
        val submission = Calibration.submission("ET-2825", "{}", "x".repeat(20_000))

        assertTrue(submission.prefilled)
        assertTrue(submission.url.contains("entry="))
        assertFalse(submission.url.contains("evidence="))
    }

    @Test
    fun `a submission too large for any URL still opens the form`() {
        val submission = Calibration.submission("ET-2825", "x".repeat(20_000), "x".repeat(20_000))

        assertFalse(submission.prefilled, "the caller falls back to the clipboard")
        assertTrue(submission.url.length <= Calibration.MAX_URL)
        assertTrue(submission.url.contains("template=calibration.yml"))
    }
}
