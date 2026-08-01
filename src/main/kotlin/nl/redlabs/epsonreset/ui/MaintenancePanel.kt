package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import nl.redlabs.epsonreset.protocol.Maintenance

/** Guided maintenance: establish need with a nozzle check before making cleaning reachable. */
@Composable
fun MaintenancePanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val maintenance = vm.maintenance
    val device = vm.selectedDevice?.device
    val assessment = maintenance.patternAssessment
    var confirming by remember { mutableStateOf<Maintenance.Operation?>(null) }
    var resetConfirming by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(
            "Printer maintenance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Routine maintenance actions live here. Counter reset has a safe simulation mode and " +
                "automatic backup; print maintenance starts with evidence before spending ink.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (device != null) {
            Note("Target: ${device.displayName} on ${device.link.kind} (${device.link.where}).", StatusColors.muted)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Counter maintenance",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        vm.selectedModel?.let { model ->
            MaintenanceResetSection(
                vm = vm,
                model = model,
                confirming = resetConfirming,
                onConfirmChange = { resetConfirming = it },
            )
        } ?: Note(
            "Select a printer model to preview or reset its counters.",
            StatusColors.muted,
        )

        // A failed live reset can have acknowledged writes even though the complete run failed.
        // Keep its recovery beside the reset action, rather than sending the user hunting for it.
        val finishedReset = vm.runState as? ResetViewModel.RunState.Finished
        val recoveryNeeded = finishedReset != null &&
            vm.runKind == ResetViewModel.RunKind.RESET &&
            !finishedReset.result.success &&
            !finishedReset.wasDryRun &&
            finishedReset.result.writesAcknowledged > 0
        if (recoveryNeeded && vm.lastBackup != null) {
            Spacer(Modifier.height(12.dp))
            MaintenanceResetRecovery(vm)
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Print maintenance",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Start with a nozzle check. Cleaning flushes ink into the waste pad and raises its counter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        maintenance.blockedReason?.let {
            Spacer(Modifier.height(5.dp))
            Note(it, StatusColors.bad)
        }
        maintenance.lastResult?.error?.let {
            Spacer(Modifier.height(5.dp))
            Note("The last operation was not sent — $it", StatusColors.bad)
        }

        Spacer(Modifier.height(16.dp))
        PrintMaintenanceStep(
            maintenance = maintenance,
            assessment = assessment,
            onRun = { confirming = it },
        )

        Spacer(Modifier.height(20.dp))
    }

    if (assessment == MaintenanceState.PatternAssessment.AWAITING_ANSWER) {
        PatternQuestion(
            printer = device?.displayName ?: "the printer",
            onAnswer = maintenance::answerNozzleCheck,
        )
    }

    confirming?.let { operation ->
        MaintenanceConfirmation(
            operation = operation,
            printer = device?.displayName ?: "the printer",
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                maintenance.run(operation)
            },
        )
    }
}

/**
 * The one step this printer is actually on.
 *
 * The sequence is unchanged and so is its gate — what changed is that the screen no longer renders
 * all three steps at full size regardless of which is live. `patternAssessment` already is the
 * state machine; this reads it instead of restating it. What is settled becomes one line, what is
 * next is a card, and what cannot be reached yet is not drawn at all.
 */
