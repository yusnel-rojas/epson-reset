package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.redlabs.epsonreset.db.PrinterModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OVERVIEW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** How often the "x min ago" label re-derives itself. */
private const val STALE_TICK_MS = 30_000L

/** Past this, the reading is old enough that acting on it deserves a second look. */
private val STALE_AFTER: Duration = Duration.ofMinutes(10)

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
private fun CounterViewSelector(vm: ResetViewModel, selected: ResetViewModel.CounterView) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        CounterViewButton(
            label = "Counters",
            selected = selected == ResetViewModel.CounterView.COUNTERS,
        ) { vm.counterView = ResetViewModel.CounterView.COUNTERS }
        CounterViewButton(
            label = "History",
            selected = selected == ResetViewModel.CounterView.HISTORY,
        ) { vm.counterView = ResetViewModel.CounterView.HISTORY }
    }
}

@Composable
private fun CounterViewButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/**
 * Alerts, then counters, then supplies, then how the reading went.
 *
 * Ordered by what the person came for. The counters are the reason this app exists, so they sit
 * above the supplies and well above the coverage list — which is really a report on the refresh
 * rather than on the printer, and belongs at the bottom.
 */
@Composable
private fun OverviewContent(vm: ResetViewModel, model: PrinterModel?) {
    val overview = vm.overviewReading

    AlertsCard(vm, overview)

    if (model == null) {
        EmptyState(vm, Modifier.fillMaxWidth().height(260.dp))
        Spacer(Modifier.height(12.dp))
        overview?.let { CoverageCard(it) }
        return
    }

    Spacer(Modifier.height(20.dp))
    CountersSection(vm, model)

    overview?.let {
        if (it.status?.inkLevels?.isNotEmpty() == true || it.printerMib != null) {
            Spacer(Modifier.height(12.dp))
            SuppliesCard(it.status, it.printerMib)
        }
        Spacer(Modifier.height(12.dp))
        CoverageCard(it)
    }
}

@Composable
private fun CountersSection(vm: ResetViewModel, model: PrinterModel) {
    val counterReport = vm.overviewReading?.counters
    val counterSpecs = vm.specsFor(model)
    val summaryAvailable = overviewCounterSummaryAvailable(counterReport, counterSpecs)

    CounterViewSelector(vm, vm.counterView)
    Spacer(Modifier.height(8.dp))

    when (vm.counterView) {
        ResetViewModel.CounterView.COUNTERS -> {
            if (summaryAvailable && counterReport != null) {
                OverviewCountersCard(
                    report = counterReport,
                    specs = counterSpecs,
                    onCalibrate = if (vm.readReport == counterReport && !vm.readWasSimulated && !vm.reading) {
                        vm.calibration::open
                    } else {
                        null
                    },
                )
                CalibrationDialog(vm)

                Spacer(Modifier.height(8.dp))
                Disclosure(
                    label = "Per-address detail",
                    expanded = vm.counterDetailsExpanded,
                    onToggle = { vm.counterDetailsExpanded = !vm.counterDetailsExpanded },
                )
                if (vm.counterDetailsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    CounterDetailsContent(vm)
                }
            } else {
                // Nothing decoded into a percentage, so the detail is all there is to show. It is
                // not hidden behind a disclosure that would open onto the only content there is.
                CounterDetailsContent(vm)
            }
        }

        ResetViewModel.CounterView.HISTORY -> CounterHistoryPanel(vm)
    }
}

