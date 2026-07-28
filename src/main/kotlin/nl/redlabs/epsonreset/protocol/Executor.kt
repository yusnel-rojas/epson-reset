package nl.redlabs.epsonreset.protocol

/** Drives a generated sequence over a [Transport], verifying each EEPROM write. */
object Executor {

    data class Options(val maxWriteAttempts: Int = 3, val interPacketDelayMs: Long = 100, val retryDelayMs: Long = 200)

    data class Result(
        val success: Boolean = false,
        val packetsSent: Int = 0,
        val ackCount: Int = 0,
        val writesTotal: Int = 0,
        val writesVerified: Int = 0,
        val writesRejected: Int = 0,
        val error: String = "",
    )

    /** Live feedback for the UI. [onPacket] fires once per packet, 1-based. */
    interface Listener {
        fun onPacket(index: Int, total: Int, message: String) {}
        fun onTrace(line: String) {}
    }

    fun execute(
        transport: Transport,
        sequence: List<ByteArray>,
        options: Options = Options(),
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        var packetsSent = 0
        var ackCount = 0
        var writesTotal = 0
        var writesVerified = 0
        var writesRejected = 0

        fun fail(error: String) = Result(
            success = false,
            packetsSent = packetsSent,
            ackCount = ackCount,
            writesTotal = writesTotal,
            writesVerified = writesVerified,
            writesRejected = writesRejected,
            error = error,
        )

        // Sends one packet and collects whatever the printer replies; null means the wire broke.
        fun sendAndDrain(packet: ByteArray, index: Int): ByteArray? {
            listener?.onTrace("[OUT] Packet ${index + 1} (${packet.size} bytes)\n${hexDump(packet)}")

            if (!transport.send(packet)) {
                listener?.onTrace("[!] SEND FAILED on packet ${index + 1}")
                return null
            }
            packetsSent++

            if (options.interPacketDelayMs > 0) Thread.sleep(options.interPacketDelayMs)

            val ack = transport.drain()
            listener?.onTrace("[IN]  ACK (${ack.size} bytes)\n${hexDump(ack)}")
            if (ack.isNotEmpty()) ackCount++
            return ack
        }

        for ((i, packet) in sequence.withIndex()) {
            if (isCancelled()) return fail("Cancelled by user after $packetsSent packets.")

            val isWrite = isWritePacket(packet)
            if (isWrite) writesTotal++

            val maxAttempts = if (isWrite) options.maxWriteAttempts else 1
            var confirmed = false

            for (attempt in 1..maxAttempts) {
                if (attempt > 1) {
                    listener?.onPacket(i + 1, sequence.size, "Retrying write ($attempt/$maxAttempts)…")
                    if (options.retryDelayMs > 0) Thread.sleep(options.retryDelayMs)

                    // A retried write needs its credit pair replayed, or the printer has no
                    // buffer allowance left to accept it.
                    if (i >= 2 && !isWritePacket(sequence[i - 2]) && !isWritePacket(sequence[i - 1])) {
                        sendAndDrain(sequence[i - 2], i - 2) ?: return fail(sendFailure(i - 2))
                        sendAndDrain(sequence[i - 1], i - 1) ?: return fail(sendFailure(i - 1))
                    }
                }

                val ack = sendAndDrain(packet, i) ?: return fail(sendFailure(i))

                if (!isWrite) {
                    listener?.onPacket(
                        i + 1,
                        sequence.size,
                        if (ack.isNotEmpty()) "Triggered ACK: cleared ${ack.size} bytes." else "Sent. (No ACK)",
                    )
                    confirmed = true
                    break
                }

                if (isWriteNgAck(ack)) {
                    writesRejected++
                    listener?.onPacket(i + 1, sequence.size, "EEPROM write REJECTED (||:42:NG;).")
                    return fail(
                        "Printer REJECTED the EEPROM write (packet ${i + 1}, reply ':42:NG;'). " +
                            "The write key likely does not match this model — check you picked the right one.",
                    )
                }

                if (isWriteOkAck(ack)) {
                    writesVerified++
                    confirmed = true
                    listener?.onPacket(i + 1, sequence.size, "EEPROM write verified (||:42:OK;).")
                    break
                }

                listener?.onTrace("[WARNING] Packet ${i + 1} write ACK missing ':42:OK;'")
            }

            if (isWrite && !confirmed) {
                return fail("EEPROM write not acknowledged after $maxAttempts attempts (packet ${i + 1}).")
            }
        }

        if (writesTotal == 0) return fail("The sequence contains no EEPROM write packets — nothing was reset.")
        if (ackCount ==
            0
        ) {
            return fail("The printer did not acknowledge any packets. The sequence was rejected or ignored.")
        }

        val success = writesVerified == writesTotal
        return Result(
            success = success,
            packetsSent = packetsSent,
            ackCount = ackCount,
            writesTotal = writesTotal,
            writesVerified = writesVerified,
            writesRejected = writesRejected,
            error = if (success) {
                ""
            } else {
                "Incomplete EEPROM write: verified $writesVerified of $writesTotal write operations."
            },
        )
    }

