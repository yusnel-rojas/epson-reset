package nl.redlabs.epsonreset.ui
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.probe.SweepAnalysis

/** Read-only exploration of a printer the database doesn't cover. */
@Composable
fun InspectPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val inspect = vm.inspect

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Header(vm)
        Spacer(Modifier.height(16.dp))

        StepCard(
            number = 1,
            title = "Find a read key",
            blurb = "Every model needs a 16-bit read key before it will report anything. Yours is " +
                "probably one the database already knows — there are only a few hundred, and they " +
                "run in families.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { inspect.discoverReadKey() },
                    enabled = inspect.canInspect,
                ) { Text("Try known keys") }

                Spacer(Modifier.width(10.dp))
                if (inspect.inspecting) OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }

                Spacer(Modifier.width(16.dp))
                inspect.key?.let {
                    Text(
                        "Using 0x%04X".format(it),
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
                        detail = "${result.hits}/${result.probes} probes" +
                            result.exampleModels.takeIf { it.isNotEmpty() }
                                ?.let { " · like ${it.joinToString(", ")}" }.orEmpty(),
                        selected = inspect.key == result.readKey,
                        onClick = { inspect.chooseKey(result.readKey) },
                    )
                }
                if (answered.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Note(
                        "Several keys answered, so this printer probably doesn't check the key at " +
                            "all. Any of them will do — the sweep is the real finding.",
                        StatusColors.warn,
                    )
                }
            } else if (inspect.keys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Note(
                    "No known key produced a reading. This model may use a key nobody has recorded yet.",
                    StatusColors.bad,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        StepCard(
            number = 2,
            title = "Sweep the EEPROM",
            blurb = "Reads every address in range and shows what came back. Read-only: the read " +
                "command carries no write key, so this cannot alter the printer.",
            enabled = inspect.key != null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { inspect.sweepAddresses() },
                    enabled = inspect.canInspect && inspect.key != null,
                ) { Text("Sweep 0x0000–0x%04X".format(inspect.rangeEnd)) }

                Spacer(Modifier.width(12.dp))
                RangeChoice("256", 0xFF, inspect)
                RangeChoice("512", 0x1FF, inspect)
                RangeChoice("2048", 0x7FF, inspect)
            }

            inspect.sweep?.let { sweep ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "${sweep.answered} of ${sweep.total} addresses answered",
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
            title = "Candidate counters",
            blurb = "Ranked by what the guess rests on. A family match means a model sharing your " +
                "read key uses exactly these addresses — that is a good deal stronger than a byte " +
                "pattern that merely looks like a count.",
            enabled = inspect.sweep != null,
        ) {
            if (inspect.candidates.isEmpty()) {
                Text(
                    if (inspect.sweep == null) {
                        "Run a sweep first."
                    } else {
                        "Nothing in the sweep looked like a counter."
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
            title = "Share what you found",
            blurb = "The overlay makes this app read your printer straight away. The report belongs " +
                "upstream at reinkpy, where both bundled files come from — a model added there " +
                "reaches every tool built on it, not just this one.",
            enabled = inspect.canExport,
        ) {
            OutlinedTextField(
                value = inspect.modelName,
                onValueChange = { inspect.modelName = it },
                label = { Text("Model name") },
                placeholder = { Text(vm.selectedDevice?.device?.displayName ?: "ET-0000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(inspect.overlay())) },
                    enabled = inspect.canExport && inspect.candidates.isNotEmpty(),
                ) { Text("Copy overlay JSON") }

                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(inspect.report())) },
                    enabled = inspect.canExport,
                ) { Text("Copy report") }
            }
            Spacer(Modifier.height(10.dp))
            Note(
                "Save the overlay as counters-overlay.json next to the database cache " +
                    "(${nl.redlabs.epsonreset.AppPaths.counterOverlay}) and restart.",
                StatusColors.muted,
            )
        }

        if (inspect.inspecting) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(vm.progressLabel, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Header(vm: ResetViewModel) {
    Column {
        Text(
            "Device inspector",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "For a printer the database doesn't cover. Everything on this screen is read-only — " +
                "no write is ever sent, so it is safe to run against a printer nobody has identified.",
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
            "No printer detected. Plug the printer in and press Rescan on the Reset tab — this " +
                "tab works on real hardware only.",
            StatusColors.warn,
        )
        return
    }

    Note("Target: ${device.device.displayName} (${device.device.pidHex}) — read-only.", StatusColors.muted)
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
                candidate.value?.toString() ?: "bytes",
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
