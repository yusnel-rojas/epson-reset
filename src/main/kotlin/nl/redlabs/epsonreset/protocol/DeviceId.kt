package nl.redlabs.epsonreset.protocol

/** Asks the printer what it is, over EJL. */
object DeviceId {

    /**
     * `ESC SOH @EJL ID`, behind the same three NULs the reset sequence opens with — they flush a
     * parser that may be part-way through something else, which on a port shared with the print
     * queue is the ordinary state rather than the exceptional one.
     */
    val REQUEST: ByteArray =
        "\u0000\u0000\u0000\u001B\u0001@EJL ID\r\n".toByteArray(Charsets.ISO_8859_1)

    data class Id(val fields: Map<String, String>) {
        /** Both the abbreviated and spelled-out keys are legal; printers use either. */
        val manufacturer: String? get() = pick("MFG", "MANUFACTURER")
        val model: String? get() = pick("MDL", "MODEL")
        val serial: String? get() = pick("SERN", "SN", "SERIALNUMBER")
        val description: String? get() = pick("DES", "DESCRIPTION")
        val commandSets: List<String>
            get() = pick("CMD", "COMMAND SET")?.split(',')?.map { it.trim() }.orEmpty()

        val isEpson: Boolean
            get() = listOfNotNull(manufacturer, model, description)
                .any { it.contains("EPSON", ignoreCase = true) }

        private fun pick(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) }
    }

    /** Sends the request and parses whatever comes back. */
    fun query(transport: Transport): Id? {
        if (!transport.send(REQUEST)) return null
        return parse(transport.drain())
    }

    /** Pulls `KEY:VALUE;` pairs out of a reply. */
    fun parse(reply: ByteArray): Id? {
        if (reply.isEmpty()) return null

        val text = String(reply, Charsets.ISO_8859_1)
        val fields = FIELD.findAll(text).associate { match ->
            match.groupValues[1].trim().uppercase() to match.groupValues[2].trim()
        }

        return if (fields.isEmpty()) null else Id(fields)
    }

    private val FIELD = Regex("""([A-Za-z][A-Za-z ]{1,20}):([^;]*);""")
}
