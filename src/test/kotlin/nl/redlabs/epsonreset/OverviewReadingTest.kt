package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.history.CounterProjection
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.DeviceId
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.ui.OverviewAlert
import nl.redlabs.epsonreset.ui.OverviewCounterLevel
import nl.redlabs.epsonreset.ui.OverviewReading
import nl.redlabs.epsonreset.ui.overviewCounterCoverageLabel
import nl.redlabs.epsonreset.ui.overviewCounterLevel
import nl.redlabs.epsonreset.ui.overviewCounterRows
import nl.redlabs.epsonreset.ui.overviewCounterSummaryAvailable
import nl.redlabs.epsonreset.ui.sparklineGeometry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OverviewReadingTest {
    private val now = Instant.parse("2026-08-01T12:00:00Z")

    private fun reading(
        status: Status.Report? = null,
        counters: CounterReader.Report? = null,
        specs: List<CounterSpec> = emptyList(),
    ) = OverviewReading.create(
        targetId = "usb:1:2",
        printerName = "TEST-1",
        linkKind = "USB",
        model = "TEST-1",
        refreshedAt = now,
        connection = null,
        status = status,
        printerMib = null,
        counters = counters,
        specs = specs,
    )

    @Test
    fun `only established printer signals become alerts`() {
        val status = Status.Report(
            fields = listOf(
                Status.Field(Status.TYPE_STATE, byteArrayOf(Status.STATE_BUSY.toByte())),
                Status.Field(Status.TYPE_ERROR, byteArrayOf(0x04)),
                Status.Field(Status.TYPE_INK, byteArrayOf(3, 0, 0, 10)),
            ),
            raw = ByteArray(0),
        )
        val mib = PrinterMib.Reading(
            lifeCount = 100,
            supplies = listOf(PrinterMib.Supply(1, "Waste ink", 4, 8, 90, 100, null)),
        )
        val spec = CounterSpec(listOf(10), "Waste counter", max = 100)
        val report = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(10, 100, 0, "Waste")),
        )

        val snapshot = OverviewReading.create(
            targetId = "usb:1:2",
            printerName = "TEST-1",
            linkKind = "USB",
            model = "TEST-1",
            refreshedAt = now,
            connection = ConnectionTest.Result(true, null, true, status),
            status = status,
            printerMib = mib,
            counters = report,
            specs = listOf(spec),
        )

        assertTrue(snapshot.alerts.any { it.severity == OverviewAlert.Severity.ERROR })
        assertTrue(snapshot.alerts.any { it.title == "Printer is not idle" })
        assertTrue(snapshot.alerts.any { it.title == "Black ink is low" })
        assertTrue(snapshot.alerts.any { it.title.contains("Waste ink is filling up") })
        assertTrue(snapshot.alerts.any { it.title == "Waste counter is at its maximum" })
    }

    /** The error is named, not spelled `0x04` — the same wording `Status.busyReason` uses. */
    @Test
    fun `a reported error code is put into words`() {
        val alert = assertNotNull(
            reading(
                status = Status.Report(
                    fields = listOf(Status.Field(Status.TYPE_ERROR, byteArrayOf(0x04))),
                    raw = ByteArray(0),
                ),
            ).alerts.firstOrNull { it.severity == OverviewAlert.Severity.ERROR },
        )

        assertEquals("The printer reports paper jam.", alert.detail)
    }

    /**
     * The band the counter table already paints amber. Before this, a pad at 94% coloured a row
     * while the headline above it read "No reported warnings".
     */
    @Test
    fun `a counter near its maximum is an alert, not silence`() {
        val spec = CounterSpec(listOf(10, 11), "Waste counter", max = 100)

        fun alertsAt(value: Int) = reading(
            counters = CounterReader.Report("TEST-1", listOf(CounterReader.Reading(10, value, 0, "Waste"))),
            specs = listOf(CounterSpec(listOf(10), spec.description, max = spec.max)),
        ).alerts

        assertEquals(emptyList(), alertsAt(89), "below the band nothing is claimed")

        val nearly = assertNotNull(alertsAt(94).singleOrNull())
        assertEquals(OverviewAlert.Severity.ATTENTION, nearly.severity)
        assertEquals("Waste counter is nearly full", nearly.title)
        assertEquals(OverviewAlert.Action.MAINTENANCE, nearly.action)

        val full = assertNotNull(alertsAt(100).singleOrNull())
        assertEquals(OverviewAlert.Severity.ERROR, full.severity)
        assertEquals("Waste counter is at its maximum", full.title)
        assertEquals(OverviewAlert.Action.MAINTENANCE, full.action)
    }

    @Test
    fun `missing sources are unavailable coverage and never warnings`() {
        val snapshot = OverviewReading.create(
            targetId = "usb:1:2",
            printerName = "TEST-1",
            linkKind = "USB",
            model = "TEST-1",
            refreshedAt = now,
            connection = null,
            status = null,
            printerMib = null,
            counters = null,
            specs = emptyList(),
        )

        assertTrue(snapshot.alerts.isEmpty())
        assertTrue(snapshot.coverage.all { !it.available })
    }

    @Test
    fun `status ink suppresses duplicate standard-MIB ink warnings`() {
        val status = Status.Report(
            fields = listOf(Status.Field(Status.TYPE_INK, byteArrayOf(3, 0, 0, 10))),
            raw = ByteArray(0),
        )
        val mib = PrinterMib.Reading(
            lifeCount = null,
            supplies = listOf(PrinterMib.Supply(1, "Black", 3, 5, 10, 100, "black")),
        )

        val snapshot = OverviewReading.create(
            "usb:1:2",
            "TEST-1",
            "USB",
            "TEST-1",
            now,
            ConnectionTest.Result(true, null, true, status),
            status,
            mib,
            null,
            emptyList(),
        )

        assertEquals(1, snapshot.alerts.count { it.title.contains("Black") })
    }
}

