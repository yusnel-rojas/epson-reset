package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.launch
import nl.redlabs.epsonreset.update.AppVersion

/**
 * The errands: what the app fetches, and what it is allowed to work out for itself. A window rather
 * than a tab, because none of it is part of resetting a printer — see CalibrationDialog, which is
 * here for the same reason.
 */
@Composable
fun SettingsDialog(vm: ResetViewModel, updates: AppUpdates) {
    if (!vm.settingsOpen) return

    DialogWindow(
        onCloseRequest = { vm.settingsOpen = false },
        state = rememberDialogState(size = DpSize(660.dp, 700.dp)),
        title = "Settings",
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Identification(vm)
                Divider()
                Database(vm)
                Divider()
                AppUpdate(vm, updates)
                Divider()
                CounterMaxima(vm)
                Divider()
                RememberedChoices(vm)
                Divider()
                DataDirectory(vm)

                Divider()
                About()
                Divider()
                OutlinedButton(onClick = { vm.settingsOpen = false }) { Text("Close") }
            }
        }
    }
}

@Composable
private fun Identification(vm: ResetViewModel) {
    Section("Identification")

    Toggle(
        label = "Resolve the exact model over SNMP",
        checked = vm.crossCheckOverSnmp,
        onChange = { vm.crossCheckOverSnmp = it },
        body = "A USB descriptor names a family — \"ET-2820 Series\" covers eight units that are " +
            "not all the same printer. The same machine on the network answers SNMP with the unit. " +
            "When both links show the same serial, the unit is used. Costs no extra traffic: the " +
            "network entry has already been asked by the time the two are compared.",
    )
}

@Composable
private fun Database(vm: ResetViewModel) {
    Section("Printer database")

    Text(
        vm.database?.let { "${it.size} models · ${it.source.name.lowercase()}" }
            ?: vm.databaseError?.let { "could not be loaded — $it" }
            ?: "loading…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "The copy in the jar is used until a download replaces it. A download that does not parse " +
            "is discarded rather than cached, so a failed update keeps the working copy.",
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.muted,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.refreshDatabaseFromNetwork() }) { Text("Update database") }
    Result(vm.databaseUpdateStatus)
}

/**
 * The answer to whatever was just clicked, where it was clicked — the outcome in a sentence, and a
 * pointer to the log for the URL, HTTP code or exception behind it.
 */
@Composable
private fun Result(outcome: ResetViewModel.Outcome?) {
    val result = outcome ?: return

    Spacer(Modifier.height(6.dp))
    Text(
        result.text,
        style = MaterialTheme.typography.labelSmall,
        color = if (result.ok) StatusColors.good else StatusColors.bad,
    )
    if (!result.ok) {
        Text(
            "The log, at the bottom of the main window, has the detail.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun AppUpdate(vm: ResetViewModel, updates: AppUpdates) {
    val scope = rememberCoroutineScope()

    Section("App updates")

    Text(
        "Version ${AppVersion.display}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Toggle(
        label = "Check automatically",
        checked = vm.checkForUpdates,
        onChange = { vm.checkForUpdates = it },
        body = "Once a day at most, and never for a development build. A check that cannot reach " +
            "GitHub does not count against the day's allowance.",
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        enabled = !updates.checking,
        onClick = { scope.launch { updates.check(vm, automatic = false) } },
    ) {
        Text(if (updates.checking) "Checking…" else "Check now")
    }
    Result(updates.lastResult)
}

/**
 * The undo for a measurement that turned out to be wrong.
 *
 * A maximum is what every percentage is divided by, so one bad figure makes every reading of that
 * counter wrong — and the two ways to apply one differ in exactly the way that matters when you
 * want it back: the session is gone at exit, and the file is not.
 */
@Composable
private fun CounterMaxima(vm: ResetViewModel) {
    Section("Counter maxima")

    Text(
        "Percentages need a maximum, and most counters ship without one. A calibration supplies " +
            "it. \"Use this maximum now\" on the calibration form applies one to this session " +
            "only — it is gone at the next launch. Saving counters-overlay.json into the data " +
            "directory applies it permanently, and nothing but removing that file undoes it.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Text(
        when {
            vm.calibration.overlayInForce ->
                "An overlay file is in force. Counters it names read from it, not " +
                    "from the shipped figures."
            vm.calibration.applied -> "A calibration is applied to this session only."
            else -> "No overlay and no session calibration — the shipped figures are in use."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (vm.calibration.overlayInForce || vm.calibration.applied) StatusColors.warn else StatusColors.muted,
    )

    if (!vm.calibration.overlayInForce && !vm.calibration.applied) return

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vm.calibration.applied) {
            OutlinedButton(onClick = { vm.calibration.revertSession() }) { Text("Undo for this session") }
            Spacer(Modifier.width(8.dp))
        }
        if (vm.calibration.overlayInForce) {
            OutlinedButton(onClick = { vm.calibration.removeCounterOverlay() }, colors = dangerOutline()) {
                Text("Delete the overlay file")
            }
        }
    }
}

/** Where everything the app keeps between runs lives, and the way to it. */
@Composable
private fun DataDirectory(vm: ResetViewModel) {
    Section("Data directory")

    Text(
        "The database cache, saved snapshots, the overlay, remembered choices and preferences. " +
            "Every one of them is a plain file you can read, edit or delete.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        nl.redlabs.epsonreset.AppPaths.dataDir.path,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = StatusColors.muted,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.calibration.openDataDirectory() }) { Text("Open data directory") }
}

/**
 * The answers given to the question a family-naming printer leaves open. Listed because they are
 * otherwise invisible — the app stops asking once one is on file, so a wrong one would never
 * surface again on its own.
 */
@Composable
private fun RememberedChoices(vm: ResetViewModel) {
    Section("Remembered model choices")

    val choices = vm.rememberedChoices
    if (choices.isEmpty()) {
        Text(
            "None. One is filed whenever a printer names a family whose members do not share a " +
                "reset recipe, and you say which of them it is.",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
        return
    }

    for (choice in choices) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    choice.model,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "reports \"${choice.reported}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    choice.key,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )
            }
            TextButton(onClick = { vm.forgetRememberedChoice(choice.key) }) { Text("Forget") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.forgetAllRememberedChoices() }) {
        Text("Forget all", color = StatusColors.bad)
    }
}

@Composable
private fun About() {
    Section("About")

    Text(
        "Epson Reset ${AppVersion.display}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "by Yusnel Rojas",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Licensed under the GNU Affero General Public License v3.0",
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.muted,
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.height(20.dp))
}
