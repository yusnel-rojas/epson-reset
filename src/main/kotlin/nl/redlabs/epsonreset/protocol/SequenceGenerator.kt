package nl.redlabs.epsonreset.protocol

import nl.redlabs.epsonreset.db.PrinterModel

/** Builds the packet sequence that zeroes a model's waste counters. */
object SequenceGenerator {

    /** Three NUL bytes flush the hardware parser, then EJL negotiates the 1284.4 channel. */
    private val EJL_INIT = byteArrayOfInts(
        0x00, 0x00, 0x00, 0x1B, 0x01, '@'.code, 'E'.code, 'J'.code, 'L'.code, ' '.code,
        '1'.code, '2'.code, '8'.code, '4'.code, '.'.code, '4'.code, '\n'.code,
        '@'.code, 'E'.code, 'J'.code, 'L'.code, '\n'.code,
        '@'.code, 'E'.code, 'J'.code, 'L'.code, '\n'.code,
    )

    private val D4_INIT = byteArrayOfInts(0x00, 0x00, 0x00, 0x08, 0x01, 0x00, 0x00, 0x10)

    private val D4_OPEN = byteArrayOfInts(
        0x00, 0x00, 0x00, 0x11, 0x01, 0x00, 0x01,
        EpsonD4.SOCKET_EPSON_CTRL, EpsonD4.SOCKET_EPSON_CTRL,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private val D4_CREDIT_GRANT = byteArrayOfInts(
        0x00, 0x00, 0x00, 0x0B, 0x01, 0x00, 0x03,
        EpsonD4.SOCKET_EPSON_CTRL, EpsonD4.SOCKET_EPSON_CTRL, 0x00, 0x01,
    )

    private val D4_CREDIT_REQ = byteArrayOfInts(
        0x00, 0x00, 0x00, 0x0D, 0x01, 0x00, 0x04,
        EpsonD4.SOCKET_EPSON_CTRL, EpsonD4.SOCKET_EPSON_CTRL, 0xFF, 0xFF, 0x00, 0x01,
    )

    /**
     * Full reset sequence: channel setup, then a grant/request/write triplet per EEPROM address.
     * Each write gets its own credit pair because the control channel only ever holds one credit.
     */
    fun generate(model: PrinterModel): List<ByteArray> = generateWrites(
        model,
        model.padGroups.flatMap { group ->
            group.addresses.indices.map { i -> group.addresses[i] to group.resetValues[i] }
        },
    )

    /** The same sequence for an arbitrary set of `address to value` pairs. */
    fun generateWrites(model: PrinterModel, writes: List<Pair<Int, Int>>): List<ByteArray> = buildList {
        add(EJL_INIT)
        add(D4_INIT)
        add(D4_OPEN)

        for ((address, value) in writes) {
            add(D4_CREDIT_GRANT)
            add(D4_CREDIT_REQ)
            add(
                writePacket(
                    readKey = model.readKey,
                    address = address,
                    value = value,
                    writeKey = model.writeKey,
                ),
            )
        }
    }

    /**
     * Channel setup only — the three packets every session starts with, with no writes attached.
     * Reading needs the same handshake as writing.
     */
    fun handshake(): List<ByteArray> = listOf(EJL_INIT, D4_INIT, D4_OPEN)

    /** Credit grant + request. The printer needs credit before it may send a reply back. */
    fun creditPair(): List<ByteArray> = listOf(D4_CREDIT_GRANT, D4_CREDIT_REQ)

    /** One EEPROM read. */
    fun readPacket(readKey: Int, address: Int): ByteArray {
        val c = EpsonD4.CMD_EEPROM_READ
        val notC = c.inv() and 0xFF
        val shiftC = ((c shr 1) and 0x7F) or ((c shl 7) and 0x80)

        val inner = ArrayList<Byte>().apply {
            add((readKey and 0xFF).toByte())
            add(((readKey shr 8) and 0xFF).toByte())
            add(c.toByte())
            add(notC.toByte())
            add(shiftC.toByte())
            add((address and 0xFF).toByte())
            add(((address shr 8) and 0xFF).toByte())
        }

        return frame(inner)
    }

    fun writePacket(readKey: Int, address: Int, value: Int, writeKey: String): ByteArray {
        val c = EpsonD4.CMD_EEPROM_WRITE
        val notC = c.inv() and 0xFF
        val shiftC = ((c shr 1) and 0x7F) or ((c shl 7) and 0x80)

        val inner = ArrayList<Byte>().apply {
            add((readKey and 0xFF).toByte())
            add(((readKey shr 8) and 0xFF).toByte())
            add(c.toByte())
            add(notC.toByte())
            add(shiftC.toByte())
            add((address and 0xFF).toByte())
            add(((address shr 8) and 0xFF).toByte())
            add((value and 0xFF).toByte())
            // Latin-1: the keys are ASCII words and each character must stay one byte.
            addAll(writeKey.toByteArray(Charsets.ISO_8859_1).toList())
        }

        return frame(inner)
    }

    /**
     * A control-channel command: two ASCII command bytes, a little-endian payload length, then the
     * payload — then the whole thing wrapped in a D4 header.
     */
    fun controlCommand(command: String, payload: List<Byte>): ByteArray {
        val epsonCmd = ArrayList<Byte>().apply {
            addAll(command.toByteArray(Charsets.ISO_8859_1).toList())
            add((payload.size and 0xFF).toByte()) // little-endian
            add(((payload.size shr 8) and 0xFF).toByte())
            addAll(payload)
        }

        val d4Len = epsonCmd.size + 6
        return ArrayList<Byte>().apply {
            add(EpsonD4.SOCKET_EPSON_CTRL.toByte())
            add(EpsonD4.SOCKET_EPSON_CTRL.toByte())
            add(((d4Len shr 8) and 0xFF).toByte()) // big-endian
            add((d4Len and 0xFF).toByte())
            add(EpsonD4.CREDIT.toByte())
            add(0x00)
            addAll(epsonCmd)
        }.toByteArray()
    }

    /**
     * Status query. Returns a `@BDC ST2` block describing the printer's own state — the one route
     * to a real maintenance figure that doesn't need a counter maximum from anywhere.
     */
    fun statusPacket(): ByteArray = controlCommand("st", listOf(0x01))

    private fun frame(inner: List<Byte>): ByteArray = controlCommand("||", inner)

    private fun byteArrayOfInts(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }
}