class SparklineGeometryTest {
    private val at = Instant.parse("2026-08-01T12:00:00Z")

    @Test
    fun `equal timestamps and values remain finite and centered`() {
        val points = listOf(
            CounterProjection.TrendPoint(at, 10, startsSegment = true),
            CounterProjection.TrendPoint(at, 10, startsSegment = false),
        )

        val geometry = sparklineGeometry(points, maximum = null, width = 100f, height = 40f)

        assertEquals(listOf(0f, 100f), geometry.map { it.x })
        assertTrue(geometry.all { it.y == 20f && it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun `reset boundaries survive chart normalization`() {
        val points = listOf(
            CounterProjection.TrendPoint(at, 100, startsSegment = true),
            CounterProjection.TrendPoint(at.plusSeconds(60), 120, startsSegment = false),
            CounterProjection.TrendPoint(at.plusSeconds(120), 5, startsSegment = true),
        )

        val geometry = sparklineGeometry(points, maximum = 200, width = 100f, height = 40f)

        assertFalse(geometry[1].startsSegment)
        assertTrue(geometry[2].startsSegment)
        assertTrue(geometry.all { it.x in 0f..100f && it.y in 0f..40f })
    }
}

class OverviewCounterRowsTest {
    @Test
    fun `summary is unavailable without a reported decoded counter`() {
        val spec = CounterSpec(listOf(10), "Waste counter", resetValues = listOf(0), max = 100)
        val unknownOnly = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(11, 42, 0, "Unknown")),
        )

        assertFalse(overviewCounterSummaryAvailable(null, listOf(spec)))
        assertFalse(overviewCounterSummaryAvailable(unknownOnly, emptyList()))
        assertFalse(overviewCounterSummaryAvailable(unknownOnly, listOf(spec)))
    }

    @Test
    fun `summary is available when a decoded counter reported a byte`() {
        val spec = CounterSpec(listOf(10), "Waste counter", resetValues = listOf(0), max = 100)
        val report = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(10, 42, 0, "Waste")),
        )

        assertTrue(overviewCounterSummaryAvailable(report, listOf(spec)))
    }

    @Test
    fun `no printer answers produce no status rows or reset targets`() {
        val spec = CounterSpec(listOf(10), "Waste counter", resetValues = listOf(0), max = 100)
        val report = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(10, null, expectedAfterReset = 0, groupDescription = "Waste")),
        )

        assertTrue(overviewCounterRows(report, listOf(spec)).isEmpty())
    }

    @Test
    fun `reported counters expose current status and measured maximum`() {
        val spec = CounterSpec(listOf(10), "Waste counter", resetValues = listOf(0), max = 100)
        val report = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(10, 95, expectedAfterReset = 0, groupDescription = "Waste")),
        )

        val row = overviewCounterRows(report, listOf(spec)).single()
        assertEquals("95", row.current)
        assertEquals(100, row.maximum)
        assertEquals(95.0, row.percent)
        assertEquals("95.0% of measured maximum", row.detail)
        assertFalse(row.detail.contains("100"), "the maximum value belongs only in the current / maximum label")
        assertFalse(row.current.contains("00"), "Overview must not render the reset destination")
    }

    @Test
    fun `counter emphasis uses established reaching and maximum boundaries`() {
        assertEquals(OverviewCounterLevel.LOW, overviewCounterLevel(0.0))
        assertEquals(OverviewCounterLevel.LOW, overviewCounterLevel(89.9))
        assertEquals(OverviewCounterLevel.REACHING, overviewCounterLevel(90.0))
        assertEquals(OverviewCounterLevel.REACHING, overviewCounterLevel(99.9))
        assertEquals(OverviewCounterLevel.MAXED, overviewCounterLevel(100.0))
        assertEquals(OverviewCounterLevel.MAXED, overviewCounterLevel(120.0))
    }

    @Test
    fun `address coverage is shown only for a partial counter read`() {
        val complete = CounterReader.Report(
            "TEST-1",
            listOf(CounterReader.Reading(10, 1, 0, "Waste")),
        )
        val partial = CounterReader.Report(
            "TEST-1",
            listOf(
                CounterReader.Reading(10, 1, 0, "Waste"),
                CounterReader.Reading(11, null, 0, "Waste"),
            ),
        )

        assertEquals(null, overviewCounterCoverageLabel(complete))
        assertEquals("1/2 addresses reported", overviewCounterCoverageLabel(partial))
    }
}

class ConnectionHeadlineTest {
    @Test
    fun `USB counter refusal is explained without protocol jargon`() {
        val result = ConnectionTest.Result(
            opened = true,
            identity = DeviceId.Id(mapOf("MDL" to "ET-2825")),
            answered = false,
            status = null,
        )

        assertEquals(
            "Printer identified over USB, but counter access is unavailable on this connection.",
            result.headline,
        )
        assertFalse(result.headline.contains("packet channel"))
    }
}
