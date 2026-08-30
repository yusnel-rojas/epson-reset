package nl.rlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.unit.dp
import nl.rlabs.epsonreset.backup.EepromBackup
import nl.rlabs.epsonreset.backup.SnapshotComparison
import nl.rlabs.epsonreset.i18n.resolve
import nl.rlabs.epsonreset.protocol.CounterReader
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.reset_confirm_no_warranty
import nl.rlabs.epsonreset.resources.reset_confirm_the_printer
import nl.rlabs.epsonreset.resources.snap_addr_count
import nl.rlabs.epsonreset.resources.snap_address_count
import nl.rlabs.epsonreset.resources.snap_all_at_reset_value
import nl.rlabs.epsonreset.resources.snap_back_to_saved
import nl.rlabs.epsonreset.resources.snap_cancel
import nl.rlabs.epsonreset.resources.snap_compare
import nl.rlabs.epsonreset.resources.snap_comparing
import nl.rlabs.epsonreset.resources.snap_create_blurb
import nl.rlabs.epsonreset.resources.snap_create_title
import nl.rlabs.epsonreset.resources.snap_differing
import nl.rlabs.epsonreset.resources.snap_file_unreadable
import nl.rlabs.epsonreset.resources.snap_invalid_file
import nl.rlabs.epsonreset.resources.snap_looking
import nl.rlabs.epsonreset.resources.snap_no_layout_known
import nl.rlabs.epsonreset.resources.snap_no_target
import nl.rlabs.epsonreset.resources.snap_none
import nl.rlabs.epsonreset.resources.snap_none_for_model
import nl.rlabs.epsonreset.resources.snap_none_selected
import nl.rlabs.epsonreset.resources.snap_none_yet
import nl.rlabs.epsonreset.resources.snap_not_at_reset_value
import nl.rlabs.epsonreset.resources.snap_not_read_yet
import nl.rlabs.epsonreset.resources.snap_nothing_would_change
import nl.rlabs.epsonreset.resources.snap_pick_one
import nl.rlabs.epsonreset.resources.snap_printer_now
import nl.rlabs.epsonreset.resources.snap_read_and_save
import nl.rlabs.epsonreset.resources.snap_reading
import nl.rlabs.epsonreset.resources.snap_refresh
import nl.rlabs.epsonreset.resources.snap_restore_confirm
import nl.rlabs.epsonreset.resources.snap_restore_explainer
import nl.rlabs.epsonreset.resources.snap_restore_headline
import nl.rlabs.epsonreset.resources.snap_restore_metadata
import nl.rlabs.epsonreset.resources.snap_restore_no_save
import nl.rlabs.epsonreset.resources.snap_restore_only_recovery
import nl.rlabs.epsonreset.resources.snap_restore_saves_first
import nl.rlabs.epsonreset.resources.snap_restore_title
import nl.rlabs.epsonreset.resources.snap_restore_warning
import nl.rlabs.epsonreset.resources.snap_restore_without_saving
import nl.rlabs.epsonreset.resources.snap_save_then_restore
import nl.rlabs.epsonreset.resources.snap_saved_count
import nl.rlabs.epsonreset.resources.snap_saved_count_filtered
import nl.rlabs.epsonreset.resources.snap_select_model
import nl.rlabs.epsonreset.resources.snap_selected_model_label
import nl.rlabs.epsonreset.resources.snap_serial
import nl.rlabs.epsonreset.resources.snap_serial_not_recorded
import nl.rlabs.epsonreset.resources.snap_show_all
import nl.rlabs.epsonreset.resources.snap_showing_filter
import nl.rlabs.epsonreset.resources.snap_showing_the_write
import nl.rlabs.epsonreset.resources.snap_simulate_restore
import nl.rlabs.epsonreset.resources.snap_simulating_write
import nl.rlabs.epsonreset.resources.snap_stop_comparing
import nl.rlabs.epsonreset.resources.snap_title
import nl.rlabs.epsonreset.resources.snap_unexplained_moved
import nl.rlabs.epsonreset.resources.snap_unexplained_note
import nl.rlabs.epsonreset.resources.snap_unreadable
import nl.rlabs.epsonreset.resources.snap_what_would_change
import nl.rlabs.epsonreset.resources.snap_would_be_saved_as
import nl.rlabs.epsonreset.resources.snap_writing_back
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Every snapshot on disk, and what each one holds. */
@Composable
fun SnapshotPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    // Entering the tab is the moment the list can be stale — a run may have written one since.
    LaunchedEffect(Unit) { vm.snapshot.refreshSnapshots() }

    Row(modifier) {
        SnapshotList(vm, Modifier.width(380.dp).fillMaxHeight())
        VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SnapshotDetail(vm, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SnapshotList(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val visible = vm.snapshot.visibleSnapshots
    val filter = vm.snapshot.modelFilter

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.snap_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { vm.snapshot.refreshSnapshots() },
                enabled = !vm.snapshot.loadingSnapshots,
            ) { Text(stringResource(Res.string.snap_refresh)) }
        }

        Spacer(Modifier.height(10.dp))
        CreateSnapshotControl(vm)

        Spacer(Modifier.height(10.dp))

        if (filter != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.snap_showing_filter, filter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    vm.snapshot.showAllSnapshots()
                }) { Text(stringResource(Res.string.snap_show_all)) }
            }
            Spacer(Modifier.height(4.dp))
        }

        Text(
            if (filter == null) {
                stringResource(Res.string.snap_saved_count, vm.snapshot.snapshots.size, vm.snapshot.snapshotDir)
            } else {
                stringResource(
                    Res.string.snap_saved_count_filtered,
                    visible.size,
                    filter,
                    vm.snapshot.snapshots.size,
                    vm.snapshot.snapshotDir,
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )

        Spacer(Modifier.height(10.dp))

        if (visible.isEmpty()) {
            Text(
                if (vm.snapshot.loadingSnapshots) {
                    stringResource(Res.string.snap_looking)
                } else if (filter != null) {
                    stringResource(Res.string.snap_none_for_model, filter)
                } else {
                    stringResource(Res.string.snap_none_yet)
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        LazyColumn {
            items(visible) { snapshot ->
                SnapshotRow(
                    snapshot = snapshot,
                    selected = vm.snapshot.selectedSnapshot?.file == snapshot.file,
                    onClick = { vm.snapshot.selectSnapshot(snapshot) },
                )
            }
        }
    }
}

/** A fresh read from the shared printer-and-model target, saved without visiting Reset. */
@Composable
private fun CreateSnapshotControl(vm: ResetViewModel) {
    val device = vm.selectedDevice?.device
    val model = vm.selectedModel
    val blocked = vm.snapshot.createSnapshotBlockedReason

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.snap_create_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(device?.displayName, model?.name).joinToString(" · ")
                        .ifBlank { stringResource(Res.string.snap_no_target) },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blocked == null && vm.modelMismatch == null) {
                        StatusColors.good
                    } else {
                        StatusColors.warn
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.snapshot.readAndSaveSnapshot() },
                enabled = vm.snapshot.canCreateSnapshot,
            ) {
                Text(stringResource(if (vm.reading) Res.string.snap_reading else Res.string.snap_read_and_save))
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            blocked ?: stringResource(Res.string.snap_create_blurb),
            style = MaterialTheme.typography.labelSmall,
            color = if (blocked == null) StatusColors.muted else StatusColors.warn,
        )

        // Reading is how a disagreement about the model gets settled, so it is said here rather
        // than used to disable the button. The saved file is labelled with the selected model,
        // and the restore gate is where that has to match.
        if (blocked == null) {
            vm.modelMismatch?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.snap_would_be_saved_as, it, model?.name.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.warn,
                )
            }
        }
    }
}

