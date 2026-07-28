package nl.redlabs.epsonreset.protocol

/** Wire constants for Epson's IEEE 1284.4 ("D4") control channel. */
object EpsonD4 {
    const val CMD_EEPROM_WRITE = 0x42
    const val CMD_EEPROM_READ = 0x41
    const val PREFIX_PIPE = 0x7C
    const val SOCKET_EPSON_CTRL = 0x02

    /** Must stay 0. A nonzero credit locks up the printer's buffer. */
    const val CREDIT = 0x00

    const val REPLY_OPEN_CHANNEL = 0x81
    const val REPLY_CREDIT_GRANT = 0x83
    const val REPLY_CREDIT_REQ = 0x84

    const val EPSON_VID = 0x04B8
}
