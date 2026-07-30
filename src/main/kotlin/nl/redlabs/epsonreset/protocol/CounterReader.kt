package nl.redlabs.epsonreset.protocol

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterModel

/** Reads EEPROM bytes back off the printer. */
object CounterReader {

    private data class Target(val address: Int, val expected: Int, val group: PadGroup)

    /** One address sampled off the device. [value] is null when the printer didn't answer. */
    data class Reading(
        val address: Int,
        val value: Int?,
        val expectedAfterReset: Int,
        val groupDescription: String,
        val error: String? = null,
    ) {
        /** True when the byte already holds its post-reset value. */
        val isAtResetValue: Boolean get() = value != null && value == expectedAfterReset

        val hex: String get() = value?.let { "0x%02X".format(it) } ?: "—"
    }

    data class Report(val model: String, val readings: List<Reading>, val error: String? = null) {
        val answered: Int get() = readings.count { it.value != null }
        val total: Int get() = readings.size
        val allAtResetValue: Boolean
            get() = readings.isNotEmpty() && readings.all { it.isAtResetValue }
    }

    interface Listener {
        fun onProgress(done: Int, total: Int, address: Int) {}
        fun onReading(reading: Reading) {}
        fun onTrace(line: String) {}
    }

    /** A [CounterSpec] resolved against real bytes. */
    data class DecodedCounter(val spec: CounterSpec, val value: Long?, val bytes: List<Int?>) {
        val percent: Double? get() = value?.let { spec.percentOf(it) }

        /** The number, or a dash when the group isn't a single value or a byte is missing. */
        val display: String get() = value?.toString() ?: if (spec.isSingleValue) "—" else "bytes"

        val hexBytes: String
            get() = bytes.joinToString(" ") { b -> b?.let { "%02X".format(it) } ?: "--" }
    }

    /** Groups a flat set of readings into the model's actual counters. */
    fun decode(readings: List<Reading>, specs: List<CounterSpec>): List<DecodedCounter> {
        val bytes = readings.associate { it.address to it.value }
        return specs.map { spec ->
            DecodedCounter(
                spec = spec,
                value = spec.decode(bytes),
                bytes = spec.addresses.map { bytes[it] },
            )
        }
    }

    /**
     * The part of a counter report known from the model database alone. [defaultValue] is used by
     * the dry-run preview; null deliberately means that no printer value has been read yet.
     */
    fun layout(model: PrinterModel, specs: List<CounterSpec> = emptyList(), defaultValue: Int? = null): Report = Report(
        model = model.name,
        readings = targets(model, specs).map { target ->
            Reading(
                address = target.address,
                value = defaultValue,
                expectedAfterReset = target.expected,
                groupDescription = target.group.description,
            )
        },
    )

    /** Asks the printer for its own status block (serial, ink levels, state). */
    fun readStatus(transport: Transport, listener: Listener? = null): Status.Report? {
        val collected = java.io.ByteArrayOutputStream()

        for (packet in SequenceGenerator.creditPair()) {
            if (!transport.send(packet)) return null
            collected.write(transport.drain())
        }

        if (!transport.send(SequenceGenerator.statusPacket())) return null
        collected.write(transport.drain())

        // Same credit deferral as the EEPROM read: the reply rides a later exchange.
        if (Status.parse(collected.toByteArray()) == null) {
            for (packet in SequenceGenerator.creditPair()) {
                if (!transport.send(packet)) break
                collected.write(transport.drain())
            }
        }

        val reply = collected.toByteArray()
        listener?.onTrace("[IN]  status (${reply.size} bytes)\n${Executor.hexDump(reply)}")
        return Status.parse(reply)
    }

    /** Samples every address in the model's pad groups. */
    fun readAll(
        transport: Transport,
        model: PrinterModel,
        specs: List<CounterSpec> = emptyList(),
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
    ): Report {
        val targets = targets(model, specs)
        if (targets.isEmpty()) return Report(model.name, emptyList(), "This model has no known counter addresses.")

        for (packet in SequenceGenerator.handshake()) {
            if (!transport.send(packet)) {
                return Report(model.name, emptyList(), "Transport failure during channel handshake.")
            }
            listener?.onTrace("[OUT] handshake (${packet.size} bytes)\n${Executor.hexDump(packet)}")
            transport.drain()
        }

        val readings = mutableListOf<Reading>()

        for ((index, target) in targets.withIndex()) {
            val (address, expected, group) = target
            if (isCancelled()) {
                return Report(model.name, readings, "Cancelled after ${readings.size} of ${targets.size} reads.")
            }

            listener?.onProgress(index + 1, targets.size, address)
            val reading = readOne(transport, model, address, expected, group, listener)
            readings += reading
            listener?.onReading(reading)

            // A firmware that declines factory commands declines all of them, so there is nothing
            // to learn from the remaining addresses and a reason to stop asking: the run should say
            // why it cannot read rather than grind through a hundred identical refusals.
            if (reading.error == REFUSED) {
                return Report(model.name, readings, REFUSAL_EXPLANATION)
            }
        }

        return Report(model.name, readings)
    }

