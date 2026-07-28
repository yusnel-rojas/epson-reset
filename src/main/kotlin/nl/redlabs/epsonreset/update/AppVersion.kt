package nl.redlabs.epsonreset.update

import java.util.Properties

/** What version of the app is running. */
object AppVersion {

    const val DEV = "dev"

    val current: String by lazy {
        runCatching {
            AppVersion::class.java.getResourceAsStream("/app-version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: DEV
    }

    val isDev: Boolean get() = current == DEV

    /** "1.2.0" or "dev build" — for the log line and the diagnostics header. */
    val display: String get() = if (isDev) "dev build" else current
}
