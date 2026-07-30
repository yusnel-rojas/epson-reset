package nl.redlabs.epsonreset.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** A minimal SNMP v1 client — one GET, no MIBs, no agent. */
object Snmp {

    /** The standard agent port. Overridable because a test needs a loopback agent to talk to. */
    const val PORT = 161
    private const val DEFAULT_COMMUNITY = "public"
    private const val RECEIVE_BUFFER = 8192

    // BER tags.
    private const val INTEGER = 0x02
    private const val OCTET_STRING = 0x04
    private const val NULL = 0x05
    private const val OBJECT_ID = 0x06
    private const val SEQUENCE = 0x30
    private const val GET_REQUEST = 0xA0
    private const val GET_NEXT_REQUEST = 0xA1
    private const val GET_RESPONSE = 0xA2

    // SNMPv2 application value tags, worth telling apart so an integer isn't read as text.
    const val COUNTER32 = 0x41
    const val GAUGE32 = 0x42
    const val TIMETICKS = 0x43

    /** SNMP v1 error-status values worth telling apart. */
    private const val ERROR_NO_SUCH_NAME = 2

    sealed interface Result {
        /** [value] is the varbind's octet-string content, or empty for other types. */
        data class Ok(val value: ByteArray) : Result {
            override fun equals(other: Any?) = other is Ok && value.contentEquals(other.value)
            override fun hashCode() = value.contentHashCode()
        }

        /** The agent answered, and said it has nothing at that OID. */
        data object NoSuchObject : Result

        /** The agent answered with some other error-status. */
        data class Error(val status: Int) : Result

        /** Nothing came back before the timeout — no agent, wrong host, or SNMP disabled. */
        data object Timeout : Result

        data class Failed(val message: String) : Result
    }

    /**
     * The answer to a GETNEXT. Unlike [Result] it carries the OID the agent *replied* about, which a
     * walk needs to know where to step next and when it has left the subtree.
     */
    sealed interface Next {
        /** [oid] is the next OID in the tree; [type] its BER value tag; [value] the raw content. */
        data class Ok(val oid: List<Int>, val type: Int, val value: ByteArray) : Next {
            override fun equals(other: Any?) =
                other is Ok && oid == other.oid && type == other.type && value.contentEquals(other.value)

            override fun hashCode() = (31 * oid.hashCode() + type) * 31 + value.contentHashCode()
        }

        /** The tree ended: endOfMibView, or a v1 agent's `noSuchName` past the last OID. */
        data object EndOfMib : Next

        data object Timeout : Next

        data class Failed(val message: String) : Next
    }

    /** One GET. */
    fun get(
        host: String,
        oid: List<Int>,
        community: String = DEFAULT_COMMUNITY,
        timeoutMs: Int = 2000,
        retries: Int = 1,
        port: Int = PORT,
    ): Result {
        val requestId = (System.nanoTime() and 0x7FFFFFFF).toInt()
        val request = encode(GET_REQUEST, requestId, community, oid)

        return exchange(host, request, timeoutMs, retries, port, onTimeout = Result.Timeout, ::decodeResponse)
            .getOrElse { Result.Failed(it.message ?: it::class.simpleName ?: "SNMP failed") }
    }

    /** One GETNEXT — the varbind following [oid], whatever its type. */
    fun getNext(
        host: String,
        oid: List<Int>,
        community: String = DEFAULT_COMMUNITY,
        timeoutMs: Int = 2000,
        retries: Int = 1,
        port: Int = PORT,
    ): Next {
        val requestId = (System.nanoTime() and 0x7FFFFFFF).toInt()
        val request = encode(GET_NEXT_REQUEST, requestId, community, oid)

        return exchange(host, request, timeoutMs, retries, port, onTimeout = Next.Timeout, ::decodeNextResponse)
            .getOrElse { Next.Failed(it.message ?: it::class.simpleName ?: "SNMP failed") }
    }

    /**
     * Walks the subtree rooted at [root] with successive GETNEXTs, returning the varbinds inside it.
     * Stops at the first OID outside [root], on end-of-tree, on [maxSteps], or if the agent ever
     * fails to advance the OID — a buggy agent that repeats one must not loop the caller forever.
     */
    fun walk(
        host: String,
        root: List<Int>,
        community: String = DEFAULT_COMMUNITY,
        timeoutMs: Int = 2000,
        port: Int = PORT,
        maxSteps: Int = 256,
    ): List<Next.Ok> {
        val collected = mutableListOf<Next.Ok>()
        var cursor = root

        repeat(maxSteps) {
            when (val next = getNext(host, cursor, community, timeoutMs, port = port)) {
                is Next.Ok -> {
                    if (!next.oid.startsWithPrefix(root)) return collected
                    if (compareOids(next.oid, cursor) <= 0) return collected
                    collected += next
                    cursor = next.oid
                }

                Next.EndOfMib -> return collected
                Next.Timeout, is Next.Failed -> return collected
            }
        }

        return collected
    }

