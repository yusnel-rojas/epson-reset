package nl.redlabs.epsonreset.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/** One-shot DNS-SD browse for printers advertising raw port 9100. */
object MdnsDiscovery {

    const val SERVICE = "_pdl-datastream._tcp.local"

    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val RECEIVE_BUFFER = 8192

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    /** One advertised service instance, resolved as far as the responses allow. */
    data class Service(val instance: String, val host: String, val port: Int, val txt: Map<String, String>) {
        /** `usb_MDL` is the USB model string verbatim, which is what the matcher wants. */
        val model: String? get() = txt["usb_MDL"] ?: txt["ty"] ?: instance.takeIf { it.isNotBlank() }

        val manufacturer: String? get() = txt["usb_MFG"] ?: txt["mfg"]

        /** True when this looks like an Epson. */
        val isEpson: Boolean
            get() = listOfNotNull(manufacturer, model, instance)
                .any { it.contains("EPSON", ignoreCase = true) }
    }

    sealed interface BrowseResult {
        data class Ok(val services: List<Service>) : BrowseResult

        /** Multicast couldn't be used at all — sandbox, permissions, or no usable interface. */
        data class Unavailable(val detail: String, val hint: String) : BrowseResult
    }

    /** Sends one query and collects answers until [timeoutMs] elapses. */
    fun browse(service: String = SERVICE, timeoutMs: Long = 2500): BrowseResult {
        val records = mutableListOf<Record>()

        val multicast = openMulticast()
        if (multicast != null) {
            multicast.use { collect(it, query(service), records, timeoutMs) }
        } else {
            val unicast = openUnicastFallback()
                ?: return BrowseResult.Unavailable(
                    "No multicast socket could be opened.",
                    "Add the printer by IP address instead — discovery is a convenience, not a " +
                        "requirement.",
                )
            unicast.use { collect(it, query(service, unicastReply = true), records, timeoutMs) }
        }

        // Everything advertised, Epson or not. Deciding what we are willing to send EEPROM
        // commands to is the device layer's call, not the wire's.
        return BrowseResult.Ok(assemble(records, service))
    }

    /** Binds 5353 and joins the group on every interface that supports it. */
    private fun openMulticast(): MulticastSocket? = runCatching {
        // The constructor sets SO_REUSEADDR before binding, which is the whole reason sharing the
        // port with the OS resolver works.
        val socket = MulticastSocket(MDNS_PORT)
        socket.timeToLive = 255

        val group = InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT)
        var joined = 0
        for (nif in usableInterfaces()) {
            runCatching { socket.joinGroup(group, nif) }.onSuccess { joined++ }
        }

