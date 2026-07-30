package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.history.CounterJournal
import nl.redlabs.epsonreset.history.CounterProjection
import nl.redlabs.epsonreset.protocol.CounterReader
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val historySpec = CounterSpec(listOf(10, 11), "Waste pad", max = 1000)
private val historyStart: Instant = Instant.parse("2026-01-01T00:00:00Z")

private fun historyReport(value: Int, model: String = "TEST-1"): CounterReader.Report = CounterReader.Report(
    model,
    listOf(
        CounterReader.Reading(10, value and 0xFF, 0, "Waste"),
        CounterReader.Reading(11, (value shr 8) and 0xFF, 0, "Waste"),
    ),
)

private fun historySample(day: Long, value: Int) = CounterJournal.Sample(
    historyStart.plusSeconds(day * 86_400L),
    "QWER012345",
    historyReport(value),
)

class CounterJournalTest {
    private fun file(): File = File(createTempDirectory("history-test").toFile(), "counter-history.jsonl")

    @Test
    fun `append canonicalises a USB serial and round trips a report`() {
        val file = file()
        val journal = CounterJournal(file)

        journal.append("51574552303132333435", historyReport(321), historyStart)

        val sample = journal.load("QWER012345").single()
        assertEquals("QWER012345", sample.serial)
        assertEquals(historyStart, sample.takenAt)
        assertEquals(321L, historySpec.decode(sample.report.readings.associate { it.address to it.value }))
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test
    fun `partly encoded USB and plain network serials load the same history`() {
        val file = file()
        val journal = CounterJournal(file)
        val partialUsb = "515745523031323345"
        val plain = "QWER012345"

        journal.append(partialUsb, historyReport(100), historyStart)
        journal.append(plain, historyReport(200), historyStart.plusSeconds(86_400))

        assertEquals(2, journal.load(partialUsb).size)
        assertEquals(2, journal.load(plain).size)
        assertEquals(1, journal.stats().printers)
    }

    @Test
    fun `a malformed line costs only itself`() {
        val file = file()
        file.writeText("not json\n")
        val journal = CounterJournal(file)

        journal.append("QWER012345", historyReport(100), historyStart)

        assertEquals(1, journal.load("QWER012345").size)
        assertEquals(1, journal.stats().samples)
    }

    @Test
    fun `unidentified and unsuccessful reads are not appended`() {
        val journal = CounterJournal(file())

        assertNull(journal.append(null, historyReport(100), historyStart))
        assertNull(journal.append("QWER012345", CounterReader.Report("TEST-1", emptyList()), historyStart))
        assertEquals(0, journal.stats().samples)
    }

    @Test
    fun `delete removes all printers without changing recording policy`() {
        val file = file()
        val journal = CounterJournal(file)
        journal.append("UNIT0001", historyReport(100), historyStart)
        journal.append("UNIT0002", historyReport(200), historyStart)

        assertEquals(2, journal.stats().printers)
        assertTrue(journal.deleteAll())
        assertFalse(file.exists())
        assertEquals(0, journal.stats().samples)
    }
}

class CounterProjectionTest {
    @Test
    fun `projects the measured maximum from the current fill rate`() {
        val trend = CounterProjection.calculate(
            listOf(historySample(0, 100), historySample(10, 200)),
            listOf(historySpec),
        ).single()

        assertEquals(100L, trend.delta)
        assertEquals(10.0, trend.ratePerDay)
        assertEquals(historyStart.plusSeconds(90L * 86_400L), trend.projectedAt)
        assertNull(trend.projectionReason)
    }

    @Test
    fun `a reset starts a new monotonic trend segment`() {
        val trend = CounterProjection.calculate(
            listOf(
                historySample(0, 500),
                historySample(10, 600),
                historySample(11, 10),
                historySample(21, 110),
            ),
            listOf(historySpec),
        ).single()

        assertTrue(trend.resetObserved)
        assertEquals(2, trend.samplesUsed)
        assertEquals(10L, trend.first)
        assertEquals(110L, trend.latest)
        assertEquals(10.0, trend.ratePerDay)
    }

    @Test
    fun `less than one day never produces a date`() {
        val trend = CounterProjection.calculate(
            listOf(
                historySample(0, 100),
                CounterJournal.Sample(historyStart.plusSeconds(3600), "QWER012345", historyReport(200)),
            ),
            listOf(historySpec),
        ).single()

        assertNull(trend.projectedAt)
        assertTrue(assertNotNull(trend.projectionReason).contains("at least a day"))
    }

    @Test
    fun `a stationary counter has a zero rate and no projection`() {
        val trend = CounterProjection.calculate(
            listOf(historySample(0, 100), historySample(10, 100)),
            listOf(historySpec),
        ).single()

        assertEquals(0.0, trend.ratePerDay)
        assertNull(trend.projectedAt)
        assertTrue(assertNotNull(trend.projectionReason).contains("No increase"))
    }

    @Test
    fun `a rate survives when no maximum is measured but a date does not`() {
        val withoutMaximum = historySpec.copy(max = null)
        val trend = CounterProjection.calculate(
            listOf(historySample(0, 100), historySample(10, 200)),
            listOf(withoutMaximum),
        ).single()

        assertEquals(10.0, trend.ratePerDay)
        assertNull(trend.projectedAt)
        assertEquals("No measured maximum", trend.projectionReason)
    }
}
