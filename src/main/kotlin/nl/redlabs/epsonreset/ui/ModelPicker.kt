package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Sidebar half two: pick the model. Device selection sits above it. */
@Composable
fun ModelPicker(vm: ResetViewModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(results, key = { it.name }) { model ->
                ModelRow(model, model.name == vm.selectedModel?.name) { vm.selectModel(model) }
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
            mismatch ?: "This printer reports itself as ${identified.name}.",
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

    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
            Text(
                "✓ named by the printer",
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

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { vm.manualModelRequested = true }) {
            Text("Choose a different model", style = MaterialTheme.typography.labelSmall)
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
