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
import nl.redlabs.epsonreset.i18n.Strings
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.reset_confirm_absorbed_ink
import nl.redlabs.epsonreset.resources.reset_confirm_addresses
import nl.redlabs.epsonreset.resources.reset_confirm_family_warning
import nl.redlabs.epsonreset.resources.reset_confirm_headline
import nl.redlabs.epsonreset.resources.reset_confirm_metadata
import nl.redlabs.epsonreset.resources.reset_confirm_no_warranty
import nl.redlabs.epsonreset.resources.reset_confirm_snapshot_first
import nl.redlabs.epsonreset.resources.reset_confirm_the_printer
import nl.redlabs.epsonreset.resources.reset_confirm_title
import nl.redlabs.epsonreset.resources.reset_controls_cancel
import nl.redlabs.epsonreset.resources.reset_controls_metadata
import nl.redlabs.epsonreset.resources.reset_controls_no_printer
import nl.redlabs.epsonreset.resources.reset_controls_none_body
import nl.redlabs.epsonreset.resources.reset_controls_none_title
import nl.redlabs.epsonreset.resources.reset_controls_pad_caveat
import nl.redlabs.epsonreset.resources.reset_controls_platen_body
import nl.redlabs.epsonreset.resources.reset_controls_platen_title
import nl.redlabs.epsonreset.resources.reset_controls_preview_heading
import nl.redlabs.epsonreset.resources.reset_controls_progress_heading
import nl.redlabs.epsonreset.resources.reset_controls_recovery_confirm
import nl.redlabs.epsonreset.resources.reset_controls_recovery_saved
import nl.redlabs.epsonreset.resources.reset_controls_recovery_title
import nl.redlabs.epsonreset.resources.reset_controls_restore_from_backup
import nl.redlabs.epsonreset.resources.reset_controls_save_then_reset
import nl.redlabs.epsonreset.resources.reset_controls_simulate
import nl.redlabs.epsonreset.resources.reset_controls_simulation_only
import nl.redlabs.epsonreset.resources.reset_controls_subtitle
import nl.redlabs.epsonreset.resources.reset_controls_title
import nl.redlabs.epsonreset.resources.reset_controls_write_back
import nl.redlabs.epsonreset.resources.run_backup_unreadable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
                stringResource(Res.string.reset_controls_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(
                    Res.string.reset_controls_metadata,
                    model.readKey,
                    model.writeLength,
                    "0x%X".format(model.memHigh),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusColors.muted,
            )
        }
        Text(
            stringResource(Res.string.reset_controls_subtitle, model.name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!model.hasResettableCounters) {
            Spacer(Modifier.height(12.dp))
            Callout(
                stringResource(Res.string.reset_controls_none_title),
                stringResource(Res.string.reset_controls_none_body),
                StatusColors.bad,
            )
            return@Column
        }

        if (model.isPlatenOnly) {
            Spacer(Modifier.height(12.dp))
            Callout(
                stringResource(Res.string.reset_controls_platen_title),
                stringResource(Res.string.reset_controls_platen_body),
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
                    if (vm.dryRun) {
                        stringResource(Res.string.reset_controls_preview_heading)
                    } else {
                        stringResource(Res.string.reset_controls_progress_heading)
                    },
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
                OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(Res.string.reset_controls_cancel)) }
            } else {
                SplitButton(
                    label = stringResource(Res.string.reset_controls_save_then_reset),
                    primaryEnabled = vm.canResetLive,
                    onPrimary = { onConfirmChange(true) },
                    container = StatusColors.bad,
                    onContainer = StatusColors.onBad,
                    actions = listOf(
                        SplitAction(
                            stringResource(Res.string.reset_controls_simulate),
                            enabled = vm.canSimulateReset,
                        ) { vm.run(simulate = true) },
                    ),
                    modifier = Modifier.width(300.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.reset_controls_pad_caveat),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.warn,
        )

        if (vm.selectedDevice == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.reset_controls_no_printer),
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
                stringResource(Res.string.reset_controls_simulation_only, it),
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
            stringResource(Res.string.reset_controls_recovery_title),
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.warn,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (confirming) {
                stringResource(Res.string.reset_controls_recovery_confirm)
            } else {
                stringResource(Res.string.reset_controls_recovery_saved, file.name)
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
                            ?: vm.bad(Strings.get(Res.string.run_backup_unreadable, file.name))
                        confirming = false
                    },
                    colors = cautionButtonColors(),
                ) { Text(stringResource(Res.string.reset_controls_write_back)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    confirming = false
                }) { Text(stringResource(Res.string.reset_controls_cancel)) }
            } else {
                OutlinedButton(onClick = {
                    confirming = true
                }) { Text(stringResource(Res.string.reset_controls_restore_from_backup)) }
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
    val printer = vm.selectedDevice?.device?.displayName ?: stringResource(Res.string.reset_confirm_the_printer)
    val addresses = pluralStringResource(Res.plurals.reset_confirm_addresses, model.writeCount, model.writeCount)
    EepromWriteConfirmation(
        title = stringResource(Res.string.reset_confirm_title, model.name),
        headline = stringResource(Res.string.reset_confirm_headline, model.name, addresses, printer),
        metadata = stringResource(Res.string.reset_confirm_metadata, model.readKey, model.writeCount),
        warning = vm.confirmedClass?.let {
            stringResource(Res.string.reset_confirm_family_warning, it, model.name)
        },
        paragraphs = listOf(
            stringResource(Res.string.reset_confirm_absorbed_ink),
            stringResource(Res.string.reset_confirm_snapshot_first),
            stringResource(Res.string.reset_confirm_no_warranty),
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
