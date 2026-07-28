package nl.redlabs.epsonreset.debug

import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.net.EpsonMib
import nl.redlabs.epsonreset.net.NetworkAddress
import nl.redlabs.epsonreset.net.Snmp
import nl.redlabs.epsonreset.net.SnmpTransport
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.DeviceId
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FactoryReply
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status

/** Staged experiment for the network path. */
object NetworkProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val target = args.firstOrNull { !it.startsWith("--") }
        if (target == null) return usage()

        val link = NetworkAddress.parse(target) ?: return usage("'$target' is not an address.")
        val host = link.host

        val wantsRead = args.any { it == "--read" || it.startsWith("--read=") }
        val explicitAddress = args.firstOrNull { it.startsWith("--read=") }
            ?.removePrefix("--read=")?.toIntOrNull()
        val modelName = args.firstOrNull { it.startsWith("--model=") }?.removePrefix("--model=")

        section("Plan")
        println("target     $host  (SNMP, UDP 161)")
        println("stage 1    is an SNMP agent answering")
        println("stage 2    identity: model, serial, firmware, status")
        println("stage 3    does the command passthrough exist (plain 'st')")
        println(
            if (wantsRead) {
                "stage 4    one EEPROM read — the factory command"
            } else {
                "stage 4    skipped — pass --read to try it"
            },
        )
        println()
        println("All of it is SNMP GETs. Nothing is written and nothing can be printed.")

        // ── Stage 1 ──────────────────────────────────────────────────────────────────────────
        section("Stage 1: is anything there")

        when (val probe = Snmp.get(host, EpsonMib.DEVICE_ID)) {
            is Snmp.Result.Ok -> println("agent      answering, and it knows the Epson device-ID OID")
            Snmp.Result.Timeout -> return stop(
                "No answer on SNMP. Check the address, that the printer is awake, and that SNMP",
                "is enabled in its network settings.",
            )
            Snmp.Result.NoSuchObject -> return stop(
                "SNMP answered, but this device has no Epson device-ID OID.",
                "It is probably not an Epson.",
            )
            is Snmp.Result.Error -> return stop("SNMP refused the query (error ${probe.status}).", "")
            is Snmp.Result.Failed -> return stop("SNMP failed: ${probe.message}", "")
        }

        // ── Stage 2 ──────────────────────────────────────────────────────────────────────────
        section("Stage 2: identity")

        val reportedModel = SnmpTransport.string(host, EpsonMib.MODEL)
        val product = SnmpTransport.string(host, EpsonMib.PRODUCT)
        val serial = SnmpTransport.string(host, EpsonMib.SERIAL)
        val firmware = SnmpTransport.string(host, EpsonMib.FIRMWARE)

        println("model      ${reportedModel ?: "—"}      <- the exact model, the database's own key")
        println("product    ${product ?: "—"}      <- the marketing name, what DNS-SD advertises")
        println("serial     ${serial ?: "—"}")
        println("firmware   ${firmware ?: "—"}")

        SnmpTransport.string(host, EpsonMib.DEVICE_ID)?.let { raw ->
            DeviceId.parse(raw.toByteArray(Charsets.ISO_8859_1))?.let {
                println("commands   ${it.commandSets.joinToString(", ")}")
            }
        }

        SnmpTransport.bytes(host, EpsonMib.STATUS)?.let { raw ->
            Status.parse(raw)?.let { status ->
                println("status     ${status.fields.size} fields")
                for (ink in status.inkLevels) {
                    println("ink        %-14s %3d%%%s".format(ink.colour, ink.percent, if (ink.isLow) "   LOW" else ""))
                }
            }
        }

        if (reportedModel != null && product != null && reportedModel != product) {
            println()
            println("Note the two differ. Anything matching on '$product' lands on the wrong")
            println("database entry; '$reportedModel' is the one with the right addresses.")
        }

        // ── Stage 3 ──────────────────────────────────────────────────────────────────────────
        section("Stage 3: command passthrough")

        val plain = SequenceGenerator.statusPacket()
        println("sending    st, through ${oidText(EpsonMib.PASSTHROUGH)}.<command bytes>")

        val viaPassthrough = exchange(host, plain)
        if (viaPassthrough == null || viaPassthrough.isEmpty()) {
            return stop(
                "The passthrough did not answer a plain command.",
                "This model has no command channel over SNMP; identity above is all it offers.",
            )
        }

        println("PASSTHROUGH WORKS — a plain command went in and its reply came back.")

        if (!wantsRead) {
            println()
            println("Stage 4 not requested. The remaining question is whether this firmware accepts")
            println("a factory command, which is what counters need:")
            println("   ./gradlew netProbe --args=\"$target --read --model=${reportedModel ?: "<MODEL>"}\"")
            return
        }

        // ── Stage 4 ──────────────────────────────────────────────────────────────────────────
        section("Stage 4: factory command (one EEPROM read)")

        val name = modelName ?: reportedModel
        if (name == null) return stop("Pass --model=<NAME> so the read carries the right key.", "")

        val model = PrinterDatabase.load()[name]
            ?: return stop("'$name' is not in the database.", "")

        val address = explicitAddress
            ?: model.padGroups.firstOrNull()?.addresses?.firstOrNull()
            ?: return stop("${model.name} has no counter addresses to read.", "")

        println("model      ${model.name}  rkey=${model.readKey} (0x%04X)".format(model.readKey))
        println("address    $address (0x%04X)".format(address))
        println("note       carries the read key and no write key — it cannot modify anything")
        println()

        val reply = exchange(host, SequenceGenerator.readPacket(model.readKey, address)) ?: ByteArray(0)
        val readings = CounterReader.parseReplies(reply)

        println()
        when {
            readings.any { it.first == address } -> {
                val value = readings.first { it.first == address }.second
                println("THE READ WORKED — address $address holds 0x%02X (%d).".format(value, value))
                println("This firmware accepts factory commands over the network, so counters and")
                println("resets are both possible on this printer.")
            }

            FactoryReply.isRefused(reply) -> {
                println("REFUSED — the printer answered, and declined.")
                println()
                println(FactoryReply.explain(reply))
                println()
                println("Worth knowing this is a refusal of the command class, not of the key: a")
                println("deliberately wrong read key produces the identical reply. Nothing on this")
                println("side can change it. Counters need USB on this printer.")
            }

            reply.isEmpty() -> println("No reply at all, where the plain command in stage 3 answered.")
            else -> println("Something came back but it holds no EE: reading. Hex above.")
        }
    }

    /** Sends one command through the passthrough, dumping both directions. */
    private fun exchange(host: String, packet: ByteArray): ByteArray? {
        val command = nl.redlabs.epsonreset.protocol.EscpRemote.remoteCommandOf(packet) ?: return null

        println("[OUT] the command, as OID sub-identifiers:")
        println(Executor.hexDump(command).prependIndent("      "))
        println("      ...${command.joinToString(".") { (it.toInt() and 0xFF).toString() }}")

        return when (val result = Snmp.get(host, EpsonMib.passthroughFor(command))) {
            is Snmp.Result.Ok -> {
                val payload = EpsonMib.payloadOf(result.value)
                println("[IN]  ${payload.size} bytes (after the passthrough's status byte)")
                println(Executor.hexDump(payload).prependIndent("      "))
                payload
            }

            Snmp.Result.NoSuchObject -> {
                println("[IN]  noSuchObject — no passthrough at that OID")
                null
            }

            Snmp.Result.Timeout -> {
                println("[IN]  timeout")
                null
            }

            is Snmp.Result.Error -> {
                println("[IN]  SNMP error ${result.status}")
                null
            }

            is Snmp.Result.Failed -> {
                println("[IN]  ${result.message}")
                null
            }
        }
    }

    private fun oidText(oid: List<Int>) = oid.joinToString(".")

    private fun stop(vararg lines: String) {
        println()
        lines.filter { it.isNotEmpty() }.forEach { println(it) }
    }

    private fun usage(problem: String? = null) {
        problem?.let { println(it) }
        println()
        println("Usage: ./gradlew netProbe --args=\"<address> [--read[=<addr>]] [--model=<NAME>]\"")
        println()
        println("   ./gradlew netProbe --args=\"192.168.2.39\"")
        println("       stages 1-3 — agent, identity, and the command passthrough")
        println()
        println("   ./gradlew netProbe --args=\"192.168.2.39 --read\"")
        println("       also stage 4 — one EEPROM read, using the model the printer reports")
    }

    private fun section(name: String) = println("\n── $name ${"─".repeat((60 - name.length).coerceAtLeast(0))}")
}
