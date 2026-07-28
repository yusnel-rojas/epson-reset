package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.backup.SnapshotComparison
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status

/** The decoded view: each counter as a single number, assembled from its grouped addresses. */
@Composable
fun DecodedCounters(
    counters: List<CounterReader.DecodedCounter>,
    modifier: Modifier = Modifier,
    /**
     * Opens the contribution form. Null where there is nothing to contribute from — a snapshot read
     * back off disk shows counters too, and none of them were measured against a printer.
     */
    onCalibrate: (() -> Unit)? = null,
) {
    if (counters.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Counters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            onCalibrate?.let {
                // The one action a "no limit" invites. It lives up here rather than as a panel
                // below, because measuring a maximum is a once-ever errand and the counters are
                // what the tab is for.
                TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(
                        if (counters.any { c -> c.percent == null }) {
                            "Measure a maximum…"
                        } else {
                            "Check the maximums…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        for (c in counters) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.display,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (c.value == null) StatusColors.bad else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(90.dp),
                )

                Column(Modifier.weight(1f)) {
                    Text(
                        c.spec.description + if (c.spec.isUncertain) "  (layout uncertain)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.spec.isUncertain) {
                            StatusColors.warn
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "addr ${c.spec.addresses.joinToString(",")}  ·  bytes ${c.hexBytes}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = StatusColors.muted,
                    )
                }

                c.percent?.let { pct ->
                    Text(
                        // Two decimals so the figure lines up with what other tools report,
                        // rather than looking like a near-miss.
                        "%.2f%%".format(pct),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            pct >= 100 -> StatusColors.bad
                            pct >= 90 -> StatusColors.warn
                            else -> StatusColors.good
                        },
                    )
                } ?: Text(
                    "no limit",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Multi-byte counters are little-endian. A percentage appears only where the layout " +
                "data declares a maximum.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

/** The same counters, across two samples: what each one was, what it is, and how far it moved. */
@Composable
fun CounterDeltas(deltas: List<SnapshotComparison.CounterDelta>, modifier: Modifier = Modifier) {
    if (deltas.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Counters, before and after",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${deltas.count { it.moved }} of ${deltas.size} moved",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        Spacer(Modifier.height(10.dp))

        for (d in deltas) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.deltaLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (d.moved) FontWeight.SemiBold else FontWeight.Normal,
                    color = deltaColour(d),
                    modifier = Modifier.width(90.dp),
                )

                Column(Modifier.weight(1f)) {
                    Text(
                        d.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.spec.isUncertain) {
                            StatusColors.warn
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "addr ${d.spec.addresses.joinToString(",")}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = StatusColors.muted,
                    )
                }

                Text(
                    d.display,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (d.moved) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        StatusColors.muted
                    },
                    modifier = Modifier.width(180.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "A counter that went up was used between the two samples; one that went down was reset. " +
                "A counter shown as unmoved is one whose bytes are identical on both sides.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

/** Down is good, up is ordinary. */
@Composable
private fun deltaColour(d: SnapshotComparison.CounterDelta): Color {
    val delta = d.delta
    return when {
        !d.moved -> StatusColors.muted
        delta == null -> StatusColors.warn
        delta < 0 -> StatusColors.good
        else -> MaterialTheme.colorScheme.primary
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

/** Shows what the EEPROM actually holds, address by address. */
@Composable
fun CounterTable(report: CounterReader.Report, before: CounterReader.Report?, modifier: Modifier = Modifier) {
    val previous = before?.readings?.associate { it.address to it.value } ?: emptyMap()
    val showBefore = previous.isNotEmpty()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Counter values",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${report.answered}/${report.total} answered",
                style = MaterialTheme.typography.labelSmall,
                color = if (report.answered == report.total) StatusColors.good else StatusColors.warn,
            )
        }

        report.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad)
        }

        Spacer(Modifier.height(10.dp))

        Row {
            HeaderCell("Address", 80.dp)
            if (showBefore) HeaderCell("Before", 70.dp)
            HeaderCell(if (showBefore) "After" else "Value", 70.dp)
            HeaderCell("Reset to", 70.dp)
            HeaderCell("Group", 160.dp)
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            items(report.readings) { r ->
                val old = previous[r.address]
                val changed = showBefore && old != null && r.value != null && old != r.value

                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cell("%d".format(r.address), 80.dp, StatusColors.muted)

                    if (showBefore) {
                        Cell(old?.let { "0x%02X".format(it) } ?: "—", 70.dp, StatusColors.muted)
                    }

                    Cell(
                        r.hex,
                        70.dp,
                        when {
                            r.value == null -> StatusColors.bad
                            r.isAtResetValue -> StatusColors.good
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        bold = changed,
                    )

                    Cell("0x%02X".format(r.expectedAfterReset), 70.dp, StatusColors.muted)

                    Text(
                        r.error ?: r.groupDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (r.error != null) StatusColors.bad else StatusColors.muted,
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Raw EEPROM bytes. This database carries no counter maximums, so these are not " +
                "percentages — the useful comparison is against the reset column.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = StatusColors.muted,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, color: Color, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        modifier = Modifier.width(width),
    )
}
