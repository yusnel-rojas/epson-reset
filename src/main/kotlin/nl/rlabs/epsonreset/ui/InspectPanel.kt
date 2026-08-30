package nl.rlabs.epsonreset.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.rlabs.epsonreset.probe.SweepAnalysis
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.inspect_bytes
import nl.rlabs.epsonreset.resources.inspect_cancel
import nl.rlabs.epsonreset.resources.inspect_copy_overlay
import nl.rlabs.epsonreset.resources.inspect_copy_report
import nl.rlabs.epsonreset.resources.inspect_intro
import nl.rlabs.epsonreset.resources.inspect_like_models
import nl.rlabs.epsonreset.resources.inspect_model_name
import nl.rlabs.epsonreset.resources.inspect_no_known_key
import nl.rlabs.epsonreset.resources.inspect_no_printer
import nl.rlabs.epsonreset.resources.inspect_nothing_looked_like
import nl.rlabs.epsonreset.resources.inspect_probe_hits
import nl.rlabs.epsonreset.resources.inspect_run_sweep_first
import nl.rlabs.epsonreset.resources.inspect_save_overlay
import nl.rlabs.epsonreset.resources.inspect_several_keys
import nl.rlabs.epsonreset.resources.inspect_step_candidates_blurb
import nl.rlabs.epsonreset.resources.inspect_step_candidates_title
import nl.rlabs.epsonreset.resources.inspect_step_key_blurb
import nl.rlabs.epsonreset.resources.inspect_step_key_title
import nl.rlabs.epsonreset.resources.inspect_step_share_blurb
import nl.rlabs.epsonreset.resources.inspect_step_share_title
import nl.rlabs.epsonreset.resources.inspect_step_sweep_blurb
import nl.rlabs.epsonreset.resources.inspect_step_sweep_title
import nl.rlabs.epsonreset.resources.inspect_sweep_answered
import nl.rlabs.epsonreset.resources.inspect_sweep_button
import nl.rlabs.epsonreset.resources.inspect_target_readonly
import nl.rlabs.epsonreset.resources.inspect_target_readonly_no_pid
import nl.rlabs.epsonreset.resources.inspect_title
import nl.rlabs.epsonreset.resources.inspect_try_keys
import nl.rlabs.epsonreset.resources.inspect_using_key
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Read-only exploration of a printer the database doesn't cover. */
@Composable
fun InspectPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val copy = rememberClipboardCopy()
    val inspect = vm.inspect

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Header(vm)
        Spacer(Modifier.height(16.dp))

        StepCard(
            number = 1,
            title = stringResource(Res.string.inspect_step_key_title),
            blurb = stringResource(Res.string.inspect_step_key_blurb),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { inspect.discoverReadKey() },
                    enabled = inspect.canInspect,
                ) { Text(stringResource(Res.string.inspect_try_keys)) }

                Spacer(Modifier.width(10.dp))
                if (inspect.inspecting) {
                    OutlinedButton(onClick = {
                        vm.cancel()
                    }) { Text(stringResource(Res.string.inspect_cancel)) }
                }

                Spacer(Modifier.width(16.dp))
                inspect.key?.let {
                    Text(
                        stringResource(Res.string.inspect_using_key, "0x%04X".format(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = StatusColors.good,
                    )
                }
            }

            val answered = inspect.keys.filter { it.answered }
            if (answered.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (result in answered.take(6)) {
                    KeyRow(
                        key = result.hex,
                        detail = pluralStringResource(
                            Res.plurals.inspect_probe_hits,
                            result.probes,
                            result.hits,
                            result.probes,
                        ) + result.exampleModels.takeIf { it.isNotEmpty() }
                            ?.let { stringResource(Res.string.inspect_like_models, it.joinToString(", ")) }
                            .orEmpty(),
                        selected = inspect.key == result.readKey,
                        onClick = { inspect.chooseKey(result.readKey) },
                    )
                }
                if (answered.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Note(
                        stringResource(Res.string.inspect_several_keys),
                        StatusColors.warn,
                    )
                }
            } else if (inspect.keys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Note(
                    stringResource(Res.string.inspect_no_known_key),
                    StatusColors.bad,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        StepCard(
            number = 2,
            title = stringResource(Res.string.inspect_step_sweep_title),
            blurb = stringResource(Res.string.inspect_step_sweep_blurb),
            enabled = inspect.key != null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { inspect.sweepAddresses() },
                    enabled = inspect.canInspect && inspect.key != null,
                ) { Text(stringResource(Res.string.inspect_sweep_button, "0x%04X".format(inspect.rangeEnd))) }

                Spacer(Modifier.width(12.dp))
                RangeChoice("256", 0xFF, inspect)
                RangeChoice("512", 0x1FF, inspect)
                RangeChoice("2048", 0x7FF, inspect)
            }

            inspect.sweep?.let { sweep ->
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.inspect_sweep_answered, sweep.answered, sweep.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (sweep.answered > 0) StatusColors.good else StatusColors.bad,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        SweepAnalysis.dump(sweep),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        StepCard(
            number = 3,
            title = stringResource(Res.string.inspect_step_candidates_title),
            blurb = stringResource(Res.string.inspect_step_candidates_blurb),
            enabled = inspect.sweep != null,
        ) {
            if (inspect.candidates.isEmpty()) {
                Text(
                    if (inspect.sweep == null) {
                        stringResource(Res.string.inspect_run_sweep_first)
                    } else {
                        stringResource(Res.string.inspect_nothing_looked_like)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.muted,
                )
            } else {
                for (candidate in inspect.candidates) CandidateRow(candidate)
            }
        }

        Spacer(Modifier.height(12.dp))

        StepCard(
            number = 4,
            title = stringResource(Res.string.inspect_step_share_title),
            blurb = stringResource(Res.string.inspect_step_share_blurb),
            enabled = inspect.canExport,
        ) {
            OutlinedTextField(
                value = inspect.modelName,
                onValueChange = { inspect.modelName = it },
                label = { Text(stringResource(Res.string.inspect_model_name)) },
                placeholder = { Text(vm.selectedDevice?.device?.displayName ?: "ET-0000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = { copy(inspect.overlay()) },
                    enabled = inspect.canExport && inspect.candidates.isNotEmpty(),
                ) { Text(stringResource(Res.string.inspect_copy_overlay)) }

                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { copy(inspect.report()) },
                    enabled = inspect.canExport,
                ) { Text(stringResource(Res.string.inspect_copy_report)) }
            }
            Spacer(Modifier.height(10.dp))
            Note(
                stringResource(Res.string.inspect_save_overlay, nl.rlabs.epsonreset.AppPaths.counterOverlay),
                StatusColors.muted,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Header(vm: ResetViewModel) {
    Column {
        Text(
            stringResource(Res.string.inspect_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.inspect_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.muted,
        )

        Spacer(Modifier.height(12.dp))
        Target(vm)
    }
}

/**
 * What the inspector is pointed at. Real hardware only — see [InspectState.canInspect] for why
 * there is no simulated mode.
 */
@Composable
private fun Target(vm: ResetViewModel) {
    val device = vm.selectedDevice

    if (device == null) {
        Note(
            stringResource(Res.string.inspect_no_printer),
            StatusColors.warn,
        )
        return
    }

    // A network target has no product id; the empty bracket it used to leave is worse than no bracket.
    val pid = device.device.pidHex
    Note(
        if (pid == null) {
            stringResource(Res.string.inspect_target_readonly_no_pid, device.device.displayName)
        } else {
            stringResource(Res.string.inspect_target_readonly, device.device.displayName, pid)
        },
        StatusColors.muted,
    )
}

@Composable
private fun KeyRow(key: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(12.dp))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
    }
}

@Composable
private fun CandidateRow(candidate: SweepAnalysis.Candidate) {
    val colour = when (candidate.confidence) {
        SweepAnalysis.Confidence.FAMILY -> StatusColors.good
        SweepAnalysis.Confidence.LIKELY -> StatusColors.warn
        SweepAnalysis.Confidence.WEAK -> StatusColors.muted
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                candidate.label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                candidate.value?.toString() ?: stringResource(Res.string.inspect_bytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                candidate.confidence.name.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colour,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(candidate.why, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
        Spacer(Modifier.height(3.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun RangeChoice(label: String, end: Int, inspect: InspectState) {
    val active = inspect.rangeEnd == end
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else StatusColors.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !inspect.inspecting) { inspect.rangeEnd = end }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
