package nl.rlabs.epsonreset.i18n

import nl.rlabs.epsonreset.db.PrinterDatabase
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.db_source_bundled
import nl.rlabs.epsonreset.resources.db_source_cached
import org.jetbrains.compose.resources.StringResource

fun PrinterDatabase.Source.label(): StringResource = when (this) {
    PrinterDatabase.Source.BUNDLED -> Res.string.db_source_bundled
    PrinterDatabase.Source.CACHED -> Res.string.db_source_cached
}