    /** Reads a signed big-endian integer out of an INTEGER/Gauge/Counter value's content bytes. */
    fun intOf(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0
        var value = if (bytes[0].toInt() and 0x80 != 0) -1L else 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }

    private fun List<Int>.startsWithPrefix(prefix: List<Int>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    private fun compareOids(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    /** Sends one request, retrying on timeout, and decodes the first reply with [decode]. */
    private inline fun <T> exchange(
        host: String,
        request: ByteArray,
        timeoutMs: Int,
        retries: Int,
        port: Int,
        onTimeout: T,
        decode: (ByteArray) -> T,
    ): kotlin.Result<T> = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val target = InetSocketAddress(InetAddress.getByName(host), port)

            repeat(retries + 1) {
                socket.send(DatagramPacket(request, request.size, target))

                val buffer = ByteArray(RECEIVE_BUFFER)
                val reply = DatagramPacket(buffer, buffer.size)

                try {
                    socket.receive(reply)
                    return@runCatching decode(reply.data.copyOfRange(0, reply.length))
                } catch (e: SocketTimeoutException) {
                    // Fall through and retry; UDP loses packets and printers are slow to wake.
                }
            }

            onTimeout
        }
    }

    // ── Encoding ─────────────────────────────────────────────────────────────────────────────

    internal fun encodeGet(requestId: Int, community: String, oid: List<Int>): ByteArray =
        encode(GET_REQUEST, requestId, community, oid)

    internal fun encodeGetNext(requestId: Int, community: String, oid: List<Int>): ByteArray =
        encode(GET_NEXT_REQUEST, requestId, community, oid)

    /** A single-varbind request PDU. GET and GETNEXT differ only in [pduTag]. */
    private fun encode(pduTag: Int, requestId: Int, community: String, oid: List<Int>): ByteArray {
        val varbind = tlv(SEQUENCE, encodeOid(oid) + tlv(NULL, ByteArray(0)))
        val varbinds = tlv(SEQUENCE, varbind)

        val pdu = tlv(
            pduTag,
            encodeInt(requestId) + encodeInt(0) + encodeInt(0) + varbinds,
        )

        return tlv(
            SEQUENCE,
            encodeInt(0) + // version 1 is encoded as 0
                tlv(OCTET_STRING, community.toByteArray(Charsets.ISO_8859_1)) +
                pdu,
        )
    }

    /**
     * OID encoding: the first two sub-identifiers share a byte, the rest are base-128 with the top
     * bit set on every byte but the last.
     */
    internal fun encodeOid(oid: List<Int>): ByteArray {
        require(oid.size >= 2) { "an OID needs at least two sub-identifiers" }

        val body = ByteArrayOutputStream()
        body.write(oid[0] * 40 + oid[1])
        for (part in oid.drop(2)) body.write(base128(part))

        return tlv(OBJECT_ID, body.toByteArray())
    }

    private fun base128(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())

        val parts = ArrayDeque<Int>()
        var remaining = value
        while (remaining > 0) {
            parts.addFirst(remaining and 0x7F)
            remaining = remaining ushr 7
        }

