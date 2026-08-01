package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter

/** Contents of the top-bar printer menu. */
@Composable
fun PrinterSelectorContent(vm: ResetViewModel, modifier: Modifier = Modifier, onModelSelected: () -> Unit = {}) {
    Column(modifier.padding(16.dp)) {
        // The explicit IP action is its own short task. Replacing the picker keeps the field at the
        // point of attention instead of making the user find it underneath an arbitrary list.
        if (vm.addByAddressRequested) {
            AddByAddress(vm)
            return@Column
        }

        if (vm.modelSelectionVisible) {
            ModelPicker(
                vm = vm,
                modifier = Modifier.fillMaxWidth(),
                onBack = vm::leaveModelSelection,
                onModelSelected = onModelSelected,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Printers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (vm.scanState is ResetViewModel.ScanState.Scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Scanning both buses is the path; manual network entry hangs off the chevron.
        SplitButton(
            label = when {
                vm.scanState is ResetViewModel.ScanState.Scanning -> "Stop scanning"
                vm.devices.isEmpty() -> "Scan USB and network"
                else -> "Rescan"
            },
            primaryEnabled = vm.canScan,
            onPrimary = { vm.scan() },
            actions = listOf(
                SplitAction("Add printer by IP address…") { vm.addByAddressRequested = true },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        when (val state = vm.scanState) {
            is ResetViewModel.ScanState.Scanning -> if (vm.devices.isEmpty()) {
                Notice(
                    title = "Scanning…",
                    body = "You can stop the scan or add a printer by address from the scan menu.",
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            is ResetViewModel.ScanState.LibraryMissing -> Notice(
                title = "Nothing found",
                body = "libusb is missing, so USB detection is off, and nothing answered on the " +
                    "network. Dry runs work regardless — or add the printer by address.",
                mono = state.hint,
                tone = StatusColors.warn,
            )

            is ResetViewModel.ScanState.Failed -> Notice(
                title = "Scan failed",
                body = state.message,
                tone = StatusColors.bad,
            )

            is ResetViewModel.ScanState.Done -> if (vm.devices.isEmpty()) {
                Notice(
                    title = "No Epson printers found",
                    body = "Connect the printer over USB or put it on this network, switch it on, " +
                        "then rescan. A printer that doesn't advertise itself can be added by " +
                        "address.",
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            is ResetViewModel.ScanState.Stopped -> if (vm.devices.isEmpty()) {
                Notice(
                    title = "Scan stopped",
                    body = "Scan again, or add a printer by address from the scan menu.",
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            else -> Notice(
                title = "Not scanned yet",
                body = "Scan to find Epson printers on USB and on this network.",
                tone = StatusColors.muted,
            )
        }

        // Per-source complaints sit under the list rather than replacing it: with a printer found
        // on one bus, a problem on the other is a footnote, not the headline.
        if (vm.devices.isNotEmpty()) {
            vm.usbNote?.let {
                Spacer(Modifier.height(8.dp))
                FootNote(it)
            }
            vm.networkNote?.let {
                Spacer(Modifier.height(8.dp))
                FootNote("Network discovery: $it")
            }
        }
    }
}

/** Manual entry. */
@Composable
private fun AddByAddress(vm: ResetViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add by address",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.addByAddressRequested = false }) { Text("Back") }
        }
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.networkAddressInput,
                onValueChange = { vm.networkAddressInput = it },
                singleLine = true,
                placeholder = { Text("192.168.1.50", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (vm.canAddNetworkPrinter) {
                            vm.addByAddressRequested = false
                            vm.addNetworkPrinter()
                        }
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.addByAddressRequested = false
                    vm.addNetworkPrinter()
                },
                enabled = vm.canAddNetworkPrinter,
            ) {
                Text("Add")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "The printer's IP, from its network status sheet or its web page. Saved for next time.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

/**
 * A plain column, not a lazy one: there are only ever a handful of printers, and the menu provides
 * its own bounded scrolling when its contents grow taller than the window.
 */
@Composable
private fun DeviceList(vm: ResetViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in vm.devices) {
            DeviceCard(
                vm = vm,
                entry = entry,
                selected = vm.selectedDevice?.device?.id == entry.device.id,
                enabled = vm.canChangeTarget,
                onClick = { vm.selectAndRefreshOverview(entry) },
            )
        }
    }
}

@Composable
private fun DeviceCard(
    vm: ResetViewModel,
    entry: MatchedPrinter,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val presenceTone = when {
        !entry.device.reachable -> StatusColors.muted
        selected -> StatusColors.good
        else -> StatusColors.muted
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(presenceTone),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                entry.device.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    entry.device.reachable -> entry.device.link.kind
                    // Only network entries are remembered; an unreachable USB one is live-scanned
                    // from its queue, so "saved" would be the wrong word for it.
                    entry.device.link is Link.Network -> "Saved · not reached"
                    else -> "USB · not answering"
                },
                style = MaterialTheme.typography.labelSmall,
                color = presenceTone,
            )
        }

        Spacer(Modifier.height(6.dp))

        when (val link = entry.device.link) {
            is Link.Usb -> {
                entry.device.pidHex?.let { Meta("PID", it) }
                Meta("Bus", "${link.busNumber}.${link.deviceAddress}")
                Meta(
                    "Interface",
                    "${link.interfaceNumber} " +
                        if (link.isPrinterClass) "(printer class)" else "(vendor specific)",
                )
            }

            is Link.Network -> {
                Meta("Address", link.host)
                // No port at the default. "Network" in the corner already says how this is reached,
                // and the number that used to sit here was the raw printing port — advertised by
                // the printer, never dialled by this app, and so purely misleading.
                if (link.port != Link.SNMP_PORT) Meta("SNMP port", link.port.toString())
            }

            is Link.WindowsPrinter -> {
                // Reached through the printer's own Windows driver — the queue and its port are all
                // there is to show, and both come straight from the spooler.
                link.port?.let { Meta("Port", it) }
                Meta("Queue", link.queueName)
            }
        }
        // The decoded form, because it is the one the same printer shows on its other link. The
        // descriptor's own hex spelling is not shown — it is what the device said, but saying it
        // twice in the card taught the reader nothing. `./gradlew diagnose` still prints it.
        entry.device.canonicalSerial?.let { Meta("Serial", it) }

        entry.device.crossCheck?.let {
            Meta("Model from", "${it.name} · SNMP at ${it.link.where}")
        }

        Spacer(Modifier.height(8.dp))

        when (entry.confidence) {
            MatchedPrinter.Confidence.EXACT -> MatchTag(
                "✓ ${entry.model?.name}",
                StatusColors.good,
            )
            MatchedPrinter.Confidence.LIKELY -> MatchTag(
                "≈ ${entry.model?.name} — confirm",
                StatusColors.warn,
            )
            MatchedPrinter.Confidence.CLASS_ONLY -> MatchTag(
                "≈ one of ${entry.candidates.size} — pick the model",
                StatusColors.warn,
            )
            MatchedPrinter.Confidence.NONE -> MatchTag(
                "No database match — pick manually",
                StatusColors.muted,
            )
        }

        entry.device.accessNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.warn)
        }

        if (selected) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = vm.canTestConnection,
                    modifier = Modifier.width(112.dp),
                ) {
                    Text(if (vm.testing) "Testing…" else "Test", maxLines = 1)
                }
                if (vm.isSaved(entry)) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.forgetNetworkPrinter(entry) }) { Text("Forget") }
                }
            }

            vm.lastTest?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    result.headline,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.usable) StatusColors.good else StatusColors.warn,
                )
                result.advice?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                }
            }

            if (vm.selectedModel != null) {
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = vm::requestModelSelection,
                    enabled = vm.canChangeTarget,
                ) {
                    Text("Change model…", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row {
        Text(
            "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchTag(text: String, tone: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tone,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun FootNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.warn,
    )
}

@Composable
private fun Notice(title: String, body: String, tone: androidx.compose.ui.graphics.Color, mono: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = tone,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mono != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                mono,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            )
        }
    }
}
