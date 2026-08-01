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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PadKind
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status

internal data class OverviewCounterRow(
    val description: String,
    val current: String,
    val value: Long?,
    val maximum: Long?,
    val percent: Double?,
    val detail: String,
    val uncertain: Boolean,
)

internal enum class OverviewCounterLevel { LOW, REACHING, MAXED }

internal fun overviewCounterLevel(percent: Double): OverviewCounterLevel = when {
    percent >= 100.0 -> OverviewCounterLevel.MAXED
    percent >= 90.0 -> OverviewCounterLevel.REACHING
    else -> OverviewCounterLevel.LOW
}

internal fun overviewCounterCoverageLabel(report: CounterReader.Report): String? =
    if (report.answered < report.total) "${report.answered}/${report.total} addresses reported" else null

/** Whether the percentage/value summary has at least one decoded, reported counter to show. */
internal fun overviewCounterSummaryAvailable(report: CounterReader.Report?, specs: List<CounterSpec>): Boolean =
    report?.takeIf { it.answered > 0 }?.let { current ->
        specs.isNotEmpty() && CounterReader.decode(current.readings, specs).any { counter ->
            counter.bytes.any { it != null }
        }
    } == true

/** Current counter status only: no reset destinations and no model-only placeholder values. */
internal fun overviewCounterRows(report: CounterReader.Report, specs: List<CounterSpec>): List<OverviewCounterRow> {
    val decoded = CounterReader.decode(report.readings, specs)
    val rows = decoded.mapNotNull { counter ->
        if (counter.bytes.none { it != null }) return@mapNotNull null
        val maximum = counter.spec.max?.takeIf { it > 0 }?.toLong()
        val current = counter.value?.let { "%,d".format(it) } ?: counter.hexBytes
        OverviewCounterRow(
            description = counter.spec.description.removeSuffix(" (?)"),
            current = current,
            value = counter.value,
            maximum = maximum,
            percent = counter.percent,
            detail = when {
                counter.value == null -> "Current bytes; the complete counter value was not available."
                maximum != null -> "%.1f%% of measured maximum".format(counter.percent)
                else -> "Maximum not measured"
            },
            uncertain = counter.spec.isUncertain,
        )
    }.toMutableList()

    val describedAddresses = specs.flatMapTo(mutableSetOf()) { it.addresses }
    val other = report.readings.filter { it.value != null && it.address !in describedAddresses }
    if (other.isNotEmpty()) {
        rows += OverviewCounterRow(
            description = "Other counter bytes",
            current = other.joinToString("  ") { "${it.address}=%02X".format(it.value) },
            value = null,
            maximum = null,
            percent = null,
            detail = "Reported addresses without a decoded counter layout.",
            uncertain = true,
        )
    }
    return rows
}

/** Read-only Overview presentation. Reset targets remain exclusive to reset/snapshot tables. */
@Composable
fun OverviewCountersCard(
    report: CounterReader.Report,
    specs: List<CounterSpec>,
    onCalibrate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rows = overviewCounterRows(report, specs)
    if (report.answered == 0 || rows.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Counters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            overviewCounterCoverageLabel(report)?.let { coverage ->
                Text(coverage, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
            }
            if (onCalibrate != null && rows.any { it.value != null && it.maximum == null }) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Measure a maximum…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onCalibrate).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        rows.forEachIndexed { index, row ->
            Spacer(Modifier.height(if (index == 0) 12.dp else 8.dp))
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            OverviewCounterStatusRow(row)
        }
    }
}

