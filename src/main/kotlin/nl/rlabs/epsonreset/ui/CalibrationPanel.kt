package nl.rlabs.epsonreset.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import nl.rlabs.epsonreset.db.Calibration
import nl.rlabs.epsonreset.i18n.StatusText
import nl.rlabs.epsonreset.i18n.Strings
import nl.rlabs.epsonreset.i18n.counterName
import nl.rlabs.epsonreset.i18n.resolve
import nl.rlabs.epsonreset.i18n.resolveNow
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.cal_applied_label
import nl.rlabs.epsonreset.resources.cal_cannot_calibrate
import nl.rlabs.epsonreset.resources.cal_close
import nl.rlabs.epsonreset.resources.cal_confirms
import nl.rlabs.epsonreset.resources.cal_copy_entry
import nl.rlabs.epsonreset.resources.cal_copy_overlay
import nl.rlabs.epsonreset.resources.cal_copy_report
import nl.rlabs.epsonreset.resources.cal_count_label
import nl.rlabs.epsonreset.resources.cal_dialog_title
import nl.rlabs.epsonreset.resources.cal_disagrees
import nl.rlabs.epsonreset.resources.cal_fills_a_gap
import nl.rlabs.epsonreset.resources.cal_has_maximum
import nl.rlabs.epsonreset.resources.cal_intro
import nl.rlabs.epsonreset.resources.cal_limit_marker
import nl.rlabs.epsonreset.resources.cal_loose_decimals
import nl.rlabs.epsonreset.resources.cal_measure_title
import nl.rlabs.epsonreset.resources.cal_model_label
import nl.rlabs.epsonreset.resources.cal_model_placeholder
import nl.rlabs.epsonreset.resources.cal_models_with_limit
import nl.rlabs.epsonreset.resources.cal_no_candidates
import nl.rlabs.epsonreset.resources.cal_no_maximum
import nl.rlabs.epsonreset.resources.cal_no_model
import nl.rlabs.epsonreset.resources.cal_no_serial
import nl.rlabs.epsonreset.resources.cal_not_measured
import nl.rlabs.epsonreset.resources.cal_note_label
import nl.rlabs.epsonreset.resources.cal_note_placeholder
import nl.rlabs.epsonreset.resources.cal_nothing_yet
import nl.rlabs.epsonreset.resources.cal_open_issue
import nl.rlabs.epsonreset.resources.cal_percent_label
import nl.rlabs.epsonreset.resources.cal_percent_placeholder
import nl.rlabs.epsonreset.resources.cal_pick
import nl.rlabs.epsonreset.resources.cal_report_body
import nl.rlabs.epsonreset.resources.cal_report_contains
import nl.rlabs.epsonreset.resources.cal_report_ink
import nl.rlabs.epsonreset.resources.cal_report_no_ink
import nl.rlabs.epsonreset.resources.cal_result
import nl.rlabs.epsonreset.resources.cal_result_range
import nl.rlabs.epsonreset.resources.cal_service_required
import nl.rlabs.epsonreset.resources.cal_session_note
import nl.rlabs.epsonreset.resources.cal_siblings
import nl.rlabs.epsonreset.resources.cal_source_confirmed
import nl.rlabs.epsonreset.resources.cal_source_reported
import nl.rlabs.epsonreset.resources.cal_source_selected
import nl.rlabs.epsonreset.resources.cal_undo
import nl.rlabs.epsonreset.resources.cal_use_maximum
import org.jetbrains.compose.resources.stringResource

