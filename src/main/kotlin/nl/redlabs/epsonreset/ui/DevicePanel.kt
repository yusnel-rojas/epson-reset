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

/** Left rail: what's reachable, over USB or the network, and what each device resolved to. */
@Composable
fun DevicePanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp)) {
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

        // Scanning both buses is the path; the two manual ones hang off the chevron. See
        // [SplitButton] for why they are no longer on permanent display.
        SplitButton(
            label = if (vm.devices.isEmpty()) "Scan USB and network" else "Rescan",
            primaryEnabled = vm.scanState !is ResetViewModel.ScanState.Scanning,
            onPrimary = { vm.scan() },
            actions = listOf(
                SplitAction("Add printer by IP address…") { vm.addByAddressRequested = true },
                SplitAction(
                    label = "Choose the model by hand…",
                    // Already the state of the sidebar when nothing has named itself.
                    enabled = !vm.modelPickerExpanded,
                ) { vm.manualModelRequested = true },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        when (val state = vm.scanState) {
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
                    title = "No Epson found",
                    body = "Connect the printer over USB or put it on this network, switch it on, " +
                        "then rescan. A printer that doesn't advertise itself can be added by " +
                        "address.",
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

        // Shown on request, and unprompted when a scan came up empty — see [AddByAddress].
        val scanning = vm.scanState is ResetViewModel.ScanState.Scanning
        val nothingFound = vm.devices.isEmpty() && !scanning &&
            vm.scanState !is ResetViewModel.ScanState.Idle

        if (vm.addByAddressRequested || nothingFound) {
            Spacer(Modifier.height(16.dp))
            AddByAddress(vm, dismissible = vm.addByAddressRequested && !nothingFound)
        }
    }
}

/** Manual entry. */
@Composable
private fun AddByAddress(vm: ResetViewModel, dismissible: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add by address",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dismissible) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.addByAddressRequested = false }) { Text("Hide") }
            }
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
                    onDone = { if (vm.canAddNetworkPrinter) vm.addNetworkPrinter() },
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.addNetworkPrinter() },
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
 * A plain column, not a lazy one: there are only ever a handful of printers, and a lazy list here
 * would compete with the model picker below it for the sidebar's vertical space.
 */
@Composable
private fun DeviceList(vm: ResetViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in vm.devices) {
            DeviceCard(
                vm = vm,
                entry = entry,
                selected = vm.selectedDevice?.device?.id == entry.device.id,
                onClick = { vm.select(entry) },
            )
        }
    }
}

@Composable
private fun DeviceCard(vm: ResetViewModel, entry: MatchedPrinter, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else StatusColors.muted),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                entry.device.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                entry.device.link.kind,
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
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
                Meta("Port", link.port.toString())
            }
        }
        entry.device.serial?.let { Meta("Serial", it) }

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
                ) {
                    Text(if (vm.testing) "Testing…" else "Test connection")
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
