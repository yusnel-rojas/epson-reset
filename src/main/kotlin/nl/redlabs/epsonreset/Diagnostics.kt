package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.update.AppVersion
import nl.redlabs.epsonreset.usb.LibUsb
import nl.redlabs.epsonreset.usb.UsbPrinterScanner

/** Headless self-check: database, libusb, connected devices, and a dry run. */
object Diagnostics {

    @JvmStatic
    fun main(args: Array<String>) {
        section("Environment")
        println("app        ${AppVersion.display}")
        println("os.name    ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        println("os.arch    ${System.getProperty("os.arch")}")
        println("java       ${System.getProperty("java.version")}")

        section("Database")
        val db = runCatching { PrinterDatabase.load() }.getOrElse {
            println("FAILED to load: ${it.message}")
            return
        }
        println("models     ${db.size}")
        println("source     ${db.source.name.lowercase()}")
        println("cache      ${AppPaths.database}")
        val resettable = db.models.count { it.hasResettableCounters }
        val platenOnly = db.models.count { it.isPlatenOnly }
        println("resettable $resettable")
        println("platenOnly $platenOnly")

        section("Counter layouts")
        val specs = runCatching { CounterSpecs.load() }.getOrNull()
        if (specs == null) {
            println("FAILED to load counters.json")
        } else {
            println("models     ${specs.modelCount}")
            println(
                "overlay    ${if (specs.overlayLoaded) "APPLIED from ${AppPaths.counterOverlay}" else "none (${AppPaths.counterOverlay})"}",
            )
            specs.overlayError?.let { println("overlayErr $it") }
        }

        section("libusb")
        if (LibUsb.instance == null) {
            println("NOT AVAILABLE — ${LibUsb.loadError}")
            println("install    ${UsbPrinterScanner.installHint()}")
        } else {
            println("loaded     ok")
        }

        section("Printer scan")
        val discovery = PrinterDiscovery.scan()
        var detected: DetectedPrinter? = discovery.printers.firstOrNull()

        when (val result = discovery.usb) {
            is UsbPrinterScanner.ScanResult.Ok ->
                println("usb        ${result.printers.size} found")
            is UsbPrinterScanner.ScanResult.LibraryMissing ->
                println("usb        skipped — ${result.detail}")
            is UsbPrinterScanner.ScanResult.Failed ->
                println("usb        FAILED — ${result.message}")
        }

        when (val net = discovery.network) {
            is PrinterDiscovery.NetworkOutcome.Ok ->
                println("network    ${net.discovered} advertised, ${net.saved} saved (${AppPaths.networkPrinters})")
            is PrinterDiscovery.NetworkOutcome.Unavailable ->
                println("network    unavailable — ${net.detail}")
            PrinterDiscovery.NetworkOutcome.Skipped ->
                println("network    skipped")
        }

        if (discovery.printers.isEmpty()) {
            println("\nNothing found on USB (vendor 0x04B8) or the network.")
        } else {
            println()
            for (matched in DeviceMatcher.matchAll(discovery.printers, db)) {
                val d = matched.device
                println("${d.displayName}  [${d.link.kind}] ${d.link.where}")
                d.pidHex?.let { println("   pid        $it") }
                println("   serial     ${d.serial ?: "—"}")
                (d.link as? Link.Usb)?.let { usb ->
                    println(
                        "   interface  ${usb.interfaceNumber} in=0x%02X out=0x%02X %s".format(
                            usb.endpointIn,
                            usb.endpointOut,
                            if (usb.isPrinterClass) "(printer class)" else "(vendor specific)",
                        ),
                    )
                }
                println("   match      ${matched.model?.name ?: "none"} (${matched.confidence})")
                d.accessNote?.let { println("   note       $it") }
            }
        }

        val live = args.any { it == "--live" }
        val modelName = args.firstOrNull { !it.startsWith("--") } ?: "L3150"

        val model = db[modelName]
        if (model == null) {
            section("Model")
            println("'$modelName' is not in the database.")
            return
        }

        if (live) {
            liveRead(model, detected)
            return
        }

        section("Dry run: $modelName  [SIMULATED — no printer involved]")

        val sequence = SequenceGenerator.generate(model)
        println("groups     ${model.padGroups.size}")
        model.padGroups.forEach {
            println(
                "   ${it.effectiveKind.name.lowercase().padEnd(8)} ${it.addresses.size} addresses  ${it.description}",
            )
        }
        println("packets    ${sequence.size}")
        println("writes     ${sequence.count { Executor.isWritePacket(it) }}")
        println("first write")
        println(Executor.hexDump(sequence.first { Executor.isWritePacket(it) }).prependIndent("   "))

        println("first read")
        println(
            Executor.hexDump(SequenceGenerator.readPacket(model.readKey, model.padGroups.first().addresses.first()))
                .prependIndent("   "),
        )

        val transport = FakeTransport()
        val before = CounterReader.readAll(transport, model)
        println("read       ${before.answered}/${before.total} answered  (simulated EEPROM, 0x7F fill)")

        val result = Executor.execute(
            transport = transport,
            sequence = sequence,
            options = Executor.Options(interPacketDelayMs = 0, retryDelayMs = 0),
        )
        println("result     ${if (result.success) "OK" else "FAILED — ${result.error}"}")
        println("write ACKs ${result.writesAcknowledged}/${result.writesTotal}")

        val after = CounterReader.readAll(transport, model)
        println("read-back  ${if (after.allAtResetValue) "all addresses at reset values" else "MISMATCH"}")
        after.readings.take(6).forEach {
            println(
                "   %4d  %s → %s".format(
                    it.address,
                    before.readings.first { b -> b.address == it.address }.hex,
                    it.hex,
                ),
            )
        }

        println(
            "\nThese values are simulated. To read the real printer: ./gradlew diagnose --args=\"$modelName --live\"",
        )
    }

    /**
     * Reads the real device. Read-only by construction — the read command carries no write key (see
     * SequenceGenerator.readPacket), so this cannot alter the EEPROM.
     */
    private fun liveRead(model: PrinterModel, device: DetectedPrinter?) {
        section("Live read: ${model.name}  [REAL HARDWARE — read-only]")

        if (device == null) {
            println("No printer detected — nothing to read.")
            return
        }

        println("target     ${device.displayName} on ${device.link.kind} at ${device.link.where}")

        // Over the network the connection proves much less than an enumerated USB device does, so
        // say what the printer answered before reporting counters read through it.
        if (device.link is Link.Network) {
            val test = ConnectionTest.run(device)
            println("probe      ${test.headline}")
            test.model?.let { println("   reports    $it") }
            test.advice?.let { println("   advice     $it") }
            if (!test.usable) return
        }

        when (val opened = PrinterTransports.open(device)) {
            is PrinterTransports.OpenResult.Failed -> {
                println("FAILED     ${opened.message}")
                opened.remedy?.let { println("remedy     $it") }
            }

            is PrinterTransports.OpenResult.Ok -> opened.transport.use { transport ->
                val specs = CounterSpecs.load()[model.name] ?: emptyList()
                val report = CounterReader.readAll(transport, model, specs)

                // The status block needs the channel that readAll already opened.
                CounterReader.readStatus(transport)?.let { status ->
                    println()
                    status.serial?.let { println("   serial     $it") }
                    if (status.inkLevels.isNotEmpty()) {
                        println("   ink levels:")
                        for (ink in status.inkLevels) {
                            println("   %-14s %3d%%%s".format(ink.colour, ink.percent, if (ink.isLow) "   LOW" else ""))
                        }
                    }
                }

                println("answered   ${report.answered}/${report.total}")
                report.error?.let { println("error      $it") }
                println()
                println("   addr   value   resets to   group")
                for (r in report.readings) {
                    println(
                        "   %4d   %-7s %-11s %s%s".format(
                            r.address,
                            r.hex,
                            "0x%02X".format(r.expectedAfterReset),
                            r.groupDescription,
                            r.error?.let { "  [$it]" } ?: "",
                        ),
                    )
                }

                if (specs.isNotEmpty()) {
                    println()
                    println("   counters (grouped, little-endian):")
                    for (c in CounterReader.decode(report.readings, specs)) {
                        println(
                            "   %-10s %-24s addr %s%s".format(
                                c.display,
                                c.spec.description,
                                c.spec.addresses.joinToString(","),
                                c.percent?.let { "  %.2f%% of max".format(it) } ?: "",
                            ),
                        )
                    }
                }

                println()
                when {
                    report.answered == 0 ->
                        println("The printer answered no reads. The read framing may be wrong for this model.")
                    report.allAtResetValue ->
                        println("Every address already holds its reset value — counters look already clear.")
                    else ->
                        println("Counters hold real data. Nothing was written.")
                }
            }
        }
    }

    private fun section(name: String) = println("\n── $name ${"─".repeat((60 - name.length).coerceAtLeast(0))}")
}
