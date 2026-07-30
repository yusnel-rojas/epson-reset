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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(
            "Printer maintenance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Start with evidence. A nozzle check spends little ink; cleaning flushes ink into the " +
                "waste pad whose counter this app reads and resets.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (device != null) {
            Note("Target: ${device.displayName} on ${device.link.kind} (${device.link.where}).", StatusColors.muted)
        }
        maintenance.blockedReason?.let {
            Spacer(Modifier.height(5.dp))
            Note(it, StatusColors.bad)
        }
        maintenance.lastResult?.error?.let {
            Spacer(Modifier.height(5.dp))
            Note("The last operation was not sent — $it", StatusColors.bad)
        }

        Spacer(Modifier.height(16.dp))
        StepCard(
            number = 1,
            title = "Print a nozzle check",
            blurb = Maintenance.Operation.NOZZLE_CHECK.summary,
            enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
        ) {
            Button(
                onClick = { confirming = Maintenance.Operation.NOZZLE_CHECK },
                enabled = maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK),
            ) {
                Text(if (maintenance.cleaningCompleted) "Run a confirmation check" else "Print nozzle check")
            }

            if (maintenance.cleaningCompleted) {
                Spacer(Modifier.height(8.dp))
                Note(
                    "A cleaning was sent. Print the pattern again to confirm that it actually cleared the gaps.",
                    StatusColors.good,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        StepCard(
            number = 2,
            title = "Look at the pattern",
            blurb = "The printer cannot ask when a host starts the job, so the app asks: do you see " +
                "gaps or missing lines? That answer is the cleaning safety gate.",
            enabled = assessment != MaintenanceState.PatternAssessment.NOT_CHECKED,
        ) {
            when (assessment) {
                MaintenanceState.PatternAssessment.NOT_CHECKED ->
                    Note("Print the nozzle check first.", StatusColors.muted)

                MaintenanceState.PatternAssessment.AWAITING_ANSWER ->
                    Note("Waiting for your answer in the pattern dialog.", StatusColors.warn)

                MaintenanceState.PatternAssessment.NO_GAPS ->
                    Note(
                        "Nothing to do. A cleaning would only use ink and fill the waste pad, so it stays disabled.",
                        StatusColors.good,
                    )

                MaintenanceState.PatternAssessment.GAPS ->
                    Note(
                        "The pattern has gaps. Cleaning is available below; its pad cost is stated before each button.",
                        StatusColors.warn,
                    )
            }
        }

        Spacer(Modifier.height(12.dp))
        StepCard(
            number = 3,
            title = "Clean only if the pattern has gaps",
            blurb = "Both cleaning cycles raise the waste counter. Run the ordinary cycle first; " +
                "power cleaning is a last resort, not a stronger default.",
            enabled = maintenance.cleaningEnabled,
        ) {
            CleaningChoice(
                operation = Maintenance.Operation.HEAD_CLEANING,
                enabled = maintenance.canRun(Maintenance.Operation.HEAD_CLEANING),
                onClick = { confirming = Maintenance.Operation.HEAD_CLEANING },
            )

            Spacer(Modifier.height(14.dp))
            CleaningChoice(
                operation = Maintenance.Operation.POWER_CLEANING,
                enabled = maintenance.canRun(Maintenance.Operation.POWER_CLEANING),
                onClick = { confirming = Maintenance.Operation.POWER_CLEANING },
            )
            Spacer(Modifier.height(5.dp))
            Note(
                "Power cleaning is the only operation on this screen that has not been run on a " +
                    "printer in this project; its command bytes are independently corroborated.",
                StatusColors.muted,
            )
        }

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

@Composable
private fun CleaningChoice(operation: Maintenance.Operation, enabled: Boolean, onClick: () -> Unit) {
    Text(
        operation.summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Ink cost: ${operation.inkCost.label}. The flushed ink goes into the waste pad and raises its counter.",
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.warn,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
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
