package nl.redlabs.epsonreset.debug

import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.protocol.Alignment
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Maintenance
import nl.redlabs.epsonreset.protocol.Status

/**
 * Hardware experiment: get a printer to actually perform a maintenance operation.
 *
 * Nothing is sent without `--live`. Two framings are available, and they answer different questions:
 *
 * - **remote mode** (the default) — the command as print data, which is where the *action* form
 *   lives. This is the one that might work.
 * - `--control` — the command on the 1284.4 control channel. Already answered: an ET-2825 parses it
 *   (`nc:;`) and does nothing. Kept because that finding is worth being able to reproduce.
 *
 * `--params=00,10` overrides the remote parameter bytes, which are the least established part of
 * this and the most likely thing to need a second attempt.
 */
object MaintenanceProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val live = args.any { it == "--live" }
        val useControl = args.any { it == "--control" }
        val precheck = args.none { it == "--no-precheck" }
        val override = args.firstOrNull { it.startsWith("--params=") }
            ?.removePrefix("--params=")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull(16) }

        val requested = args.firstOrNull { !it.startsWith("--") }

        if (requested.equals("align", ignoreCase = true)) {
            alignment(args, live)
            return
        }

        val operation = requested?.let { name ->
            Maintenance.Operation.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        if (requested != null && operation == null) {
            println("Unknown operation '$requested'. One of:")
            Maintenance.Operation.entries.forEach { println("   ${it.name.lowercase()}   ${it.label}") }
            println("   align          Print-head alignment (its own three steps)")
            return
        }

        section("Operations")
        for (op in Maintenance.Operation.entries) {
            println(op.name.lowercase())
            println("   ${op.label} — ${op.summary}")
            println("   ink        ${op.inkCost.label}${if (op.printsPage) ", prints a page" else ""}")
            println("   confidence ${op.confidence.name.lowercase()}")
            println("   control    ${hex(Maintenance.packetFor(op))}")
            println("   remote     ${hex(Maintenance.remoteSequenceFor(op))}")
        }

        section("Printer")
        val device = PrinterDiscovery.scan().printers.firstOrNull()
        if (device == null) {
            println("No Epson found on USB or the network.")
            println("\nThe sequences above were built without one; nothing was sent.")
            return
        }
        println("target     ${device.displayName} on ${device.link.kind} at ${device.link.where}")
        println("framing    ${if (useControl) "control channel" else "remote mode (print data)"}")
        if (!useControl) {
            println(
                "order      " + if (precheck) {
                    "status, explicit packet-mode exit, command, then hands off to the printer"
                } else {
                    "command only (--no-precheck), then hands off to the printer"
                },
            )
        }

        if (!useControl && device.link !is Link.Usb) {
            println(
                "\nRemote mode is print data, and the SNMP transport carries control commands only.\n" +
                    "Connect this printer over USB to try it, or add --control to reproduce the\n" +
                    "control-channel finding over the network.",
            )
            return
        }

        // Each phase of a remote run needs its own connection, so the probe hands over a way to
        // make them rather than one transport.
        val connection = Maintenance.Connection {
            when (val opened = PrinterTransports.open(device)) {
                is PrinterTransports.OpenResult.Ok -> opened.transport
                is PrinterTransports.OpenResult.Failed -> {
                    println("open       FAILED — ${opened.detail}")
                    null
                }
            }
        }

        if (operation == null) {
            connection.open()?.use { report(CounterReader.readStatus(it)) }
            println("\nNothing was sent. Name an operation to go further, e.g.")
            println("  ./gradlew maintenanceProbe --args=\"nozzle_check\"")
            return
        }

        val parameters = override ?: Maintenance.remoteParametersOf(operation)

        section("${operation.label}  ${if (live) "[LIVE — this spends ink]" else "[preview]"}")

        if (!live) {
            println("would send ${hex(Maintenance.remoteSequenceFor(operation, parameters))}")
            println("params     ${parameters.joinToString(" ") { "%02X".format(it) }.ifEmpty { "(none)" }}")
            println("ink cost   ${operation.inkCost.label}")
            if (operation.raisesWasteCounter) {
                println("note       this RAISES the waste counter — the ink lands in the pad")
            }
            println("\nNothing was sent. To run it for real:")
            println("  ./gradlew maintenanceProbe --args=\"$requested --live\"")
            return
        }

        val listener = object : Maintenance.Listener {
            override fun onTrace(line: String) = println(line.prependIndent("   "))
            override fun onNote(text: String) = println(">>> $text")
        }

        val result = if (useControl) {
            connection.open()?.use { Maintenance.run(it, operation, listener) }
        } else {
            Maintenance.runInRemoteMode(connection, operation, parameters, listener, precheck)
        } ?: run {
            println("Could not open the printer.")
            return
        }

        section("Result")
        val sentWithoutPoll = !useControl && result.error == null && result.stateAfter == null
        println(
            "outcome    " + when {
                result.accepted -> "accepted — printer state showed the operation running"
                result.error != null -> "failed"
                sentWithoutPoll -> "sent — deliberately not polled; verify at the printer"
                else -> "inconclusive"
            },
        )
        println(
            "state      ${describeState(result.stateBefore)} -> " +
                if (sentWithoutPoll) {
                    "not polled (printer may still be processing)"
                } else {
                    describeState(result.stateAfter)
                },
        )
        if (useControl) {
            println(
                "echo       " + when (val e = result.echo) {
                    null -> "none — the command reached no parser"
                    "" -> "parsed, empty answer"
                    else -> "'$e'"
                },
            )
        }
        result.error?.let { println("error      $it") }

        if (result.accepted) {
            println("\nThat is the operation running. Note the parameter bytes that did it:")
            println("  ${parameters.joinToString(" ") { "%02X".format(it) }.ifEmpty { "(none)" }}")
        } else if (sentWithoutPoll) {
            println("\nNo more USB traffic will be sent. Let the printer finish and return to idle.")
            println("The printed page or physical cleaning activity is the acceptance signal.")

            // The question a host-driven maintenance run has to ask itself, because the printer
            // will not ask it. Its panel prompts belong to its panel menus; a command sent down the
            // data stream gets the operation and none of the interaction. escputil is the same —
            // it prints the pattern and then asks on its own stdout.
            if (operation == Maintenance.Operation.NOZZLE_CHECK) {
                println()
                println("Now read the page. Each colour block should be a continuous grid with no")
                println("gaps or missing lines. Broken or absent segments mean a blocked nozzle.")
                println()
                println("  gaps       ./gradlew maintenanceProbe --args=\"head_cleaning --live\"")
                println("  no gaps    nothing to do — a cleaning would only fill the pad")
                println()
                println("The printer will not prompt for this itself. Its panel asks because the")
                println("panel started the job; a host-started job leaves the asking to the host.")
            }
        } else if (result.inconclusive) {
            println("\nWatch the printer — it is the tiebreaker, and nothing on this stream answers.")
        }
    }

    /**
     * Head alignment, in the three steps the operation actually has — because the middle one needs a
     * human to have looked at a sheet of paper, and the last one is not reversible.
     *
     * The steps are separate invocations rather than one interactive prompt on purpose. A prompt
     * read through Gradle's stdin would be the least reliable part of the whole feature, and
     * splitting them means the irreversible step is something you type deliberately.
     */
    private fun alignment(args: Array<String>, live: Boolean) {
        val save = args.any { it == "--save" }
        val picks = args.firstOrNull { it.startsWith("--choose=") }
            ?.removePrefix("--choose=")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }

        val stream = when {
            save -> Alignment.save()
            picks != null -> {
                val byPass = Alignment.PASSES.zip(picks).toMap()
                Alignment.problemWith(byPass)?.let {
                    println("REFUSED    $it")
                    println("\nGive one pair number per pass, e.g. --choose=8,8,8,8")
                    return
                }
                Alignment.choices(byPass)
            }
            else -> Alignment.patterns()
        }

        section(
            when {
                save -> "Alignment: save  ${if (live) "[LIVE — PERMANENT]" else "[preview]"}"
                picks != null -> "Alignment: choices ${picks.joinToString(",")}  ${if (live) "[LIVE]" else "[preview]"}"
                else -> "Alignment: print patterns  ${if (live) "[LIVE — spends a sheet]" else "[preview]"}"
            },
        )

        if (save) println("WARNING    ${Alignment.SAVE_WARNING}")
        println("would send ${hex(stream)}".takeIf { !live } ?: "sending    ${hex(stream)}")

        if (!live) {
            println("\nNothing was sent. The three steps, in order:")
            println("  1. ./gradlew maintenanceProbe --args=\"align --live\"")
            println("     Prints the four patterns. Costs a sheet; changes nothing.")
            println("  2. ./gradlew maintenanceProbe --args=\"align --choose=8,8,8,8 --live\"")
            println("     One pair number per pass, read off that sheet. Reversible: a power cycle")
            println("     discards it.")
            println("  3. ./gradlew maintenanceProbe --args=\"align --save --live\"")
            println("     Makes it permanent. No undo, no backup.")
            return
        }

        val device = PrinterDiscovery.scan().printers.firstOrNull { it.link is Link.Usb }
        if (device == null) {
            println("No Epson on USB — alignment is print data, so it needs the USB link.")
            return
        }

        val sent = when (val opened = PrinterTransports.open(device)) {
            is PrinterTransports.OpenResult.Ok -> opened.transport.use { it.send(stream) }
            is PrinterTransports.OpenResult.Failed -> {
                println("open       FAILED — ${opened.detail}")
                return
            }
        }

        println("result     ${if (sent) "written to the printer" else "TRANSPORT FAILURE"}")
        if (!sent) return

        println(
            when {
                save -> "\nSaved. The alignment now in the printer is the one you chose."
                picks != null ->
                    "\nApplied, but not saved. Print the patterns again to check them; a\n" +
                        "power cycle discards this if it looks worse."
                else ->
                    "\nRead the sheet: in each of the four patterns, pick the pair whose lines\n" +
                        "meet most cleanly, then pass those four numbers to --choose."
            },
        )
    }

    private fun report(status: Status.Report?) {
        if (status == null) {
            println("status     no @BDC ST2 block came back")
            return
        }

        println("state      ${describeState(status.state)}")
        status.errorCode?.let { println("error code 0x%02X".format(it)) }
        status.inkLevels.takeIf { it.isNotEmpty() }?.let { levels ->
            println("ink        " + levels.joinToString(", ") { "${it.colour} ${it.percent}%" })
        }
    }

    private fun describeState(state: Int?) = when (state) {
        null -> "unknown"
        Status.STATE_IDLE -> "idle (0x04)"
        Status.STATE_CLEANING -> "cleaning (0x07)"
        Status.STATE_SELF_TEST -> "self-test (0x01)"
        Status.STATE_BUSY -> "busy (0x02)"
        else -> "0x%02X".format(state)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun section(title: String) {
        println("\n===== $title =====")
    }
}
