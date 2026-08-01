package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.ModelCapabilities
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.db.ResetScope
import nl.redlabs.epsonreset.db.ValueSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The capability matrix is only worth showing if each column means exactly one thing. */
class CapabilityTest {

    private fun model(vararg groups: PadGroup) = PrinterModel(name = "FAKE-1", padGroups = groups.toList())

    private fun group(kind: String, vararg addresses: Int) =
        PadGroup("$kind Pad Counter", kind, addresses.toList(), addresses.map { 0 })

    @Test
    fun `a model with no addresses can do nothing`() {
        val capability = ModelCapabilities.of(model(), emptyList())

        assertFalse(capability.canReset)
        assertFalse(capability.canRead)
        assertEquals(ResetScope.NONE, capability.scope)
        assertEquals(ValueSupport.RAW, capability.values)
        assertEquals(0, capability.writeCount)
    }

    /**
     * Reading is the safe half of the tool and CounterReader samples layout addresses as well as
     * pad-group ones, so a layout alone is enough to read — it must not inherit "no" from the write
     * path.
     */
    @Test
    fun `a layout alone makes a model readable but not resettable`() {
        val capability = ModelCapabilities.of(
            model(),
            listOf(CounterSpec(listOf(48, 49), "Waste counter")),
        )

        assertFalse(capability.canReset)
        assertTrue(capability.canRead)
        assertEquals(ResetScope.NONE, capability.scope)
    }

    @Test
    fun `platen-only models are called out separately from full ones`() {
        assertEquals(
            ResetScope.PLATEN_ONLY,
            ModelCapabilities.of(model(group("platen", 28, 52)), emptyList()).scope,
        )
        assertEquals(
            ResetScope.FULL,
            ModelCapabilities.of(model(group("platen", 28), group("main", 47)), emptyList()).scope,
        )
    }

    @Test
    fun `values are decoded only when every counter is certain and narrow`() {
        val m = model(group("main", 48, 49))

        assertEquals(
            ValueSupport.DECODED,
            ModelCapabilities.of(m, listOf(CounterSpec(listOf(48, 49), "Waste counter"))).values,
        )

        // A "(?)" in the description marks the grouping as a guess.
        assertEquals(
            ValueSupport.UNCERTAIN,
            ModelCapabilities.of(m, listOf(CounterSpec(listOf(48, 49), "Waste counters (?)"))).values,
        )

        // Wider than 4 bytes is not one integer, so those addresses show as bytes.
        assertEquals(
            ValueSupport.UNCERTAIN,
            ModelCapabilities.of(
                m,
                listOf(
                    CounterSpec(listOf(48, 49), "Waste counter"),
                    CounterSpec(listOf(1, 2, 3, 4, 5, 6), "Waste counter"),
                ),
            ).values,
        )
    }

    @Test
    fun `a limit is reported only when a positive maximum exists`() {
        val m = model(group("main", 48, 49))

        assertFalse(ModelCapabilities.of(m, listOf(CounterSpec(listOf(48, 49), "c"))).hasLimit)
        assertFalse(ModelCapabilities.of(m, listOf(CounterSpec(listOf(48, 49), "c", max = 0))).hasLimit)
        assertTrue(ModelCapabilities.of(m, listOf(CounterSpec(listOf(48, 49), "c", max = 6346))).hasLimit)
    }

    // The bundled files, not PrinterDatabase.load(), so a cached OTA download on the developer's
    // machine can't move these numbers.
    private val bundledDatabase: PrinterDatabase by lazy {
        val text = assertNotNull(
            PrinterDatabase::class.java.getResourceAsStream(CounterSpecs.PRINTER_DATA)
                ?.bufferedReader()?.use { it.readText() },
        )
        PrinterDatabase.parse(text)
    }

    private val bundledSpecs by lazy { CounterSpecs.loadBundled() }

    @Test
    fun `the bundled data yields the capability set the UI advertises`() {
        val capabilities = ModelCapabilities.of(bundledDatabase, bundledSpecs)
        val summary = ModelCapabilities.summarise(capabilities, bundledDatabase, bundledSpecs)

        assertEquals(1588, summary.total)
        assertEquals(1475, summary.resettable)
        assertEquals(313, summary.platenOnly)

        // Almost every layout carries a "(?)" entry or a group too wide to be one integer, which is
        // why docs/counter-database.md claims so little: 123 models decode cleanly, 1352 only in
        // part.
        assertEquals(123, summary.decoded)
        assertEquals(1352, summary.uncertain)

        // 6 counters declare a maximum in counters.json; calibrations.json adds the ET-282x family.
        assertEquals(14, summary.withLimit)

        // Zero as long as both data files are generated together: every model with a read layout has
        // reset data too.
        assertEquals(0, summary.layoutOnly)

        assertEquals(summary.total, capabilities.size)
        assertTrue(
            summary.readable >= summary.resettable,
            "reading must never be narrower than resetting",
        )
    }

    @Test
    fun `the ET-2825 row matches what the printer actually offers`() {
        val model = assertNotNull(bundledDatabase["ET-2825"])
        val capability = ModelCapabilities.of(model, bundledSpecs["ET-2825"].orEmpty())

        assertTrue(capability.canReset)
        assertTrue(capability.canRead)
        assertEquals(ResetScope.FULL, capability.scope)
        assertEquals(14, capability.writeCount)
        assertEquals(6, capability.counterCount)

        // The 6-address platen entry is marked "(?)" and is too wide to be one value.
        assertEquals(ValueSupport.UNCERTAIN, capability.values)

        // Measured on a real ET-2820 and recorded in calibrations.json — this is the only family
        // where the UI can show a percentage.
        assertTrue(capability.hasLimit)
    }
}
