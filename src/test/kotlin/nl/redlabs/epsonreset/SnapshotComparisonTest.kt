package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.backup.SnapshotComparison
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.protocol.CounterReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What moved between two samples, and what that is allowed to be reported as. */

/** A counter split across two addresses, little-endian, as most of the database's are. */
private val pad = CounterSpec(addresses = listOf(48, 49), description = "Waste ink pad counter")

private fun reading(address: Int, value: Int?, resetValue: Int = 0) =
    CounterReader.Reading(address, value, resetValue, "Waste")

private fun side(
    label: String,
    vararg readings: CounterReader.Reading,
    model: String = "TEST-1",
    serial: String? = null,
) = SnapshotComparison.Side(
    label = label,
    takenAt = label,
    model = model,
    serial = serial,
    readings = readings.toList(),
)

class SnapshotComparisonTest {

    /** The case the whole counter level exists for. */
    @Test
    fun `a carry across two addresses is one counter moving forward`() {
        val before = side("before", reading(48, 0xFF), reading(49, 0x0E))
        val after = side("after", reading(48, 0x23), reading(49, 0x0F))

        val result = SnapshotComparison.compare(before, after, listOf(pad))
        val counter = result.counters.single()

        assertEquals(3839L, counter.before)
        assertEquals(3875L, counter.after)
        assertEquals(36L, counter.delta)
        assertEquals("+36", counter.deltaLabel)
        assertTrue(counter.moved)

        // And the byte level still reports both addresses, low one included, falling.
        assertEquals(2, result.changedBytes)
    }

    /** A reset run between the samples reads as a negative delta, not as an anomaly to hide. */
    @Test
    fun `a reset between the samples shows as a negative delta`() {
        val before = side("before", reading(48, 0x19), reading(49, 0x0F))
        val after = side("after", reading(48, 0x00), reading(49, 0x00))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertEquals(-3865L, result.counters.single().delta)
        assertEquals("-3865", result.counters.single().deltaLabel)
        assertTrue(result.afterIsAtResetValue)
    }

    /**
     * The durable version of the in-run read-back: a snapshot taken before a reset still proves,
     * days later and with the app restarted in between, that every address landed on its reset
     * value.
     */
    @Test
    fun `afterIsAtResetValue is false when one address did not land`() {
        val before = side("before", reading(48, 0x19), reading(49, 0x0F))
        val after = side("after", reading(48, 0x00), reading(49, 0x0F))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertFalse(result.afterIsAtResetValue)
    }

    /**
     * A group too wide to be one integer has no delta, and that is not the same as not moving.
     * Reporting "unchanged" for a group whose bytes plainly differ is the failure worth preventing.
     */
    @Test
    fun `a group that cannot decode still reports that it moved`() {
        val wide = CounterSpec(addresses = (40..45).toList(), description = "Six bytes (?)")
        val before = side("before", *(40..45).map { reading(it, 0x00) }.toTypedArray())
        val after = side("after", *(40..45).map { reading(it, if (it == 42) 0x01 else 0x00) }.toTypedArray())

        val result = SnapshotComparison.compare(before, after, listOf(wide))
        val counter = result.counters.single()

        assertNull(counter.delta)
        assertTrue(counter.moved)
        assertEquals("changed", counter.deltaLabel)
    }

    /**
     * The finding this feature is worth building for: a byte the printer maintains that the model's
     * layout does not claim. Two samples cannot say what it counts, but they can say it exists.
     */
    @Test
    fun `a byte that moved outside every counter is reported as unexplained`() {
        val before = side("before", reading(48, 0x19), reading(49, 0x0F), reading(87, 0x10))
        val after = side("after", reading(48, 0x19), reading(49, 0x0F), reading(87, 0x11))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertContentEquals(listOf(87), result.unexplained.map { it.address })
        assertFalse(result.counters.single().moved)
    }

    /**
     * With no layout at all every byte is unclaimed, so listing them as findings would just restate
     * the table underneath in more alarming type.
     */
    @Test
    fun `nothing is unexplained when the model has no layout`() {
        val before = side("before", reading(48, 0x19))
        val after = side("after", reading(48, 0x1A))

        val result = SnapshotComparison.compare(before, after, specs = emptyList())

        assertTrue(result.unexplained.isEmpty())
        assertEquals(1, result.changedBytes)
    }

    /**
     * An address only one sample carries is unknown on the other, and unknown is not changed —
     * counting it would inflate every summary a partial read appears in.
     */
    @Test
    fun `an address present on only one side is not counted as changed`() {
        val before = side("before", reading(48, 0x19))
        val after = side("after", reading(48, 0x19), reading(87, 0x10))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertEquals(0, result.changedBytes)
        assertTrue(result.identical)
        assertTrue(result.notes.any { it.contains("only one side") }, "${result.notes}")

        // Both addresses still appear — the union, so the one-sided one is visible rather than
        // quietly dropped.
        assertContentEquals(listOf(48, 87), result.bytes.map { it.address })
    }

    /** An unanswered address is the same kind of unknown, arriving by a different route. */
    @Test
    fun `an unanswered address is not counted as changed`() {
        val before = side("before", reading(48, 0x19), reading(49, null))
        val after = side("after", reading(48, 0x19), reading(49, 0x0F))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertEquals(0, result.changedBytes)
        assertNull(result.counters.single().before)
    }

    @Test
    fun `two serials that differ are called out as two printers`() {
        val before = side("before", reading(48, 0x19), serial = "X1")
        val after = side("after", reading(48, 0x20), serial = "X2")

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertTrue(result.notes.any { it.contains("Different printers") }, "${result.notes}")
    }

    /**
     * A snapshot predating serial capture has none. Warning about that on every old file would
     * train the user past the line that matters when two serials really do differ.
     */
    @Test
    fun `a missing serial raises nothing`() {
        val before = side("before", reading(48, 0x19), serial = null)
        val after = side("after", reading(48, 0x20), serial = "X2")

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertFalse(result.notes.any { it.contains("Different printers") }, "${result.notes}")
    }

    @Test
    fun `two models are called out as meaning nothing to compare`() {
        val before = side("before", reading(48, 0x19), model = "TEST-1")
        val after = side("after", reading(48, 0x20), model = "OTHER-9")

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertTrue(result.notes.any { it.contains("different models") }, "${result.notes}")
    }

    /**
     * A read that answered nothing has no bytes to disagree with, which is not the same as
     * agreeing.
     */
    @Test
    fun `a sample that answered nothing is not reported as unchanged`() {
        val before = side("before", reading(48, 0x19), reading(49, 0x0F))
        val after = side("after", reading(48, null), reading(49, null))

        val result = SnapshotComparison.compare(before, after, listOf(pad))

        assertEquals(0, result.comparable)
        assertFalse(result.identical)
        assertFalse(result.afterIsAtResetValue)
        assertTrue(result.summary.contains("Nothing can be compared"), result.summary)
    }

    @Test
    fun `identical samples say so rather than listing zero changes`() {
        val readings = arrayOf(reading(48, 0x19), reading(49, 0x0F))
        val result = SnapshotComparison.compare(
            side("before", *readings),
            side("after", *readings),
            listOf(pad),
        )

        assertTrue(result.identical)
        assertTrue(result.movedCounters.isEmpty())
        assertTrue(result.summary.contains("Nothing moved"), result.summary)
    }
}
