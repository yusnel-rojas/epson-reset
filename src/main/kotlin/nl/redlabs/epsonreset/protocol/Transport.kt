package nl.redlabs.epsonreset.protocol

/**
 * A byte pipe to the printer. Implemented over libusb and over TCP, and by fakes in tests and dry-
 * run mode.
 */
interface Transport : AutoCloseable {
    /** Returns true only when the whole packet went out. */
    fun send(packet: ByteArray): Boolean

    /** Reads until the printer stops talking; empty when it had nothing to say. */
    fun drain(): ByteArray

    override fun close() {}
}

/**
 * Accepts everything, acknowledges every write, and answers reads out of an in-memory EEPROM, so
 * the UI can exercise the full flow with no hardware attached. Records what it was asked to send.
 */
class FakeTransport(
    private val ackWrites: Boolean = true,
    memory: Map<Int, Int> = emptyMap(),
    private val defaultValue: Int = 0x7F,
    private val readKey: Int? = null,
) : Transport {
    val sent = mutableListOf<ByteArray>()
    val memory = memory.toMutableMap()

    override fun send(packet: ByteArray): Boolean {
        sent += packet
        if (ackWrites && Executor.isWritePacket(packet) && packet.size >= 18) {
            val address = (packet[15].toInt() and 0xFF) or ((packet[16].toInt() and 0xFF) shl 8)
            this.memory[address] = packet[17].toInt() and 0xFF
        }
        return true
    }

    override fun drain(): ByteArray {
        val last = sent.lastOrNull() ?: return ByteArray(0)

        Executor.readPacketAddress(last)?.let { address ->
            // A key-sensitive fake stays silent on the wrong key, the way a firmware that validates
            // the key would. Silence, not an error reply — that is the only signal to discriminate on.
            if (readKey != null && Executor.readPacketKey(last) != readKey) return ByteArray(0)
            val value = memory[address] ?: defaultValue
            return "@BDC PS\r\nEE:%04X%02X;".format(address, value).toByteArray(Charsets.ISO_8859_1)
        }

        return when {
            !Executor.isWritePacket(last) -> "||status;".toByteArray(Charsets.ISO_8859_1)
            ackWrites -> "||:42:OK;".toByteArray(Charsets.ISO_8859_1)
            else -> "||:42:NG;".toByteArray(Charsets.ISO_8859_1)
        }
    }
}