@Composable
private fun SnapshotRow(snapshot: SnapshotState.SavedSnapshot, selected: Boolean, onClick: () -> Unit) {
    val backup = snapshot.backup

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                backup?.model ?: snapshot.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (backup == null) StatusColors.bad else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            backup?.let {
                Text(
                    stringResource(Res.string.snap_addr_count, it.entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }

        Text(
            backup?.takenAt ?: stringResource(Res.string.snap_unreadable),
            style = MaterialTheme.typography.labelSmall,
            color = if (backup == null) StatusColors.bad else StatusColors.muted,
        )
    }
}

@Composable
private fun SnapshotDetail(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val snapshot = vm.snapshot.selectedSnapshot
    val backup = snapshot?.backup

    if (backup == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(440.dp),
            ) {
                Text(
                    if (snapshot == null) {
                        stringResource(Res.string.snap_none_selected)
                    } else {
                        stringResource(Res.string.snap_file_unreadable)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (snapshot == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        StatusColors.bad
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (snapshot == null) {
                        stringResource(Res.string.snap_pick_one)
                    } else {
                        stringResource(Res.string.snap_invalid_file, snapshot.file.name)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.muted,
                )
            }
        }
        return
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        SnapshotHeader(vm, snapshot.file.name, backup)

        // Nothing about the running restore is rendered here: the status bar carries progress for
        // every operation, and the completion dialog says what it did, once.

        // Three views of the same snapshot, one at a time. Each shows both sides of what it is
        // about, so stacking them would put the same bytes on screen twice in layouts that differ
        // only in which sample they came from — the arrangement most likely to be misread.
        val restore = vm.snapshot.liveRestore
        val comparison = vm.snapshot.comparison
        val counters = vm.snapshot.snapshotCounters

        Spacer(Modifier.height(16.dp))
        when {
            restore != null -> RestoreWrite(restore, vm.snapshot.restoreSimulated)

            comparison != null -> ComparisonResult(comparison)

            else -> {
                vm.snapshot.snapshotReport?.let { report ->
                    CounterOverview(counters = counters, report = report)
                }
                if (counters.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(Res.string.snap_no_layout_known, backup.model),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted,
                    )
                }
            }
        }

        // The prose the two actions need, kept below the thing the tab is for rather than in front
        // of it. Neither action is reached by reading down to it — both are in the header.
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(Res.string.snap_restore_explainer),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

/**
 * The snapshot as a write: the printer's bytes on the left, the saved bytes as the target, and the
 * per-address progress of a running restore. The same table a reset draws, handed the snapshot's
 * targets instead of the model's reset values.
 */
@Composable
private fun RestoreWrite(restore: SnapshotState.LiveRestore, dryRun: Boolean) {
    Column {
        Text(
            when {
                restore.running && dryRun -> stringResource(Res.string.snap_simulating_write)
                restore.running -> stringResource(Res.string.snap_writing_back)
                else -> stringResource(Res.string.snap_what_would_change)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (restore.running) StatusColors.warn else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                !restore.haveCurrent ->
                    stringResource(Res.string.snap_not_read_yet)

                restore.differing == 0 ->
                    stringResource(Res.string.snap_nothing_would_change, restore.comparable)

                else ->
                    stringResource(Res.string.snap_differing, restore.differing, restore.comparable)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !restore.haveCurrent -> StatusColors.warn
                restore.differing == 0 -> StatusColors.good
                else -> StatusColors.muted
            },
        )

        Spacer(Modifier.height(10.dp))
        CounterOverview(
            counters = restore.counters,
            report = restore.report,
            plan = restore.plan,
            showError = false,
        )
    }
}

/** The two things that can be done to the selected snapshot, on one row. */
@Composable
private fun SnapshotActions(vm: ResetViewModel, backup: EepromBackup) {
    // Null while nothing is being confirmed; otherwise whether that restore saves the printer's
    // current bytes first.
    var confirming by remember(backup) { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val running = vm.runState is ResetViewModel.RunState.Running
    val restoreBlocked = vm.snapshot.snapshotRestoreBlockedReason
    val candidates = vm.snapshot.compareCandidates
    val comparing = vm.snapshot.compareTarget != SnapshotState.CompareTarget.None
    val previewing = vm.snapshot.showRestorePlan
    val compareBlocked = vm.snapshot.compareReadBlockedReason

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(Res.string.snap_cancel)) }

                // This tab decides for itself whether a restore is real, rather than inheriting the
                // Maintenance tab's Dry run switch. That switch is about resetting counters, it is
                // not visible from here, and it silently decided whether this write took a safety
                // net — which is exactly the thing that must never depend on somewhere you aren't
                // looking. Simulating is still one click away, just chosen on purpose.
                //
                // Saving first stays the primary action because it is the one that leaves a way
                // back. Skipping it is named for what it skips.
                else -> SplitButton(
                    label = stringResource(Res.string.snap_save_then_restore),
                    primaryEnabled = restoreBlocked == null,
                    onPrimary = { confirming = true },
                    actions = listOf(
                        SplitAction(
                            stringResource(Res.string.snap_restore_without_saving),
                            enabled = restoreBlocked == null,
                        ) { confirming = false },
                        SplitAction(
                            stringResource(Res.string.snap_simulate_restore),
                            enabled = restoreBlocked == null,
                        ) { vm.snapshot.restoreSelectedSnapshot(saveFirst = false, simulate = true) },
                    ),
                    modifier = Modifier.width(280.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = candidates.isNotEmpty() || vm.snapshot.canReadForComparison ||
                        comparing || previewing || vm.snapshot.canPreviewRestore,
                ) {
                    Text(
                        when {
                            previewing -> stringResource(Res.string.snap_showing_the_write)
                            comparing -> stringResource(Res.string.snap_comparing)
                            else -> stringResource(Res.string.snap_compare)
                        },
                    )
                }

                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (vm.reading) {
                                    stringResource(Res.string.snap_reading)
                                } else {
                                    stringResource(Res.string.snap_printer_now)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        enabled = vm.snapshot.canReadForComparison,
                        onClick = {
                            menuOpen = false
                            vm.snapshot.readForComparison()
                        },
                    )

                    for (candidate in candidates) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        candidate.backup?.takenAt ?: candidate.file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        candidate.file.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = StatusColors.muted,
                                    )
                                }
                            },
                            onClick = {
                                menuOpen = false
                                vm.snapshot.compareWithSnapshot(candidate.file)
                            },
                        )
                    }

                    // Not a comparison: a comparison is two samples in time order, and the current
                    // reading is always the later one, so it can never sit on the left. This is the
                    // other direction — the write a restore would make, current byte to saved byte.
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(Res.string.snap_what_would_change),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        enabled = vm.snapshot.canPreviewRestore && !previewing,
                        onClick = {
                            menuOpen = false
                            vm.snapshot.previewRestore()
                        },
                    )

                    if (comparing || previewing) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (previewing) {
                                        stringResource(Res.string.snap_back_to_saved)
                                    } else {
                                        stringResource(Res.string.snap_stop_comparing)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (previewing) vm.snapshot.clearRestorePlan() else vm.snapshot.clearComparison()
                            },
                        )
                    }
                }
            }
        }

        confirming?.let { saveFirst ->
            RestoreConfirmation(
                vm = vm,
                backup = backup,
                saveFirst = saveFirst,
                onDismiss = { confirming = null },
                onConfirm = {
                    confirming = null
                    vm.snapshot.restoreSelectedSnapshot(saveFirst, simulate = false)
                },
            )
        }

        restoreBlocked?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }

        // Only when the user has no offline option either. With another snapshot to hand, the read
        // is one of two ways in and its unavailability is not worth a line.
        if (compareBlocked != null && candidates.isEmpty() && compareBlocked != restoreBlocked) {
            Spacer(Modifier.height(8.dp))
            Text(compareBlocked, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }
    }
}