/** Where a printer's own maximum gets measured and reported. */
@Composable
fun CalibrationDialog(vm: ResetViewModel) {
    val calibration = vm.calibration
    if (!calibration.dialogOpen) return

    val copy = rememberClipboardCopy()
    val state = rememberDialogState(size = DpSize(740.dp, 780.dp))

    DialogWindow(
        onCloseRequest = { calibration.dialogOpen = false },
        state = state,
        title = stringResource(
            Res.string.cal_dialog_title,
            vm.selectedModel?.name ?: stringResource(Res.string.cal_no_model),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Header(vm)

                val blocked = calibration.blockedReason
                if (blocked != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(Res.string.cal_nothing_yet, blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.warn,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        calibration.dialogOpen = false
                    }) { Text(stringResource(Res.string.cal_close)) }
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
                    label = { Text(stringResource(Res.string.cal_note_label)) },
                    placeholder = { Text(stringResource(Res.string.cal_note_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Included(vm)

                Spacer(Modifier.height(14.dp))
                Actions(vm, copy)
            }
        }
    }
}

@Composable
private fun Header(vm: ResetViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.cal_measure_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Derived, and it moves: applying a measurement ticks this up, which is the clearest
            // possible statement of what one contribution is worth.
            vm.capabilitySummary?.let {
                Text(
                    stringResource(Res.string.cal_models_with_limit, it.withLimit, it.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.cal_intro),
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
                label = { Text(stringResource(Res.string.cal_model_label)) },
                placeholder = { Text(stringResource(Res.string.cal_model_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(10.dp))

            Box {
                OutlinedButton(onClick = { menu = true }, enabled = candidates.isNotEmpty()) {
                    Text(
                        if (candidates.isEmpty()) {
                            stringResource(Res.string.cal_no_candidates)
                        } else {
                            stringResource(Res.string.cal_pick)
                        },
                    )
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
                stringResource(Res.string.cal_siblings, siblings),
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
        if (vm.confirmedClass == null) {
            Strings.get(Res.string.cal_source_reported)
        } else {
            Strings.get(Res.string.cal_source_confirmed)
        }
    vm.selectedModel?.name -> Strings.get(Res.string.cal_source_selected)
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
                    counterName(spec.description).resolve(),
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
                stringResource(Res.string.cal_no_maximum)
            } else {
                stringResource(
                    Res.string.cal_has_maximum,
                    existing,
                    "%.2f".format(row.counter.percent ?: 0.0),
                )
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
                label = { Text(stringResource(Res.string.cal_percent_label)) },
                placeholder = { Text(stringResource(Res.string.cal_percent_placeholder)) },
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
                label = { Text(stringResource(Res.string.cal_count_label)) },
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
                stringResource(Res.string.cal_service_required),
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
            stringResource(Res.string.cal_not_measured),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )

        is Calibration.Outcome.Rejected -> Text(
            stringResource(Res.string.cal_cannot_calibrate, outcome.reason),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.warn,
        )

        is Calibration.Outcome.Ok -> {
            val m = outcome.measured
            Column {
                Text(
                    stringResource(
                        Res.string.cal_result,
                        m.max,
                        "%.2f%%".format(m.percent),
                        if (m.range.first == m.range.last) {
                            ""
                        } else {
                            stringResource(Res.string.cal_result_range, m.range.first, m.range.last)
                        },
                    ),
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
                            stringResource(Res.string.cal_fills_a_gap)

                        Calibration.Agreement.CONFIRMS ->
                            stringResource(Res.string.cal_confirms, m.existingMax.toString())

                        Calibration.Agreement.DISAGREES ->
                            stringResource(
                                Res.string.cal_disagrees,
                                m.existingMax.toString(),
                                "%.2f".format(m.existingPercent ?: 0.0),
                            )
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
                        stringResource(Res.string.cal_limit_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.warn,
                    )
                }
                if (m.isCoarse) {
                    Text(
                        stringResource(Res.string.cal_loose_decimals),
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
            stringResource(Res.string.cal_report_contains),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                Res.string.cal_report_body,
                if (ink.isEmpty()) {
                    stringResource(Res.string.cal_report_no_ink)
                } else {
                    stringResource(
                        Res.string.cal_report_ink,
                        ink.joinToString(", ") { "${StatusText.inkColour(it).resolveNow()} ${it.percent}%" },
                    )
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.cal_no_serial),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun Actions(vm: ResetViewModel, copy: (String) -> Unit) {
    val calibration = vm.calibration
    val enabled = calibration.canSubmit

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { calibration.openIssue(copy) },
                enabled = enabled,
            ) { Text(stringResource(Res.string.cal_open_issue)) }
            Spacer(Modifier.width(8.dp))
            // Named for what it changes, not for what it reveals: it replaces the divisor behind
            // every percentage on this model, and "Show it now" reads like a preview.
            OutlinedButton(onClick = { calibration.applyToSession() }, enabled = enabled) {
                Text(
                    if (calibration.applied) {
                        stringResource(Res.string.cal_applied_label)
                    } else {
                        stringResource(Res.string.cal_use_maximum)
                    },
                )
            }
            if (calibration.applied) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { calibration.revertSession() }) { Text(stringResource(Res.string.cal_undo)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { calibration.dialogOpen = false }) { Text(stringResource(Res.string.cal_close)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { copy(calibration.entry()) },
                enabled = enabled,
            ) { Text(stringResource(Res.string.cal_copy_entry)) }
            TextButton(
                onClick = { copy(calibration.overlay()) },
                enabled = enabled,
            ) { Text(stringResource(Res.string.cal_copy_overlay)) }
            TextButton(
                onClick = { copy(calibration.report()) },
                enabled = enabled,
            ) { Text(stringResource(Res.string.cal_copy_report)) }
        }

        if (calibration.applied) {
            Spacer(Modifier.height(6.dp))
            Hint(
                stringResource(Res.string.cal_session_note, nl.rlabs.epsonreset.AppPaths.counterOverlay),
                StatusColors.good,
            )
        }
    }
}

@Composable
private fun Hint(text: String, colour: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
}
