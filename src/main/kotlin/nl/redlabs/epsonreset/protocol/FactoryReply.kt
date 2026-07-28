package nl.redlabs.epsonreset.protocol

/** Reading the printer's answer to a factory (`||`) command. */
object FactoryReply {

    /** True when the reply is a `:NA;` refusal rather than an answer. */
    fun isRefused(reply: ByteArray): Boolean = refusedCommand(reply) != null

    /**
     * The command byte a `:NA;` refusal names — `0x41` for a read, `0x42` for a write — or null
     * when the reply is not a refusal.
     */
    fun refusedCommand(reply: ByteArray): Int? {
        val match = REFUSAL.find(String(reply, Charsets.ISO_8859_1)) ?: return null
        return match.groupValues[1].toIntOrNull(16)
    }

    /** What to tell someone whose printer just refused. */
    fun explain(reply: ByteArray): String? = when (refusedCommand(reply)) {
        EpsonD4.CMD_EEPROM_READ ->
            "The printer refused the counter read (:41:NA;). Its firmware does not accept factory " +
                "commands over this connection — a wrong read key gives the identical answer, so " +
                "the key is not what it is objecting to. Connect it over USB to read counters."

        EpsonD4.CMD_EEPROM_WRITE ->
            "The printer refused the EEPROM write (:42:NA;). This is a refusal of the command " +
                "itself, not of the write key — nothing was written. Connect it over USB to reset."

        else -> null
    }

    private val REFUSAL = Regex(""":([0-9a-fA-F]{2}):NA;""")
}