    private fun sendFailure(index: Int) = "Transport failure while sending packet ${index + 1}."

    /**
     * Recognises a write packet by its fixed prologue: D4 socket bytes, the `||` frame prefix, and
     * the plain/inverted/rotated command triplet at 12..14.
     */
    fun isWritePacket(p: ByteArray): Boolean {
        if (p.size < 15) return false

        val c = EpsonD4.CMD_EEPROM_WRITE
        val notC = (c.inv() and 0xFF).toByte()
        val rorC = (((c shr 1) and 0x7F) or ((c shl 7) and 0x80)).toByte()

        return p[0] == EpsonD4.SOCKET_EPSON_CTRL.toByte() &&
            p[1] == EpsonD4.SOCKET_EPSON_CTRL.toByte() &&
            p[6] == EpsonD4.PREFIX_PIPE.toByte() &&
            p[7] == EpsonD4.PREFIX_PIPE.toByte() &&
            p[12] == c.toByte() &&
            p[13] == notC &&
            p[14] == rorC
    }

    /** Same prologue check as [isWritePacket], for the 0x41 read opcode. */
    fun isReadPacket(p: ByteArray): Boolean {
        if (p.size < 15) return false

        val c = EpsonD4.CMD_EEPROM_READ
        val notC = (c.inv() and 0xFF).toByte()
        val rorC = (((c shr 1) and 0x7F) or ((c shl 7) and 0x80)).toByte()

        return p[0] == EpsonD4.SOCKET_EPSON_CTRL.toByte() &&
            p[1] == EpsonD4.SOCKET_EPSON_CTRL.toByte() &&
            p[6] == EpsonD4.PREFIX_PIPE.toByte() &&
            p[7] == EpsonD4.PREFIX_PIPE.toByte() &&
            p[12] == c.toByte() &&
            p[13] == notC &&
            p[14] == rorC
    }

    /** Address a read packet is asking for, or null if it isn't one. */
    fun readPacketAddress(p: ByteArray): Int? {
        if (!isReadPacket(p) || p.size < 17) return null
        return (p[15].toInt() and 0xFF) or ((p[16].toInt() and 0xFF) shl 8)
    }

    /** Address and value a write packet will commit, or null if it isn't one. */
    fun writePacketTarget(p: ByteArray): Pair<Int, Int>? {
        if (!isWritePacket(p) || p.size < 18) return null
        val address = (p[15].toInt() and 0xFF) or ((p[16].toInt() and 0xFF) shl 8)
        return address to (p[17].toInt() and 0xFF)
    }

    /** Read key a read packet carries, or null if it isn't one. Used by the inspector's fake. */
    fun readPacketKey(p: ByteArray): Int? {
        if (!isReadPacket(p) || p.size < 12) return null
        return (p[10].toInt() and 0xFF) or ((p[11].toInt() and 0xFF) shl 8)
    }

    fun isWriteOkAck(ack: ByteArray) = ack.containsToken(":42:OK;")
    fun isWriteNgAck(ack: ByteArray) = ack.containsToken(":42:NG;")
    fun isChannelOpenAck(ack: ByteArray) = ack.size >= 7 && ack[6] == EpsonD4.REPLY_OPEN_CHANNEL.toByte()

    private fun ByteArray.containsToken(token: String): Boolean {
        val t = token.toByteArray(Charsets.ISO_8859_1)
        if (size < t.size) return false
        outer@ for (i in 0..size - t.size) {
            for (j in t.indices) if (this[i + j] != t[j]) continue@outer
            return true
        }
        return false
    }

    /** Canonical 16-column hex + ASCII. */
    fun hexDump(data: ByteArray): String {
        if (data.isEmpty()) return "    (empty)"

        return buildString {
            for (start in data.indices step 16) {
                val end = minOf(start + 16, data.size)
                val row = data.copyOfRange(start, end)

                row.forEach { append("%02x ".format(it.toInt() and 0xFF)) }
                repeat(16 - row.size) { append("   ") }
                append(" | ")
                row.forEach { b ->
                    val v = b.toInt() and 0xFF
                    append(if (v in 32..126) v.toChar() else '.')
                }
                if (end < data.size) append('\n')
            }
        }
    }
}
