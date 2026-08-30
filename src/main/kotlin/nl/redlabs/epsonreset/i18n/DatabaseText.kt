package nl.redlabs.epsonreset.i18n

import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.db_source_bundled
import nl.redlabs.epsonreset.resources.db_source_cached
import org.jetbrains.compose.resources.StringResource

fun PrinterDatabase.Source.label(): StringResource = when (this) {
    PrinterDatabase.Source.BUNDLED -> Res.string.db_source_bundled
    PrinterDatabase.Source.CACHED -> Res.string.db_source_cached
}