@Composable
private fun Disclosure(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverviewHeader(vm: ResetViewModel, model: PrinterModel?) {
    val overview = vm.overviewReading
    Row(verticalAlignment = Alignment.CenterVertically) {
        // No title and no printer name: the tab is already called Overview, and the target chip
        // above names the printer and model on every tab. What is left is what only this screen
        // knows — where the reading came from, and how old it is.
        Column(Modifier.weight(1f)) {
            if (overview == null) {
                Text(
                    "Select a live printer to collect overview information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    listOfNotNull(overview.linkKind, overview.firmware?.let { "firmware $it" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RefreshedAt(overview.refreshedAt)
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

/**
 * How old the reading is, in the terms a person thinks in, with the exact time on hover.
 *
 * "12:04:31" answers a question nobody asked; what matters is whether this is current. The exact
 * stamp stays reachable, because when it does matter it matters to the second.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefreshedAt(refreshedAt: Instant) {
    // Recomposition is driven by the refresh itself, so the label is re-derived on a timer of its
    // own — a reading that silently stays "just now" for an hour is the failure worth avoiding.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(refreshedAt) {
        while (true) {
            now = Instant.now()
            delay(STALE_TICK_MS)
        }
    }

    val age = Duration.between(refreshedAt, now).coerceAtLeast(Duration.ZERO)
    val label = when {
        age < Duration.ofMinutes(1) -> "just now"
        age < Duration.ofHours(1) -> "${age.toMinutes()} min ago"
        age < Duration.ofDays(1) -> "${age.toHours()} h ago"
        else -> OVERVIEW_TIME.format(refreshedAt)
    }

    TooltipArea(tooltip = { TooltipText(OVERVIEW_TIME.format(refreshedAt)) }) {
        Text(
            "Refreshed $label",
            style = MaterialTheme.typography.labelSmall,
            color = if (age >= STALE_AFTER) StatusColors.warn else StatusColors.muted,
        )
    }
}

@Composable
private fun TooltipText(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OverviewCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun AlertsCard(vm: ResetViewModel, overview: OverviewReading?) {
    OverviewCard {
        if (overview == null) {
            Text("Overview not refreshed yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Refresh reads connection details, status, supplies and counters. Missing sources stay " +
                    "listed as unavailable instead of being treated as good news.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@OverviewCard
        }

        val allReported = overview.coverage.all { it.available }
        Text(
            when (overview.alerts.size) {
                0 -> "No reported warnings"
                1 -> "1 thing needs attention"
                else -> "${overview.alerts.size} things need attention"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                overview.alerts.any { it.severity == OverviewAlert.Severity.ERROR } -> StatusColors.bad
                overview.alerts.isNotEmpty() -> StatusColors.warn
                allReported -> StatusColors.good
                else -> StatusColors.muted
            },
        )

        overview.alerts.forEach { alert ->
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        (if (alert.severity == OverviewAlert.Severity.ERROR) StatusColors.bad else StatusColors.warn)
                            .copy(alpha = 0.10f),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(alert.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(alert.detail, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
                }
                // A full pad is exactly when somebody wants the tab that deals with it.
                alert.action?.let { action ->
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { vm.tab = actionTab(action) }) { Text(action.label) }
                }
            }
        }
    }
}

private fun actionTab(action: OverviewAlert.Action): ResetViewModel.Tab = when (action) {
    OverviewAlert.Action.MAINTENANCE -> ResetViewModel.Tab.MAINTENANCE
}

/**
 * How the refresh itself went — last, and quiet when there is nothing to say.
 *
 * Five ticks reading "Status reported" is the app congratulating itself. What earns space is a
 * section that did *not* report, because that is the difference between a good reading and a
 * missing one being mistaken for good news.
 */
@Composable
private fun CoverageCard(overview: OverviewReading) {
    var expanded by remember { mutableStateOf(false) }
    val missing = overview.coverage.filterNot { it.available }
    val shown = if (expanded || missing.isEmpty()) overview.coverage else missing

    OverviewCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (missing.isEmpty()) {
                    "All ${overview.coverage.size} sections reported"
                } else {
                    "${missing.size} of ${overview.coverage.size} sections did not report"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (missing.isEmpty()) StatusColors.good else StatusColors.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "Hide detail" else "Show detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (expanded || missing.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            shown.forEach { coverage ->
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
