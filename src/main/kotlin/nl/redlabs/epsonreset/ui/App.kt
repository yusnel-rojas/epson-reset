package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.redlabs.epsonreset.prefs.PreferencesStore
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.app_name
import nl.redlabs.epsonreset.resources.tab_inspect
import nl.redlabs.epsonreset.resources.tab_maintenance
import nl.redlabs.epsonreset.resources.tab_models
import nl.redlabs.epsonreset.resources.tab_overview
import nl.redlabs.epsonreset.resources.tab_snapshots
import nl.redlabs.epsonreset.resources.topbar_database
import nl.redlabs.epsonreset.resources.topbar_database_error
import nl.redlabs.epsonreset.resources.topbar_database_loading
import nl.redlabs.epsonreset.resources.topbar_update_available
import org.jetbrains.compose.resources.stringResource

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val vm = remember { ResetViewModel(scope) }
    val updates = remember { AppUpdates() }

    RememberedState(vm)

    LaunchedEffect(Unit) { vm.start(PreferencesStore.current().lastPrinterId) }
    LaunchedEffect(Unit) { updates.check(vm, automatic = true) }

    EpsonResetTheme {
        SettingsDialog(vm, updates)
        RunCompletionDialog(vm)

        key(vm.language) { AppContent(vm, updates) }
    }
}

@Composable
private fun AppContent(vm: ResetViewModel, updates: AppUpdates) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, updates)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            when (vm.tab) {
                ResetViewModel.Tab.COUNTERS ->
                    // Printer and model now form one application-scoped target in the top bar.
                    OverviewPanel(vm, Modifier.fillMaxWidth().weight(1f))

                // The matrix is about the database rather than one printer, so it takes the
                // whole width — there is no selection for a sidebar to show.
                ResetViewModel.Tab.MODELS ->
                    CapabilityMatrix(vm, Modifier.fillMaxWidth().weight(1f))

                // Same reasoning: the inspector is a guided sequence, not a selection.
                ResetViewModel.Tab.INSPECT ->
                    InspectPanel(vm, Modifier.fillMaxWidth().weight(1f))

                // Also a guided sequence: establish need before the ink-spending operation.
                ResetViewModel.Tab.MAINTENANCE ->
                    MaintenancePanel(vm, Modifier.fillMaxWidth().weight(1f))

                // Its own selection — a file, not a printer — so it brings its own sidebar.
                ResetViewModel.Tab.SNAPSHOTS ->
                    SnapshotPanel(vm, Modifier.fillMaxWidth().weight(1f))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            // Height belongs to the panel now — it decides it from its collapsed state.
            LogPanel(vm, Modifier.fillMaxWidth())
        }
    }
}

