package nl.rlabs.epsonreset.ui

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
import nl.rlabs.epsonreset.history.CounterProjection
import nl.rlabs.epsonreset.i18n.UiText
import nl.rlabs.epsonreset.i18n.counterName
import nl.rlabs.epsonreset.i18n.resolve
import nl.rlabs.epsonreset.i18n.resolveNow
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.history_live_samples
import nl.rlabs.epsonreset.resources.history_no_layout
import nl.rlabs.epsonreset.resources.history_no_rate
import nl.rlabs.epsonreset.resources.history_no_span
import nl.rlabs.epsonreset.resources.history_none_paused
import nl.rlabs.epsonreset.resources.history_none_recording
import nl.rlabs.epsonreset.resources.history_one_sample
import nl.rlabs.epsonreset.resources.history_paused
import nl.rlabs.epsonreset.resources.history_rate
import nl.rlabs.epsonreset.resources.history_rate_relative
import nl.rlabs.epsonreset.resources.history_recording
import nl.rlabs.epsonreset.resources.history_reset_note
import nl.rlabs.epsonreset.resources.history_summary
import nl.rlabs.epsonreset.resources.history_title
import nl.rlabs.epsonreset.resources.history_too_early
import nl.rlabs.epsonreset.resources.projection_arrives_around
import nl.rlabs.epsonreset.resources.projection_at_maximum
import nl.rlabs.epsonreset.resources.projection_none_yet
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
                stringResource(Res.string.history_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.keepCounterHistory) {
                    stringResource(Res.string.history_recording)
                } else {
                    stringResource(Res.string.history_paused)
                },
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
            stringResource(
                Res.string.history_summary,
                pluralStringResource(Res.plurals.history_live_samples, view.samples.size, view.samples.size),
                view.model,
                view.serial,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (view.samples.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.keepCounterHistory) {
                    stringResource(Res.string.history_none_recording)
                } else {
                    stringResource(Res.string.history_none_paused)
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        if (view.trends.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.history_no_layout),
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
                stringResource(Res.string.history_reset_note),
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
                counterName(trend.spec.description).resolve() + if (trend.spec.isUncertain) " (?)" else "",
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
            movementLabel(trend).resolve(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            projectionLabel(trend).resolve(),
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

private fun movementLabel(trend: CounterProjection.Trend): UiText {
    if (trend.samplesUsed < 2) return UiText.of(Res.string.history_one_sample)
    val elapsed = trend.elapsedDays ?: return UiText.of(Res.string.history_no_span)
    if (elapsed < 1.0) return UiText.of(Res.string.history_too_early, signed(trend.delta))
    val rate = trend.ratePerDay ?: return UiText.of(Res.string.history_no_rate)
    val percentRate = trend.spec.max?.takeIf { it > 0 }?.let { rate / it * 100.0 }
    val relative = percentRate
        ?.let { UiText.of(Res.string.history_rate_relative, "%.3f".format(it)).resolveNow() }
        .orEmpty()

    return UiText.of(
        Res.string.history_rate,
        signed(trend.delta),
        "%.1f".format(elapsed),
        "%.2f".format(rate),
        relative,
    )
}

private fun projectionLabel(trend: CounterProjection.Trend): UiText {
    val maximum = trend.spec.max?.toLong()
    if (maximum != null && trend.latest != null && trend.latest >= maximum) {
        return UiText.of(Res.string.projection_at_maximum)
    }
    trend.projectedAt?.let {
        return UiText.of(Res.string.projection_arrives_around, DATE.format(it))
    }
    return trend.projectionReason ?: UiText.of(Res.string.projection_none_yet)
}

private fun signed(value: Long?): String = value?.let { if (it > 0L) "+$it" else it.toString() } ?: "—"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
