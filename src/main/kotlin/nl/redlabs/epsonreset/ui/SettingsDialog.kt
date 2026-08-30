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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.settings_about_author
import nl.redlabs.epsonreset.resources.settings_about_licence
import nl.redlabs.epsonreset.resources.settings_about_name
import nl.redlabs.epsonreset.resources.settings_about_section
import nl.redlabs.epsonreset.resources.settings_cancel
import nl.redlabs.epsonreset.resources.settings_choices_forget
import nl.redlabs.epsonreset.resources.settings_choices_forget_all
import nl.redlabs.epsonreset.resources.settings_choices_none
import nl.redlabs.epsonreset.resources.settings_choices_section
import nl.redlabs.epsonreset.resources.settings_close
import nl.redlabs.epsonreset.resources.settings_data_dir_body
import nl.redlabs.epsonreset.resources.settings_data_dir_open
import nl.redlabs.epsonreset.resources.settings_data_dir_section
import nl.redlabs.epsonreset.resources.settings_database_body
import nl.redlabs.epsonreset.resources.settings_database_error
import nl.redlabs.epsonreset.resources.settings_database_loaded
import nl.redlabs.epsonreset.resources.settings_database_loading
import nl.redlabs.epsonreset.resources.settings_database_section
import nl.redlabs.epsonreset.resources.settings_database_update
import nl.redlabs.epsonreset.resources.settings_developer_body
import nl.redlabs.epsonreset.resources.settings_developer_section
import nl.redlabs.epsonreset.resources.settings_developer_toggle
import nl.redlabs.epsonreset.resources.settings_history_body
import nl.redlabs.epsonreset.resources.settings_history_confirm_delete
import nl.redlabs.epsonreset.resources.settings_history_delete
import nl.redlabs.epsonreset.resources.settings_history_delete_prompt
import nl.redlabs.epsonreset.resources.settings_history_printers
import nl.redlabs.epsonreset.resources.settings_history_samples
import nl.redlabs.epsonreset.resources.settings_history_section
import nl.redlabs.epsonreset.resources.settings_history_stats
import nl.redlabs.epsonreset.resources.settings_history_toggle
import nl.redlabs.epsonreset.resources.settings_identification_body
import nl.redlabs.epsonreset.resources.settings_identification_section
import nl.redlabs.epsonreset.resources.settings_identification_toggle
import nl.redlabs.epsonreset.resources.settings_language_en
import nl.redlabs.epsonreset.resources.settings_language_section
import nl.redlabs.epsonreset.resources.settings_language_system
import nl.redlabs.epsonreset.resources.settings_maxima_body
import nl.redlabs.epsonreset.resources.settings_maxima_delete_overlay
import nl.redlabs.epsonreset.resources.settings_maxima_overlay
import nl.redlabs.epsonreset.resources.settings_maxima_section
import nl.redlabs.epsonreset.resources.settings_maxima_session
import nl.redlabs.epsonreset.resources.settings_maxima_shipped
import nl.redlabs.epsonreset.resources.settings_maxima_undo_session
import nl.redlabs.epsonreset.resources.settings_model_reports
import nl.redlabs.epsonreset.resources.settings_result_see_log
import nl.redlabs.epsonreset.resources.settings_title
import nl.redlabs.epsonreset.resources.settings_updates_body
import nl.redlabs.epsonreset.resources.settings_updates_check_now
import nl.redlabs.epsonreset.resources.settings_updates_checking
import nl.redlabs.epsonreset.resources.settings_updates_section
import nl.redlabs.epsonreset.resources.settings_updates_toggle
import nl.redlabs.epsonreset.resources.settings_updates_version
import nl.redlabs.epsonreset.update.AppVersion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
        title = stringResource(Res.string.settings_title),
    ) {
        // Inside the window, not around it: the OS window survives a language change this way.
        key(vm.language) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Language(vm)
                    Divider()
                    Identification(vm)
                    Divider()
                    CounterHistory(vm)
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
                    Developer(vm)

                    Divider()
                    About()
                    Divider()
                    OutlinedButton(onClick = {
                        vm.settingsOpen = false
                    }) { Text(stringResource(Res.string.settings_close)) }
                }
            }
        }
    }
}

