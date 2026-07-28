package nl.redlabs.epsonreset.probe

import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Transport

/** Read-only exploration of a printer the database doesn't cover. */
object DeviceInspector {

    /** How one candidate read key fared. [hits] is how many probe addresses actually answered. */
    data class KeyResult(
        val readKey: Int,
        val hits: Int,
        val probes: Int,
        val readings: Map<Int, Int>,
        val exampleModels: List<String> = emptyList(),
    ) {
        val answered: Boolean get() = hits > 0
        val hitRate: Float get() = if (probes == 0) 0f else hits.toFloat() / probes
        val hex: String get() = "0x%04X".format(readKey)
    }

    /** A completed address sweep. [values] holds only the addresses that answered. */
    data class Sweep(
        val readKey: Int,
        val requested: List<Int>,
        val values: Map<Int, Int>,
        val error: String? = null,
    ) {
        val answered: Int get() = values.size
        val total: Int get() = requested.size
        val silent: List<Int> get() = requested.filter { it !in values }
    }

    interface Listener {
        fun onProgress(done: Int, total: Int, label: String) {}
        fun onTrace(line: String) {}
        fun onNote(text: String) {}
    }

    /** Candidate read keys, most widely used first. */
    fun candidateKeys(db: PrinterDatabase): List<Int> = db.models
        .filter { it.hasResettableCounters && it.readKey != 0 }
        .groupingBy { it.readKey }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    /** Models sharing a read key — the family whose layouts are the best guess for a new member. */
    fun siblingsOf(db: PrinterDatabase, readKey: Int): List<PrinterModel> =
        db.models.filter { it.readKey == readKey && it.hasResettableCounters }

    /** Addresses worth probing for a given key: the ones that key's family actually uses. */
    fun probeAddressesFor(db: PrinterDatabase, readKey: Int, limit: Int = 3): List<Int> = siblingsOf(db, readKey)
        .flatMap { model -> model.padGroups.flatMap { it.addresses } }
        .distinct()
        .sorted()
        .take(limit)

    /** Tries each candidate key against the printer and reports which ones answered. */
    fun discoverKey(
        transport: Transport,
        db: PrinterDatabase,
        keys: List<Int> = candidateKeys(db),
        listener: Listener? = null,
        stopAfter: Int = 3,
        isCancelled: () -> Boolean = { false },
    ): List<KeyResult> {
        if (!handshake(transport, listener)) {
            listener?.onNote("The D4 channel never opened — the printer answered nothing.")
            return emptyList()
        }

        val results = mutableListOf<KeyResult>()

        for ((index, key) in keys.withIndex()) {
            if (isCancelled() || results.count { it.answered } >= stopAfter) break

            val probes = probeAddressesFor(db, key).ifEmpty { DEFAULT_PROBES }
            listener?.onProgress(index + 1, keys.size, "Trying key 0x%04X".format(key))

            val readings = mutableMapOf<Int, Int>()
            for (address in probes) {
                if (isCancelled()) break
                readOne(transport, key, address, listener)?.let { readings[address] = it }
            }

            val result = KeyResult(
                readKey = key,
                hits = readings.size,
                probes = probes.size,
                readings = readings,
                exampleModels = siblingsOf(db, key).take(3).map { it.name },
            )
            results += result

            if (result.answered) {
                listener?.onNote(
                    "Key ${result.hex} answered ${result.hits}/${result.probes} probes" +
                        result.exampleModels.takeIf { it.isNotEmpty() }
                            ?.let { " (used by ${it.joinToString(", ")})" }.orEmpty(),
                )
            }
        }

        return results.sortedWith(compareByDescending<KeyResult> { it.hitRate }.thenBy { it.readKey })
    }

    /** Reads every address in [addresses] with [readKey]. */
    fun sweep(
        transport: Transport,
        readKey: Int,
        addresses: List<Int>,
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
        handshakeFirst: Boolean = true,
    ): Sweep {
        if (handshakeFirst && !handshake(transport, listener)) {
            return Sweep(readKey, addresses, emptyMap(), "The D4 channel never opened.")
        }

        val values = mutableMapOf<Int, Int>()
        for ((index, address) in addresses.withIndex()) {
            if (isCancelled()) {
                return Sweep(readKey, addresses, values, "Cancelled after $index of ${addresses.size} reads.")
            }
            listener?.onProgress(index + 1, addresses.size, "Reading 0x%04X".format(address))
            readOne(transport, readKey, address, listener)?.let { values[address] = it }
        }
        return Sweep(readKey, addresses, values)
    }

    /** The EEPROM window a model's own `mem_high` implies, capped so a sweep stays finite. */
    fun defaultRange(memHigh: Int, cap: Int = 512): List<Int> = (0..minOf(memHigh, cap - 1)).toList()

    private fun handshake(transport: Transport, listener: Listener?): Boolean {
        var sawAck = false
        for (packet in SequenceGenerator.handshake()) {
            assertReadOnly(packet)
            if (!transport.send(packet)) return false
            val reply = transport.drain()
            if (reply.isNotEmpty()) sawAck = true
            listener?.onTrace("[OUT] handshake (${packet.size} bytes)\n${Executor.hexDump(packet)}")
            if (reply.isNotEmpty()) listener?.onTrace("[IN]  ${reply.size} bytes\n${Executor.hexDump(reply)}")
        }
        // A totally silent handshake means nothing downstream can work; anything else is worth trying.
        return sawAck
    }

    /**
     * One read, with the same credit deferral [CounterReader] needs: the reply to a read often
     * rides a *later* credit exchange rather than the drain that follows the command.
     */
    private fun readOne(transport: Transport, readKey: Int, address: Int, listener: Listener?): Int? {
        val collected = java.io.ByteArrayOutputStream()

        for (packet in SequenceGenerator.creditPair()) {
            assertReadOnly(packet)
            if (!transport.send(packet)) return null
            collected.write(transport.drain())
        }

        val packet = SequenceGenerator.readPacket(readKey, address)
        assertReadOnly(packet)
        if (!transport.send(packet)) return null
        collected.write(transport.drain())

        if (CounterReader.parseReplies(collected.toByteArray()).none { it.first == address }) {
            for (nudge in SequenceGenerator.creditPair()) {
                assertReadOnly(nudge)
                if (!transport.send(nudge)) break
                collected.write(transport.drain())
            }
        }

        val reply = collected.toByteArray()
        if (reply.isNotEmpty()) {
            listener?.onTrace("[IN]  0x%04X (${reply.size} bytes)\n%s".format(address, Executor.hexDump(reply)))
        }

        // Match on the echoed address, never on position — one drain can carry a previous read's
        // reply, and misattributing it would invent a value the printer never reported.
        return CounterReader.parseReplies(reply).firstOrNull { it.first == address }?.second
    }

    /** Probe addresses for a key with no family — the low block every Epson seems to populate. */
    private val DEFAULT_PROBES = listOf(0x14, 0x18, 0x1C)

    /** The guarantee, enforced rather than documented: this object never emits an EEPROM write. */
    private fun assertReadOnly(packet: ByteArray) {
        check(!Executor.isWritePacket(packet)) {
            "DeviceInspector attempted an EEPROM write — this path must stay read-only"
        }
    }
}
