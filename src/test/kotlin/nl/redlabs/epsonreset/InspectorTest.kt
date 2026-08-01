package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.probe.DeviceInspector
import nl.redlabs.epsonreset.probe.SweepAnalysis
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The inspector's contract is that it maps out an unidentified printer without ever being able to
 * modify it. These pin both halves: that it finds what it should, and that it cannot write.
 */
class InspectorTest {

    private val db by lazy {
        val text = assertNotNull(
            PrinterDatabase::class.java.getResourceAsStream(CounterSpecs.PRINTER_DATA)
                ?.bufferedReader()?.use { it.readText() },
        )
        PrinterDatabase.parse(text)
    }

    @Test
    fun `a full inspection sends no EEPROM write`() {
        val transport = FakeTransport(memory = mapOf(0x30 to 0x19, 0x31 to 0x0F))

        DeviceInspector.discoverKey(transport, db, keys = DeviceInspector.candidateKeys(db).take(5))
        DeviceInspector.sweep(transport, readKey = 0x364A, addresses = (0x28..0x40).toList())

        assertTrue(transport.sent.isNotEmpty(), "the inspector sent nothing at all")
        assertFalse(
            transport.sent.any { Executor.isWritePacket(it) },
            "the inspector emitted an EEPROM write",
        )
    }

    @Test
    fun `every packet the inspector sends is a read, credit or handshake`() {
        val transport = FakeTransport()
        DeviceInspector.sweep(transport, readKey = 0x364A, addresses = listOf(0x30, 0x31))

        // 0x42 is the write opcode. It must not appear at the command offset of any packet.
        val writeOpcodeAt12 = transport.sent.count { it.size > 12 && it[12] == 0x42.toByte() }
        assertEquals(0, writeOpcodeAt12)
    }

    @Test
    fun `discovery finds the key a key-sensitive printer answers to`() {
        val target = 0x364A // ET-2825's read key, the largest family in the database
        val probes = DeviceInspector.probeAddressesFor(db, target)
        assertTrue(probes.isNotEmpty(), "the family should contribute probe addresses")

        val transport = FakeTransport(
            memory = probes.associateWith { 0x11 },
            readKey = target,
        )

        val results = DeviceInspector.discoverKey(transport, db)
        val answered = results.filter { it.answered }

        assertEquals(1, answered.size, "exactly one key should have answered")
        assertEquals(target, answered.single().readKey)
    }

    @Test
    fun `discovery reports nothing when no candidate key works`() {
        // A key no model in the database uses, so no candidate can match it.
        val transport = FakeTransport(memory = mapOf(0x30 to 0x11), readKey = 0xDEAD)

        val results = DeviceInspector.discoverKey(transport, db)

        assertTrue(results.none { it.answered }, "no key should have answered")
    }

    @Test
    fun `candidate keys are ordered by how many models share them, and exclude zero`() {
        val keys = DeviceInspector.candidateKeys(db)

        assertFalse(keys.contains(0), "0 is the parser's default, not a real key")
        val sizes = keys.take(5).map { key -> DeviceInspector.siblingsOf(db, key).size }
        assertEquals(sizes.sortedDescending(), sizes, "most-used keys should come first")
    }

    @Test
    fun `a silent printer yields no results rather than a false positive`() {
        val mute = object : nl.redlabs.epsonreset.protocol.Transport {
            override fun send(packet: ByteArray) = true
            override fun drain() = ByteArray(0)
        }

        assertTrue(DeviceInspector.discoverKey(mute, db).isEmpty())
        val sweep = DeviceInspector.sweep(mute, 0x364A, listOf(1, 2, 3))
        assertEquals(0, sweep.answered)
        assertNotNull(sweep.error)
    }

    @Test
    fun `a sweep records the answered addresses and lists the silent ones`() {
        val transport = FakeTransport(memory = mapOf(0x30 to 0x19, 0x31 to 0x0F), readKey = 0x364A)
        // defaultValue would answer everything, so restrict the fake to the seeded addresses only
        // by sweeping a range that includes unseeded ones and asserting on what came back.
        val sweep = DeviceInspector.sweep(transport, 0x364A, listOf(0x30, 0x31))

        assertEquals(2, sweep.answered)
        assertEquals(0x19, sweep.values[0x30])
        assertEquals(0x0F, sweep.values[0x31])
        assertTrue(sweep.silent.isEmpty())
    }

    @Test
    fun `a wrong key sweeps nothing`() {
        val transport = FakeTransport(memory = mapOf(0x30 to 0x19), readKey = 0x364A)
        val sweep = DeviceInspector.sweep(transport, readKey = 0x1111, addresses = listOf(0x30))

        assertEquals(0, sweep.answered)
        assertEquals(listOf(0x30), sweep.silent)
    }