@Composable
private fun OverviewCounterStatusRow(row: OverviewCounterRow) {
    val percent = row.percent
    val level = percent?.let(::overviewCounterLevel)
    val tone = when (level) {
        OverviewCounterLevel.MAXED -> StatusColors.bad
        OverviewCounterLevel.REACHING -> StatusColors.warn
        OverviewCounterLevel.LOW -> StatusColors.good
        null -> MaterialTheme.colorScheme.primary
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(190.dp)) {
            Text(
                row.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.uncertain) StatusColors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (percent == null) {
                Text(row.detail, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted, maxLines = 1)
            }
        }

        if (percent != null) {
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tone),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "%.1f%%".format(percent),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
                modifier = Modifier.width(76.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        Text(
            if (row.maximum != null && row.value != null) {
                "${row.current} / %,d".format(row.maximum)
            } else {
                row.current
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(150.dp),
        )
    }
}

/** Decoded counters and the EEPROM bytes behind them, kept together so neither view repeats the other. */
@Composable
fun CounterOverview(
    counters: List<CounterReader.DecodedCounter>,
    report: CounterReader.Report,
    before: CounterReader.Report? = null,
    /** The write this table is drawing, if any: where each byte is headed and how far it has got. */
    plan: WritePlan = WritePlan.None,
    simulated: Boolean = false,
    modifier: Modifier = Modifier,
    /** Whether to echo [report].error inline. Off where the status bar already reports it, so a live
     *  read failure isn't said twice. */
    showError: Boolean = true,
    /**
     * Opens the contribution form. Null where there is nothing to contribute from — a snapshot read
     * back off disk shows counters too, and none of them were measured against a printer.
     */
    onCalibrate: (() -> Unit)? = null,
) {
    val byteStates = plan.states
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
            val coverageLabel = when {
                simulated -> "Simulated values"
                layoutOnly -> "Current values not read"
                report.answered < report.total -> "${report.answered}/${report.total} answered"
                else -> null
            }
            coverageLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (report.answered < report.total && !layoutOnly) {
                        StatusColors.warn
                    } else {
                        StatusColors.muted
                    },
                )
            }
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

        report.error?.takeIf { showError }?.let {
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
        ByteLegend(showBefore, plan.targetLabel)
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
    // The box itself is neutral for every kind; only the M/P/? marker carries the kind's colour.
    val boxTone = StatusColors.muted
    val kindTone = when (cell.kind) {
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

    // The two bytes on the chip differ. In a before/after pair that means something moved; in a
    // plan it means this is an address the write would actually change. Either way it is the one
    // thing worth picking out, and an address already holding the target is worth not picking out.
    val changed = if (showBefore) {
        cell.previous != null && shownCurrent != null && cell.previous != shownCurrent
    } else {
        cell.reset != null && shownCurrent != null && shownCurrent != cell.reset
    }
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
            .background(
                if (showBefore && changed) {
                    StatusColors.changed.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
            )
            .border(1.dp, boxTone.copy(alpha = 0.75f), RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(boxTone.copy(alpha = 0.18f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                cell.address.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = boxTone,
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
                color = kindTone,
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
                        showBefore && changed -> StatusColors.changed
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
                        showBefore && changed -> StatusColors.changed
                        showBefore -> currentTone
                        // Red says "this byte is not what is there now", which is the whole point
                        // of showing it. An address already at its target says nothing loudly.
                        changed -> StatusColors.bad
                        else -> StatusColors.muted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private fun byteHex(value: Int?): String = value?.let { "%02X".format(it) } ?: "--"

@Composable
private fun ByteLegend(showBefore: Boolean, targetLabel: String) {
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
                    "  $targetLabel, red where it differs from the byte on the left",
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
 * Ink and standard-MIB status in one card, split down the middle: on the left the printer's own ink
 * block — exact, and each bar coloured by its ink — and on the right the RFC 3805 extras that block
 * cannot give, chiefly the lifetime page count and any waste receptacle. Either side stands alone
 * (full width, no divider) when the other has no data; the card hides entirely when neither does.
 */
@Composable
fun SuppliesCard(status: Status.Report?, mib: PrinterMib.Reading?, modifier: Modifier = Modifier) {
    val levels = status?.inkLevels.orEmpty()
    val inkShown = levels.isNotEmpty()

    // The ink block already lists the colour inks, so the right side drops those and keeps only what
    // the block cannot show. With no ink block, the full supplies list is the only place levels appear.
    val supplies = mib?.supplies.orEmpty().let {
        if (inkShown) it.filterNot(PrinterMib.Supply::isInkConsumable) else it
    }
    val statusShown = mib != null && (mib.lifeCount != null || supplies.isNotEmpty())

    if (!inkShown && !statusShown) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (inkShown) InkColumn(levels, status?.serial, Modifier.weight(1f))
            if (inkShown && statusShown) {
                VerticalDivider(
                    Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (statusShown) StatusColumn(mib?.lifeCount, supplies, Modifier.weight(1f))
        }
    }
}

/** The colours side: exact ink levels from the ST2 block, each bar tinted by its ink. */
@Composable
private fun InkColumn(levels: List<Status.InkLevel>, serial: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ink", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            serial?.let {
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
                    modifier = Modifier.width(96.dp),
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
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

/** The extras side: the standard-MIB figures the ink block can't give — lifetime pages, waste. */
@Composable
private fun StatusColumn(lifeCount: Long?, supplies: List<PrinterMib.Supply>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Printer status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("standard MIB", style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
        }

        lifeCount?.let { pages ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Lifetime pages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%,d".format(pages),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (supplies.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(6.dp))
            for (supply in supplies) SupplyRow(supply)
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

@Composable
private fun SupplyRow(supply: PrinterMib.Supply) {
    val name = supply.description.ifBlank {
        supply.colour ?: supply.typeLabel ?: "Supply ${supply.index}"
    }

    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(140.dp),
        )

        val percent = supply.percent
        if (percent != null) {
            val tone = supplyTone(supply)
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(percent / 100f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tone),
                )
            }
            Text(
                // A receptacle's level is how full it is; a consumable's, how much is left.
                if (supply.isWaste) "%3d%% full".format(percent) else "%3d%%".format(percent),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (supply.isWarn) FontWeight.Bold else FontWeight.Normal,
                color = if (supply.isWarn) StatusColors.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(84.dp),
            )
        } else {
            Text(
                supply.levelNote ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = StatusColors.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Consumables run down (warn near empty); receptacles fill up (warn near full). Ink is tinted by
 *  its colour — the fallback view where these are the only levels shown reads far better for it. */
@Composable
private fun supplyTone(supply: PrinterMib.Supply): Color = when {
    supply.isWarn -> StatusColors.bad
    supply.isWaste -> StatusColors.warn
    else -> supplyInkColour(supply.colour ?: supply.description) ?: MaterialTheme.colorScheme.primary
}

/** Matches a supply's colour or description against the ink palette, case-insensitively. */
@Composable
private fun supplyInkColour(name: String): Color? = when {
    name.contains("black", ignoreCase = true) ->
        if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFCFD8DC) else Color(0xFF37474F)
    name.contains("cyan", ignoreCase = true) -> Color(0xFF00ACC1)
    name.contains("magenta", ignoreCase = true) -> Color(0xFFD81B60)
    name.contains("yellow", ignoreCase = true) -> Color(0xFFF9A825)
    else -> null
}
