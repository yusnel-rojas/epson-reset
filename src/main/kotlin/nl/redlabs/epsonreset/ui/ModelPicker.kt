package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.PrinterModel

/** Model half of the app-wide target menu. */
@Composable
fun ModelPicker(
    vm: ResetViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onModelSelected: () -> Unit = {},
) {
    Column(modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Printer model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }

        // Only reachable over the top of a printer that named itself, and that is worth saying
        // where the choosing happens rather than only at the moment the run is refused.
        vm.identifiedModel?.let { identified ->
            Spacer(Modifier.height(10.dp))
            OverrideWarning(vm, identified)
        }

        val scopedCandidates = vm.scopedModelCandidates
        if (scopedCandidates.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ScopedModelChoice(
                vm = vm,
                reported = vm.pendingClass?.reported ?: vm.confirmedClass,
                candidates = scopedCandidates,
                onModelSelected = onModelSelected,
            )
            return@Column
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            label = { Text("Search ${vm.database?.size ?: 0} models") },
            singleLine = true,
            enabled = vm.canChangeTarget,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        val results = vm.searchResults
        Text(
            if (results.isEmpty()) {
                "no matches"
            } else {
                "${results.size} match${if (results.size == 1) "" else "es"}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )

        Spacer(Modifier.height(6.dp))

        // DropdownMenu asks its contents for intrinsic width. LazyColumn is built on
        // SubcomposeLayout, which deliberately cannot answer intrinsic measurements and crashes
        // as soon as printer selection recomposes this menu. The menu already owns bounded
        // scrolling, and database search caps this list at 200, so a plain column belongs here.
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            results.forEach { model ->
                ModelRow(model, model.name == vm.selectedModel?.name, vm.canChangeTarget) {
                    vm.selectModel(model)
                    onModelSelected()
                }
            }
        }
    }
}

/**
 * The printer named its family and the family disagrees with itself. Only this shortlist is shown:
 * "one of these eight" is a question a person can answer off the label on the printer, while "one
 * of 1588" is not.
 */
@Composable
private fun ScopedModelChoice(
    vm: ResetViewModel,
    reported: String?,
    candidates: List<PrinterModel>,
    onModelSelected: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(StatusColors.warn.copy(alpha = 0.10f))
            .border(1.dp, StatusColors.warn.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            reported?.let { "This printer reports \"$it\" — choose the model on its label." }
                ?: "Choose the model printed on this printer.",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = StatusColors.warn,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Only the ${candidates.size} model${if (candidates.size == 1) "" else "s"} in the " +
                "identified series ${if (candidates.size == 1) "is" else "are"} shown. " +
                "The choice is remembered.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        candidates.forEach { model ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = vm.canChangeTarget) {
                        vm.selectModel(model)
                        onModelSelected()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "rkey ${model.readKey} · ${model.writeCount} writes",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }
    }
}

/** What the printer says it is, with the way back to it. */
@Composable
private fun OverrideWarning(vm: ResetViewModel, identified: PrinterModel) {
    val mismatch = vm.modelMismatch
    val tone = if (mismatch == null) StatusColors.muted else StatusColors.warn

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            mismatch
                // Same rule as the collapsed card: a name the user supplied is not the printer's word.
                ?: vm.confirmedClass?.let {
                    "You confirmed this printer as ${identified.name}. It reports only \"$it\"."
                }
                ?: "This printer reports itself as ${identified.name}.",
            style = MaterialTheme.typography.labelSmall,
            color = if (mismatch == null) MaterialTheme.colorScheme.onSurfaceVariant else tone,
        )
        Spacer(Modifier.height(2.dp))
        TextButton(
            onClick = { vm.useIdentifiedModel() },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                if (mismatch == null) "Done" else "Back to ${identified.name}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ModelRow(model: PrinterModel, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            model.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${model.writeCount}",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}
