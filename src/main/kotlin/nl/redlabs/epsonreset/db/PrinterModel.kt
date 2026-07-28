package nl.redlabs.epsonreset.db

/** One printer's EEPROM recipe, as stored in database.json. */
data class PrinterModel(
    val name: String,
    val readKey: Int = 0,
    val writeKey: String = "",
    val writeKey1: String = "",
    val readLength: Int = 2,
    val writeLength: Int = 2,
    val memHigh: Int = 0x7FF,
    val padGroups: List<PadGroup> = emptyList(),
) {
    /** True when every group targets the platen pad. */
    val isPlatenOnly: Boolean
        get() = padGroups.isNotEmpty() && padGroups.all { it.effectiveKind == PadKind.PLATEN }

    val hasResettableCounters: Boolean
        get() = padGroups.any { it.addresses.isNotEmpty() }

    /** Total EEPROM writes a reset performs — one per address across all groups. */
    val writeCount: Int
        get() = padGroups.sumOf { it.addresses.size }
}

enum class PadKind { PLATEN, MAIN, UNKNOWN }

data class PadGroup(val description: String, val kind: String, val addresses: List<Int>, val resetValues: List<Int>) {
    /** The DB leaves `kind` blank on older entries, so fall back to sniffing the description. */
    val effectiveKind: PadKind
        get() = when {
            kind.isNotBlank() -> kindFromToken(kind)
            else -> kindFromDescription(description)
        }

    companion object {
        private fun kindFromToken(token: String): PadKind = when (token.lowercase()) {
            "platen" -> PadKind.PLATEN
            "main" -> PadKind.MAIN
            else -> PadKind.UNKNOWN
        }

        fun kindFromDescription(d: String): PadKind = when {
            d.contains("platen", ignoreCase = true) -> PadKind.PLATEN
            d.contains("main", ignoreCase = true) || d == "Waste counter" -> PadKind.MAIN
            else -> PadKind.UNKNOWN
        }
    }
}