        return ByteArray(parts.size) { index ->
            val last = index == parts.size - 1
            (parts[index] or if (last) 0x00 else 0x80).toByte()
        }
    }

    private fun encodeInt(value: Int): ByteArray {
        val bytes = ArrayDeque<Byte>()
        var remaining = value

        do {
            bytes.addFirst((remaining and 0xFF).toByte())
            remaining = remaining shr 8
        } while (remaining != 0 && remaining != -1)

        // A leading bit set on a positive number would read as negative, so pad it.
        if (value >= 0 && (bytes.first().toInt() and 0x80) != 0) bytes.addFirst(0)

        return tlv(INTEGER, bytes.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(encodeLength(content.size))
        out.write(content)
        return out.toByteArray()
    }

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }

    // ── Decoding ─────────────────────────────────────────────────────────────────────────────

    /** Pulls the first varbind's value out of a GetResponse. */
    internal fun decodeResponse(packet: ByteArray): Result = runCatching {
        val reader = Ber(packet)

        reader.expect(SEQUENCE)
        reader.skipField() // version
        reader.skipField() // community

        val pduTag = reader.peekTag()
        if (pduTag != GET_RESPONSE) {
            val prefix = packet.take(24).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
            return Result.Failed("not a GetResponse (tag 0x%02X) — starts %s".format(pduTag, prefix))
        }
        reader.expect(GET_RESPONSE)

        reader.skipField() // request id
        val errorStatus = reader.readInt()
        reader.skipField() // error index

        if (errorStatus == ERROR_NO_SUCH_NAME) return Result.NoSuchObject
        if (errorStatus != 0) return Result.Error(errorStatus)

        reader.expect(SEQUENCE) // varbind list
        reader.expect(SEQUENCE) // first varbind
        reader.skipField() // the OID we asked for

        // SNMPv2 exception tags arrive in the value slot rather than as an error-status.
        when (val tag = reader.peekTag()) {
            0x80, 0x81, 0x82 -> Result.NoSuchObject
            NULL -> Result.Ok(ByteArray(0))
            else -> Result.Ok(reader.readValue(tag))
        }
    }.getOrElse {
        // The first bytes are worth carrying: every decode failure so far has been a cursor
        // landing somewhere it shouldn't, and the header is where you can see that.
        val prefix = packet.take(24).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
        Result.Failed("malformed SNMP reply (${it.message}) — starts $prefix")
    }

    /** Pulls the first varbind's OID and value out of a GetResponse to a GETNEXT. */
    internal fun decodeNextResponse(packet: ByteArray): Next = runCatching {
        val reader = Ber(packet)

        reader.expect(SEQUENCE)
        reader.skipField() // version
        reader.skipField() // community

        val pduTag = reader.peekTag()
        if (pduTag != GET_RESPONSE) {
            val prefix = packet.take(24).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
            return Next.Failed("not a GetResponse (tag 0x%02X) — starts %s".format(pduTag, prefix))
        }
        reader.expect(GET_RESPONSE)

        reader.skipField() // request id
        val errorStatus = reader.readInt()
        reader.skipField() // error index

        // A v1 agent walked off the end of the tree answers noSuchName rather than endOfMibView.
        if (errorStatus == ERROR_NO_SUCH_NAME) return Next.EndOfMib
        if (errorStatus != 0) return Next.Failed("error-status $errorStatus")

        reader.expect(SEQUENCE) // varbind list
        reader.expect(SEQUENCE) // first varbind
        val oid = reader.readOid()

        when (val tag = reader.peekTag()) {
            // noSuchObject / noSuchInstance / endOfMibView all mean "nothing more here" to a walk.
            0x80, 0x81, 0x82 -> Next.EndOfMib
            else -> Next.Ok(oid, tag, reader.readValue(tag))
        }
    }.getOrElse {
        val prefix = packet.take(24).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
        Next.Failed("malformed SNMP reply (${it.message}) — starts $prefix")
    }

    /** Just enough BER to walk a response: tags, self-describing lengths, and skipping. */
    private class Ber(private val data: ByteArray) {
        private var position = 0

        fun peekTag(): Int {
            require(position < data.size) { "truncated" }
            return data[position].toInt() and 0xFF
        }

        /** Steps into a constructed field, or past a primitive tag, leaving the content next. */
        fun expect(tag: Int) {
            val actual = readByte()
            require(actual == tag) { "expected tag 0x%02X, got 0x%02X".format(tag, actual) }
            readLength()
        }

        fun skipField() {
            readByte()

            // Not `position += readLength()`.
            val length = readLength()
            position += length
        }

        fun readInt(): Int {
            readByte()
            val length = readLength()
            var value = 0
            repeat(length) { value = (value shl 8) or readByte() }
            return value
        }

        /** Reads an OBJECT IDENTIFIER back into sub-identifiers — the reverse of [encodeOid]. */
        fun readOid(): List<Int> {
            val actual = readByte()
            require(actual == OBJECT_ID) { "expected an OID, got 0x%02X".format(actual) }
            val length = readLength()
            require(position + length <= data.size) { "truncated OID" }
            val end = position + length

            val first = readByte()
            val oid = mutableListOf(first / 40, first % 40)

            while (position < end) {
                var value = 0
                var b: Int
                do {
                    b = readByte()
                    value = (value shl 7) or (b and 0x7F)
                } while (b and 0x80 != 0)
                oid += value
            }

            return oid
        }

        fun readValue(tag: Int): ByteArray {
            val actual = readByte()
            require(actual == tag) { "value tag moved" }
            val length = readLength()
            require(position + length <= data.size) { "truncated value" }
            val value = data.copyOfRange(position, position + length)
            position += length
            return value
        }

        private fun readByte(): Int {
            require(position < data.size) { "truncated" }
            return data[position++].toInt() and 0xFF
        }

        /** Short form, or long form where the first byte counts the length's own bytes. */
        private fun readLength(): Int {
            val first = readByte()
            if (first and 0x80 == 0) return first

            var length = 0
            repeat(first and 0x7F) { length = (length shl 8) or readByte() }
            return length
        }
    }
}
