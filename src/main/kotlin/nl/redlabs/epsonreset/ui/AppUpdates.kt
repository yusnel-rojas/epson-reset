package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.prefs.PreferencesStore
import nl.redlabs.epsonreset.update.AppVersion
import nl.redlabs.epsonreset.update.UpdateCheck

/** Whether a newer release exists, and the one action offered about it. */
class AppUpdates {

    var checking by mutableStateOf(false)
        private set

    /** The newer release, once one is known. Null covers both "up to date" and "not asked". */
    var available by mutableStateOf<UpdateCheck.Release?>(null)
        private set

    /**
     * How the last check went, in one line, for the settings window that started it — a check whose
     * only answer is in the log behind the window has not answered the person who asked.
     */
    var lastResult by mutableStateOf<ResetViewModel.Outcome?>(null)
        private set

    /** Runs a check and reports it into the log. */
    suspend fun check(log: ResetViewModel, automatic: Boolean) {
        if (checking) return

        if (automatic) {
            val prefs = PreferencesStore.current()
            if (!prefs.checkForUpdates) return
            if (AppVersion.isDev) return
            val since = System.currentTimeMillis() - prefs.lastUpdateCheck
            if (since in 0 until UpdateCheck.CHECK_INTERVAL_MS) return
        }

        checking = true
        val result = try {
            withContext(Dispatchers.IO) { UpdateCheck.check() }
        } finally {
            checking = false
        }

        // Only a check that reached GitHub resets the clock, so a week offline doesn't spend the
        // day's allowance on failures.
        if (result !is UpdateCheck.Result.Failed) {
            PreferencesStore.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        }

        when (result) {
            is UpdateCheck.Result.Available -> {
                available = result.release
                lastResult = ResetViewModel.Outcome(
                    "Version ${result.release.version} is available.",
                    ok = true,
                )
                log.good(
                    "Version ${result.release.version} is available — you have " +
                        "${AppVersion.display}.",
                )
            }

            UpdateCheck.Result.UpToDate -> {
                available = null
                lastResult = ResetViewModel.Outcome("Up to date — ${AppVersion.display}.", ok = true)
                if (!automatic) log.info("Up to date — ${AppVersion.display}.")
            }

            // Plain in the window, specific in the log: an HTTP code names what the release feed
            // did, which is not something the reader can do anything about.
            is UpdateCheck.Result.Unknown -> {
                lastResult = ResetViewModel.Outcome("Could not confirm the latest version.", ok = false)
                if (!automatic) log.warn("Update check inconclusive — ${result.detail}.")
            }

            is UpdateCheck.Result.Failed -> {
                lastResult = ResetViewModel.Outcome("Could not check for updates.", ok = false)
                if (!automatic) log.warn("Update check failed — ${result.detail}.")
            }
        }
    }

    /** Opens the release page in the user's browser. Reports rather than throws if it can't. */
    fun openReleasePage(log: ResetViewModel) {
        val page = available?.page ?: UpdateCheck.RELEASES_PAGE
        if (!Browser.open(page)) log.warn("Could not open a browser. The release page is $page")
    }
}
