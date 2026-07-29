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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import nl.redlabs.epsonreset.db.PadKind
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.protocol.Executor

/** The full-width Reset screen for the model in the application-wide target. */
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

        val hasResults = vm.readReport != null || vm.runState is ResetViewModel.RunState.Finished
        if (hasResults) {
            Spacer(Modifier.height(20.dp))
            ResultBanner(vm)

            vm.readReport?.let { report ->
                Spacer(Modifier.height(12.dp))
                InkLevels(vm.status)
                Spacer(Modifier.height(12.dp))
                // The action belongs on the counters, since a maximum is what the "no limit" in
                // them is missing. The form itself opens in its own window — see CalibrationDialog.
                DecodedCounters(vm.decodedCounters, onCalibrate = { vm.calibration.open() })
                CalibrationDialog(vm)
                Spacer(Modifier.height(12.dp))
                CounterTable(report, vm.beforeReport)

                if (vm.snapshot.canOfferComparison) {
                    Spacer(Modifier.height(12.dp))
                    CompareOffer(vm)
                }
            }
        }
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

        Spacer(Modifier.height(12.dp))

        if (!model.hasResettableCounters) {
            Callout(
                "No resettable counters",
                "This entry has no EEPROM addresses, so there is nothing to write.",
                StatusColors.bad,
            )
            return@Column
        }

        for (group in model.padGroups) {
            val tone = when (group.effectiveKind) {
                PadKind.PLATEN -> StatusColors.warn
                PadKind.MAIN -> MaterialTheme.colorScheme.primary
                PadKind.UNKNOWN -> StatusColors.muted
            }

            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    group.description.ifBlank { "Waste counters" },
                    style = MaterialTheme.typography.bodySmall,
                    color = tone,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(160.dp),
                )
                Text(
                    group.addresses.joinToString(" ") { "%d".format(it) },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    Column {
        // Nothing is inserted here when Live is switched on. A warning that displaces the controls
        // it is about trains people to scroll past it, and by the time the button is clicked it has
        // been on screen long enough to have stopped being read — so it lives on the act instead,
        // in ResetConfirmation.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DryRunToggle(vm.dryRun, enabled = !running && !vm.reading) {
                vm.dryRun = it
                onConfirmChange(false)
            }
            Spacer(Modifier.weight(1f))

            if (running || vm.reading) {
                OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
            } else {
                // Reading never writes, so it sits outside the confirmation gate.
                OutlinedButton(onClick = { vm.readCounters() }, enabled = vm.canRead) {
                    Text("Read counters")
                }
                Spacer(Modifier.width(8.dp))
                // Saving writes a file, not EEPROM, so it needs no confirmation either — and it
                // belongs next to the read, because what it saves is whatever that read returned.
                OutlinedButton(onClick = { vm.snapshot.saveSnapshot() }, enabled = vm.snapshot.canSaveSnapshot) {
                    Text("Save snapshot")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (vm.dryRun) vm.run() else onConfirmChange(true) },
                    enabled = vm.canRun,
                ) {
                    Text(if (vm.dryRun) "Dry run" else "Reset counters")
                }
            }
        }

        if (!vm.dryRun && vm.selectedDevice == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Select a connected printer before writing.",
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

        // Only once there are counters on screen. Before that, a disabled Save snapshot next to
        // Read counters says what it needs to say on its own.
        if (vm.readReport != null) {
            vm.snapshot.snapshotBlockedReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cannot save these as a snapshot — $it.",
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

        if (running || vm.reading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { vm.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                vm.progressLabel,
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "${model.writeCount} EEPROM writes across ${model.padGroups.size} group(s).",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
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

@Composable
private fun ResultBanner(vm: ResetViewModel) {
    val finished = vm.runState as? ResetViewModel.RunState.Finished ?: return
    val r: Executor.Result = finished.result

    val (title, tone) = when {
        r.success && finished.wasDryRun -> "Dry run passed" to StatusColors.good
        r.success -> "Counters reset" to StatusColors.good
        else -> "Reset failed" to StatusColors.bad
    }

    val body = buildString {
        append("${r.writesVerified} of ${r.writesTotal} writes verified · ")
        append("${r.packetsSent} packets sent · ${r.ackCount} acknowledged.")
        if (r.error.isNotBlank()) append("\n\n${r.error}")
        if (r.success && !finished.wasDryRun) {
            append("\n\nPower-cycle the printer now to finalise the change.")
        }
    }

    Callout(title, body, tone)

    // Only offered where it can actually help: a live run that stopped after some writes had
    // already landed. A clean success needs no undo, and a run that wrote nothing has nothing to
    // put back.
    val strandedWrites = !r.success && !finished.wasDryRun && r.writesVerified > 0
    if (strandedWrites && vm.lastBackup != null) {
        Spacer(Modifier.height(8.dp))
        RestoreOffer(vm)
    }
}

/** The recovery path, surfaced at the only moment it is the obvious next action. */
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

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(560.dp, 460.dp)),
        title = "Reset counters — ${model.name}",
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Write ${model.name}'s key to ${model.writeCount} " +
                        "address${if (model.writeCount == 1) "" else "es"} on $printer.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "rkey ${model.readKey} · ${model.writeCount} writes",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )

                // Only where the name above was a person's answer rather than the printer's, which
                // is exactly where a last look at it is worth prompting for.
                vm.confirmedClass?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This printer reports only \"$it\". ${model.name} is the model you " +
                            "confirmed it to be — a near neighbour's key is not the same key, so " +
                            "it is worth checking against the label on the printer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Paragraph(
                    "This clears the printer's record of the ink its pad has absorbed. The ink is " +
                        "still in the pad, and a pad at the end of its life still needs servicing.",
                )
                Spacer(Modifier.height(10.dp))
                Paragraph(
                    "The bytes about to be overwritten are saved to a snapshot first, and the run " +
                        "stops rather than proceeding if that cannot be done.",
                )
                Spacer(Modifier.height(10.dp))
                Paragraph(
                    "Whether to do this is your decision, and what follows from it is yours to " +
                        "carry: this software comes with no warranty, and its authors are not " +
                        "accountable for what happens to your printer.",
                )

                Spacer(Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = dangerButtonColors(),
                    ) { Text("Yes, write EEPROM") }
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
