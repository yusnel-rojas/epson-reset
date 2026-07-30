package nl.redlabs.epsonreset.history

import nl.redlabs.epsonreset.db.CounterSpec
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** Trends one printer's decoded counters, restarting a counter's trend whenever its value drops. */
object CounterProjection {

    data class Trend(
        val spec: CounterSpec,
        val samplesUsed: Int,
        val firstAt: Instant?,
        val latestAt: Instant?,
        val first: Long?,
        val latest: Long?,
        val delta: Long?,
        val elapsedDays: Double?,
        val ratePerDay: Double?,
        val projectedAt: Instant?,
        val projectionReason: String?,
        val resetObserved: Boolean,
    )

    /** One sample reduced to the single number this counter decodes to at that moment. */
    private data class Point(val at: Instant, val value: Long)

    fun calculate(samples: List<CounterJournal.Sample>, specs: List<CounterSpec>): List<Trend> {
        val ordered = samples.sortedBy { it.takenAt }
        return specs.filter { it.isSingleValue }.map { spec -> trend(ordered, spec) }
    }

    private fun trend(samples: List<CounterJournal.Sample>, spec: CounterSpec): Trend {
        // A sample that never answered this counter's addresses decodes to null and is dropped.
        val usable = samples.mapNotNull { sample ->
            spec.decode(sample.bytes())?.let { Point(sample.takenAt, it) }
        }

        // A value that fell since the previous sample is a reset; the trend restarts from there so an
        // earlier reset cannot pull the fill rate negative.
        var segmentStart = 0
        var resetObserved = false
        for (index in 1 until usable.size) {
            if (usable[index].value < usable[index - 1].value) {
                segmentStart = index
                resetObserved = true
            }
        }

        val segment = usable.drop(segmentStart)
        val first = segment.firstOrNull()
        val latest = segment.lastOrNull()
        val delta = if (first != null && latest != null) latest.value - first.value else null
        val elapsedMillis = if (first != null && latest != null) {
            Duration.between(first.at, latest.at).toMillis().coerceAtLeast(0L)
        } else {
            0L
        }
        val elapsedDays = elapsedMillis.takeIf { it > 0L }?.toDouble()?.div(DAY_MILLIS)
        val rate = if (delta != null && delta >= 0L && elapsedDays != null) delta / elapsedDays else null
        val projection = projection(spec, latest?.value, latest?.at, segment.size, elapsedMillis, rate)

        return Trend(
            spec = spec,
            samplesUsed = segment.size,
            firstAt = first?.at,
            latestAt = latest?.at,
            first = first?.value,
            latest = latest?.value,
            delta = delta,
            elapsedDays = elapsedDays,
            ratePerDay = rate,
            projectedAt = projection.first,
            projectionReason = projection.second,
            resetObserved = resetObserved,
        )
    }

    private fun projection(
        spec: CounterSpec,
        latest: Long?,
        latestAt: Instant?,
        samples: Int,
        elapsedMillis: Long,
        ratePerDay: Double?,
    ): Pair<Instant?, String?> {
        val maximum = spec.max?.takeIf { it > 0 }?.toLong() ?: return null to "No measured maximum"
        if (latest == null || latestAt == null) return null to "No complete reading"
        if (latest >= maximum) return latestAt to null
        if (samples < 2) return null to "Needs another live read"
        if (elapsedMillis < MIN_PROJECTION_SPAN_MILLIS) return null to "Needs readings at least a day apart"
        val rate = ratePerDay?.takeIf { it > 0.0 } ?: return null to "No increase in this period"

        val remainingDays = (maximum - latest) / rate
        val remainingMillis = (remainingDays * DAY_MILLIS).takeIf { it.isFinite() }?.roundToLong()
            ?: return null to "Projection is outside the supported date range"
        val projected = runCatching { latestAt.plusMillis(remainingMillis.coerceAtLeast(0L)) }.getOrNull()
            ?: return null to "Projection is outside the supported date range"
        return projected to null
    }

    /** The counter bytes of one sample, keyed by address, as [CounterSpec.decode] expects them. */
    private fun CounterJournal.Sample.bytes(): Map<Int, Int?> = report.readings.associate { it.address to it.value }

    private const val DAY_MILLIS = 86_400_000.0
    private const val MIN_PROJECTION_SPAN_MILLIS = 86_400_000L
}
