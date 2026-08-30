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
import nl.redlabs.epsonreset.i18n.resolve
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.overview_age_hours
import nl.redlabs.epsonreset.resources.overview_age_just_now
import nl.redlabs.epsonreset.resources.overview_age_minutes
import nl.redlabs.epsonreset.resources.overview_attention_count
import nl.redlabs.epsonreset.resources.overview_cancel_refresh
import nl.redlabs.epsonreset.resources.overview_choose_printer
import nl.redlabs.epsonreset.resources.overview_empty_choose
import nl.redlabs.epsonreset.resources.overview_empty_model_kept
import nl.redlabs.epsonreset.resources.overview_empty_needs_printer
import nl.redlabs.epsonreset.resources.overview_empty_scanning
import nl.redlabs.epsonreset.resources.overview_empty_scanning_body
import nl.redlabs.epsonreset.resources.overview_firmware
import nl.redlabs.epsonreset.resources.overview_hide_detail
import nl.redlabs.epsonreset.resources.overview_no_model
import nl.redlabs.epsonreset.resources.overview_no_model_choose
import nl.redlabs.epsonreset.resources.overview_no_model_scan
import nl.redlabs.epsonreset.resources.overview_no_warnings
import nl.redlabs.epsonreset.resources.overview_not_refreshed
import nl.redlabs.epsonreset.resources.overview_not_refreshed_body
import nl.redlabs.epsonreset.resources.overview_open_maintenance
import nl.redlabs.epsonreset.resources.overview_per_address_detail
import nl.redlabs.epsonreset.resources.overview_printers_found
import nl.redlabs.epsonreset.resources.overview_refresh
import nl.redlabs.epsonreset.resources.overview_refreshed
import nl.redlabs.epsonreset.resources.overview_scan
import nl.redlabs.epsonreset.resources.overview_sections_all
import nl.redlabs.epsonreset.resources.overview_sections_missing
import nl.redlabs.epsonreset.resources.overview_select_live_printer
import nl.redlabs.epsonreset.resources.overview_show_detail
import nl.redlabs.epsonreset.resources.overview_stop_scanning
import nl.redlabs.epsonreset.resources.overview_view_counters
import nl.redlabs.epsonreset.resources.overview_view_history
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
                stringResource(Res.string.overview_empty_scanning)
            } else {
                stringResource(Res.string.overview_empty_choose)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                vm.scanState is ResetViewModel.ScanState.Scanning ->
                    stringResource(Res.string.overview_empty_scanning_body)

                vm.devices.isNotEmpty() ->
                    pluralStringResource(Res.plurals.overview_printers_found, vm.devices.size, vm.devices.size)

                else ->
                    stringResource(Res.string.overview_empty_needs_printer)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.overview_empty_model_kept, it.name),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.warn,
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                vm.scanState is ResetViewModel.ScanState.Scanning ->
                    OutlinedButton(onClick = { vm.scan() }) { Text(stringResource(Res.string.overview_stop_scanning)) }
                vm.devices.isEmpty() ->
                    Button(onClick = {
                        vm.scan()
                    }, enabled = vm.canScan) { Text(stringResource(Res.string.overview_scan)) }
                else ->
                    Button(onClick = vm::requestPrinterMenu) {
                        Text(stringResource(Res.string.overview_choose_printer))
                    }
            }
            if (model != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.tab = ResetViewModel.Tab.MAINTENANCE }) {
                    Text(stringResource(Res.string.overview_open_maintenance))
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
            label = stringResource(Res.string.overview_view_counters),
            selected = selected == ResetViewModel.CounterView.COUNTERS,
        ) { vm.counterView = ResetViewModel.CounterView.COUNTERS }
        CounterViewButton(
            label = stringResource(Res.string.overview_view_history),
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
                    label = stringResource(Res.string.overview_per_address_detail),
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
                    stringResource(Res.string.overview_select_live_printer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    listOfNotNull(
                        overview.linkKind,
                        overview.firmware?.let { stringResource(Res.string.overview_firmware, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RefreshedAt(overview.refreshedAt)
            }
        }

        if (vm.selectedDevice != null) {
            Spacer(Modifier.width(16.dp))
            if (vm.overviewRefreshing) {
                OutlinedButton(onClick = vm::cancel) { Text(stringResource(Res.string.overview_cancel_refresh)) }
            } else {
                Button(
                    onClick = vm::refreshOverview,
                    enabled = vm.canRefreshOverview,
                ) { Text(stringResource(Res.string.overview_refresh)) }
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
        age < Duration.ofMinutes(1) -> stringResource(Res.string.overview_age_just_now)
        age < Duration.ofHours(1) -> stringResource(Res.string.overview_age_minutes, age.toMinutes())
        age < Duration.ofDays(1) -> stringResource(Res.string.overview_age_hours, age.toHours())
        else -> OVERVIEW_TIME.format(refreshedAt)
    }

    TooltipArea(tooltip = { TooltipText(OVERVIEW_TIME.format(refreshedAt)) }) {
        Text(
            stringResource(Res.string.overview_refreshed, label),
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
            Text(
                stringResource(Res.string.overview_not_refreshed),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.overview_not_refreshed_body),
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            return@OverviewCard
        }

        val allReported = overview.coverage.all { it.available }
        Text(
            if (overview.alerts.isEmpty()) {
                stringResource(Res.string.overview_no_warnings)
            } else {
                pluralStringResource(Res.plurals.overview_attention_count, overview.alerts.size, overview.alerts.size)
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
                    Text(
                        alert.title.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        alert.detail.resolve(),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.muted,
                    )
                }
                // A full pad is exactly when somebody wants the tab that deals with it.
                alert.action?.let { action ->
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { vm.tab = actionTab(action) }) { Text(stringResource(action.label)) }
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
                    pluralStringResource(
                        Res.plurals.overview_sections_all,
                        overview.coverage.size,
                        overview.coverage.size,
                    )
                } else {
                    stringResource(Res.string.overview_sections_missing, missing.size, overview.coverage.size)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (missing.isEmpty()) StatusColors.good else StatusColors.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) {
                    stringResource(Res.string.overview_hide_detail)
                } else {
                    stringResource(Res.string.overview_show_detail)
                },
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
                        stringResource(coverage.section.label),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        coverage.detail.resolve(),
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
                stringResource(Res.string.overview_no_model),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.devices.isEmpty()) {
                    stringResource(Res.string.overview_no_model_scan)
                } else {
                    stringResource(Res.string.overview_no_model_choose)
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
        }
    }
}
