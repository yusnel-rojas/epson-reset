package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import nl.redlabs.epsonreset.db.Calibration

/** Where a printer's own maximum gets measured and reported. */
@Composable
fun CalibrationDialog(vm: ResetViewModel) {
    val calibration = vm.calibration
    if (!calibration.dialogOpen) return

    val clipboard = LocalClipboardManager.current
    val state = rememberDialogState(size = DpSize(740.dp, 780.dp))

    DialogWindow(
        onCloseRequest = { calibration.dialogOpen = false },
        state = state,
        title = "Contribute a calibration — ${vm.selectedModel?.name ?: "no model"}",
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Header(vm)

                val blocked = calibration.blockedReason
                if (blocked != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Nothing to calibrate yet — $blocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.warn,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { calibration.dialogOpen = false }) { Text("Close") }
                    return@Column
                }

                Spacer(Modifier.height(16.dp))
                ModelField(vm)

                Spacer(Modifier.height(16.dp))
                for (row in calibration.rows) {
                    CounterRow(calibration, row)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = calibration.note,
                    onValueChange = { calibration.note = it },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Which tool reported the percentage, what the printer's panel said…") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Included(vm)

                Spacer(Modifier.height(14.dp))
                Actions(vm, clipboard::setText)
            }
        }
    }
}

@Composable
private fun Header(vm: ResetViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Measure a counter maximum",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Derived, and it moves: applying a measurement ticks this up, which is the clearest
            // possible statement of what one contribution is worth.
            vm.capabilitySummary?.let {
                Text(
                    "${it.withLimit} of ${it.total} models can show a percentage",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "A percentage needs a maximum, and Epson publishes none — every one this app knows was " +
                "measured off a printer. Yours can supply one: say what a counter reads at a moment " +
                "its true percentage is known, and the maximum follows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which model this measurement is about — the field that decides what the submission is worth. */
@Composable
private fun ModelField(vm: ResetViewModel) {
    var menu by remember { mutableStateOf(false) }
    val calibration = vm.calibration
    val candidates = calibration.modelCandidates
    val siblings = calibration.layoutSiblings.size

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = calibration.model,
                onValueChange = { calibration.model = it },
                label = { Text("The model this printer actually is") },
                placeholder = { Text("ET-2825") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(10.dp))

            Box {
                OutlinedButton(onClick = { menu = true }, enabled = candidates.isNotEmpty()) {
                    Text(if (candidates.isEmpty()) "No candidates" else "Pick…")
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    for (name in candidates) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, style = MaterialTheme.typography.bodySmall)
                                    label(vm, name)?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusColors.muted,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                calibration.model = name
                                menu = false
                            },
                        )
                    }
                }
            }
        }

        calibration.modelWarning?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }

        if (siblings > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$siblings models share this counter layout. The entry claims only the one named " +
                    "above — a shared layout is not proof of a shared pad capacity, and if a " +
                    "sibling ever measures differently, these names are what let the group be split.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
    }
}

/** Why a candidate is in the list, when there is a reason worth stating. */
private fun label(vm: ResetViewModel, name: String): String? = when (name) {
    // Same distinction the form's warning draws: a name the firmware gave carries more than one
    // the user supplied, and the two must not be captioned alike.
    vm.identifiedModel?.name ->
        if (vm.confirmedClass == null) "reported by the printer" else "confirmed by you"
    vm.selectedModel?.name -> "selected in the target"
    else -> null
}

/**
 * One counter: what the app knows about it today, what you can say about it, and what that implies.
 */