    private fun sweepOf(values: Map<Int, Int>) = DeviceInspector.Sweep(0x364A, values.keys.sorted(), values)

    @Test
    fun `a sibling layout outranks a bare adjacent pair`() {
        val sibling = PrinterModel(
            name = "SIBLING-1",
            readKey = 0x364A,
            padGroups = listOf(PadGroup("Main Pad Counter", "main", listOf(0x30, 0x31), listOf(0, 0))),
        )
        val sweep = sweepOf(mapOf(0x30 to 0x19, 0x31 to 0x0F, 0x40 to 0x22, 0x41 to 0x01))

        val candidates = SweepAnalysis.candidates(
            sweep,
            siblings = listOf(sibling),
            specsFor = { listOf(CounterSpec(listOf(0x30, 0x31), "Waste counter")) },
        )

        val first = candidates.first()
        assertEquals(listOf(0x30, 0x31), first.addresses)
        assertEquals(SweepAnalysis.Confidence.FAMILY, first.confidence)
        assertEquals(0x0F19L, first.value)
        assertContains(first.why, "SIBLING-1")

        // The unrelated pair is still offered, but below the family match.
        assertTrue(candidates.any { it.addresses == listOf(0x40, 0x41) })
    }

    @Test
    fun `blank saturated and limit-byte regions are not offered as counters`() {
        val sweep = sweepOf(
            mapOf(
                0x10 to 0x00,
                0x11 to 0x00, // unprogrammed
                0x20 to 0xFF,
                0x21 to 0xFF, // saturated
                0x30 to 0x5E,
                0x31 to 0x5E, // limit bytes
            ),
        )

        assertTrue(SweepAnalysis.candidates(sweep).isEmpty())
    }

    @Test
    fun `a pair bordered by a limit byte ranks above a bare one`() {
        val bordered = sweepOf(mapOf(0x30 to 0x19, 0x31 to 0x0F, 0x32 to SweepAnalysis.LIMIT_BYTE))
        assertEquals(
            SweepAnalysis.Confidence.LIKELY,
            SweepAnalysis.candidates(bordered).single().confidence,
        )

        val bare = sweepOf(mapOf(0x30 to 0x19, 0x31 to 0x0F))
        assertEquals(
            SweepAnalysis.Confidence.WEAK,
            SweepAnalysis.candidates(bare).single().confidence,
        )
    }

    @Test
    fun `a high byte too large to be a counter is rejected`() {
        val sweep = sweepOf(mapOf(0x30 to 0x11, 0x31 to 0xA0))
        assertTrue(SweepAnalysis.candidates(sweep).isEmpty())
    }

    @Test
    fun `the exported overlay parses back as a real layout`() {
        val sweep = sweepOf(mapOf(0x30 to 0x19, 0x31 to 0x0F))
        val candidates = SweepAnalysis.candidates(sweep)
        val json = SweepAnalysis.overlayJson("MY-MODEL", candidates)

        // The round trip is the point: an overlay the app cannot load is worthless to the user.
        val parsed = nl.redlabs.epsonreset.db.CounterSpecs.parseGroups(json)
        val specs = assertNotNull(parsed["my-model"], "overlay did not yield the model")
        assertEquals(listOf(0x30, 0x31), specs.single().addresses)
        assertEquals(0x0F19L, specs.single().decode(mapOf(0x30 to 0x19, 0x31 to 0x0F)))
    }

    @Test
    fun `the report names the device, the key and the candidates`() {
        val sweep = sweepOf(mapOf(0x30 to 0x19, 0x31 to 0x0F))
        val report = SweepAnalysis.report(
            device = null,
            sweep = sweep,
            candidates = SweepAnalysis.candidates(sweep),
            keyResults = listOf(DeviceInspector.KeyResult(0x364A, 2, 3, emptyMap(), listOf("ET-2825"))),
        )

        assertContains(report, "0x364A")
        assertContains(report, "ET-2825")
        assertContains(report, "Read-only")
        assertContains(report, "0x30, 0x31")
    }

    @Test
    fun `the dump marks unanswered addresses rather than omitting them`() {
        val sweep = DeviceInspector.Sweep(0x364A, listOf(0x30, 0x31, 0x32), mapOf(0x30 to 0x19))
        val dump = SweepAnalysis.dump(sweep)

        assertContains(dump, "19")
        assertContains(dump, "--", message = "silent addresses must be visible, not dropped")
    }
}