/** Reconnects the view model to what the last session left behind, and keeps it written down. */
@Composable
private fun RememberedState(vm: ResetViewModel) {
    // Restore and observe in one effect, in that order.
    LaunchedEffect(Unit) {
        vm.applyLanguage(PreferencesStore.current().language)
        snapshotFlow { vm.language }.collect { tag ->
            PreferencesStore.update { it.copy(language = tag) }
        }
    }

    LaunchedEffect(Unit) {
        vm.logCollapsed = PreferencesStore.current().logCollapsed
        snapshotFlow { vm.logCollapsed }.collect { collapsed ->
            PreferencesStore.update { it.copy(logCollapsed = collapsed) }
        }
    }

    LaunchedEffect(Unit) {
        vm.crossCheckOverSnmp = PreferencesStore.current().crossCheckOverSnmp
        snapshotFlow { vm.crossCheckOverSnmp }.collect { on ->
            PreferencesStore.update { it.copy(crossCheckOverSnmp = on) }
        }
    }

    LaunchedEffect(Unit) {
        vm.checkForUpdates = PreferencesStore.current().checkForUpdates
        snapshotFlow { vm.checkForUpdates }.collect { on ->
            PreferencesStore.update { it.copy(checkForUpdates = on) }
        }
    }

    LaunchedEffect(Unit) {
        vm.keepCounterHistory = PreferencesStore.current().keepCounterHistory
        snapshotFlow { vm.keepCounterHistory }.collect { on ->
            PreferencesStore.update { it.copy(keepCounterHistory = on) }
        }
    }

    LaunchedEffect(Unit) {
        vm.developerMode = PreferencesStore.current().developerMode
        vm.applyDeveloperMode(vm.developerMode)
        snapshotFlow { vm.developerMode }.collect { on ->
            PreferencesStore.update { it.copy(developerMode = on) }
            vm.applyDeveloperMode(on)
        }
    }

    // The model is only restorable once the database it names exists. A name that no longer matches
    // an entry — a renamed model, a rolled-back database — is dropped in silence;
    LaunchedEffect(Unit) {
        val name = PreferencesStore.current().lastModel ?: return@LaunchedEffect
        snapshotFlow { vm.database }.filterNotNull().first()[name]?.let { vm.restoreModel(it) }
    }

    // Only a real selection is recorded. The selection is null at launch and briefly null again
    // whenever it is being replaced, and neither is the user forgetting their printer.
    LaunchedEffect(Unit) {
        snapshotFlow { vm.selectedModel?.name }.filterNotNull().collect { name ->
            PreferencesStore.update { it.copy(lastModel = name) }
        }
    }

    // Keep the actual printer as well as its model. A USB/network pair can name the same unit, but
    // the exact link is still the least surprising default on the next launch.
    LaunchedEffect(Unit) {
        snapshotFlow { vm.selectedDevice?.device?.id }.filterNotNull().collect { id ->
            PreferencesStore.update { it.copy(lastPrinterId = id) }
        }
    }
}

@Composable
private fun TopBar(vm: ResetViewModel, updates: AppUpdates) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                vm.database?.let { stringResource(Res.string.topbar_database, it.size) }
                    ?: vm.databaseError?.let { stringResource(Res.string.topbar_database_error) }
                    ?: stringResource(Res.string.topbar_database_loading),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }

        Spacer(Modifier.width(20.dp))

        // The tabs take the slack instead of a plain spacer, and wrap onto a second line inside it
        // rather than being clipped. Everything after them is measured first and so always has
        // room: the gear is how you reach Settings, and wider words must not be able to hide it.
        Box(Modifier.weight(1f)) { Tabs(vm) }

        Spacer(Modifier.width(12.dp))
        PrinterChip(vm)
        Spacer(Modifier.width(10.dp))

        // News, not a setting: a release that exists is worth saying without being asked. Running
        // the check, and everything else that was along this row, is in the window behind the gear.
        updates.available?.let { release ->
            TextButton(onClick = { updates.openReleasePage(vm) }) {
                Text(
                    stringResource(Res.string.topbar_update_available, release.version),
                    color = StatusColors.good,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        TextButton(onClick = { vm.openSettings() }) {
            Text("⚙", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Tabs(vm: ResetViewModel) {
    // A translation whose words are longer takes a second line here instead of losing a tab off
    // the end. Every tab stays visible and clickable at the narrowest window the app allows.
    FlowRow(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Keep the internal COUNTERS route stable; only the experience and visible name change.
        Tab(stringResource(Res.string.tab_overview), vm.tab == ResetViewModel.Tab.COUNTERS) {
            vm.tab = ResetViewModel.Tab.COUNTERS
        }
        Tab(stringResource(Res.string.tab_maintenance), vm.tab == ResetViewModel.Tab.MAINTENANCE) {
            vm.tab = ResetViewModel.Tab.MAINTENANCE
        }
        Tab(stringResource(Res.string.tab_snapshots), vm.tab == ResetViewModel.Tab.SNAPSHOTS) {
            vm.snapshot.showAllSnapshots()
            vm.tab = ResetViewModel.Tab.SNAPSHOTS
        }
        Tab(stringResource(Res.string.tab_inspect), vm.tab == ResetViewModel.Tab.INSPECT) {
            vm.tab = ResetViewModel.Tab.INSPECT
        }
        Tab(stringResource(Res.string.tab_models), vm.tab == ResetViewModel.Tab.MODELS) {
            vm.tab = ResetViewModel.Tab.MODELS
        }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
