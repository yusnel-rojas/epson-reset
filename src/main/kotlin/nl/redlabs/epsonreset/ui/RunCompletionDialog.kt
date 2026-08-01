package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/**
 * What a reset or a restore did, said once and dismissed. Both operations end the same way — bytes
 * were written and the printer has to be power-cycled for it to take — so both report it here
 * rather than each leaving a card behind on the screen it was started from.
 */
@Composable
internal fun RunCompletionDialog(vm: ResetViewModel) {
    val completion = vm.completion ?: return
    val result = completion.result
    val restore = completion.kind == ResetViewModel.RunKind.RESTORE

    val title = when {
        !result.success && restore -> "Restore failed"
        !result.success -> "Reset failed"
        completion.wasDryRun && restore -> "Restore simulated"
        completion.wasDryRun -> "Dry run passed"
        restore -> "Snapshot restored"
        else -> "Counters reset"
    }
    val tone = if (result.success) StatusColors.good else StatusColors.bad

    // Only a live run that actually landed writes needs the printer restarted; a dry run touched
    // nothing, and a failure that wrote nothing left the printer as it was.
    val needsPowerCycle = !completion.wasDryRun && result.writesAcknowledged > 0

    DialogWindow(
        onCloseRequest = vm::dismissCompletion,
        state = rememberDialogState(size = DpSize(520.dp, 400.dp)),
        title = title,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tone,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.writesAcknowledged} of ${result.writesTotal} writes acknowledged · " +
                        "${result.packetsSent} packets sent",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (result.error.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            result.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.bad,
                        )
                    }

                    if (needsPowerCycle) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(StatusColors.warn.copy(alpha = 0.12f))
                                .border(1.dp, StatusColors.warn.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                "Turn the printer off and on again",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusColors.warn,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "The new values are in EEPROM, but the printer is still running on " +
                                    "what it read at startup. Power it off with its own button, wait " +
                                    "for it to finish parking, then switch it back on.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!result.success && !completion.wasDryRun && result.writesAcknowledged > 0) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Some writes landed before this stopped, so the printer is in a partly " +
                                "changed state. The pre-write bytes were saved beforehand — the " +
                                "Snapshots tab can put them back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (completion.wasDryRun) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Nothing reached a printer. This was generated and checked against the " +
                                "simulated device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.muted,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::dismissCompletion) { Text("Done") }
                }
            }
        }
    }
}
