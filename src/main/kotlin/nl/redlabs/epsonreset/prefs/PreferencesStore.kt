package nl.redlabs.epsonreset.prefs

import nl.redlabs.epsonreset.AppPaths

/** The one writer of `preferences.json`. */
object PreferencesStore {

    private var cached: Preferences? = null

    /** The current preferences, read from disk once per run and kept in memory after that. */
    @Synchronized
    fun current(): Preferences = cached ?: load().also { cached = it }

    /**
     * Applies [block] and persists the result. A failed write costs the preference, not the run — a
     * read-only data directory should not take the app down with it.
     */
    @Synchronized
    fun update(block: (Preferences) -> Preferences) {
        val updated = block(current()).sanitised()
        if (updated == cached) return
        cached = updated
        runCatching { AppPaths.preferences.writeText(Preferences.format(updated)) }
    }

    /** Re-reads from disk, for tests and for anyone who edited the file while the app ran. */
    @Synchronized
    fun reload(): Preferences = load().also { cached = it }

    private fun load(): Preferences = runCatching { AppPaths.preferences.takeIf { it.isFile }?.readText() }.getOrNull()
        ?.let { Preferences.parse(it) }
        ?: Preferences()
}
