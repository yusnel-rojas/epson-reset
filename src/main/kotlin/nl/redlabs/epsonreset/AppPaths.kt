package nl.redlabs.epsonreset

import java.io.File

/** Every file the app keeps between runs, and the one place that decides where they live. */
object AppPaths {

    /** `~/Library/Application Support/EpsonReset` and its equivalents, created on access. */
    val dataDir: File
        get() {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            val dir = when {
                os.contains("win") ->
                    File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "EpsonReset")
                os.contains("mac") ->
                    File(home, "Library/Application Support/EpsonReset")
                else ->
                    File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "epson-reset")
            }
            dir.mkdirs()
            return dir
        }

    /** The OTA-refreshed printer database. Absent until one is downloaded; the jar has a copy. */
    val database: File get() = File(dataDir, "database.json")

    /** User-supplied counter layouts, which win over the bundled ones for a model they name. */
    val counterOverlay: File get() = File(dataDir, "counters-overlay.json")

    /** Window geometry, last model, update-check state. */
    val preferences: File get() = File(dataDir, "preferences.json")

    /** Network printers added by hand, one address per line. */
    val networkPrinters: File get() = File(dataDir, "network-printers.txt")

    /** Which model a printer is, for printers that report only the family. One per line. */
    val modelChoices: File get() = File(dataDir, "model-choices.txt")

    /** EEPROM snapshots, taken before a live run's first write or on request. */
    val backups: File get() = File(dataDir, "backups")

    /** Append-only successful live counter reads, keyed by canonical printer serial. */
    val counterHistory: File get() = File(dataDir, "counter-history.jsonl")
}
