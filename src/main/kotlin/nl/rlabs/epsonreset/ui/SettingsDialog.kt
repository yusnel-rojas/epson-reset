package nl.rlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.launch
import nl.rlabs.epsonreset.AppPaths
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.settings_about_author
import nl.rlabs.epsonreset.resources.settings_about_licence
import nl.rlabs.epsonreset.resources.settings_about_name
import nl.rlabs.epsonreset.resources.settings_about_section
import nl.rlabs.epsonreset.resources.settings_cancel
import nl.rlabs.epsonreset.resources.settings_choices_forget
import nl.rlabs.epsonreset.resources.settings_choices_forget_all
import nl.rlabs.epsonreset.resources.settings_choices_none
import nl.rlabs.epsonreset.resources.settings_choices_section
import nl.rlabs.epsonreset.resources.settings_data_dir_body
import nl.rlabs.epsonreset.resources.settings_data_dir_open
import nl.rlabs.epsonreset.resources.settings_data_dir_section
import nl.rlabs.epsonreset.resources.settings_database_body
import nl.rlabs.epsonreset.resources.settings_database_error
import nl.rlabs.epsonreset.resources.settings_database_loaded
import nl.rlabs.epsonreset.resources.settings_database_loading
import nl.rlabs.epsonreset.resources.settings_database_section
import nl.rlabs.epsonreset.resources.settings_database_update
import nl.rlabs.epsonreset.resources.settings_developer_body
import nl.rlabs.epsonreset.resources.settings_developer_section
import nl.rlabs.epsonreset.resources.settings_developer_toggle
import nl.rlabs.epsonreset.resources.settings_history_body
import nl.rlabs.epsonreset.resources.settings_history_confirm_delete
import nl.rlabs.epsonreset.resources.settings_history_delete
import nl.rlabs.epsonreset.resources.settings_history_delete_prompt
import nl.rlabs.epsonreset.resources.settings_history_printers
import nl.rlabs.epsonreset.resources.settings_history_samples
import nl.rlabs.epsonreset.resources.settings_history_section
import nl.rlabs.epsonreset.resources.settings_history_stats
import nl.rlabs.epsonreset.resources.settings_history_toggle
import nl.rlabs.epsonreset.resources.settings_identification_body
import nl.rlabs.epsonreset.resources.settings_identification_section
import nl.rlabs.epsonreset.resources.settings_identification_toggle
import nl.rlabs.epsonreset.resources.settings_language_body
import nl.rlabs.epsonreset.resources.settings_language_en
import nl.rlabs.epsonreset.resources.settings_language_es
import nl.rlabs.epsonreset.resources.settings_language_section
import nl.rlabs.epsonreset.resources.settings_language_system
import nl.rlabs.epsonreset.resources.settings_maxima_body
import nl.rlabs.epsonreset.resources.settings_maxima_delete_overlay
import nl.rlabs.epsonreset.resources.settings_maxima_overlay
import nl.rlabs.epsonreset.resources.settings_maxima_section
import nl.rlabs.epsonreset.resources.settings_maxima_session
import nl.rlabs.epsonreset.resources.settings_maxima_shipped
import nl.rlabs.epsonreset.resources.settings_maxima_undo_session
import nl.rlabs.epsonreset.resources.settings_model_reports
import nl.rlabs.epsonreset.resources.settings_nav_advanced
import nl.rlabs.epsonreset.resources.settings_nav_general
import nl.rlabs.epsonreset.resources.settings_nav_printers
import nl.rlabs.epsonreset.resources.settings_result_see_log
import nl.rlabs.epsonreset.resources.settings_title
import nl.rlabs.epsonreset.resources.settings_updates_body
import nl.rlabs.epsonreset.resources.settings_updates_check_now
import nl.rlabs.epsonreset.resources.settings_updates_checking
import nl.rlabs.epsonreset.resources.settings_updates_dev
import nl.rlabs.epsonreset.resources.settings_updates_section
import nl.rlabs.epsonreset.resources.settings_updates_toggle
import nl.rlabs.epsonreset.resources.settings_updates_version
import nl.rlabs.epsonreset.update.AppVersion
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The errands: what the app fetches, and what it is allowed to work out for itself. A window rather
 * than a tab, because none of it is part of resetting a printer — see CalibrationDialog, which is
 * here for the same reason.
 *
 * Laid out as a rail beside one scrolling pane. Ten sections down a single scroll read as a wall;
 * four names you can point at do not.
 */
