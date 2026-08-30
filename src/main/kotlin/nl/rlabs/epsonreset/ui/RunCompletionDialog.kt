package nl.rlabs.epsonreset.ui

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
import nl.rlabs.epsonreset.i18n.Strings
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.confirm_restore_eeprom
import nl.rlabs.epsonreset.resources.recovery_confirm_headline
import nl.rlabs.epsonreset.resources.recovery_confirm_metadata
import nl.rlabs.epsonreset.resources.recovery_confirm_recovery_point
import nl.rlabs.epsonreset.resources.recovery_confirm_snapshot_first
import nl.rlabs.epsonreset.resources.recovery_confirm_title
import nl.rlabs.epsonreset.resources.recovery_confirm_warning
import nl.rlabs.epsonreset.resources.reset_confirm_no_warranty
import nl.rlabs.epsonreset.resources.reset_confirm_the_printer
import nl.rlabs.epsonreset.resources.run_backup_unreadable
import nl.rlabs.epsonreset.resources.run_choose_snapshot
import nl.rlabs.epsonreset.resources.run_dry_run_body
import nl.rlabs.epsonreset.resources.run_metadata
import nl.rlabs.epsonreset.resources.run_ok
import nl.rlabs.epsonreset.resources.run_power_cycle_body
import nl.rlabs.epsonreset.resources.run_power_cycle_title
import nl.rlabs.epsonreset.resources.run_restore_this_backup
import nl.rlabs.epsonreset.resources.run_stranded_backup
import nl.rlabs.epsonreset.resources.run_stranded_body
import nl.rlabs.epsonreset.resources.run_title_counters_reset
import nl.rlabs.epsonreset.resources.run_title_dry_run_passed
import nl.rlabs.epsonreset.resources.run_title_reset_failed
import nl.rlabs.epsonreset.resources.run_title_restore_failed
import nl.rlabs.epsonreset.resources.run_title_restore_simulated
import nl.rlabs.epsonreset.resources.run_title_snapshot_restored
import nl.rlabs.epsonreset.resources.run_waste_unchanged_body
import nl.rlabs.epsonreset.resources.run_waste_unchanged_title
import org.jetbrains.compose.resources.stringResource
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
        !result.success && restore -> stringResource(Res.string.run_title_restore_failed)
        !result.success -> stringResource(Res.string.run_title_reset_failed)
        completion.wasDryRun && restore -> stringResource(Res.string.run_title_restore_simulated)
        completion.wasDryRun -> stringResource(Res.string.run_title_dry_run_passed)
        restore -> stringResource(Res.string.run_title_snapshot_restored)
        else -> stringResource(Res.string.run_title_counters_reset)
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
                    stringResource(
                        Res.string.run_metadata,
                        result.writesAcknowledged,
                        result.writesTotal,
                        result.packetsSent,
                    ),
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
                                stringResource(Res.string.run_power_cycle_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusColors.warn,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.run_power_cycle_body),
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
                                stringResource(Res.string.run_waste_unchanged_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusColors.warn,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.run_waste_unchanged_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (stranded) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(Res.string.run_stranded_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recovery?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Res.string.run_stranded_backup, it.name),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.muted,
                            )
                        }
                    }

                    if (completion.wasDryRun) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(Res.string.run_dry_run_body),
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
                        ) { Text(stringResource(Res.string.run_choose_snapshot)) }
                        Spacer(Modifier.width(8.dp))
                    }

                    recovery?.let {
                        OutlinedButton(onClick = {
                            confirming = true
                        }) { Text(stringResource(Res.string.run_restore_this_backup)) }
                        Spacer(Modifier.width(8.dp))
                    }

                    Button(onClick = vm::dismissCompletion) { Text(stringResource(Res.string.run_ok)) }
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
            vm.bad(Strings.get(Res.string.run_backup_unreadable, file.name))
            onDismiss()
        }
        return
    }

    val printer = vm.selectedDevice?.device?.displayName ?: stringResource(Res.string.reset_confirm_the_printer)
    EepromWriteConfirmation(
        title = stringResource(Res.string.recovery_confirm_title, backup.model),
        headline = stringResource(Res.string.recovery_confirm_headline, backup.entries.size, printer),
        metadata = stringResource(Res.string.recovery_confirm_metadata, file.name, backup.takenAt),
        warning = stringResource(Res.string.recovery_confirm_warning),
        paragraphs = listOf(
            stringResource(Res.string.recovery_confirm_recovery_point),
            stringResource(Res.string.recovery_confirm_snapshot_first),
            stringResource(Res.string.reset_confirm_no_warranty),
        ),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            vm.dismissCompletion()
            vm.snapshot.restore(backup, file)
        },
        confirmLabel = stringResource(Res.string.confirm_restore_eeprom),
    )
}
