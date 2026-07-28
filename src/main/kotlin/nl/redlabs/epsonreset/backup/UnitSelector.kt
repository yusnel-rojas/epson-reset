package nl.redlabs.epsonreset.backup

import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.MatchedPrinter

/** Which physical printer a restore should write to, given what is on the bus. */
sealed interface UnitChoice {
    /**
     * Safe to write. [unconfirmed] is non-null when no serial could tie the backup to this exact
     * unit — allowed, because there was nothing to confuse it with, but worth saying out loud.
     */
    data class Write(val device: DetectedPrinter, val unconfirmed: String?) : UnitChoice

    /** Nothing on the bus resolves to the backup's model. [found] is what was there instead. */
    data class NoSuchModel(val model: String, val found: List<String>) : UnitChoice

    /** The right model, but a different unit than the backup came from. */
    data class WrongUnit(val wanted: String, val connected: List<String>) : UnitChoice

    /** Several of the right model, and no serial to tell them apart. */
    data class Ambiguous(val model: String, val count: Int) : UnitChoice
}

/** The rule deciding where a restore is allowed to land. */
object UnitSelector {

    fun choose(backup: EepromBackup, matched: List<MatchedPrinter>): UnitChoice {
        val candidates = matched.filter { it.model?.name.equals(backup.model, ignoreCase = true) }
        if (candidates.isEmpty()) {
            return UnitChoice.NoSuchModel(
                backup.model,
                matched.map { "${it.device.displayName} → ${it.model?.name ?: "unmatched"}" },
            )
        }

        val wanted = backup.printerSerial

        if (wanted != null) {
            candidates.firstOrNull { it.device.serial == wanted }
                ?.let { return UnitChoice.Write(it.device, unconfirmed = null) }

            // Every unit that could identify itself named something else. Descriptors that came
            // back empty are a different case — they fall through to the ambiguity rule below.
            val known = candidates.mapNotNull { it.device.serial }
            if (known.isNotEmpty()) return UnitChoice.WrongUnit(wanted, known)
        }

        if (candidates.size > 1) return UnitChoice.Ambiguous(backup.model, candidates.size)

        val why = if (wanted == null) {
            "the backup records no serial"
        } else {
            "the backup names $wanted but no connected unit reports a serial"
        }
        return UnitChoice.Write(candidates.single().device, unconfirmed = why)
    }
}
