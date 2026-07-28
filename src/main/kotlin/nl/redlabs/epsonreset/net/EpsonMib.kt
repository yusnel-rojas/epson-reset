package nl.redlabs.epsonreset.net

/** The Epson private OIDs this app uses, all under enterprise 1248. */
object EpsonMib {

    private val ROOT = listOf(1, 3, 6, 1, 4, 1, 1248, 1, 2, 2)

    /** The exact model, e.g. `ET-2825` — the database's own key, not a marketing name. */
    val MODEL = ROOT + listOf(1, 1, 1, 2, 1)

    /** The marketing name, e.g. `EPSON ET-2820 Series`. Kept for display, not for matching. */
    val PRODUCT = ROOT + listOf(1, 1, 1, 3, 1)

    /** The IEEE 1284 device ID string — `MFG:EPSON;…;MDL:…;SN:…;`, as [nl.redlabs.epsonreset.protocol.DeviceId] parses. */
    val DEVICE_ID = ROOT + listOf(1, 1, 1, 1, 1)

    /** The unit serial, e.g. `QWER012345`. What binds a backup to one physical printer. */
    val SERIAL = ROOT + listOf(1, 1, 1, 5, 1)

    /** A complete `@BDC ST2` block, byte-identical to the one the USB path reads. */
    val STATUS = ROOT + listOf(1, 1, 1, 4, 1)

    /** Main firmware version, e.g. `05.24`. Worth reporting: refusals below are firmware-specific. */
    val FIRMWARE = ROOT + listOf(2, 1, 1, 2, 1, 3)

    /**
     * The command passthrough: append an ESC/P command's bytes as sub-identifiers and the reply
     * comes back as the value, behind a one-byte status.
     */
    val PASSTHROUGH = ROOT + listOf(44, 1, 1, 2, 1)

    /** The passthrough OID carrying one command. */
    fun passthroughFor(command: ByteArray): List<Int> = PASSTHROUGH + command.map { it.toInt() and 0xFF }

    /** Strips the leading status byte the passthrough prefixes every reply with. */
    fun payloadOf(reply: ByteArray): ByteArray = if (reply.isEmpty()) reply else reply.copyOfRange(1, reply.size)
}
