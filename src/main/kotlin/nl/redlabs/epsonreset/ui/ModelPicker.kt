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
fun ModelPicker(vm: ResetViewModel, modifier: Modifier = Modifier, onModelSelected: () -> Unit = {}) {
    Column(modifier.padding(vertical = 12.dp)) {
        Text(
            "Printer model",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // Only reachable over the top of a printer that named itself, and that is worth saying
        // where the choosing happens rather than only at the moment the run is refused.
        vm.identifiedModel?.let { identified ->
            Spacer(Modifier.height(10.dp))
            OverrideWarning(vm, identified)
        }

        val scopedChoice = vm.pendingClass ?: vm.confirmedClass?.takeIf { vm.manualModelRequested }?.let { reported ->
            ResetViewModel.PendingClass(reported, vm.scopedModelCandidates)
        }
        scopedChoice?.let { pending ->
            Spacer(Modifier.height(10.dp))
            ClassChoice(vm, pending, onModelSelected)
            return@Column
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            label = { Text("Search ${vm.database?.size ?: 0} models") },
            singleLine = true,
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
                ModelRow(model, model.name == vm.selectedModel?.name) {
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
private fun ClassChoice(vm: ResetViewModel, pending: ResetViewModel.PendingClass, onModelSelected: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(StatusColors.warn.copy(alpha = 0.10f))
            .border(1.dp, StatusColors.warn.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            "This printer reports \"${pending.reported}\" — a family, not a model.",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = StatusColors.warn,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Its ${pending.candidates.size} members do not share a reset recipe, so the right one " +
                "has to come off the label on the printer. The choice is remembered.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        pending.candidates.forEach { model ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
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

/**
 * The collapsed form of the picker: the one model that is staged, and no way to mis-click another.
 */
@Composable
fun SelectedModelCard(vm: ResetViewModel, modifier: Modifier = Modifier) {
    // Only reached with the two in agreement — `modelPickerExpanded` opens the list otherwise.
    val model = vm.identifiedModel ?: return

    Column(modifier.padding(vertical = 12.dp)) {
        Text(
            "Printer model",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            // The card asserts where the name came from, so a name the user supplied must not be
            // dressed up as one the printer gave.
            Text(
                vm.confirmedClass?.let { "✓ confirmed by you — the printer says only \"$it\"" }
                    ?: "✓ named by the printer",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.good,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "rkey ${model.readKey} · ${model.writeCount} writes",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusColors.muted,
            )
        }

        vm.confirmedClass?.let {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { vm.manualModelRequested = true }) {
                Text("Choose another in this series…", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { vm.forgetModelChoice() }) {
                Text("Forget this choice", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ModelRow(model: PrinterModel, selected: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
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