@Composable
private fun PrintMaintenanceStep(
    maintenance: MaintenanceState,
    assessment: MaintenanceState.PatternAssessment,
    onRun: (Maintenance.Operation) -> Unit,
) {
    when (assessment) {
        MaintenanceState.PatternAssessment.NOT_CHECKED,
        MaintenanceState.PatternAssessment.AWAITING_ANSWER,
        -> StepCard(
            number = 1,
            title = if (maintenance.cleaningCompleted) "Check whether the cleaning worked" else "Print a nozzle check",
            blurb = Maintenance.Operation.NOZZLE_CHECK.summary,
            enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
        ) {
            Button(
                onClick = { onRun(Maintenance.Operation.NOZZLE_CHECK) },
                enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
            ) {
                Text(if (maintenance.cleaningCompleted) "Run a confirmation check" else "Print nozzle check")
            }

            if (assessment == MaintenanceState.PatternAssessment.AWAITING_ANSWER) {
                Spacer(Modifier.height(8.dp))
                Note("Waiting for your answer in the pattern dialog.", StatusColors.warn)
            }

            // The gate is that somebody said there are gaps, not that this app printed the sheet.
            if (assessment == MaintenanceState.PatternAssessment.NOT_CHECKED) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = maintenance::assumeGaps, enabled = maintenance.blockedReason == null) {
                    Text("I have already seen the pattern — it has gaps")
                }
            }
        }

        // Nothing to do, and nothing worth a card to say it with.
        MaintenanceState.PatternAssessment.NO_GAPS -> SettledStep(
            text = "The pattern has no gaps — nothing needs cleaning.",
            colour = StatusColors.good,
            onStartOver = maintenance::clearAssessment,
        )

        MaintenanceState.PatternAssessment.GAPS -> {
            SettledStep(
                text = "The pattern has gaps.",
                colour = StatusColors.warn,
                onStartOver = maintenance::clearAssessment,
            )
            Spacer(Modifier.height(10.dp))
            StepCard(
                number = 2,
                title = "Clean the head",
                blurb = "Both cycles flush ink into the waste pad and raise its counter. Run the " +
                    "ordinary one first; power cleaning is a last resort, not a stronger default. " +
                    "What each costs is on its confirmation.",
                enabled = maintenance.cleaningEnabled,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CleaningChoice(
                        operation = Maintenance.Operation.HEAD_CLEANING,
                        enabled = maintenance.canRun(Maintenance.Operation.HEAD_CLEANING),
                        onClick = { onRun(Maintenance.Operation.HEAD_CLEANING) },
                    )
                    Spacer(Modifier.width(10.dp))
                    CleaningChoice(
                        operation = Maintenance.Operation.POWER_CLEANING,
                        enabled = maintenance.canRun(Maintenance.Operation.POWER_CLEANING),
                        onClick = { onRun(Maintenance.Operation.POWER_CLEANING) },
                    )
                }
            }
        }
    }
}

/** A step whose answer is in: one line, and the way back to it. */
@Composable
private fun SettledStep(text: String, colour: Color, onStartOver: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = colour, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onStartOver) { Text("Start over") }
    }
}

@Composable
private fun CleaningChoice(operation: Maintenance.Operation, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (operation == Maintenance.Operation.POWER_CLEANING) {
            dangerButtonColors()
        } else {
            cautionButtonColors()
        },
    ) { Text(operation.label) }
}

@Composable
private fun PatternQuestion(printer: String, onAnswer: (Boolean) -> Unit) {
    DialogWindow(
        onCloseRequest = {},
        state = rememberDialogState(size = DpSize(520.dp, 280.dp)),
        title = "Check the nozzle pattern — $printer",
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Do you see gaps or missing lines?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Compare the printed lines carefully. Cleaning is enabled only when the page " +
                        "shows that it is needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onAnswer(false) }) { Text("No gaps") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onAnswer(true) }, colors = cautionButtonColors()) {
                        Text("Yes, there are gaps")
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceConfirmation(
    operation: Maintenance.Operation,
    printer: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(560.dp, 420.dp)),
        title = "${operation.label} — $printer",
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Send ${operation.label.lowercase()} to $printer over USB?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    operation.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (operation.printsPage) {
                    Text(
                        "This prints one sheet and uses ${operation.inkCost.label} ink.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.muted,
                    )
                } else if (operation.raisesWasteCounter) {
                    Text(
                        "Ink cost: ${operation.inkCost.label}. That ink is flushed into the waste pad " +
                            "and raises the counter this app otherwise exists to lower.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.warn,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Read here, at the moment of committing, rather than sitting permanently on the
                // screen behind a button that could not be pressed anyway.
                if (operation == Maintenance.Operation.POWER_CLEANING) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This is the only operation in the app that has not been run on a printer " +
                            "in this project. Its command bytes are independently corroborated, but " +
                            "nobody here has watched one execute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "The app checks that the printer is idle, sends the job, and then deliberately " +
                        "does not poll while it may still be active. Watch the printer to verify the result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.muted,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = when (operation) {
                            Maintenance.Operation.NOZZLE_CHECK -> ButtonDefaults.buttonColors()
                            Maintenance.Operation.HEAD_CLEANING -> cautionButtonColors()
                            Maintenance.Operation.POWER_CLEANING -> dangerButtonColors()
                        },
                    ) { Text("Run ${operation.label.lowercase()}") }
                }
            }
        }
    }
}
