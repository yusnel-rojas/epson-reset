package nl.redlabs.epsonreset.protocol

/** Parser for Epson's `@BDC ST2` status block. */
object Status {

    data class Field(val type: Int, val value: ByteArray) {
        val name: String get() = NAMES[type] ?: "unknown"

        val hex: String get() = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

        val ascii: String
            get() = value.map { if ((it.toInt() and 0xFF) in 32..126) it.toInt().toChar() else '.' }
                .joinToString("")

        /** Little-endian 32-bit words, which is how the counter-ish blocks are laid out. */
        val words32: List<Long>
            get() = (0 until value.size / 4).map { i ->
                var v = 0L
                for (b in 0 until 4) v = v or ((value[i * 4 + b].toLong() and 0xFF) shl (8 * b))
                v
            }

        override fun equals(other: Any?) = other is Field && type == other.type && value.contentEquals(other.value)

        override fun hashCode() = 31 * type + value.contentHashCode()
    }

    data class Report(val fields: List<Field>, val raw: ByteArray) {
        operator fun get(type: Int): Field? = fields.firstOrNull { it.type == type }

        val serial: String? get() = get(TYPE_SERIAL)?.ascii?.takeIf { it.isNotBlank() }

        /** Ink levels per colour. */
        val inkLevels: List<InkLevel>
            get() {
                val v = get(TYPE_INK)?.value ?: return emptyList()
                if (v.size < 2) return emptyList()

                val width = v[0].toInt() and 0xFF
                if (width < 3) return emptyList()

                val count = (v.size - 1) / width
                return (0 until count).map { i ->
                    val base = 1 + i * width
                    InkLevel(
                        slot = v[base].toInt() and 0xFF,
                        colourCode = v[base + 1].toInt() and 0xFF,
                        percent = v[base + width - 1].toInt() and 0xFF,
                    )
                }
            }

        /** Printer state from field 0x01, or null when the block carries no state field. */
        val state: Int? get() = get(TYPE_STATE)?.value?.firstOrNull()?.toInt()?.and(0xFF)

        /** Error code from field 0x02, or null when the block carries none. */
        val errorCode: Int? get() = get(TYPE_ERROR)?.value?.firstOrNull()?.toInt()?.and(0xFF)

        /**
         * The error in words — "cover open" rather than `0x02`.
         *
         * One phrasing for the whole app: [busyReason] resolves the same codes through the same
         * table, and a screen showing the name in one place and the raw byte in another is telling
         * the user those are two different things.
         */
        val errorDescription: String? get() = errorCode?.let { describeError(it) }

        /**
         * Why the printer's own account of itself argues against giving it anything to do now, or
         * null when it doesn't. Phrased without naming the operation, since every caller's answer
         * to a busy printer is the same one.
         */
        val busyReason: String?
            get() {
                val code = state ?: return null
                if (code == STATE_IDLE) return null

                val detail = errorCode?.let { " (${describeError(it)})" }
                return "The printer reports it is " +
                    (STATES[code] ?: "in state 0x%02X".format(code)) + (detail ?: "") + "."
            }

        /** Why writing to the EEPROM now is a bad idea, or null when it isn't. */
        val writeBlocker: String? get() = busyReason

        override fun equals(other: Any?) = other is Report && fields == other.fields && raw.contentEquals(other.raw)

        override fun hashCode() = 31 * fields.hashCode() + raw.contentHashCode()
    }

    /** One colour's reported level. */
    data class InkLevel(val slot: Int, val colourCode: Int, val percent: Int) {
        /** Codes 0..3 are confirmed on hardware; the rest follow the usual Epson ordering. */
        val colour: String get() = COLOURS[colourCode] ?: "colour $colourCode"

        val isLow: Boolean get() = percent <= LOW_THRESHOLD
    }

    private const val LOW_THRESHOLD = 20

    private val COLOURS = mapOf(
        0x00 to "Black",
        0x01 to "Cyan",
        0x02 to "Magenta",
        0x03 to "Yellow",
        0x04 to "Light Cyan",
        0x05 to "Light Magenta",
        0x06 to "Light Black",
        0x0A to "Matte Black",
    )

    const val TYPE_STATE = 0x01
    const val TYPE_ERROR = 0x02
    const val TYPE_INK = 0x0F
    const val TYPE_SERIAL = 0x40

    /** The one state a reset is allowed to run in. Confirmed: an idle ET-2820 reports 0x04. */
    const val STATE_IDLE = 0x04

    /** Printing its own test pattern — what a nozzle check puts it into. */
    const val STATE_SELF_TEST = 0x01

    const val STATE_BUSY = 0x02

    /** Running a cleaning cycle — what a cleaning command puts it into, and the one to wait out. */
    const val STATE_CLEANING = 0x07

    /** States field 0x01 can report, phrased for the refusal they produce. */
    private val STATES = mapOf(
        0x00 to "reporting an error",
        STATE_SELF_TEST to "printing a self-test page",
        STATE_BUSY to "busy",
        0x03 to "waiting",
        STATE_IDLE to "idle",
        0x05 to "paused",
        STATE_CLEANING to "cleaning the print head",
        0x08 to "in its factory-shipment state",
        0x0A to "shutting down",
    )

    /** A code's name, or the code itself when [ERRORS] has never seen it. */
    fun describeError(code: Int): String = ERRORS[code] ?: "error code 0x%02X".format(code)

    /**
     * Error codes for field 0x02, as far as they are agreed on. Unlisted codes are shown raw for
     * the same reason unlisted field types are: a wrong label is worse than a number.
     */
    private val ERRORS = mapOf(
        0x00 to "fatal error",
        0x01 to "another interface is selected",
        0x02 to "cover open",
        0x04 to "paper jam",
        0x05 to "ink out",
        0x06 to "paper out",
        0x10 to "maintenance request",
    )

    /**
     * Field names established by the common ST2 documentation. Types absent here are reported as
     * "unknown" with their raw bytes rather than guessed at.
     */
    private val NAMES = mapOf(
        0x01 to "status",
        0x02 to "error",
        0x03 to "self-print",
        0x04 to "warning",
        0x06 to "paper path",
        0x0A to "job",
        0x0F to "ink levels",
        0x10 to "loaded paper",
        0x13 to "cancel code",
        0x19 to "job name",
        0x1F to "serial (alt)",
        0x40 to "serial",
        0x4B to "paper count",
    )

    /** Returns null when the buffer holds no ST2 block. */
    fun parse(reply: ByteArray): Report? {
        val marker = "@BDC ST2".toByteArray(Charsets.ISO_8859_1)
        val start = indexOf(reply, marker) ?: return null

        var i = start + marker.size

        // Exactly one line terminator, not a run of them.
        if (i < reply.size && reply[i] == '\r'.code.toByte()) i++
        if (i < reply.size && reply[i] == '\n'.code.toByte()) i++

        if (i + 2 > reply.size) return null

        val length = (reply[i].toInt() and 0xFF) or ((reply[i + 1].toInt() and 0xFF) shl 8)
        i += 2

        val end = minOf(i + length, reply.size)
        val fields = mutableListOf<Field>()

        while (i + 2 <= end) {
            val type = reply[i].toInt() and 0xFF
            val len = reply[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > end) break
            fields += Field(type, reply.copyOfRange(i, i + len))
            i += len
        }

        return Report(fields, reply.copyOfRange(start, end))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (haystack.size < needle.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }
}
