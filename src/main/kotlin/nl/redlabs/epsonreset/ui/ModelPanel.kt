package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** The full-width Counters screen for the model in the application-wide target. */
@Composable
fun ModelPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    val model = vm.selectedModel

    if (model == null) {
        EmptyState(vm, modifier)
        return
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        ModelDetail(model)

        Spacer(Modifier.height(16.dp))
        RunControls(vm, model, confirming, onConfirmChange = { confirming = it })

        // The outcome itself is the completion dialog's to report, once. What stays behind is only
        // the offer that is still actionable: a live reset that stopped with writes already landed.
        val finished = vm.runState as? ResetViewModel.RunState.Finished
        val stranded = finished != null &&
            vm.runKind == ResetViewModel.RunKind.RESET &&
            !finished.result.success &&
            !finished.wasDryRun &&
            finished.result.writesAcknowledged > 0
        if (stranded && vm.lastBackup != null) {
            Spacer(Modifier.height(20.dp))
            RestoreOffer(vm)
        }

        vm.counterDisplayReport?.let { report ->
            Spacer(Modifier.height(if (stranded) 12.dp else 20.dp))
            if (vm.status != null || vm.printerMib != null) {
                SuppliesCard(vm.status, vm.printerMib)
                Spacer(Modifier.height(12.dp))
            }
            // The model supplies addresses, types, and reset targets before a printer has answered.
            // Current values are filled into this same table as reads and writes progress.
            CounterOverview(
                counters = vm.displayDecodedCounters,
                report = report,
                before = vm.beforeReport,
                byteStates = vm.counterByteStates,
                simulated = vm.dryRun,
                // The status bar already reports a read failure; no need to say it twice.
                showError = false,
                onCalibrate = if (vm.readReport != null && !vm.readWasSimulated && !vm.reading) {
                    vm.calibration::open
                } else {
                    null
                },
            )
            CalibrationDialog(vm)

            if (vm.snapshot.canOfferComparison) {
                Spacer(Modifier.height(12.dp))
                CompareOffer(vm)
            }
        }

        // Rendered once, whether or not this session has a fresh reading. The panel hides itself when
        // the selected printer has neither a serial-keyed history nor a reason it can't have one, and
        // its modifier — top spacing included — applies only on that render path, so nothing shows a
        // gap when it stays hidden.
        CounterHistoryPanel(vm, Modifier.padding(top = 20.dp))
    }
}

/** A way through to the comparison, not a second copy of it. */
@Composable
private fun CompareOffer(vm: ResetViewModel) {
    val saved = vm.snapshot.snapshotsForSelectedModel

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${saved.size} saved snapshot(s) for this model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Comparing this reading against one of them shows what has moved since — and " +
                    "whether a reset run earlier still holds.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = { vm.snapshot.compareCurrentReadingWithNewestSnapshot() }) {
            Text("Compare")
        }
    }
}

@Composable
private fun EmptyState(vm: ResetViewModel, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(420.dp),
        ) {
            Text(
                "No model selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.devices.isEmpty()) {
                    "Open the target above to scan for a printer or choose a model for a dry run."
                } else {
                    "Open the target above to choose a printer and resolve its model."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
        }
    }
}

/** Shows exactly which EEPROM addresses a reset would touch — no hidden writes. */
@Composable
private fun ModelDetail(model: PrinterModel) {
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
                model.name,
                style = MaterialTheme.typography.titleLarge,
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
    val active = running || vm.reading

    Column {
        // Nothing is inserted here when Live is switched on. A warning that displaces the controls
        // it is about trains people to scroll past it, and by the time the button is clicked it has
        // been on screen long enough to have stopped being read — so it lives on the act instead,
        // in ResetConfirmation.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DryRunToggle(vm.dryRun, enabled = !running && !vm.reading) {
                vm.changeDryRunMode(it)
                onConfirmChange(false)
            }
            Spacer(Modifier.weight(1f))

            if (active) {
                OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
            } else {
                if (!vm.dryRun) {
                    // Reading never writes, so it sits outside the confirmation gate.
                    OutlinedButton(onClick = { vm.readCounters() }, enabled = vm.canRead) {
                        Text("Read counters")
                    }
                    Spacer(Modifier.width(8.dp))
                    // Saving writes a file, not EEPROM, so it needs no confirmation either — and
                    // belongs next to the live read whose values it preserves.
                    OutlinedButton(onClick = { vm.snapshot.saveSnapshot() }, enabled = vm.snapshot.canSaveSnapshot) {
                        Text("Save snapshot")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { if (vm.dryRun) vm.run() else onConfirmChange(true) },
                    enabled = vm.canRun,
                ) {
                    Text(if (vm.dryRun) "Simulate reset" else "Reset counters")
                }
            }
        }

        if (!vm.dryRun && vm.selectedDevice == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Select a connected printer to use live mode.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.warn,
            )
        }

        // Not a caveat any more. The first attempt at writing over a network connection made a
        // printer render the commands and jam, so the path is closed until it is proven.
        if (!vm.dryRun) {
            vm.writeBlockedReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.bad,
                )
            }
        } else {
            // A dry run against a model the printer disagrees with is fine — it writes nothing —
            // but the Live switch is already closed, and finding that out only after flipping it is
            // worse than knowing now.
            vm.modelMismatch?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dry run only — $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.warn,
                )
            }
        }

        if (confirming && !vm.dryRun) {
            ResetConfirmation(
                vm = vm,
                model = model,
                onDismiss = { onConfirmChange(false) },
                onConfirm = {
                    onConfirmChange(false)
                    vm.run()
                },
            )
        }
    }
}

@Composable
private fun DryRunToggle(dryRun: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        SegmentButton("Dry run", dryRun, enabled) { onChange(true) }
        SegmentButton("Live", !dryRun, enabled) { onChange(false) }
    }
}

@Composable
private fun SegmentButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        active && label == "Live" -> StatusColors.bad
        active -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            // The Live segment fills with `bad` rather than `primary`, so it takes that fill's label.
            active && label == "Live" -> StatusColors.onBad
            active -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> StatusColors.muted
        },
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * The recovery path, surfaced at the only moment it is the obvious next action: a live run that
 * stopped after some writes had already landed. A clean success needs no undo, and a run that wrote
 * nothing has nothing to put back.
 */
@Composable
private fun RestoreOffer(vm: ResetViewModel) {
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
                    "before the run — including the waste levels. Continue?"
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
                        vm.snapshot.loadBackup(file)?.let { vm.snapshot.restore(it) }
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
            "This clears the printer's record of the ink its pad has absorbed. The ink is " +
                "still in the pad, and a pad at the end of its life still needs servicing.",
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
