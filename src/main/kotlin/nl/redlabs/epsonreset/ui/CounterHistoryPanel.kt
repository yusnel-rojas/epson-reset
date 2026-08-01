package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.history.CounterProjection
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Trends from successful live reads of the currently selected physical printer. */
@Composable
fun CounterHistoryPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val view = vm.history.view
    val unavailable = vm.history.unavailableReason
    if (view == null && unavailable == null) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Counter history",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.keepCounterHistory) "Recording locally" else "Recording paused",
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.keepCounterHistory) StatusColors.good else StatusColors.warn,
            )
        }

        Spacer(Modifier.height(4.dp))
        if (unavailable != null) {
            Text(unavailable, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            return@Column
        }

        view ?: return@Column
        Text(
            "${view.samples.size} live sample(s) for ${view.model} · serial ${view.serial}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (view.samples.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.keepCounterHistory) {
                    "The next successful live read starts this printer's history."
                } else {
                    "No saved history for this printer. Enable recording in Settings to start one."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        if (view.trends.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The reads are recorded, but this model has no decoded counter layout to trend.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        view.trends.forEachIndexed { index, trend ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            TrendRow(trend)
        }

        if (view.trends.any { it.resetObserved }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "A detected counter drop starts a new trend, so an earlier reset cannot turn the " +
                    "fill rate negative.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
    }
}

@Composable
private fun TrendRow(trend: CounterProjection.Trend) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                trend.spec.description + if (trend.spec.isUncertain) " (?)" else "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                buildString {
                    append(trend.latest?.toString() ?: "—")
                    trend.spec.max?.let { append(" / $it") }
                },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(3.dp))
        Text(
            movementLabel(trend),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            projectionLabel(trend),
            style = MaterialTheme.typography.labelSmall,
            color = if (trend.projectedAt != null) StatusColors.warn else StatusColors.muted,
        )

        if (trend.points.size >= 2) {
            Spacer(Modifier.height(8.dp))
            TrendSparkline(trend)
        }
    }
}

/** Small, dependency-free chart. Text above remains the accessible and exact representation. */
@Composable
private fun TrendSparkline(trend: CounterProjection.Trend) {
    val line = MaterialTheme.colorScheme.primary
    val reset = StatusColors.warn
    val guide = MaterialTheme.colorScheme.surfaceVariant

    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        drawLine(guide, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
        val points = sparklineGeometry(trend.points, trend.spec.max?.toLong(), size.width, size.height)
        for (index in 1 until points.size) {
            val current = points[index]
            val previous = points[index - 1]
            if (!current.startsSegment) {
                drawLine(line, Offset(previous.x, previous.y), Offset(current.x, current.y), strokeWidth = 2.5f)
            } else {
                drawCircle(reset, radius = 3.5f, center = Offset(current.x, current.y))
            }
        }
        points.lastOrNull()?.let { drawCircle(line, radius = 3.5f, center = Offset(it.x, it.y)) }
    }
}

internal data class SparklinePoint(val x: Float, val y: Float, val startsSegment: Boolean)

/** Pure geometry kept outside Canvas so equal timestamps/ranges and reset breaks can be pinned. */
internal fun sparklineGeometry(
    points: List<CounterProjection.TrendPoint>,
    maximum: Long?,
    width: Float,
    height: Float,
): List<SparklinePoint> {
    if (points.isEmpty()) return emptyList()
    val firstAt = points.first().at.toEpochMilli()
    val elapsed = (points.last().at.toEpochMilli() - firstAt).coerceAtLeast(0L)
    val observedMin = points.minOf { it.value }
    val observedMax = points.maxOf { it.value }
    val low = if (maximum != null && maximum > 0L) 0L else observedMin
    val high = if (maximum != null && maximum > 0L) maximum.coerceAtLeast(observedMax) else observedMax
    val range = high - low
    val drawableHeight = (height - 6f).coerceAtLeast(0f)

    return points.mapIndexed { index, point ->
        val xFraction = if (elapsed > 0L) {
            (point.at.toEpochMilli() - firstAt).toFloat() / elapsed.toFloat()
        } else if (points.size > 1) {
            index.toFloat() / (points.size - 1).toFloat()
        } else {
            0.5f
        }
        val yFraction = if (range > 0L) {
            ((point.value - low).toDouble() / range.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0.5f
        }
        SparklinePoint(
            x = xFraction.coerceIn(0f, 1f) * width,
            y = 3f + (1f - yFraction) * drawableHeight,
            startsSegment = point.startsSegment,
        )
    }
}

private fun movementLabel(trend: CounterProjection.Trend): String {
    if (trend.samplesUsed < 2) return "One usable sample — another live read establishes movement."
    val elapsed = trend.elapsedDays ?: return "The samples have no measurable time between them."
    if (elapsed < 1.0) return "${signed(trend.delta)} in less than a day — too early for a stable rate."
    val rate = trend.ratePerDay ?: return "No usable fill rate."
    val percentRate = trend.spec.max?.takeIf { it > 0 }?.let { rate / it * 100.0 }
    val relative = percentRate?.let { " · ${"%.3f".format(it)}% of max/day" }.orEmpty()
    return "${signed(trend.delta)} over ${"%.1f".format(elapsed)} days · ${"%.2f".format(rate)} per day$relative"
}

private fun projectionLabel(trend: CounterProjection.Trend): String {
    val maximum = trend.spec.max?.toLong()
    if (maximum != null && trend.latest != null && trend.latest >= maximum) {
        return "The counter is at or above its measured maximum."
    }
    trend.projectedAt?.let {
        return "At this rate, the measured maximum arrives around ${DATE.format(it)}."
    }
    return trend.projectionReason ?: "No projection yet."
}

private fun signed(value: Long?): String = value?.let { if (it > 0L) "+$it" else it.toString() } ?: "—"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
