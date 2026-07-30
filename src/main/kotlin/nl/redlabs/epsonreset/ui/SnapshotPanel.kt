package nl.redlabs.epsonreset.ui

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
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.backup.SnapshotComparison
import nl.redlabs.epsonreset.protocol.CounterReader

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
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Snapshots",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { vm.snapshot.refreshSnapshots() },
                enabled = !vm.snapshot.loadingSnapshots,
            ) { Text("Refresh") }
        }

        Spacer(Modifier.height(10.dp))
        CreateSnapshotControl(vm)

        Spacer(Modifier.height(10.dp))

        Text(
            "${vm.snapshot.snapshots.size} saved · ${vm.snapshot.snapshotDir}",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )

        Spacer(Modifier.height(10.dp))

        if (vm.snapshot.snapshots.isEmpty()) {
            Text(
                if (vm.snapshot.loadingSnapshots) {
                    "Looking…"
                } else {
                    "Nothing saved yet. Use Read & save above — or run a live reset, which " +
                        "takes one for itself before it writes anything."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        LazyColumn {
            items(vm.snapshot.snapshots) { snapshot ->
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
                    "Create snapshot",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(device?.displayName, model?.name).joinToString(" · ")
                        .ifBlank { "No target selected" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blocked == null) StatusColors.good else StatusColors.warn,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.snapshot.readAndSaveSnapshot() },
                enabled = vm.snapshot.canCreateSnapshot,
            ) { Text(if (vm.reading) "Reading…" else "Read & save") }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            blocked ?: "Takes a fresh read from the printer and saves the reset/recovery " +
                "addresses. Nothing is written.",
            style = MaterialTheme.typography.labelSmall,
            color = if (blocked == null) StatusColors.muted else StatusColors.warn,
        )
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
                    "${it.entries.size} addr",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }

        Text(
            backup?.takenAt ?: "unreadable — malformed, or a byte outside 0..255",
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
                    if (snapshot == null) "No snapshot selected" else "This file could not be read",
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
                        "Pick one on the left to read it back. The bytes are in the file, so " +
                            "nothing is asked of a printer — this works with nothing plugged in."
                    } else {
                        "${snapshot.file.name} is not a valid snapshot. Loading rejects any byte " +
                            "outside 0..255, so a corrupt file fails here rather than at the printer."
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

        Spacer(Modifier.height(16.dp))
        RestoreControls(vm, backup)

        Spacer(Modifier.height(16.dp))
        CompareControls(vm)

        // The comparison replaces the single-sample view rather than sitting under it: it shows
        // both sides already, and two tables of the same bytes differing only in which sample they
        // came from is the layout most likely to be misread.
        val comparison = vm.snapshot.comparison
        if (comparison != null) {
            Spacer(Modifier.height(16.dp))
            ComparisonResult(comparison)
            return@Column
        }

        val counters = vm.snapshot.snapshotCounters
        vm.snapshot.snapshotReport?.let { report ->
            Spacer(Modifier.height(16.dp))
            CounterOverview(counters = counters, report = report)
        }

        if (counters.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "No counter layout is known for ${backup.model}, so the saved bytes are shown " +
                    "as bytes. The table above is complete either way — the layout only decides " +
                    "which of them are one number.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
    }
}

/** Choosing what to compare this snapshot against. */
@Composable
private fun CompareControls(vm: ResetViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    val candidates = vm.snapshot.compareCandidates
    val active = vm.snapshot.compareTarget != SnapshotState.CompareTarget.None
    val blocked = vm.snapshot.compareReadBlockedReason

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text("Compare", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = candidates.isNotEmpty(),
                ) {
                    Text(
                        if (candidates.isEmpty()) {
                            "No other snapshot of this model"
                        } else {
                            "Another snapshot (${candidates.size})"
                        },
                    )
                }

                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
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
                }
            }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = { vm.snapshot.readForComparison() },
                enabled = vm.snapshot.canReadForComparison,
            ) { Text(if (vm.reading) "Reading…" else "Read the printer now") }

            if (active) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.snapshot.clearComparison() }) { Text("Clear") }
            }
        }

        // Only when the user has no offline option either. With another snapshot to hand, the read
        // is one of two ways in and its unavailability is not worth a paragraph.
        if (blocked != null && candidates.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(blocked, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Two snapshots taken either side of a known amount of printing show which addresses " +
                "actually move, and by how much. Comparing against the printer as it is now says " +
                "whether a reset held.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
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
                result.summary,
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
                    "Every address on the later side holds its reset value — if a reset ran between " +
                        "these two samples, it held.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.good,
                )
            }

            for (note in result.notes) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
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
            "${changes.size} address(es) moved that no counter claims",
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
            "The printer maintains something here that this model's counter layout does not " +
                "describe. Worth reporting with both snapshots — what it counts cannot be " +
                "told from two readings, so nothing is assumed about it.",
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

        Spacer(Modifier.height(12.dp))

        Field("Taken", backup.takenAt)
        Field("Serial", backup.printerSerial ?: "not recorded")
        Field("Addresses", "${backup.entries.size}")
        Field("Differ", "${backup.changedByReset} are not at their reset value")

        // The application-wide target decides which write key a restore would use, so it belongs
        // here even though the snapshot keeps its own recorded model.
        val selected = vm.selectedModel
        if (selected == null || !selected.name.equals(backup.model, ignoreCase = true)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Selected model: ${selected?.name ?: "none"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.warn,
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { vm.snapshot.useSnapshotModel() }) {
                    Text("Select ${backup.model}")
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.muted,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Writing a snapshot back, confirmed in two steps. */
@Composable
private fun RestoreControls(vm: ResetViewModel, backup: EepromBackup) {
    var confirming by remember(backup) { mutableStateOf(false) }
    val running = vm.runState is ResetViewModel.RunState.Running
    val blocked = vm.snapshot.snapshotRestoreBlockedReason

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }

                vm.dryRun -> Button(
                    onClick = { vm.snapshot.restoreSelectedSnapshot() },
                    enabled = blocked == null,
                ) { Text("Simulate restore") }

                else -> Button(
                    onClick = { confirming = true },
                    enabled = blocked == null,
                ) { Text("Restore to printer") }
            }

            Spacer(Modifier.width(12.dp))
            Text(
                if (vm.dryRun) "Dry run — nothing reaches the printer" else "Live",
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.dryRun) StatusColors.muted else StatusColors.bad,
            )
        }

        if (confirming && !vm.dryRun) {
            RestoreConfirmation(
                vm = vm,
                backup = backup,
                onDismiss = { confirming = false },
                onConfirm = {
                    confirming = false
                    vm.snapshot.restoreSelectedSnapshot()
                },
            )
        }

        blocked?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "A restore puts these bytes back at the addresses they came from — waste levels " +
                "included. It is recovery from a half-finished run, not an undo for a successful one.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun RestoreConfirmation(
    vm: ResetViewModel,
    backup: EepromBackup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val printer = vm.selectedDevice?.device?.displayName ?: "the printer"
    EepromWriteConfirmation(
        title = "Restore EEPROM — ${backup.model}",
        headline = "Write ${backup.entries.size} saved EEPROM bytes into $printer.",
        metadata = "${backup.model} · snapshot ${backup.takenAt}",
        warning = "These are historical values. They can increase counters and replace newer printer data.",
        paragraphs = listOf(
            "Use this only to recover from an interrupted reset or restore. It is not an undo for " +
                "a successful reset.",
            "The restore does not first save the values currently in the printer. Continue only " +
                "if this snapshot is the recovery point you intend to apply.",
            "Whether to do this is your decision, and what follows from it is yours to carry: this " +
                "software comes with no warranty, and its authors are not accountable for what " +
                "happens to your printer.",
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = "Yes, restore EEPROM",
    )
}
