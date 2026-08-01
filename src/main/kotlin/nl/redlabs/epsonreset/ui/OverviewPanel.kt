package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.db.PrinterModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OVERVIEW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** The full-width Overview screen for the printer and model in the application-wide target. */
@Composable
fun OverviewPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    val model = vm.selectedModel

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        OverviewHeader(vm, model)

        Spacer(Modifier.height(12.dp))
        if (vm.selectedDevice == null) {
            OverviewNoPrinterState(vm, model)
            return@Column
        }
        OverviewContent(vm, model)
    }
}

@Composable
private fun OverviewNoPrinterState(vm: ResetViewModel, model: PrinterModel?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (vm.scanState is ResetViewModel.ScanState.Scanning) {
                "Looking for printers…"
            } else {
                "Choose a printer to see its overview"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                vm.scanState is ResetViewModel.ScanState.Scanning ->
                    "Scanning USB and the local network. The discovered printer will become the live target."
                vm.devices.isNotEmpty() ->
                    "${vm.devices.size} printers were found. Choose the one whose status, supplies and counters you want to read."
                else ->
                    "Overview needs a live printer for connection status, supplies, lifetime pages and counters."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "${it.name} remains selected for a simulated reset under Maintenance.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.warn,
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                vm.scanState is ResetViewModel.ScanState.Scanning ->
                    OutlinedButton(onClick = { vm.scan() }) { Text("Stop scanning") }
                vm.devices.isEmpty() ->
                    Button(onClick = { vm.scan() }, enabled = vm.canScan) { Text("Scan for printers") }
                else ->
                    Button(onClick = vm::requestPrinterMenu) { Text("Choose printer") }
            }
            if (model != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.tab = ResetViewModel.Tab.MAINTENANCE }) {
                    Text("Open Maintenance")
                }
            }
        }
    }
}

@Composable
private fun CounterViewSelector(vm: ResetViewModel, selected: ResetViewModel.CounterView, summaryAvailable: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        CounterViewButton(
            label = "Summary",
            selected = selected == ResetViewModel.CounterView.SUMMARY,
            enabled = summaryAvailable,
        ) { vm.counterView = ResetViewModel.CounterView.SUMMARY }
        CounterViewButton(
            label = "Counter details",
            selected = selected == ResetViewModel.CounterView.DETAILS,
        ) { vm.counterView = ResetViewModel.CounterView.DETAILS }
        CounterViewButton(
            label = "History",
            selected = selected == ResetViewModel.CounterView.HISTORY,
        ) { vm.counterView = ResetViewModel.CounterView.HISTORY }
    }
}

@Composable
private fun CounterViewButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> StatusColors.muted
        },
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun OverviewContent(vm: ResetViewModel, model: PrinterModel?) {
    OverviewSummaryCard(vm.overviewSnapshot)

    vm.overviewSnapshot?.let { overview ->
        if (overview.status?.inkLevels?.isNotEmpty() == true || overview.printerMib != null) {
            Spacer(Modifier.height(12.dp))
            SuppliesCard(overview.status, overview.printerMib)
        }
    }

    if (model == null) {
        EmptyState(vm, Modifier.fillMaxWidth().height(260.dp))
        return
    }

    val counterReport = vm.overviewSnapshot?.counters
    val counterSpecs = vm.specsFor(model)
    val summaryAvailable = overviewCounterSummaryAvailable(counterReport, counterSpecs)
    val shownCounterView = if (!summaryAvailable && vm.counterView == ResetViewModel.CounterView.SUMMARY) {
        ResetViewModel.CounterView.DETAILS
    } else {
        vm.counterView
    }

    Spacer(Modifier.height(20.dp))
    CounterViewSelector(vm, shownCounterView, summaryAvailable)
    Spacer(Modifier.height(8.dp))

    when (shownCounterView) {
        ResetViewModel.CounterView.SUMMARY -> {
            counterReport?.let { report ->
                OverviewCountersCard(
                    report = report,
                    specs = counterSpecs,
                    onCalibrate = if (vm.readReport == report && !vm.readWasSimulated && !vm.reading) {
                        vm.calibration::open
                    } else {
                        null
                    },
                )
                CalibrationDialog(vm)
            }
        }

        ResetViewModel.CounterView.DETAILS -> CounterDetailsContent(vm)
        ResetViewModel.CounterView.HISTORY -> CounterHistoryPanel(vm)
    }
}

@Composable
private fun OverviewHeader(vm: ResetViewModel, model: PrinterModel?) {
    val overview = vm.overviewSnapshot
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Printer overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    vm.selectedDevice?.device?.displayName,
                    model?.name,
                    overview?.firmware?.let { "firmware $it" },
                ).joinToString(" · ").ifBlank { "Select a live printer to collect overview information." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            overview?.let {
                Text(
                    "Last refreshed ${OVERVIEW_TIME.format(it.refreshedAt)} · ${it.linkKind}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                )
            }
            if (vm.dryRun && vm.selectedDevice != null) {
                Text(
                    "Reset remains in Dry run under Maintenance; overview refresh never writes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.warn,
                )
            }
        }

        if (vm.selectedDevice != null) {
            Spacer(Modifier.width(16.dp))
            if (vm.overviewRefreshing) {
                OutlinedButton(onClick = vm::cancel) { Text("Cancel refresh") }
            } else {
                Button(onClick = vm::refreshOverview, enabled = vm.canRefreshOverview) { Text("Refresh overview") }
            }
        }
    }
}

@Composable
private fun OverviewSummaryCard(overview: OverviewSnapshot?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        if (overview == null) {
            Text("Overview not refreshed yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Refresh reads connection details, status, supplies and counters. Missing sources stay " +
                    "listed as unavailable instead of being treated as good news.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@Column
        }

        val available = overview.coverage.count { it.available }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (overview.alerts.isEmpty()) {
                    "No reported warnings"
                } else {
                    "${overview.alerts.size} item(s) need attention"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    overview.alerts.any { it.severity == OverviewAlert.Severity.ERROR } -> StatusColors.bad
                    overview.alerts.isNotEmpty() -> StatusColors.warn
                    available == overview.coverage.size -> StatusColors.good
                    else -> StatusColors.muted
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$available of ${overview.coverage.size} sections reported",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        overview.alerts.forEach { alert ->
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        (if (alert.severity == OverviewAlert.Severity.ERROR) StatusColors.bad else StatusColors.warn)
                            .copy(alpha = 0.10f),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(alert.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(alert.detail, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
            }
        }

        Spacer(Modifier.height(10.dp))
        overview.coverage.forEach { coverage ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (coverage.available) "✓" else "—",
                    color = if (coverage.available) StatusColors.good else StatusColors.muted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    coverage.section.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    coverage.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(vm: ResetViewModel, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(420.dp),
        ) {
            Text(
                "No model selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.devices.isEmpty()) {
                    "Open the target above to scan for a printer or choose a model for a dry run."
                } else {
                    "Open the target above to choose a printer and resolve its model."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
        }
    }
}
