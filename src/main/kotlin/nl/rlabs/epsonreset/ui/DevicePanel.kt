package nl.rlabs.epsonreset.ui

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
import nl.rlabs.epsonreset.device.Link
import nl.rlabs.epsonreset.device.MatchedPrinter
import nl.rlabs.epsonreset.i18n.resolve
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.device_add
import nl.rlabs.epsonreset.resources.device_add_by_address
import nl.rlabs.epsonreset.resources.device_add_by_ip
import nl.rlabs.epsonreset.resources.device_address_hint
import nl.rlabs.epsonreset.resources.device_back
import nl.rlabs.epsonreset.resources.device_change_model
import nl.rlabs.epsonreset.resources.device_forget
import nl.rlabs.epsonreset.resources.device_interface_printer_class
import nl.rlabs.epsonreset.resources.device_interface_vendor_specific
import nl.rlabs.epsonreset.resources.device_match_class_only
import nl.rlabs.epsonreset.resources.device_match_exact
import nl.rlabs.epsonreset.resources.device_match_likely
import nl.rlabs.epsonreset.resources.device_match_none
import nl.rlabs.epsonreset.resources.device_meta_address
import nl.rlabs.epsonreset.resources.device_meta_bus
import nl.rlabs.epsonreset.resources.device_meta_interface
import nl.rlabs.epsonreset.resources.device_meta_model_from
import nl.rlabs.epsonreset.resources.device_meta_pid
import nl.rlabs.epsonreset.resources.device_meta_port
import nl.rlabs.epsonreset.resources.device_meta_queue
import nl.rlabs.epsonreset.resources.device_meta_serial
import nl.rlabs.epsonreset.resources.device_meta_snmp_port
import nl.rlabs.epsonreset.resources.device_model_from_value
import nl.rlabs.epsonreset.resources.device_network_note
import nl.rlabs.epsonreset.resources.device_none_found_body
import nl.rlabs.epsonreset.resources.device_none_found_title
import nl.rlabs.epsonreset.resources.device_not_scanned_body
import nl.rlabs.epsonreset.resources.device_not_scanned_title
import nl.rlabs.epsonreset.resources.device_nothing_found_body
import nl.rlabs.epsonreset.resources.device_nothing_found_title
import nl.rlabs.epsonreset.resources.device_rescan
import nl.rlabs.epsonreset.resources.device_saved_not_reached
import nl.rlabs.epsonreset.resources.device_scan_both
import nl.rlabs.epsonreset.resources.device_scan_failed_title
import nl.rlabs.epsonreset.resources.device_scan_stopped_body
import nl.rlabs.epsonreset.resources.device_scan_stopped_title
import nl.rlabs.epsonreset.resources.device_scanning_body
import nl.rlabs.epsonreset.resources.device_scanning_title
import nl.rlabs.epsonreset.resources.device_stop_scanning
import nl.rlabs.epsonreset.resources.device_usb_not_answering
import nl.rlabs.epsonreset.resources.devices_test
import nl.rlabs.epsonreset.resources.devices_testing
import nl.rlabs.epsonreset.resources.devices_title
import org.jetbrains.compose.resources.stringResource

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
                stringResource(Res.string.devices_title),
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
                vm.scanState is ResetViewModel.ScanState.Scanning ->
                    stringResource(Res.string.device_stop_scanning)

                vm.devices.isEmpty() -> stringResource(Res.string.device_scan_both)
                else -> stringResource(Res.string.device_rescan)
            },
            primaryEnabled = vm.canScan,
            onPrimary = { vm.scan() },
            actions = listOf(
                SplitAction(stringResource(Res.string.device_add_by_ip)) { vm.addByAddressRequested = true },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        when (val state = vm.scanState) {
            is ResetViewModel.ScanState.Scanning -> if (vm.devices.isEmpty()) {
                Notice(
                    title = stringResource(Res.string.device_scanning_title),
                    body = stringResource(Res.string.device_scanning_body),
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            is ResetViewModel.ScanState.LibraryMissing -> Notice(
                title = stringResource(Res.string.device_nothing_found_title),
                body = stringResource(Res.string.device_nothing_found_body),
                mono = state.hint,
                tone = StatusColors.warn,
            )

            is ResetViewModel.ScanState.Failed -> Notice(
                title = stringResource(Res.string.device_scan_failed_title),
                body = state.message,
                tone = StatusColors.bad,
            )

            is ResetViewModel.ScanState.Done -> if (vm.devices.isEmpty()) {
                Notice(
                    title = stringResource(Res.string.device_none_found_title),
                    body = stringResource(Res.string.device_none_found_body),
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            is ResetViewModel.ScanState.Stopped -> if (vm.devices.isEmpty()) {
                Notice(
                    title = stringResource(Res.string.device_scan_stopped_title),
                    body = stringResource(Res.string.device_scan_stopped_body),
                    tone = StatusColors.muted,
                )
            } else {
                DeviceList(vm)
            }

            else -> Notice(
                title = stringResource(Res.string.device_not_scanned_title),
                body = stringResource(Res.string.device_not_scanned_body),
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
                FootNote(stringResource(Res.string.device_network_note, it))
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
                stringResource(Res.string.device_add_by_address),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.addByAddressRequested = false }) { Text(stringResource(Res.string.device_back)) }
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
                Text(stringResource(Res.string.device_add))
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.device_address_hint),
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
                    entry.device.link is Link.Network -> stringResource(Res.string.device_saved_not_reached)
                    else -> stringResource(Res.string.device_usb_not_answering)
                },
                style = MaterialTheme.typography.labelSmall,
                color = presenceTone,
            )
        }

        Spacer(Modifier.height(6.dp))

        when (val link = entry.device.link) {
            is Link.Usb -> {
                entry.device.pidHex?.let { Meta(stringResource(Res.string.device_meta_pid), it) }
                Meta(stringResource(Res.string.device_meta_bus), "${link.busNumber}.${link.deviceAddress}")
                Meta(
                    stringResource(Res.string.device_meta_interface),
                    if (link.isPrinterClass) {
                        stringResource(Res.string.device_interface_printer_class, link.interfaceNumber)
                    } else {
                        stringResource(Res.string.device_interface_vendor_specific, link.interfaceNumber)
                    },
                )
            }

            is Link.Network -> {
                Meta(stringResource(Res.string.device_meta_address), link.host)
                // No port at the default. "Network" in the corner already says how this is reached,
                // and the number that used to sit here was the raw printing port — advertised by
                // the printer, never dialled by this app, and so purely misleading.
                if (link.port !=
                    Link.SNMP_PORT
                ) {
                    Meta(stringResource(Res.string.device_meta_snmp_port), link.port.toString())
                }
            }

            is Link.WindowsPrinter -> {
                // Reached through the printer's own Windows driver — the queue and its port are all
                // there is to show, and both come straight from the spooler.
                link.port?.let { Meta(stringResource(Res.string.device_meta_port), it) }
                Meta(stringResource(Res.string.device_meta_queue), link.queueName)
            }
        }
        // The decoded form, because it is the one the same printer shows on its other link. The
        // descriptor's own hex spelling is not shown — it is what the device said, but saying it
        // twice in the card taught the reader nothing. `./gradlew diagnose` still prints it.
        entry.device.canonicalSerial?.let { Meta(stringResource(Res.string.device_meta_serial), it) }

        entry.device.crossCheck?.let {
            Meta(
                stringResource(Res.string.device_meta_model_from),
                stringResource(Res.string.device_model_from_value, it.name, it.link.where),
            )
        }

        Spacer(Modifier.height(8.dp))

        when (entry.confidence) {
            MatchedPrinter.Confidence.EXACT -> MatchTag(
                stringResource(Res.string.device_match_exact, entry.model?.name.toString()),
                StatusColors.good,
            )

            MatchedPrinter.Confidence.LIKELY -> MatchTag(
                stringResource(Res.string.device_match_likely, entry.model?.name.toString()),
                StatusColors.warn,
            )

            MatchedPrinter.Confidence.CLASS_ONLY -> MatchTag(
                stringResource(Res.string.device_match_class_only, entry.candidates.size),
                StatusColors.warn,
            )

            MatchedPrinter.Confidence.NONE -> MatchTag(
                stringResource(Res.string.device_match_none),
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
                    Text(
                        stringResource(if (vm.testing) Res.string.devices_testing else Res.string.devices_test),
                        maxLines = 1,
                    )
                }
                if (vm.isSaved(entry)) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        vm.forgetNetworkPrinter(entry)
                    }) { Text(stringResource(Res.string.device_forget)) }
                }
            }

            vm.lastTest?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    result.headline.resolve(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.usable) StatusColors.good else StatusColors.warn,
                )
                result.advice?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it.resolve(), style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                }
            }

            if (vm.selectedModel != null) {
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = vm::requestModelSelection,
                    enabled = vm.canChangeTarget,
                ) {
                    Text(
                        stringResource(Res.string.device_change_model),
                        style = MaterialTheme.typography.labelSmall,
                    )
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