@Composable
private fun CounterHistory(vm: ResetViewModel) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val stats = vm.history.stats

    Section(stringResource(Res.string.settings_history_section))

    Toggle(
        label = stringResource(Res.string.settings_history_toggle),
        checked = vm.keepCounterHistory,
        onChange = { vm.keepCounterHistory = it },
        body = stringResource(Res.string.settings_history_body),
    )

    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            Res.string.settings_history_stats,
            pluralStringResource(Res.plurals.settings_history_samples, stats.samples, stats.samples),
            pluralStringResource(Res.plurals.settings_history_printers, stats.printers, stats.printers),
            formatBytes(stats.bytes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        AppPaths.counterHistory.path,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = StatusColors.muted,
    )

    Spacer(Modifier.height(8.dp))
    if (confirmingDelete) {
        Text(
            stringResource(Res.string.settings_history_confirm_delete),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.bad,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            OutlinedButton(
                onClick = {
                    confirmingDelete = false
                    vm.history.deleteAll()
                },
                colors = dangerOutline(),
            ) { Text(stringResource(Res.string.settings_history_delete)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.settings_cancel)) }
        }
    } else {
        OutlinedButton(
            onClick = { confirmingDelete = true },
            enabled = stats.samples > 0,
            colors = dangerOutline(),
        ) { Text(stringResource(Res.string.settings_history_delete_prompt)) }
    }

    vm.history.actionStatus?.let { status ->
        Spacer(Modifier.height(6.dp))
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = if (vm.history.actionOk) StatusColors.good else StatusColors.bad,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Language(vm: ResetViewModel) {
    var menu by remember { mutableStateOf(false) }

    Section(stringResource(Res.string.settings_language_section))

    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
        OutlinedTextField(
            value = stringResource(labelFor(vm.language)),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .width(260.dp),
        )
        ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((tag, label) in LANGUAGES) {
                DropdownMenuItem(
                    text = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        menu = false
                        vm.applyLanguage(tag)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

private val LANGUAGES: List<Pair<String?, StringResource>> = listOf(
    null to Res.string.settings_language_system,
    "en" to Res.string.settings_language_en,
)

private fun labelFor(tag: String?): StringResource =
    LANGUAGES.firstOrNull { it.first == tag }?.second ?: Res.string.settings_language_system

@Composable
private fun Identification(vm: ResetViewModel) {
    Section(stringResource(Res.string.settings_identification_section))

    Toggle(
        label = stringResource(Res.string.settings_identification_toggle),
        checked = vm.crossCheckOverSnmp,
        onChange = { vm.crossCheckOverSnmp = it },
        body = stringResource(Res.string.settings_identification_body),
    )
}

@Composable
private fun Database(vm: ResetViewModel) {
    Section(stringResource(Res.string.settings_database_section))

    Text(
        vm.database?.let { stringResource(Res.string.settings_database_loaded, it.size, it.source.name.lowercase()) }
            ?: vm.databaseError?.let { stringResource(Res.string.settings_database_error, it) }
            ?: stringResource(Res.string.settings_database_loading),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(Res.string.settings_database_body),
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.muted,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        vm.refreshDatabaseFromNetwork()
    }) { Text(stringResource(Res.string.settings_database_update)) }
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
            stringResource(Res.string.settings_result_see_log),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.muted,
        )
    }
}

@Composable
private fun AppUpdate(vm: ResetViewModel, updates: AppUpdates) {
    val scope = rememberCoroutineScope()

    Section(stringResource(Res.string.settings_updates_section))

    Text(
        stringResource(Res.string.settings_updates_version, AppVersion.display),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Toggle(
        label = stringResource(Res.string.settings_updates_toggle),
        checked = vm.checkForUpdates,
        onChange = { vm.checkForUpdates = it },
        body = stringResource(Res.string.settings_updates_body),
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        enabled = !updates.checking,
        onClick = { scope.launch { updates.check(vm, automatic = false) } },
    ) {
        Text(
            if (updates.checking) {
                stringResource(Res.string.settings_updates_checking)
            } else {
                stringResource(Res.string.settings_updates_check_now)
            },
        )
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
    Section(stringResource(Res.string.settings_maxima_section))

    Text(
        stringResource(Res.string.settings_maxima_body),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Text(
        when {
            vm.calibration.overlayInForce -> stringResource(Res.string.settings_maxima_overlay)
            vm.calibration.applied -> stringResource(Res.string.settings_maxima_session)
            else -> stringResource(Res.string.settings_maxima_shipped)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (vm.calibration.overlayInForce || vm.calibration.applied) StatusColors.warn else StatusColors.muted,
    )

    if (!vm.calibration.overlayInForce && !vm.calibration.applied) return

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vm.calibration.applied) {
            OutlinedButton(
                onClick = { vm.calibration.revertSession() },
            ) { Text(stringResource(Res.string.settings_maxima_undo_session)) }
            Spacer(Modifier.width(8.dp))
        }
        if (vm.calibration.overlayInForce) {
            OutlinedButton(onClick = { vm.calibration.removeCounterOverlay() }, colors = dangerOutline()) {
                Text(stringResource(Res.string.settings_maxima_delete_overlay))
            }
        }
    }
}

/** Where everything the app keeps between runs lives, and the way to it. */
@Composable
private fun DataDirectory(vm: ResetViewModel) {
    Section(stringResource(Res.string.settings_data_dir_section))

    Text(
        stringResource(Res.string.settings_data_dir_body),
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
    OutlinedButton(onClick = {
        vm.calibration.openDataDirectory()
    }) { Text(stringResource(Res.string.settings_data_dir_open)) }
}

/**
 * The answers given to the question a family-naming printer leaves open. Listed because they are
 * otherwise invisible — the app stops asking once one is on file, so a wrong one would never
 * surface again on its own.
 */
@Composable
private fun RememberedChoices(vm: ResetViewModel) {
    Section(stringResource(Res.string.settings_choices_section))

    val choices = vm.rememberedChoices
    if (choices.isEmpty()) {
        Text(
            stringResource(Res.string.settings_choices_none),
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
                    stringResource(Res.string.settings_model_reports, choice.reported),
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
            TextButton(
                onClick = { vm.forgetRememberedChoice(choice.key) },
                enabled = vm.canChangeTarget,
            ) { Text(stringResource(Res.string.settings_choices_forget)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.forgetAllRememberedChoices() }, enabled = vm.canChangeTarget) {
        Text(stringResource(Res.string.settings_choices_forget_all), color = StatusColors.bad)
    }
}

@Composable
private fun Developer(vm: ResetViewModel) {
    Section(stringResource(Res.string.settings_developer_section))

    Toggle(
        label = stringResource(Res.string.settings_developer_toggle),
        checked = vm.developerMode,
        onChange = { vm.developerMode = it },
        body = stringResource(Res.string.settings_developer_body),
    )
}

@Composable
private fun About() {
    Section(stringResource(Res.string.settings_about_section))

    Text(
        stringResource(Res.string.settings_about_name, AppVersion.display),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(Res.string.settings_about_author),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(Res.string.settings_about_licence),
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
