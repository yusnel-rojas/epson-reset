package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.PrinterModel

/** Reset workflow shared by Maintenance; kept separate from every read-only printer view. */
@Composable
internal fun MaintenanceResetSection(
    vm: ResetViewModel,
    model: PrinterModel,
    confirming: Boolean,
    onConfirmChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Reset controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "rkey ${model.readKey} · wlen ${model.writeLength} · mem_high 0x%X".format(model.memHigh),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusColors.muted,
            )
        }
        Text(
            "${model.name} · Simulating writes nothing; resetting writes EEPROM after confirmation.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!model.hasResettableCounters) {
            Spacer(Modifier.height(12.dp))
            Callout(
                "No resettable counters",
                "This entry has no EEPROM addresses, so there is nothing to write.",
                StatusColors.bad,
            )
            return@Column
        }

        if (model.isPlatenOnly) {
            Spacer(Modifier.height(12.dp))
            Callout(
                "Platen pad only",
                "This model keeps only the platen pad counter in EEPROM. The main waste box " +
                    "counter lives on a chip and is not reset here — the box itself still needs " +
                    "servicing when it fills.",
                StatusColors.warn,
            )
        }

        Spacer(Modifier.height(14.dp))
        RunControls(vm, model, confirming, onConfirmChange)

        // A reset table is an operation detail, not another standing copy of Overview counters, so
        // it appears only once a run — simulated or real — has actually been asked for.
        val showResetTable = vm.runState !is ResetViewModel.RunState.Idle
        if (showResetTable) {
            vm.counterDisplayReport?.let { report ->
                Spacer(Modifier.height(16.dp))
                Text(
                    if (vm.dryRun) "Simulated reset preview" else "Reset read/write progress",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                CounterOverview(
                    counters = vm.displayDecodedCounters,
                    report = report,
                    before = vm.beforeReport,
                    plan = vm.writePlan,
                    simulated = vm.dryRun,
                    showError = false,
                )
            }
        }
    }
}

@Composable
private fun RunControls(
    vm: ResetViewModel,
    model: PrinterModel,
    confirming: Boolean,
    onConfirmChange: (Boolean) -> Unit,
) {
    val running = vm.runState is ResetViewModel.RunState.Running
    val active = running || vm.reading || vm.overviewRefreshing

    Column {
        // No mode switch. Resetting and simulating are two things you can ask for, not one thing
        // done in one of two moods — and a mode left switched on is a mode the next person does
        // not know about. Saving first is not offered as a choice here the way it is for a restore:
        // a reset always takes its backup and refuses to run without one.
        //
        // Red, because unlike everything else on this screen it changes a number the printer uses
        // to decide it is worn out, and clicking again does not put it back.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
            } else {
                SplitButton(
                    label = "Save current, then reset",
                    primaryEnabled = vm.canResetLive,
                    onPrimary = { onConfirmChange(true) },
                    container = StatusColors.bad,
                    onContainer = StatusColors.onBad,
                    actions = listOf(
                        SplitAction(
                            "Simulate reset — writes nothing",
                            enabled = vm.canSimulateReset,
                        ) { vm.run(simulate = true) },
                    ),
                    modifier = Modifier.width(300.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Resetting clears the counter, which is what unblocks the printer. It does not empty " +
                "or replace the waste ink pad — or the maintenance box, if this printer uses one. " +
                "That is a physical part, and it still holds whatever it held a moment ago. Have " +
                "it replaced or cleaned, or the ink it can no longer absorb has to go somewhere.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.warn,
        )

        if (vm.selectedDevice == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Select a connected printer to reset one. Simulating needs no printer.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        // Not a caveat any more. The first attempt at writing over a network connection made a
        // printer render the commands and jam, so the path is closed until it is proven.
        vm.writeBlockedReason?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.bad)
        }

        // Simulating against a model the printer disagrees with is fine — it writes nothing — but
        // the live half is already closed, and finding that out only on the click is worse.
        vm.modelMismatch?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "Simulation only — $it",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.warn,
            )
        }

        if (confirming) {
            ResetConfirmation(
                vm = vm,
                model = model,
                onDismiss = { onConfirmChange(false) },
                onConfirm = {
                    onConfirmChange(false)
                    vm.run(simulate = false)
                },
            )
        }
    }
}

/**
 * The recovery path, surfaced in Maintenance when it is the obvious next action: a live run that
 * stopped after some writes had already landed. A clean success needs no undo, and a run that wrote
 * nothing has nothing to put back.
 */
@Composable
internal fun MaintenanceResetRecovery(vm: ResetViewModel) {
    val file = vm.lastBackup ?: return
    var confirming by remember(file) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, StatusColors.warn.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "Recovery available",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.warn,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (confirming) {
                "This writes the original bytes back, returning the counters to where they were " +
                    "before the run — including the waste levels. What the printer holds now is " +
                    "saved as its own snapshot first. Continue?"
            } else {
                "The bytes overwritten by this run were saved to ${file.name} beforehand."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (confirming) {
                Button(
                    onClick = {
                        vm.snapshot.loadBackup(file)?.let { vm.snapshot.restore(it, file) }
                            ?: vm.bad("Could not read ${file.name} — it is missing or not a valid backup.")
                        confirming = false
                    },
                    colors = cautionButtonColors(),
                ) { Text("Write the old bytes back") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { confirming = false }) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = { confirming = true }) { Text("Restore from backup") }
            }
        }
    }
}

/**
 * The last thing between a decision and an EEPROM write, and the only place the terms of one are
 * put. It names the *model* as well as the printer: a printer that reports only its family was
 * settled by hand somewhere upstream, and this is the last look anyone gets at that answer before
 * its key goes into the hardware.
 */
@Composable
private fun ResetConfirmation(vm: ResetViewModel, model: PrinterModel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val printer = vm.selectedDevice?.device?.displayName ?: "the printer"
    EepromWriteConfirmation(
        title = "Reset counters — ${model.name}",
        headline = "Write ${model.name}'s key to ${model.writeCount} " +
            "address${if (model.writeCount == 1) "" else "es"} on $printer.",
        metadata = "rkey ${model.readKey} · ${model.writeCount} writes",
        warning = vm.confirmedClass?.let {
            "This printer reports only \"$it\". ${model.name} is the model you confirmed it " +
                "to be — a near neighbour's key is not the same key, so check the printer label."
        },
        paragraphs = listOf(
            "This clears the printer's record of the ink it has absorbed, which is what unblocks " +
                "it. It does not empty anything. The waste ink pad — or the maintenance box, if " +
                "this printer uses one — is a physical part holding real ink; it will hold exactly " +
                "as much after this run as before, and only replacing or cleaning it changes that. " +
                "A full one left in place overflows.",
            "The bytes about to be overwritten are saved to a snapshot first, and the run " +
                "stops rather than proceeding if that cannot be done.",
            "Whether to do this is your decision, and what follows from it is yours to " +
                "carry: this software comes with no warranty, and its authors are not " +
                "accountable for what happens to your printer.",
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun Callout(title: String, body: String, tone: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = tone,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
