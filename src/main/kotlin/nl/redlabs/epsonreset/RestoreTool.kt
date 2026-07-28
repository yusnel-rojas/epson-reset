package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.backup.UnitChoice
import nl.redlabs.epsonreset.backup.UnitSelector
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import java.io.File

/** Writes a saved backup back to the printer. */
object RestoreTool {

    @JvmStatic
    fun main(args: Array<String>) {
        val live = args.any { it == "--live" }
        val path = args.firstOrNull { !it.startsWith("--") }

        if (path == null) {
            listBackups()
            return
        }

        val file = resolve(path)
        if (file == null) {
            println("No such backup: $path")
            println("Run without arguments to list what is saved.")
            return
        }

        val backup = EepromBackup.load(file)
        if (backup == null) {
            println("Could not read $file as a backup — it is malformed, or a byte is out of range.")
            return
        }

        section("Backup")
        println("file       ${file.name}")
        println("model      ${backup.model}")
        println("taken      ${backup.createdAt}")
        println("serial     ${backup.printerSerial ?: "—"}")
        println("addresses  ${backup.entries.size}  (${backup.changedByReset} differ from the reset value)")

        val db = runCatching { PrinterDatabase.load() }.getOrElse {
            println("\nFAILED to load the database: ${it.message}")
            return
        }
        val model = db[backup.model]
        if (model == null) {
            println("\n'${backup.model}' is no longer in the database, so its write key is unavailable.")
            return
        }

        val sequence = SequenceGenerator.generateWrites(model, backup.writes)

        section("Would write")
        println("   addr   restore to   reset had put")
        for (e in backup.entries.take(24)) {
            println(
                "   %4d   0x%02X         0x%02X%s".format(
                    e.address,
                    e.value,
                    e.resetValue,
                    if (e.value == e.resetValue) "   (unchanged)" else "",
                ),
            )
        }
        if (backup.entries.size > 24) println("   … ${backup.entries.size - 24} more")
        println("\npackets    ${sequence.size}  (${sequence.count { Executor.isWritePacket(it) }} writes)")

        if (!live) {
            simulate(sequence)
            println("\nThis wrote nothing. To write it for real:")
            // Echo back what was passed, not file.name — an out-of-directory path would not
            // resolve from its bare name.
            println("  ./gradlew restore --args=\"$path --live\"")
            return
        }

        writeForReal(backup, model, db, sequence)
    }

    private fun listBackups() {
        section("Saved backups")
        println("dir        ${AppPaths.backups}")

        val files = EepromBackup.list()
        if (files.isEmpty()) {
            println("\nNone yet. One is written automatically before every live reset.")
            return
        }

        println()
        for (f in files) {
            val b = EepromBackup.load(f)
            if (b == null) {
                println("   ${f.name}   [unreadable]")
            } else {
                println("   %-40s %-10s %d addresses".format(f.name, b.model, b.entries.size))
            }
        }
        println("\nInspect one with:  ./gradlew restore --args=\"${files.first().name}\"")
    }

    /** Accepts a bare filename from the backup dir, or any path to a backup elsewhere. */
    private fun resolve(path: String): File? {
        File(path).takeIf { it.isFile }?.let { return it }
        return File(AppPaths.backups, path).takeIf { it.isFile }
    }

    private fun simulate(sequence: List<ByteArray>) {
        section("Simulated run  [no printer involved]")

        val transport = FakeTransport()
        val result = Executor.execute(
            transport = transport,
            sequence = sequence,
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
        )
        println("result     ${if (result.success) "OK" else "FAILED — ${result.error}"}")
        println("verified   ${result.writesVerified}/${result.writesTotal}")
    }

    private fun writeForReal(
        backup: EepromBackup,
        model: PrinterModel,
        db: PrinterDatabase,
        sequence: List<ByteArray>,
    ) {
        section("Live restore  [REAL HARDWARE — this writes to EEPROM]")

        val printers = PrinterDiscovery.scan().printers
        if (printers.isEmpty()) {
            println("No Epson device found on USB or the network — nothing was written.")
            return
        }

        // Resolve what is actually plugged in rather than taking the first Epson on the bus.
        val device = when (val choice = UnitSelector.choose(backup, DeviceMatcher.matchAll(printers, db))) {
            is UnitChoice.NoSuchModel -> {
                println("\nREFUSED — no connected printer resolves to ${choice.model}.")
                choice.found.forEach { println("   found      $it") }
                return
            }

            is UnitChoice.WrongUnit -> {
                val connected = choice.connected.joinToString(", ")
                println(
                    "\nREFUSED — this backup came from ${choice.wanted}; " +
                        "the connected printer reports $connected.",
                )
                return
            }

            is UnitChoice.Ambiguous -> {
                println(
                    "\nREFUSED — ${choice.count} ${choice.model} units are connected and none can be matched by serial.",
                )
                println("Disconnect the others and run this again.")
                return
            }

            is UnitChoice.Write -> {
                choice.unconfirmed?.let { println("note       $it — cannot confirm this is the same unit") }
                choice.device
            }
        }

        println("target     ${device.displayName} on ${device.link.kind} at ${device.link.where}")
        println("model      ${model.name}")
        println("serial     ${device.serial ?: "—"}")

        when (val opened = PrinterTransports.open(device)) {
            is PrinterTransports.OpenResult.Failed -> {
                println("FAILED     ${opened.message}")
                opened.remedy?.let { println("remedy     $it") }
            }

            is PrinterTransports.OpenResult.Ok -> opened.transport.use { transport ->
                val result = Executor.execute(transport = transport, sequence = sequence)

                println("result     ${if (result.success) "OK" else "FAILED — ${result.error}"}")
                println("verified   ${result.writesVerified}/${result.writesTotal}")

                if (result.success) {
                    val back = CounterReader.readAll(transport, model)
                    val saved = backup.entries.associate { it.address to it.value }
                    val wrong = back.readings.filter { r ->
                        r.value != null && saved[r.address] != null && r.value != saved[r.address]
                    }
                    println(
                        if (wrong.isEmpty()) {
                            "read-back  every restored address matches the backup"
                        } else {
                            "read-back  ${wrong.size} address(es) do not match: " +
                                wrong.take(6).joinToString(", ") { "${it.address}=${it.hex}" }
                        },
                    )
                    println("\nPower-cycle the printer to finalise the change.")
                }
            }
        }
    }

    private fun section(name: String) = println("\n── $name ${"─".repeat((60 - name.length).coerceAtLeast(0))}")
}