        if (joined == 0) {
            socket.close()
            null
        } else {
            socket
        }
    }.getOrNull()

    /** An ordinary socket asking for a unicast reply (RFC 6762 §5.4's QU bit). */
    private fun openUnicastFallback(): DatagramSocket? = runCatching { DatagramSocket() }.getOrNull()

    private fun usableInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            runCatching { it.isUp && it.supportsMulticast() && !it.isLoopback }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    /** Sends the query on every interface, then reads until the deadline. */
    private fun collect(socket: DatagramSocket, query: ByteArray, into: MutableList<Record>, timeoutMs: Long) {
        val target = InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT)
        val packet = DatagramPacket(query, query.size, target)

        if (socket is MulticastSocket) {
            var sent = false
            for (nif in usableInterfaces()) {
                runCatching {
                    socket.networkInterface = nif
                    socket.send(packet)
                    sent = true
                }
            }
            if (!sent) runCatching { socket.send(packet) }
        } else {
            runCatching { socket.send(packet) }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(RECEIVE_BUFFER)

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val reply = DatagramPacket(buffer, buffer.size)

            val received = runCatching {
                socket.receive(reply)
                true
            }.getOrDefault(false)
            if (!received) break

            runCatching { into += parse(reply.data.copyOfRange(0, reply.length)) }
        }
    }

    // ── Wire format ──────────────────────────────────────────────────────────────────────────

    sealed interface Record {
        val name: String

        data class Ptr(override val name: String, val target: String) : Record
        data class Srv(override val name: String, val port: Int, val target: String) : Record
        data class Txt(override val name: String, val values: Map<String, String>) : Record
        data class Addr(override val name: String, val address: String) : Record
    }

    /** A standard query for one PTR. */
    fun query(service: String, unicastReply: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        fun short(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        short(0) // transaction id — mDNS ignores it
        short(0) // flags: standard query
        short(1) // one question
        short(0) // no answers
        short(0) // no authority
        short(0) // no additional

        for (label in service.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)

        short(TYPE_PTR)
        short(if (unicastReply) 0x8001 else 0x0001)

        return out.toByteArray()
    }

    /** Every A/PTR/SRV/TXT record in a response. Unknown types are skipped, not guessed at. */
    fun parse(packet: ByteArray): List<Record> {
        val reader = Reader(packet)

        return runCatching {
            reader.skip(4)
            val questions = reader.short()
            val answers = reader.short()
            val authority = reader.short()
            val additional = reader.short()

            repeat(questions) {
                reader.name()
                reader.skip(4)
            }

            val records = mutableListOf<Record>()
            repeat(answers + authority + additional) {
                readRecord(reader)?.let { records += it }
            }
            records
        }.getOrDefault(emptyList())
    }

    private fun readRecord(reader: Reader): Record? {
        val name = reader.name()
        val type = reader.short()
        reader.short() // class, plus the cache-flush bit we have no use for
        reader.skip(4) // ttl
        val length = reader.short()
        val end = reader.position + length

        val record = when (type) {
            TYPE_PTR -> Record.Ptr(name, reader.name())
            TYPE_SRV -> {
                reader.skip(4) // priority, weight
                Record.Srv(name, reader.short(), reader.name())
            }
            TYPE_TXT -> Record.Txt(name, readTxt(reader, end))
            TYPE_A -> Record.Addr(
                name,
                (0 until 4).joinToString(".") { reader.byte().toString() },
            )
            else -> null
        }

        // rdata length is authoritative — a record we half-read or skipped must not shift the
        // cursor for everything after it.
        reader.position = end
        return record
    }

    private fun readTxt(reader: Reader, end: Int): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        while (reader.position < end) {
            val length = reader.byte()
            if (length == 0 || reader.position + length > end) break
            val entry = reader.text(length)
            val split = entry.indexOf('=')
            if (split > 0) {
                values[entry.substring(0, split)] = entry.substring(split + 1)
            } else {
                values[entry] = ""
            }
        }
        return values
    }

    /** Ties the record types together into resolved services. */
    fun assemble(records: List<Record>, service: String): List<Service> {
        val suffix = ".${service.trim('.')}"
        val addresses = records.filterIsInstance<Record.Addr>().associate { it.name to it.address }
        val texts = records.filterIsInstance<Record.Txt>().associate { it.name to it.values }

        return records.filterIsInstance<Record.Srv>()
            .filter { it.name.endsWith(suffix, ignoreCase = true) }
            .distinctBy { it.name }
            .map { srv ->
                Service(
                    instance = srv.name.dropLast(suffix.length),
                    // The A record is normally in the same response's additional section.
                    host = addresses[srv.target] ?: srv.target.trimEnd('.'),
                    port = srv.port,
                    txt = texts[srv.name].orEmpty(),
                )
            }
    }

    /** Cursor over a DNS message, resolving the compression pointers names arrive as. */
    private class Reader(private val data: ByteArray) {
        var position = 0

        fun byte(): Int {
            require(position < data.size) { "truncated message" }
            return data[position++].toInt() and 0xFF
        }

        fun short(): Int = (byte() shl 8) or byte()

        fun skip(count: Int) {
            position += count
        }

        fun text(length: Int): String {
            require(position + length <= data.size) { "truncated string" }
            val value = String(data, position, length, Charsets.UTF_8)
            position += length
            return value
        }

        /**
         * A name is labels until a zero byte, except a label whose top two bits are set is a 14-bit
         * offset to the rest of the name elsewhere in the message.
         */
        fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = position
            var jumped = false
            var hops = 0

            while (true) {
                require(cursor < data.size) { "truncated name" }
                val length = data[cursor].toInt() and 0xFF

                if (length == 0) {
                    cursor++
                    break
                }

                if (length and 0xC0 == 0xC0) {
                    require(cursor + 1 < data.size) { "truncated pointer" }
                    val offset = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                    if (!jumped) {
                        position = cursor + 2
                        jumped = true
                    }
                    require(++hops < MAX_POINTER_HOPS) { "pointer loop" }
                    cursor = offset
                    continue
                }

                require(cursor + 1 + length <= data.size) { "truncated label" }
                labels += String(data, cursor + 1, length, Charsets.UTF_8)
                cursor += 1 + length
            }

            if (!jumped) position = cursor
            return labels.joinToString(".")
        }

        private companion object {
            const val MAX_POINTER_HOPS = 64
        }
    }
}
