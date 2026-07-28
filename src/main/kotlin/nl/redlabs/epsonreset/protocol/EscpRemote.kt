package nl.redlabs.epsonreset.protocol

/** Recovering the ESC/P command from inside a 1284.4 packet. */
object EscpRemote {

    /** The command inside a 1284.4 data packet, or null when [packet] isn't one. */
    fun remoteCommandOf(packet: ByteArray): ByteArray? {
        if (packet.size <= D4_HEADER) return null
        if (packet[0] != EpsonD4.SOCKET_EPSON_CTRL.toByte()) return null
        if (packet[1] != EpsonD4.SOCKET_EPSON_CTRL.toByte()) return null

        return packet.copyOfRange(D4_HEADER, packet.size)
    }

    /** True for the 1284.4 transport packets — channel setup and credit. */
    fun isChannelPacket(packet: ByteArray): Boolean =
        packet.size >= 2 && packet[0] == 0x00.toByte() && packet[1] == 0x00.toByte()

    private const val D4_HEADER = 6
}
