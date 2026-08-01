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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.io.File

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

    // A run that stopped with writes already in the printer is the one case with something to
    // decide. Everything else is read and closed.
    val stranded = !result.success && !completion.wasDryRun && result.writesAcknowledged > 0

    // The backup a reset takes before its first write is the exact recovery point for that reset.
    // A failed restore has no such thing — it was already the recovery — so it offers the file list
    // instead of pointing at a backup that belongs to some earlier run.
    val recovery = vm.lastBackup?.takeIf { stranded && !restore }
    var confirming by remember(recovery) { mutableStateOf(false) }

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

                    // Said at the end as well as before the run, because this is the moment the
                    // printer starts working again and the moment it is easiest to conclude the
                    // problem is dealt with. The counter is what was cleared. The pad is unchanged.
                    if (result.success && !restore && !completion.wasDryRun) {
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
                                "The waste ink has not gone anywhere",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusColors.warn,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "The counter is clear, so the printer will print again — but the " +
                                    "waste ink pad, or the maintenance box if this printer uses " +
                                    "one, still holds every drop it held a minute ago. It is a " +
                                    "physical part, and only replacing or cleaning it changes that. " +
                                    "One left in place after it is genuinely full overflows, and " +
                                    "the ink goes somewhere neither you nor the printer chose.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (stranded) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Some writes landed before this stopped, so the printer is in a partly " +
                                "changed state — neither where it was nor where this run was taking " +
                                "it. Putting a snapshot back is how that is settled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recovery?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The bytes this run was about to overwrite were saved to " +
                                    "${it.name} beforehand.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted,
                            )
                        }
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

                    if (stranded) {
                        // Picking a different one is a job for the panel built to show what each
                        // file holds, so this hands over rather than growing a file list.
                        OutlinedButton(
                            onClick = {
                                vm.tab = ResetViewModel.Tab.SNAPSHOTS
                                vm.dismissCompletion()
                            },
                        ) { Text("Choose a snapshot…") }
                        Spacer(Modifier.width(8.dp))
                    }

                    recovery?.let {
                        OutlinedButton(onClick = { confirming = true }) { Text("Restore this backup…") }
                        Spacer(Modifier.width(8.dp))
                    }

                    Button(onClick = vm::dismissCompletion) { Text("OK") }
                }
            }
        }
    }

    // The house gate for every EEPROM write, this one included: the outcome dialog says what
    // happened, and writing something back is a new decision that goes through the same door.
    if (confirming && recovery != null) {
        RecoveryConfirmation(vm, recovery, onDismiss = { confirming = false })
    }
}

/** Writing the pre-run backup back, from the dialog that just reported the run that needed it. */
@Composable
private fun RecoveryConfirmation(vm: ResetViewModel, file: File, onDismiss: () -> Unit) {
    val backup = remember(file) { vm.snapshot.loadBackup(file) }
    if (backup == null) {
        // Nothing to confirm against, so say why here rather than failing at the write.
        LaunchedEffect(file) {
            vm.bad("Could not read ${file.name} — it is missing or not a valid backup.")
            onDismiss()
        }
        return
    }

    val printer = vm.selectedDevice?.device?.displayName ?: "the printer"
    EepromWriteConfirmation(
        title = "Restore EEPROM — ${backup.model}",
        headline = "Write ${backup.entries.size} saved EEPROM bytes into $printer.",
        metadata = "${file.name} · taken ${backup.takenAt}",
        warning = "These are the bytes the run that just failed was about to overwrite.",
        paragraphs = listOf(
            "This puts the counters back where they were before that run started, waste levels " +
                "included. It is the recovery point for exactly this situation.",
            "What the printer holds now is read and saved as its own snapshot first, over the same " +
                "connection. If those bytes cannot all be read, nothing is written.",
            "Whether to do this is your decision, and what follows from it is yours to carry: this " +
                "software comes with no warranty, and its authors are not accountable for what " +
                "happens to your printer.",
        ),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            vm.dismissCompletion()
            vm.snapshot.restore(backup, file)
        },
        confirmLabel = "Yes, restore EEPROM",
    )
}
