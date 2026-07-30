package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.CapabilitySummary
import nl.redlabs.epsonreset.db.ModelCapability
import nl.redlabs.epsonreset.db.ResetScope
import nl.redlabs.epsonreset.db.ValueSupport

/** Every model in the database against what this app can do with it. */
@Composable
fun CapabilityMatrix(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val results = vm.matrixResults

    Column(modifier.padding(20.dp)) {
        vm.capabilitySummary?.let { SummaryHeader(it) }

        Spacer(Modifier.height(14.dp))
        Filters(vm, shown = results.size)
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        ) {
            HeaderRow()
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.capabilities.isEmpty()) "Loading the database…" else "No models match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.muted,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.name }) { capability ->
                        CapabilityRow(
                            capability = capability,
                            selected = capability.name == vm.selectedModel?.name,
                            onClick = { vm.selectModelAndShowCounters(capability) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Footnote(vm.capabilitySummary)
    }
}

@Composable
private fun SummaryHeader(summary: CapabilitySummary) {
    Column {
        Text(
            "Model capabilities",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${summary.resettable} of ${summary.total} models can be reset · " +
                "${summary.decoded} decode to real values, ${summary.uncertain} partly · " +
                "${summary.platenOnly} are platen-only · ${summary.withLimit} know a limit",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun Filters(vm: ResetViewModel, shown: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = vm.matrixQuery,
            onValueChange = { vm.matrixQuery = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier.width(280.dp),
        )

        Spacer(Modifier.width(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (filter in ResetViewModel.MatrixFilter.entries) {
                FilterChip(
                    label = filter.label,
                    active = vm.matrixFilter == filter,
                    onClick = { vm.matrixFilter = filter },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "$shown shown",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// Column geometry lives here so the header and the rows can't drift apart.
private val RESET_W = 84.dp
private val READ_W = 84.dp
private val VALUES_W = 116.dp
private val LIMIT_W = 84.dp
private val SCOPE_W = 132.dp
private val WRITES_W = 84.dp

@Composable
private fun HeaderRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Model", Modifier.weight(1f), null)
        HeaderCell(
            "Reset",
            Modifier.width(RESET_W),
            "The database has write keys and EEPROM addresses for this model, so the waste " +
                "counters can be zeroed.",
        )
        HeaderCell(
            "Read",
            Modifier.width(READ_W),
            "The counters can be sampled. The read command carries no write key, so this is safe " +
                "even if the model were mismatched.",
        )
        HeaderCell(
            "Values",
            Modifier.width(VALUES_W),
            "Whether a reading can be shown as a number. Needs a layout saying which addresses " +
                "group into one little-endian counter.",
        )
        HeaderCell(
            "Limit",
            Modifier.width(LIMIT_W),
            "A maximum is known, so the counter can be shown as a percentage. The bundled data has " +
                "one for almost no model; measured ones live in calibrations.json.",
        )
        HeaderCell(
            "Reset scope",
            Modifier.width(SCOPE_W),
            "Whether a reset clears the main waste box counter too, or only the platen pad.",
        )
        HeaderCell("Writes", Modifier.width(WRITES_W), "EEPROM writes a reset performs.")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderCell(label: String, modifier: Modifier, tooltip: String?) {
    val text = @Composable {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }

    if (tooltip == null) {
        text()
        return
    }

    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
            ) {
                Text(
                    tooltip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 320.dp).padding(10.dp),
                )
            }
        },
        content = text,
    )
}

@Composable
private fun CapabilityRow(capability: ModelCapability, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            capability.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )

        Mark(capability.canReset, Modifier.width(RESET_W))
        Mark(capability.canRead, Modifier.width(READ_W))

        val (valueLabel, valueTone) = when (capability.values) {
            ValueSupport.DECODED -> "decoded" to StatusColors.good
            ValueSupport.UNCERTAIN -> "partly" to StatusColors.warn
            ValueSupport.RAW -> "bytes only" to StatusColors.muted
        }
        Label(valueLabel, valueTone, Modifier.width(VALUES_W))

        Mark(capability.hasLimit, Modifier.width(LIMIT_W))

        val (scopeLabel, scopeTone) = when (capability.scope) {
            ResetScope.FULL -> "platen + main" to MaterialTheme.colorScheme.onSurfaceVariant
            ResetScope.PLATEN_ONLY -> "platen only" to StatusColors.warn
            ResetScope.NONE -> "—" to StatusColors.muted
        }
        Label(scopeLabel, scopeTone, Modifier.width(SCOPE_W))

        Text(
            if (capability.writeCount > 0) "${capability.writeCount}" else "—",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = StatusColors.muted,
            modifier = Modifier.width(WRITES_W),
        )
    }
}

/** A capability tick. Absence is muted rather than red: most of these are gaps, not faults. */
@Composable
private fun Mark(present: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (present) "✓" else "✕",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = if (present) StatusColors.good else StatusColors.muted,
        modifier = modifier,
    )
}

@Composable
private fun Label(text: String, tone: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        modifier = modifier,
    )
}

@Composable
private fun Footnote(summary: CapabilitySummary?) {
    Text(
        buildString {
            append("Values: “decoded” means every counter is a certain group of at most 4 bytes; ")
            append("“partly” means part of the layout is marked a guess, or a group is too ")
            append("wide to be one number, so those show as bytes. Platen-only models keep the ")
            append("main waste box counter on a chip — resetting them does not empty the box.")
            if (summary != null && summary.layoutOnly > 0) {
                append(" ${summary.layoutOnly} further models have counter layouts but no entry ")
                append("in the reset database, so they can't be selected here.")
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.muted,
    )
}
