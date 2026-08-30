package nl.rlabs.epsonreset.ui

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
import nl.rlabs.epsonreset.protocol.Maintenance
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.confirm_back
import nl.rlabs.epsonreset.resources.maint_check_again_title
import nl.rlabs.epsonreset.resources.maint_check_title
import nl.rlabs.epsonreset.resources.maint_clean_blurb
import nl.rlabs.epsonreset.resources.maint_clean_title
import nl.rlabs.epsonreset.resources.maint_confirm_ink_cost
import nl.rlabs.epsonreset.resources.maint_confirm_no_poll
import nl.rlabs.epsonreset.resources.maint_confirm_one_sheet
import nl.rlabs.epsonreset.resources.maint_confirm_question
import nl.rlabs.epsonreset.resources.maint_confirm_run
import nl.rlabs.epsonreset.resources.maint_confirm_title
import nl.rlabs.epsonreset.resources.maint_confirm_unproven
import nl.rlabs.epsonreset.resources.maint_counter_no_model
import nl.rlabs.epsonreset.resources.maint_counter_section
import nl.rlabs.epsonreset.resources.maint_gaps_note
import nl.rlabs.epsonreset.resources.maint_intro
import nl.rlabs.epsonreset.resources.maint_last_failed
import nl.rlabs.epsonreset.resources.maint_no_gaps_note
import nl.rlabs.epsonreset.resources.maint_pattern_body
import nl.rlabs.epsonreset.resources.maint_pattern_dialog_title
import nl.rlabs.epsonreset.resources.maint_pattern_no_gaps
import nl.rlabs.epsonreset.resources.maint_pattern_question
import nl.rlabs.epsonreset.resources.maint_pattern_yes_gaps
import nl.rlabs.epsonreset.resources.maint_print_intro
import nl.rlabs.epsonreset.resources.maint_print_nozzle_check
import nl.rlabs.epsonreset.resources.maint_print_section
import nl.rlabs.epsonreset.resources.maint_run_confirmation_check
import nl.rlabs.epsonreset.resources.maint_seen_gaps
import nl.rlabs.epsonreset.resources.maint_start_over
import nl.rlabs.epsonreset.resources.maint_target
import nl.rlabs.epsonreset.resources.maint_the_printer
import nl.rlabs.epsonreset.resources.maint_title
import nl.rlabs.epsonreset.resources.maint_waiting_answer
import org.jetbrains.compose.resources.stringResource

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
            stringResource(Res.string.maint_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.maint_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (device != null) {
            Note(
                stringResource(Res.string.maint_target, device.displayName, device.link.kind, device.link.where),
                StatusColors.muted,
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(Res.string.maint_counter_section),
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
            stringResource(Res.string.maint_counter_no_model),
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
            stringResource(Res.string.maint_print_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.maint_print_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        maintenance.blockedReason?.let {
            Spacer(Modifier.height(5.dp))
            Note(it, StatusColors.bad)
        }
        maintenance.lastResult?.error?.let {
            Spacer(Modifier.height(5.dp))
            Note(stringResource(Res.string.maint_last_failed, it), StatusColors.bad)
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
            printer = device?.displayName ?: stringResource(Res.string.maint_the_printer),
            onAnswer = maintenance::answerNozzleCheck,
        )
    }

    confirming?.let { operation ->
        MaintenanceConfirmation(
            operation = operation,
            printer = device?.displayName ?: stringResource(Res.string.maint_the_printer),
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
            title = if (maintenance.cleaningCompleted) {
                stringResource(Res.string.maint_check_again_title)
            } else {
                stringResource(Res.string.maint_check_title)
            },
            blurb = stringResource(Maintenance.Operation.NOZZLE_CHECK.summary),
            enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
        ) {
            Button(
                onClick = { onRun(Maintenance.Operation.NOZZLE_CHECK) },
                enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
            ) {
                Text(
                    if (maintenance.cleaningCompleted) {
                        stringResource(Res.string.maint_run_confirmation_check)
                    } else {
                        stringResource(Res.string.maint_print_nozzle_check)
                    },
                )
            }

            if (assessment == MaintenanceState.PatternAssessment.AWAITING_ANSWER) {
                Spacer(Modifier.height(8.dp))
                Note(stringResource(Res.string.maint_waiting_answer), StatusColors.warn)
            }

            // The gate is that somebody said there are gaps, not that this app printed the sheet.
            if (assessment == MaintenanceState.PatternAssessment.NOT_CHECKED) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = maintenance::assumeGaps, enabled = maintenance.blockedReason == null) {
                    Text(stringResource(Res.string.maint_seen_gaps))
                }
            }
        }

        // Nothing to do, and nothing worth a card to say it with.
        MaintenanceState.PatternAssessment.NO_GAPS -> SettledStep(
            text = stringResource(Res.string.maint_no_gaps_note),
            colour = StatusColors.good,
            onStartOver = maintenance::clearAssessment,
        )

        MaintenanceState.PatternAssessment.GAPS -> {
            SettledStep(
                text = stringResource(Res.string.maint_gaps_note),
                colour = StatusColors.warn,
                onStartOver = maintenance::clearAssessment,
            )
            Spacer(Modifier.height(10.dp))
            StepCard(
                number = 2,
                title = stringResource(Res.string.maint_clean_title),
                blurb = stringResource(Res.string.maint_clean_blurb),
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
        TextButton(onClick = onStartOver) { Text(stringResource(Res.string.maint_start_over)) }
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
    ) { Text(stringResource(operation.title)) }
}

@Composable
private fun PatternQuestion(printer: String, onAnswer: (Boolean) -> Unit) {
    DialogWindow(
        onCloseRequest = {},
        state = rememberDialogState(size = DpSize(520.dp, 280.dp)),
        title = stringResource(Res.string.maint_pattern_dialog_title, printer),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    stringResource(Res.string.maint_pattern_question),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.maint_pattern_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        onAnswer(false)
                    }) { Text(stringResource(Res.string.maint_pattern_no_gaps)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onAnswer(true) }, colors = cautionButtonColors()) {
                        Text(stringResource(Res.string.maint_pattern_yes_gaps))
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
        title = stringResource(Res.string.maint_confirm_title, stringResource(operation.title), printer),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    stringResource(
                        Res.string.maint_confirm_question,
                        stringResource(operation.title).lowercase(),
                        printer,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(operation.summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (operation.printsPage) {
                    Text(
                        stringResource(Res.string.maint_confirm_one_sheet, stringResource(operation.inkCost.label)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.muted,
                    )
                } else if (operation.raisesWasteCounter) {
                    Text(
                        stringResource(Res.string.maint_confirm_ink_cost, stringResource(operation.inkCost.label)),
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
                        stringResource(Res.string.maint_confirm_unproven),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.maint_confirm_no_poll),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.muted,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(Res.string.confirm_back)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = when (operation) {
                            Maintenance.Operation.NOZZLE_CHECK -> ButtonDefaults.buttonColors()
                            Maintenance.Operation.HEAD_CLEANING -> cautionButtonColors()
                            Maintenance.Operation.POWER_CLEANING -> dangerButtonColors()
                        },
                    ) {
                        Text(stringResource(Res.string.maint_confirm_run, stringResource(operation.title).lowercase()))
                    }
                }
            }
        }
    }
}