@Composable
fun SettingsDialog(vm: ResetViewModel, updates: AppUpdates) {
    if (!vm.settingsOpen) return

    DialogWindow(
        onCloseRequest = { vm.settingsOpen = false },
        state = rememberDialogState(size = DpSize(700.dp, 520.dp)),
        title = stringResource(Res.string.settings_title),
    ) {
        // Above the key() below on purpose: changing the language rebuilds everything under it, and
        // a rebuild that also moved you back to General would lose your place mid-errand.
        var group by remember { mutableStateOf(SettingsGroup.GENERAL) }

        // Inside the window, not around it: the OS window survives a language change this way.
        key(vm.language) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    Rail(group, onSelect = { group = it })
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (group) {
                            SettingsGroup.GENERAL -> {
                                Language(vm)
                                Break()
                                AppUpdate(vm, updates)
                            }

                            // Everything about working out which printer is in front of you: how
                            // it is identified, what it is identified against, and the answers
                            // already given for the models that cannot identify themselves.
                            SettingsGroup.PRINTERS -> {
                                Identification(vm)
                                Break()
                                Database(vm)
                                Break()
                                RememberedChoices(vm)
                            }

                            SettingsGroup.ADVANCED -> {
                                CounterHistory(vm)
                                Break()
                                CounterMaxima(vm)
                                Break()
                                DataDirectory(vm)
                                Break()
                                Developer(vm)
                            }

                            SettingsGroup.ABOUT -> About()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The rail. Names are stable identifiers; only the label each one carries is translated —
 * so a language change cannot invalidate the selection held above the key().
 */
private enum class SettingsGroup(val label: StringResource) {
    GENERAL(Res.string.settings_nav_general),
    PRINTERS(Res.string.settings_nav_printers),
    ADVANCED(Res.string.settings_nav_advanced),
    ABOUT(Res.string.settings_about_section),
}

@Composable
private fun Rail(current: SettingsGroup, onSelect: (SettingsGroup) -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
    ) {
        for (group in SettingsGroup.entries) {
            val active = group == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onSelect(group) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(group.label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CounterHistory(vm: ResetViewModel) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val stats = vm.history.stats

    Group(stringResource(Res.string.settings_history_section)) {
        Toggle(
            label = stringResource(Res.string.settings_history_toggle),
            checked = vm.keepCounterHistory,
            onChange = { vm.keepCounterHistory = it },
            body = stringResource(Res.string.settings_history_body),
        )

        Column {
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
        }

        if (confirmingDelete) {
            Column {
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
                    TextButton(onClick = { confirmingDelete = false }) {
                        Text(stringResource(Res.string.settings_cancel))
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { confirmingDelete = true },
                enabled = stats.samples > 0,
                colors = dangerOutline(),
            ) { Text(stringResource(Res.string.settings_history_delete_prompt)) }
        }

        vm.history.actionStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.history.actionOk) StatusColors.good else StatusColors.bad,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun Language(vm: ResetViewModel) {
    var menu by remember { mutableStateOf(false) }

    Group(stringResource(Res.string.settings_language_section)) {
        // A choice, so it looks like one: the same plain DropdownMenu every other picker in the app
        // uses, sized to its longest name rather than to a fixed width. The heading above already
        // says "Language", so the row carries only what the setting does.
        SettingRow(label = null, body = stringResource(Res.string.settings_language_body)) {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(stringResource(labelFor(vm.language)), style = MaterialTheme.typography.bodySmall)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((tag, label) in LANGUAGES) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                menu = false
                                vm.applyLanguage(tag)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every language the app ships, named in itself — so the one you want is findable even while the UI
 * is in one you cannot read. Both catalogs already carry these two as endonyms.
 */
private val LANGUAGES: List<Pair<String?, StringResource>> = listOf(
    null to Res.string.settings_language_system,
    "en" to Res.string.settings_language_en,
    "es" to Res.string.settings_language_es,
)

private fun labelFor(tag: String?): StringResource =
    LANGUAGES.firstOrNull { it.first == tag }?.second ?: Res.string.settings_language_system

@Composable
private fun Identification(vm: ResetViewModel) {
    Group(stringResource(Res.string.settings_identification_section)) {
        Toggle(
            label = stringResource(Res.string.settings_identification_toggle),
            checked = vm.crossCheckOverSnmp,
            onChange = { vm.crossCheckOverSnmp = it },
            body = stringResource(Res.string.settings_identification_body),
        )
    }
}

@Composable
private fun Database(vm: ResetViewModel) {
    Group(stringResource(Res.string.settings_database_section)) {
        Column {
            Text(
                vm.database?.let { stringResource(Res.string.settings_database_loaded, it.size) }
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
        }
        OutlinedButton(onClick = {
            vm.refreshDatabaseFromNetwork()
        }) { Text(stringResource(Res.string.settings_database_update)) }
        Result(vm.databaseUpdateStatus)
    }
}

/**
 * The answer to whatever was just clicked, where it was clicked — the outcome in a sentence, and a
 * pointer to the log for the URL, HTTP code or exception behind it.
 */
@Composable
private fun Result(outcome: ResetViewModel.Outcome?) {
    val result = outcome ?: return

    Column {
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
}

@Composable
private fun AppUpdate(vm: ResetViewModel, updates: AppUpdates) {
    val scope = rememberCoroutineScope()

    // The automatic check already declines to run on a dev build (AppUpdates.check), because there
    // is no version for the release feed to be newer than: compare() cannot order "1.2.0" against
    // "dev" and returns Unknown. The button was the one way left to reach that dead end, and it
    // reported it as a failure — so close it here too, and say why rather than going quiet.
    val dev = AppVersion.isDev

    Group(stringResource(Res.string.settings_updates_section)) {
        Text(
            stringResource(Res.string.settings_updates_version, AppVersion.display),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Toggle(
            label = stringResource(Res.string.settings_updates_toggle),
            checked = vm.checkForUpdates,
            onChange = { vm.checkForUpdates = it },
            body = stringResource(Res.string.settings_updates_body),
        )

        OutlinedButton(
            enabled = !updates.checking && !dev,
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
        if (dev) {
            Text(
                stringResource(Res.string.settings_updates_dev),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
        Result(updates.lastResult)
    }
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
    val overlay = vm.calibration.overlayInForce
    val session = vm.calibration.applied

    Group(stringResource(Res.string.settings_maxima_section)) {
        Column {
            Text(
                stringResource(Res.string.settings_maxima_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    overlay -> stringResource(Res.string.settings_maxima_overlay)
                    session -> stringResource(Res.string.settings_maxima_session)
                    else -> stringResource(Res.string.settings_maxima_shipped)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (overlay || session) StatusColors.warn else StatusColors.muted,
            )
        }

        // Nothing to undo while the shipped figures are the ones in force.
        if (overlay || session) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session) {
                    OutlinedButton(
                        onClick = { vm.calibration.revertSession() },
                    ) { Text(stringResource(Res.string.settings_maxima_undo_session)) }
                    Spacer(Modifier.width(8.dp))
                }
                if (overlay) {
                    OutlinedButton(onClick = { vm.calibration.removeCounterOverlay() }, colors = dangerOutline()) {
                        Text(stringResource(Res.string.settings_maxima_delete_overlay))
                    }
                }
            }
        }
    }
}

/** Where everything the app keeps between runs lives, and the way to it. */
@Composable
private fun DataDirectory(vm: ResetViewModel) {
    Group(stringResource(Res.string.settings_data_dir_section)) {
        Column {
            Text(
                stringResource(Res.string.settings_data_dir_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                AppPaths.dataDir.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = StatusColors.muted,
            )
        }
        OutlinedButton(onClick = {
            vm.calibration.openDataDirectory()
        }) { Text(stringResource(Res.string.settings_data_dir_open)) }
    }
}

/**
 * The answers given to the question a family-naming printer leaves open. Listed because they are
 * otherwise invisible — the app stops asking once one is on file, so a wrong one would never
 * surface again on its own.
 */
@Composable
private fun RememberedChoices(vm: ResetViewModel) {
    val choices = vm.rememberedChoices

    Group(stringResource(Res.string.settings_choices_section)) {
        if (choices.isEmpty()) {
            Text(
                stringResource(Res.string.settings_choices_none),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
            return@Group
        }

        Column {
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
                // A rule between list entries, not between groups — this one earns its keep.
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        TextButton(onClick = { vm.forgetAllRememberedChoices() }, enabled = vm.canChangeTarget) {
            Text(stringResource(Res.string.settings_choices_forget_all), color = StatusColors.bad)
        }
    }
}

@Composable
private fun Developer(vm: ResetViewModel) {
    Group(stringResource(Res.string.settings_developer_section)) {
        Toggle(
            label = stringResource(Res.string.settings_developer_toggle),
            checked = vm.developerMode,
            onChange = { vm.developerMode = it },
            body = stringResource(Res.string.settings_developer_body),
        )
    }
}

@Composable
private fun About() {
    Group(stringResource(Res.string.settings_about_section)) {
        Column {
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
    }
}

@Composable
private fun SettingRow(label: String?, body: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, body: String) {
    SettingRow(label, body) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A heading and the things it governs, bound together.
 *
 * The 8dp inside is deliberately tighter than the 14dp the pane puts between groups: a heading
 * spaced equally from what is above and below it belongs to neither, which is exactly how the
 * first pass at this read.
 */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** The line between one group and the next — the only rule left in the pane. */
@Composable
private fun Break() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