@Composable
private fun CounterRow(calibration: CalibrationState, row: CalibrationState.Row) {
    val spec = row.counter.spec
    val input = calibration.input(spec.addresses)

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.counter.display,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(90.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "addr ${spec.addresses.joinToString(",")}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        val existing = spec.max
        Text(
            if (existing == null) {
                "This app has no maximum for this counter, so it shows no percentage. That is what " +
                    "is missing."
            } else {
                "This app has max $existing, so it shows %.2f%%. Measuring it again is still worth "
                    .format(row.counter.percent ?: 0.0) +
                    "doing — that figure came from one printer."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (existing == null) StatusColors.warn else StatusColors.muted,
        )

        Spacer(Modifier.height(8.dp))

        Row {
            OutlinedTextField(
                value = input.percent,
                onValueChange = { calibration.setPercent(spec.addresses, it) },
                enabled = !input.serviceRequired,
                label = { Text("Percentage another tool shows") },
                placeholder = { Text("60.90") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // Checkable, and worth checking: a tool reading a different count is reading a
            // different counter, and its percentage would calibrate the wrong thing.
            OutlinedTextField(
                value = input.reportedValue,
                onValueChange = { calibration.setReportedValue(spec.addresses, it) },
                enabled = !input.serviceRequired,
                label = { Text("…and the count it shows") },
                placeholder = { Text(row.counter.value?.toString() ?: "") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = input.serviceRequired,
                onCheckedChange = { calibration.setServiceRequired(spec.addresses, it) },
            )
            Text(
                "…or the printer is saying service required right now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        Outcome(row.outcome)
    }
}

@Composable
private fun Outcome(outcome: Calibration.Outcome?) {
    when (outcome) {
        null -> Text(
            "Not measured — leave it blank if you have nothing to say about this counter.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )

        is Calibration.Outcome.Rejected -> Text(
            "Cannot calibrate this counter — ${outcome.reason}.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.warn,
        )

        is Calibration.Outcome.Ok -> {
            val m = outcome.measured
            Column {
                Text(
                    "max ${m.max}  ·  shows %.2f%%".format(m.percent) +
                        if (m.range.first == m.range.last) {
                            ""
                        } else {
                            "  ·  consistent with ${m.range.first}–${m.range.last}"
                        },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = when (m.agreement) {
                        Calibration.Agreement.DISAGREES -> StatusColors.bad
                        else -> StatusColors.good
                    },
                )
                Text(
                    when (m.agreement) {
                        Calibration.Agreement.FILLS_A_GAP ->
                            "New — this model had no maximum for this counter."
                        Calibration.Agreement.CONFIRMS ->
                            "Confirms the ${m.existingMax} already on file. Worth filing anyway: " +
                                "that figure rests on one printer, and this is a second."
                        Calibration.Agreement.DISAGREES ->
                            "Disagrees with the ${m.existingMax} already on file, which would show " +
                                "%.2f%%. Both came from one printer each, so this is a finding — "
                                    .format(m.existingPercent ?: 0.0) +
                                "file it rather than assuming either is wrong."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (m.agreement) {
                        Calibration.Agreement.DISAGREES -> StatusColors.bad
                        Calibration.Agreement.FILLS_A_GAP -> StatusColors.good
                        Calibration.Agreement.CONFIRMS -> StatusColors.muted
                    },
                )
                if (m.looksLikeLimitByte) {
                    Text(
                        "This byte reads 94 (0x5E), which is Epson's limit marker rather than a " +
                            "count — those read 94 always. Worth checking it really is a counter " +
                            "before filing this.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.warn,
                    )
                }
                if (m.isCoarse) {
                    Text(
                        "That percentage was given to few decimals, so it brackets the maximum " +
                            "loosely. Type every digit the other tool shows.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.warn,
                    )
                }
            }
        }
    }
}

/** Exactly what leaves the machine, listed rather than described. */
@Composable
private fun Included(vm: ResetViewModel) {
    val ink = vm.status?.inkLevels.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            "What the report contains",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("Model, firmware and connection; the counters above with their values and ")
                append("what this app currently believes about each; ")
                append(
                    if (ink.isEmpty()) {
                        "no ink levels (this printer reported none); "
                    } else {
                        "ink levels (" + ink.joinToString(", ") { "${it.colour} ${it.percent}%" } + "); "
                    },
                )
                append("and the printer's raw status block, undecoded.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Not the serial number. A pad capacity is a model constant, so the serial would " +
                "identify your printer in a public issue while adding nothing. Nothing is sent on " +
                "its own either: \"Open an issue\" fills in a form in your browser for you to read " +
                "and submit.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun Actions(vm: ResetViewModel, copy: (AnnotatedString) -> Unit) {
    val calibration = vm.calibration
    val enabled = calibration.canSubmit

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { calibration.openIssue { copy(AnnotatedString(it)) } },
                enabled = enabled,
            ) { Text("Open an issue") }
            Spacer(Modifier.width(8.dp))
            // Named for what it changes, not for what it reveals: it replaces the divisor behind
            // every percentage on this model, and "Show it now" reads like a preview.
            OutlinedButton(onClick = { calibration.applyToSession() }, enabled = enabled) {
                Text(if (calibration.applied) "Applied" else "Use this maximum now")
            }
            if (calibration.applied) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { calibration.revertSession() }) { Text("Undo") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { calibration.dialogOpen = false }) { Text("Close") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { copy(AnnotatedString(calibration.entry())) },
                enabled = enabled,
            ) { Text("Copy entry") }
            TextButton(
                onClick = { copy(AnnotatedString(calibration.overlay())) },
                enabled = enabled,
            ) { Text("Copy overlay") }
            TextButton(
                onClick = { copy(AnnotatedString(calibration.report())) },
                enabled = enabled,
            ) { Text("Copy report") }
        }

        if (calibration.applied) {
            Spacer(Modifier.height(6.dp))
            Hint(
                "Every percentage for this model is now divided by the maximum above, for this " +
                    "session only — Undo puts it back, and so does restarting. To keep it, save " +
                    "the overlay as counters-overlay.json in the data directory " +
                    "(${nl.redlabs.epsonreset.AppPaths.counterOverlay}); that one survives a " +
                    "restart and is undone only by deleting the file, which Settings can do. " +
                    "And file the issue, so nobody with this model has to measure it again.",
                StatusColors.good,
            )
        }
    }
}

@Composable
private fun Hint(text: String, colour: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
}