    private fun targets(model: PrinterModel, specs: List<CounterSpec>): List<Target> {
        val fromPadGroups = model.padGroups.flatMap { group ->
            group.addresses.indices.map { i ->
                Target(group.addresses[i], group.resetValues[i], group)
            }
        }

        // Counter specs can name addresses the pad groups omit. Read those too, or a counter would
        // decode to null purely because one of its bytes was never sampled.
        val known = fromPadGroups.map { it.address }.toSet()
        val extras = specs.flatMap { spec ->
            val group = PadGroup(spec.description, "", emptyList(), emptyList())
            spec.addresses.mapIndexed { index, address ->
                Target(address, spec.resetValues.getOrNull(index) ?: 0, group)
            }
        }
            .distinctBy { it.address }
            .filter { it.address !in known }

        return fromPadGroups + extras
    }

    private fun readOne(
        transport: Transport,
        model: PrinterModel,
        address: Int,
        expected: Int,
        group: PadGroup,
        listener: Listener?,
    ): Reading {
        val collected = java.io.ByteArrayOutputStream()

        // Credit first, or the printer has no allowance to send its reply.
        for (packet in SequenceGenerator.creditPair()) {
            if (!transport.send(packet)) {
                return Reading(address, null, expected, group.description, "transport failure (credit)")
            }
            collected.write(transport.drain())
        }

        val packet = SequenceGenerator.readPacket(model.readKey, address)
        listener?.onTrace("[OUT] read %d (0x%04X)\n%s".format(address, address, Executor.hexDump(packet)))

        if (!transport.send(packet)) {
            return Reading(address, null, expected, group.description, "transport failure")
        }

        collected.write(transport.drain())

        // Under D4 credit flow the printer may not transmit until it is granted credit again, so
        // the answer to this read often rides along with the *next* credit exchange rather than
        // arriving on the drain immediately after the command.
        if (parseReplies(collected.toByteArray()).none { it.first == address }) {
            for (nudge in SequenceGenerator.creditPair()) {
                if (!transport.send(nudge)) break
                collected.write(transport.drain())
            }
        }

        val reply = collected.toByteArray()
        listener?.onTrace("[IN]  reply (${reply.size} bytes)\n${Executor.hexDump(reply)}")

        // The printer echoes the address it read, so pick our own reading out of the buffer rather
        // than trusting position — a stale reply for another address must never be misreported.
        val all = parseReplies(reply)
        val mine = all.firstOrNull { it.first == address }

        return when {
            mine != null -> Reading(address, mine.second, expected, group.description)
            // A refusal is not a silent failure and must not be reported as one: the printer
            // answered, and said no to the command class. See FactoryReply.
            FactoryReply.isRefused(reply) ->
                Reading(address, null, expected, group.description, "refused (:41:NA;)")
            all.isEmpty() -> Reading(address, null, expected, group.description, "no EE: reply")
            else -> Reading(
                address,
                null,
                expected,
                group.description,
                "replies were for ${all.joinToString(",") { it.first.toString() }}",
            )
        }
    }

    /**
     * Pulls the first `EE:AAAAVV;` out of the reply. Six hex digits: address big-endian, then the
     * value byte.
     */
    fun parseReply(reply: ByteArray): Pair<Int, Int>? = parseReplies(reply).firstOrNull()

    /** All readings in a buffer. */
    fun parseReplies(reply: ByteArray): List<Pair<Int, Int>> {
        val text = String(reply, Charsets.ISO_8859_1)
        return EE_PATTERN.findAll(text).mapNotNull { match ->
            val hex = match.groupValues[1]
            val address = hex.substring(0, 4).toIntOrNull(16) ?: return@mapNotNull null
            val value = hex.substring(4, 6).toIntOrNull(16) ?: return@mapNotNull null
            address to value
        }.toList()
    }

    private val EE_PATTERN = Regex("""EE:([0-9a-fA-F]{6});""")

    private const val REFUSED = "refused (:41:NA;)"

    private const val REFUSAL_EXPLANATION =
        "The printer refused the counter read (:41:NA;) — it is declining factory commands over " +
            "this connection, not objecting to the key or the model. Connect it over USB to read " +
            "counters."
}
