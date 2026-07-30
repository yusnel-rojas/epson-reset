package nl.redlabs.epsonreset.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PadKind
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status

/** Decoded counters and the EEPROM bytes behind them, kept together so neither view repeats the other. */
@Composable
fun CounterOverview(
    counters: List<CounterReader.DecodedCounter>,
    report: CounterReader.Report,
    before: CounterReader.Report? = null,
    byteStates: Map<Int, ResetViewModel.CounterByteState> = emptyMap(),
    simulated: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * Opens the contribution form. Null where there is nothing to contribute from — a snapshot read
     * back off disk shows counters too, and none of them were measured against a printer.
     */
    onCalibrate: (() -> Unit)? = null,
) {
    val readings = report.readings.associateBy { it.address }
    val previous = before?.readings?.associate { it.address to it.value }.orEmpty()
    val showBefore = previous.isNotEmpty()
    val previousCounters = before?.let { sample ->
        CounterReader.decode(sample.readings, counters.map { it.spec })
    }.orEmpty()
    val decodedAddresses = counters.flatMapTo(mutableSetOf()) { it.spec.addresses }
    val otherBytes = report.readings.filter { it.address !in decodedAddresses }
    val layoutOnly = report.readings.isNotEmpty() &&
        report.readings.all { it.value == null && it.error == null } &&
        report.error == null

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Counters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    simulated -> "Simulated values"
                    layoutOnly -> "Current values not read"
                    else -> "${report.answered}/${report.total} answered"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    simulated -> StatusColors.muted
                    layoutOnly -> StatusColors.muted
                    report.answered == report.total -> StatusColors.good
                    else -> StatusColors.warn
                },
            )
            onCalibrate?.takeIf { counters.isNotEmpty() }?.let {
                // The one action a "no limit" invites. It lives up here rather than as a panel
                // below, because measuring a maximum is a once-ever errand and the counters are
                // what the tab is for.
                Spacer(Modifier.width(8.dp))
                Text(
                    if (counters.any { c -> c.percent == null }) {
                        "Measure a maximum…"
                    } else {
                        "Check the maximums…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = it).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        report.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad)
        }
        Spacer(Modifier.height(10.dp))

        counters.forEachIndexed { index, counter ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            CounterByteRow(
                counter = counter,
                cells = counter.spec.addresses.mapIndexed { byteIndex, address ->
                    val reading = readings[address]
                    ByteCell(
                        address = address,
                        current = reading?.value,
                        reset = reading?.expectedAfterReset ?: counter.spec.resetValues.getOrNull(byteIndex),
                        previous = previous[address],
                        kind = reading.kind(),
                        error = reading?.error,
                        state = byteStates[address],
                    )
                },
                showBefore = showBefore,
                previousValue = previousCounters.getOrNull(index)?.value,
            )
        }

        if (otherBytes.isNotEmpty()) {
            if (counters.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(
                    if (counters.isEmpty()) "EEPROM bytes" else "Other EEPROM bytes",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ByteStrip(
                    cells = otherBytes.map { reading ->
                        ByteCell(
                            address = reading.address,
                            current = reading.value,
                            reset = reading.expectedAfterReset,
                            previous = previous[reading.address],
                            kind = reading.kind(),
                            error = reading.error,
                            state = byteStates[reading.address],
                        )
                    },
                    showBefore = showBefore,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        ByteLegend(showBefore)
        Spacer(Modifier.height(6.dp))
        Text(
            "Multi-byte counters are little-endian. A percentage appears only where the layout " +
                "data declares a maximum.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

private data class ByteCell(
    val address: Int,
    val current: Int?,
    val reset: Int?,
    val previous: Int?,
    val kind: PadKind,
    val error: String?,
    val state: ResetViewModel.CounterByteState?,
)

private data class ByteTransition(val left: String, val right: String)

private fun CounterReader.Reading?.kind(): PadKind =
    this?.let { PadGroup.kindFromDescription(it.groupDescription) } ?: PadKind.UNKNOWN

@Composable
private fun CounterByteRow(
    counter: CounterReader.DecodedCounter,
    cells: List<ByteCell>,
    showBefore: Boolean,
    previousValue: Long?,
) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                counter.spec.description + if (counter.spec.isUncertain) "  (layout uncertain)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (counter.spec.isUncertain) StatusColors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            if (counter.value == null) {
                Text(
                    if (cells.all { it.current == null && it.error == null }) "?" else counter.display,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (cells.all { it.current == null && it.error == null }) {
                        StatusColors.muted
                    } else {
                        StatusColors.bad
                    },
                )
            } else {
                Text(
                    if (showBefore && previousValue != null) {
                        "$previousValue→${counter.value}"
                    } else {
                        counter.value.toString()
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                counter.percent?.let { pct ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "%.2f%%".format(pct),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            pct >= 100 -> StatusColors.bad
                            pct >= 90 -> StatusColors.warn
                            else -> StatusColors.good
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(1.dp))
        ByteStrip(cells, showBefore)
    }
}

@Composable
private fun ByteStrip(cells: List<ByteCell>, showBefore: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            ByteChip(cell, showBefore)
        }
    }
}

@Composable
private fun ByteChip(cell: ByteCell, showBefore: Boolean) {
    val tone = when (cell.kind) {
        PadKind.MAIN -> MaterialTheme.colorScheme.primary
        PadKind.PLATEN -> StatusColors.warn
        PadKind.UNKNOWN -> StatusColors.muted
    }
    val marker = when (cell.kind) {
        PadKind.MAIN -> "M"
        PadKind.PLATEN -> "P"
        PadKind.UNKNOWN -> "?"
    }
    val shownCurrent = when (cell.state) {
        ResetViewModel.CounterByteState.ACKNOWLEDGED,
        ResetViewModel.CounterByteState.VERIFIED,
        -> cell.reset
        else -> cell.current
    }
    val current = shownCurrent?.let { byteHex(it) } ?: "?"
    val reset = byteHex(cell.reset)
    val transition = if (showBefore) ByteTransition(byteHex(cell.previous), current) else ByteTransition(current, reset)
    val changed = showBefore && cell.previous != null && shownCurrent != null && cell.previous != shownCurrent
    val stateTone = when (cell.state) {
        ResetViewModel.CounterByteState.READING,
        ResetViewModel.CounterByteState.WRITING,
        ResetViewModel.CounterByteState.ACKNOWLEDGED,
        -> StatusColors.warn
        ResetViewModel.CounterByteState.READ,
        ResetViewModel.CounterByteState.VERIFIED,
        -> StatusColors.good
        ResetViewModel.CounterByteState.FAILED -> StatusColors.bad
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedStateTone by animateColorAsState(stateTone, tween(220), label = "counter byte state")
    val currentTone = when {
        cell.error != null -> StatusColors.bad
        cell.current == null && cell.state == null -> StatusColors.muted
        else -> animatedStateTone
    }

    Column(
        Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (changed) StatusColors.changed.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, tone.copy(alpha = 0.75f), RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(tone.copy(alpha = 0.18f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                cell.address.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = tone,
            )
            Spacer(Modifier.weight(1f))
            val stateMarker = when (cell.state) {
                ResetViewModel.CounterByteState.READING -> "●"
                ResetViewModel.CounterByteState.READ -> "✓"
                ResetViewModel.CounterByteState.WRITING -> "●"
                ResetViewModel.CounterByteState.ACKNOWLEDGED -> "…"
                ResetViewModel.CounterByteState.VERIFIED -> "✓"
                ResetViewModel.CounterByteState.FAILED -> "✕"
                else -> null
            }
            if (stateMarker != null) {
                Text(stateMarker, style = MaterialTheme.typography.labelSmall, color = animatedStateTone)
                Spacer(Modifier.width(2.dp))
            }
            Text(
                marker,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = tone,
            )
        }
        AnimatedContent(
            targetState = transition,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 2 })
            },
            contentAlignment = Alignment.Center,
            label = "counter byte value",
            modifier = Modifier.fillMaxWidth(),
        ) { value ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    value.left,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        changed -> StatusColors.changed
                        showBefore -> StatusColors.muted
                        else -> currentTone
                    },
                    maxLines = 1,
                )
                Text(
                    " → ",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                    maxLines = 1,
                )
                Text(
                    value.right,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        changed -> StatusColors.changed
                        showBefore -> currentTone
                        else -> StatusColors.bad
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private fun byteHex(value: Int?): String = value?.let { "%02X".format(it) } ?: "--"

@Composable
private fun ByteLegend(showBefore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendItem("M", "Main", MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        LegendItem("P", "Platen", StatusColors.warn)
        Spacer(Modifier.width(12.dp))
        LegendItem("?", "Unclassified", StatusColors.muted)
        Spacer(Modifier.weight(1f))
        if (showBefore) {
            Row {
                Text(
                    "7F → 00  before → current  ·  ",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
                Text(
                    "changed values highlighted",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusColors.changed,
                )
            }
        } else {
            Row {
                Text(
                    "7F → ",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
                Text(
                    "00",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusColors.bad,
                )
                Text(
                    "  reset target",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(marker: String, label: String, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tone.copy(alpha = 0.18f))
                .border(1.dp, tone.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(marker, style = MaterialTheme.typography.labelSmall, color = tone, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
    }
}

/**
 * Ink levels straight from the printer's own status block — no database or calibration involved, so
 * these are exact wherever the firmware reports them.
 */
@Composable
fun InkLevels(status: Status.Report?, modifier: Modifier = Modifier) {
    val levels = status?.inkLevels.orEmpty()
    if (levels.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ink", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            status?.serial?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        for (ink in levels) {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ink.colour,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp),
                )

                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ink.percent.coerceIn(0, 100) / 100f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(inkColour(ink.colour, ink.isLow)),
                    )
                }

                Text(
                    "%3d%%".format(ink.percent),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (ink.isLow) FontWeight.Bold else FontWeight.Normal,
                    color = if (ink.isLow) StatusColors.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

/** Low levels go red regardless of the ink's own colour, so the warning isn't lost in yellow. */
@Composable
private fun inkColour(name: String, isLow: Boolean): Color = when {
    isLow -> StatusColors.bad
    name == "Black" -> if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFCFD8DC) else Color(0xFF37474F)
    name.contains("Cyan") -> Color(0xFF00ACC1)
    name.contains("Magenta") -> Color(0xFFD81B60)
    name.contains("Yellow") -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.primary
}