/** What moved between the two samples: the counters first, then the bytes underneath them. */
@Composable
private fun ComparisonResult(result: SnapshotComparison.Result) {
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            Text(
                "${result.before.label}  →  ${result.after.label}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${result.before.takenAt}  →  ${result.after.takenAt}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                result.summary.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = if (result.identical) {
                    StatusColors.muted
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (result.afterIsAtResetValue) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.snap_all_at_reset_value),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.good,
                )
            }

            for (note in result.notes) {
                Spacer(Modifier.height(8.dp))
                Text(note.resolve(), style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
            }
        }

        if (result.unexplained.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            UnexplainedChanges(result.unexplained)
        }

        Spacer(Modifier.height(12.dp))
        CounterOverview(
            counters = result.counters.map { delta ->
                CounterReader.DecodedCounter(delta.spec, delta.after, delta.afterBytes)
            },
            report = CounterReader.Report(result.after.model, result.after.readings),
            before = CounterReader.Report(result.before.model, result.before.readings),
        )
    }
}

/** Addresses that moved but belong to no counter in the layout. */
@Composable
private fun UnexplainedChanges(changes: List<SnapshotComparison.ByteDelta>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(StatusColors.warn.copy(alpha = 0.08f))
            .border(1.dp, StatusColors.warn.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            pluralStringResource(Res.plurals.snap_unexplained_moved, changes.size, changes.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = StatusColors.warn,
        )

        Spacer(Modifier.height(8.dp))

        for (c in changes) {
            Text(
                "addr %-5d %s   %s".format(c.address, c.transition, c.group),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.snap_unexplained_note),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun SnapshotHeader(vm: ResetViewModel, fileName: String, backup: EepromBackup) {
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
                backup.model,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                fileName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusColors.muted,
            )
        }

        Spacer(Modifier.height(6.dp))

        // One line rather than four labelled rows: this is the provenance of what is shown below,
        // not the subject of the screen.
        Text(
            listOf(
                backup.takenAt,
                stringResource(
                    Res.string.snap_serial,
                    backup.printerSerial ?: stringResource(Res.string.snap_serial_not_recorded),
                ),
                pluralStringResource(Res.plurals.snap_address_count, backup.entries.size, backup.entries.size),
                stringResource(Res.string.snap_not_at_reset_value, backup.changedByReset),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.muted,
        )

        // The application-wide target decides which write key a restore would use, so it belongs
        // here even though the snapshot keeps its own recorded model.
        val selected = vm.selectedModel
        if (selected == null || !selected.name.equals(backup.model, ignoreCase = true)) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        Res.string.snap_selected_model_label,
                        selected?.name ?: stringResource(Res.string.snap_none),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.warn,
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { vm.snapshot.useSnapshotModel() }) {
                    Text(stringResource(Res.string.snap_select_model, backup.model))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SnapshotActions(vm, backup)
    }
}

@Composable
private fun RestoreConfirmation(
    vm: ResetViewModel,
    backup: EepromBackup,
    saveFirst: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val printer = vm.selectedDevice?.device?.displayName ?: stringResource(Res.string.reset_confirm_the_printer)
    EepromWriteConfirmation(
        title = stringResource(Res.string.snap_restore_title, backup.model),
        headline = stringResource(Res.string.snap_restore_headline, backup.entries.size, printer),
        metadata = stringResource(Res.string.snap_restore_metadata, backup.model, backup.takenAt),
        warning = stringResource(Res.string.snap_restore_warning),
        paragraphs = listOf(
            stringResource(Res.string.snap_restore_only_recovery),
            if (saveFirst) {
                stringResource(Res.string.snap_restore_saves_first)
            } else {
                stringResource(Res.string.snap_restore_no_save)
            },
            stringResource(Res.string.reset_confirm_no_warranty),
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = stringResource(Res.string.snap_restore_confirm),
    )
}
