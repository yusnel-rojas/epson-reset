@file:OptIn(ExperimentalCoroutinesApi::class)

package nl.redlabs.epsonreset

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.ui.ResetViewModel
import nl.redlabs.epsonreset.usb.UsbPrinterScanner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions that stand between a user and an EEPROM write. */

private val testModel = PrinterModel(
    name = "TEST-1",
    readKey = 1,
    writeKey = "Zvubnpsj",
    padGroups = listOf(PadGroup("Waste", "main", listOf(58, 59), listOf(0, 0))),
)

/** A printer that can be told to misbehave in the specific ways the gates exist for. */
private class ScriptedPrinter(
    private val silent: Set<Int> = emptySet(),
    /** Answers every factory read with the refusal a locked-down firmware sends. */
    private val refuseReads: Boolean = false,
    private val ackWrites: Boolean = true,
    private val commitWrites: Boolean = true,
    private val serial: String? = null,
    /** Value for status field 0x01. Null omits the field, as a printer that reports none does. */
    private val state: Int? = null,
    /** Value for error field 0x02, which a real printer only sends when it has something to say. */
    private val errorCode: Int? = null,
    memory: Map<Int, Int> = emptyMap(),
    private val defaultValue: Int = 0x7F,
) : Transport {

    private val memory = memory.toMutableMap()
    val writes = mutableListOf<Pair<Int, Int>>()

    private var last: ByteArray = ByteArray(0)

    /** Anything at all that reached this printer, which a dry run must leave at zero. */
    var packets = 0
        private set

    override fun send(packet: ByteArray): Boolean {
        last = packet
        packets++
        Executor.writePacketTarget(packet)?.let { (address, value) ->
            writes += address to value
            if (commitWrites) memory[address] = value
        }
        return true
    }

    override fun drain(): ByteArray {
        Executor.readPacketAddress(last)?.let { address ->
            if (refuseReads) return "||:41:NA;".toByteArray(Charsets.ISO_8859_1)
            if (address in silent) return ByteArray(0)
            val value = memory[address] ?: defaultValue
            return "@BDC PS\r\nEE:%04X%02X;".format(address, value).toByteArray(Charsets.ISO_8859_1)
        }

        if (isStatusRequest(last)) return statusBlock()

        return when {
            !Executor.isWritePacket(last) -> "||status;".toByteArray(Charsets.ISO_8859_1)
            ackWrites -> "||:42:OK;".toByteArray(Charsets.ISO_8859_1)
            else -> "||:42:NG;".toByteArray(Charsets.ISO_8859_1)
        }
    }

    /** `st` sits where `||` does on a factory command — see SequenceGenerator.controlCommand. */
    private fun isStatusRequest(packet: ByteArray) =
        packet.size >= 8 && packet[6] == 's'.code.toByte() && packet[7] == 't'.code.toByte()

    /** An `@BDC ST2` block carrying whichever of state (0x01), error (0x02) and serial (0x40) is set. */
    private fun statusBlock(): ByteArray {
        val payload =
            (state?.let { byteArrayOf(0x01, 1, it.toByte()) } ?: ByteArray(0)) +
                (errorCode?.let { byteArrayOf(0x02, 1, it.toByte()) } ?: ByteArray(0)) +
                (
                    serial?.let {
                        byteArrayOf(0x40, it.length.toByte()) + it.toByteArray(Charsets.ISO_8859_1)
                    } ?: ByteArray(0)
                    )

        if (payload.isEmpty()) return ByteArray(0)

        return "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf((payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()) +
            payload
    }
}

/** A second entry to switch to. Its write key is another printer's — that is the whole point. */
private val otherModel = PrinterModel(
    name = "OTHER-9",
    readKey = 99,
    writeKey = "Qwertyui",
    padGroups = listOf(PadGroup("Waste", "main", listOf(20, 21), listOf(0, 0))),
)

private fun printer(
    serial: String? = null,
    link: Link = Link.Usb(1, 4, 1, 0x81.toByte(), 0x02, true),
    product: String? = "EPSON TEST-1",
) = MatchedPrinter(
    device = DetectedPrinter(link = link, product = product, serial = serial),
    model = testModel,
    confidence = MatchedPrinter.Confidence.EXACT,
)

private fun discovery(vararg printers: MatchedPrinter) = PrinterDiscovery.Result(
    printers = printers.map { it.device },
    usb = UsbPrinterScanner.ScanResult.Ok(printers.map { it.device }),
    network = PrinterDiscovery.NetworkOutcome.Ok(discovered = 0, saved = 0),
)

private fun TestScope.viewModel(
    transport: Transport = ScriptedPrinter(),
    discover: () -> PrinterDiscovery.Result = { discovery() },
    backupDir: File = createTempDirectory("vm-test").toFile(),
    openFailure: PrinterTransports.OpenResult.Failed? = null,
) = ResetViewModel(
    scope = this,
    io = UnconfinedTestDispatcher(testScheduler),
    transports = { openFailure ?: PrinterTransports.OpenResult.Ok(transport) },
    discover = discover,
    backupDir = { backupDir },
)

private val ResetViewModel.lastLine: String get() = log.last().text
private fun ResetViewModel.said(fragment: String) = log.any { it.text.contains(fragment) }

class ViewModelBackupGateTest {

    /**
     * The load-bearing one. An address that did not answer has no byte to put back, so the run must
     * stop *before* the first write rather than proceed with a hole in the backup.
     */
    @Test
    fun `a live run refuses when an address it would write did not answer`() = runTest {
        val printer = ScriptedPrinter(silent = setOf(59))
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty(), "nothing may be written: ${printer.writes}")
        assertTrue(vm.said("Stopped before writing anything"), vm.lastLine)
        assertNull(vm.lastBackup)
    }

    /** Same rule, different cause: a backup that cannot be stored is not a backup. */
    @Test
    fun `a live run refuses when the backup cannot be saved`() = runTest {
        val printer = ScriptedPrinter()
        // A plain file where the backup directory should be, so mkdirs and the write both fail.
        val blocked = File(createTempDirectory("vm-test").toFile(), "backups").apply { writeText("x") }
        val vm = viewModel(transport = printer, backupDir = blocked)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty(), "nothing may be written: ${printer.writes}")
        assertTrue(vm.said("could not be saved"), vm.lastLine)
    }

    @Test
    fun `a live run saves the pre-write bytes and then writes`() = runTest {
        val printer = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = printer, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertEquals(listOf(58 to 0, 59 to 0), printer.writes)

        val saved = assertNotNull(EepromBackup.load(assertNotNull(vm.lastBackup)))
        assertEquals(listOf(58, 59), saved.entries.map { it.address })
        assertEquals(listOf(0x19, 0x0F), saved.entries.map { it.value })

        val finished = assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(finished.result.success)
        assertTrue(vm.said("Verified by read-back"), vm.lastLine)
    }

    /** A dry run reaches the simulated EEPROM and never the real one — not even to read. */
    @Test
    fun `a dry run never touches the printer`() = runTest {
        val printer = ScriptedPrinter()
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = true
        vm.run()
        advanceUntilIdle()

        assertEquals(0, printer.packets, "a dry run must not send anything")
        assertNull(vm.lastBackup, "a dry run writes no backup file")

        val finished = assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(finished.wasDryRun)
        assertTrue(vm.said("would be backed up; no file written"), vm.lastLine)
    }

    /**
     * A printer can acknowledge every write and commit none of them, which is precisely what the
     * read-back exists to catch — the `:42:OK;` count alone would call this a success.
     */
    @Test
    fun `a write that was acknowledged but not committed is reported`() = runTest {
        val printer = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F), commitWrites = false)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(vm.said("not at the reset value"), vm.lastLine)
    }

    /** Deliberately *not* blocked here. */
    @Test
    fun `a network run is allowed to start, because the gate is downstream`() = runTest {
        val printer = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F))
        val vm = viewModel(transport = printer)

        vm.select(printer(link = Link.Network("192.168.2.39")))
        vm.dryRun = false

        assertTrue(vm.canRun)
        assertNull(vm.writeBlockedReason)
    }

    /** A refusal is reported as a refusal. */
    @Test
    fun `a printer that refuses the read says so, rather than blaming the backup`() = runTest {
        val printer = ScriptedPrinter(refuseReads = true)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty())
        assertTrue(vm.said("refused the counter read"), vm.lastLine)
        assertFalse(vm.said("could not be read back"), "the backup is not the story here")
    }

    /** Reading is unprivileged and carries no key, so the block does not extend to it. */
    @Test
    fun `reading over a network link is still allowed`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A")
        val vm = viewModel(transport = printer)

        vm.select(printer(link = Link.Network("192.168.1.50")))
        vm.dryRun = false

        vm.readCounters()
        advanceUntilIdle()

        assertTrue(printer.packets > 0)
        assertEquals(2, assertNotNull(vm.readReport).answered)
    }

    @Test
    fun `an open failure surfaces its remedy rather than a bare error`() = runTest {
        val vm = viewModel(
            openFailure = PrinterTransports.OpenResult.Failed(
                "Could not claim the printer interface.",
                "Remove the printer in System Settings.",
            ),
        )

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(vm.said("Remove the printer in System Settings"), vm.lastLine)
    }
}

/** The pre-flight check on what the printer says it is doing. */
class ViewModelPreflightStateTest {

    private val busy = 0x02
    private val idle = Status.STATE_IDLE

    @Test
    fun `a live run refuses while the printer reports a job in progress`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A", state = busy)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty(), "nothing may be written: ${printer.writes}")
        assertTrue(vm.said("reports it is busy"), vm.lastLine)
        assertNull(vm.lastBackup, "the refusal comes before the backup, so none was taken")
    }

    /** The error field only words the refusal; the state field is what decides it. */
    @Test
    fun `an error state names the error the printer reported`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A", state = 0x00, errorCode = 0x02)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty(), "nothing may be written: ${printer.writes}")
        assertTrue(vm.said("cover open"), vm.lastLine)
    }

    /**
     * The allow-list, from the other side. A state code nobody has documented must read as "not
     * idle" rather than as permission — the failure mode of guessing wrong here is a write.
     */
    @Test
    fun `an unrecognised state code blocks and is reported raw`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A", state = 0x7B)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertTrue(printer.writes.isEmpty(), "nothing may be written: ${printer.writes}")
        assertTrue(vm.said("state 0x7B"), vm.lastLine)
    }

    @Test
    fun `an idle printer is written to`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A", state = idle)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertEquals(listOf(58 to 0, 59 to 0), printer.writes)
    }

    /**
     * The behaviour this had before the check existed, kept: a printer that reports no state field
     * has said nothing to gate on, and inventing a refusal from silence would strand it.
     */
    @Test
    fun `a printer that reports no state at all is not blocked`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A")
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        assertEquals(listOf(58 to 0, 59 to 0), printer.writes)
    }

    /** A dry run writes nothing, so there is nothing for the printer's state to protect. */
    @Test
    fun `a dry run is not gated on printer state`() = runTest {
        val printer = ScriptedPrinter(serial = "UNIT-A", state = busy)
        val vm = viewModel(transport = printer)

        vm.select(printer())
        vm.dryRun = true
        vm.run()
        advanceUntilIdle()

        assertEquals(0, printer.packets, "a dry run must not reach the printer at all")
        assertFalse(vm.said("reports it is busy"), vm.lastLine)
    }
}

/**
 * The rule from `UnitSelector`, exercised through the window that used to have its own copy of it.
 */
class ViewModelRestoreGateTest {

    private fun backup(model: String = "TEST-1", serial: String? = null) = EepromBackup(
        model = model,
        createdAt = "20260727T004500Z",
        printerSerial = serial,
        entries = listOf(EepromBackup.Entry(58, 0x19, 0)),
    )

    private fun TestScope.ready(
        deviceSerial: String? = null,
        transport: Transport = ScriptedPrinter(),
    ): ResetViewModel = viewModel(transport = transport).apply {
        select(printer(serial = deviceSerial))
        dryRun = false
    }

    @Test
    fun `refuses a backup belonging to another model`() = runTest {
        val vm = ready()

        vm.restore(backup(model = "OTHER-9"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("Refusing to write one model's bytes to another"), vm.lastLine)
    }

    @Test
    fun `refuses when the printer reports a different serial`() = runTest {
        val vm = ready(deviceSerial = "UNIT-B")

        vm.restore(backup(serial = "UNIT-A"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("came from UNIT-A"), vm.lastLine)
    }

    @Test
    fun `writes when the serials agree`() = runTest {
        val vm = ready(deviceSerial = "UNIT-A")

        vm.restore(backup(serial = "UNIT-A"))
        advanceUntilIdle()

        assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertFalse(vm.said("Refusing"))
    }

    /**
     * Deliberately allowed: refusing would strand every backup taken before serials were recorded,
     * and with one candidate there is nothing to confuse it with. It is flagged, not silent.
     */
    @Test
    fun `allows an unconfirmable unit but says so`() = runTest {
        val vm = ready(deviceSerial = null)

        vm.restore(backup(serial = null))
        advanceUntilIdle()

        assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(vm.said("can't be tied to this exact unit"))
    }

    @Test
    fun `refuses when no printer is selected`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)
        vm.dryRun = false

        vm.restore(backup())

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("Select the printer to restore to first"), vm.lastLine)
    }

    /**
     * A printer whose descriptor carries no serial is still identifiable: the status block has one.
     */
    @Test
    fun `uses the serial the status block reported when the device has none`() = runTest {
        val vm = viewModel(transport = ScriptedPrinter(serial = "UNIT-A"))
        vm.select(printer(serial = null))
        vm.dryRun = false

        vm.readCounters()
        advanceUntilIdle()
        assertEquals("UNIT-A", assertNotNull(vm.status).serial)

        vm.restore(backup(serial = "UNIT-B"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("came from UNIT-B"), vm.lastLine)
        assertFalse(vm.said("can't be tied to this exact unit"))
    }

    @Test
    fun `a dry run restore is not gated`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)
        vm.dryRun = true

        vm.restore(backup(model = "OTHER-9"))
        advanceUntilIdle()

        assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(vm.said("A live run would refuse"))
    }
}

/** What stops one model's write key reaching another model's EEPROM. */
class ViewModelModelLockTest {

    @Test
    fun `a live run refuses when the selected model is not the one the printer named`() = runTest {
        val hardware = ScriptedPrinter()
        val vm = viewModel(transport = hardware)

        vm.select(printer())
        vm.selectModel(otherModel)
        vm.dryRun = false

        assertFalse(vm.canRun)
        vm.run()
        advanceUntilIdle()

        assertTrue(hardware.writes.isEmpty(), "nothing may be written: ${hardware.writes}")
        assertEquals(0, hardware.packets, "the printer must not even be opened")
        assertTrue(vm.said("identifies itself as TEST-1"), vm.lastLine)
        assertTrue(vm.said("OTHER-9 is selected"), vm.lastLine)
    }

    /** The same gate, reached through the restore path rather than the reset one. */
    @Test
    fun `a restore refuses when the selected model is not the one the printer named`() = runTest {
        val hardware = ScriptedPrinter()
        val vm = viewModel(transport = hardware)

        vm.select(printer(serial = "UNIT-A"))
        vm.selectModel(otherModel)
        vm.dryRun = false

        // Belongs to the model that is selected, so only the printer's own answer can refuse it.
        vm.restore(
            EepromBackup(
                model = "OTHER-9",
                createdAt = "20260727T004500Z",
                printerSerial = "UNIT-A",
                entries = listOf(EepromBackup.Entry(20, 0x19, 0)),
            ),
        )
        advanceUntilIdle()

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(hardware.writes.isEmpty())
        assertTrue(vm.said("identifies itself as TEST-1"), vm.lastLine)
    }

    /** Deliberately allowed. */
    @Test
    fun `a dry run with a contradicted model still runs`() = runTest {
        val hardware = ScriptedPrinter()
        val vm = viewModel(transport = hardware)

        vm.select(printer())
        vm.selectModel(otherModel)
        vm.dryRun = true

        assertTrue(vm.canRun)
        vm.run()
        advanceUntilIdle()

        assertEquals(0, hardware.packets, "a dry run must not send anything")
        val finished = assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(finished.result.success)
        assertTrue(vm.said("A live run would refuse"))
    }

    @Test
    fun `going back to the model the printer named lifts the block`() = runTest {
        val vm = viewModel()

        vm.select(printer())
        vm.selectModel(otherModel)
        vm.dryRun = false
        assertNotNull(vm.writeBlockedReason)

        vm.useIdentifiedModel()

        assertNull(vm.writeBlockedReason)
        assertTrue(vm.canRun)
        assertEquals("TEST-1", assertNotNull(vm.selectedModel).name)
    }

    /**
     * A LIKELY match is a guess the UI already asks the user to confirm. Pinning it would make the
     * guess unarguable and strand anyone it got wrong, which is the opposite of the point.
     */
    @Test
    fun `a likely match does not pin the model`() = runTest {
        val vm = viewModel()

        vm.select(printer().copy(confidence = MatchedPrinter.Confidence.LIKELY))
        vm.selectModel(otherModel)
        vm.dryRun = false

        assertNull(vm.identifiedModel)
        assertNull(vm.writeBlockedReason)
        assertTrue(vm.canRun)
    }

    /** The collapsed card asserts the selection is the printer's own answer. It must not lie. */
    @Test
    fun `the picker opens whenever the selection is not what the printer named`() = runTest {
        val vm = viewModel()

        vm.select(printer())
        assertFalse(vm.modelPickerExpanded)

        vm.selectModel(otherModel)
        assertTrue(vm.modelPickerExpanded)

        vm.useIdentifiedModel()
        assertFalse(vm.modelPickerExpanded)
    }

    @Test
    fun `a remembered model is put back when nothing else has claimed the selection`() = runTest {
        val vm = viewModel()

        vm.restoreModel(testModel)

        assertEquals("TEST-1", assertNotNull(vm.selectedModel).name)
        assertEquals("TEST-1", vm.query)
    }

    /**
     * The preference is a convenience from a previous session; the printer in front of the user is
     * evidence from this one.
     */
    @Test
    fun `a remembered model yields to the printer's own answer`() = runTest {
        val vm = viewModel()

        vm.select(printer())
        vm.restoreModel(otherModel)

        assertEquals("TEST-1", assertNotNull(vm.selectedModel).name)
        assertNull(vm.modelMismatch)
        assertFalse(vm.modelPickerExpanded)
    }

    @Test
    fun `a remembered model arriving late does not displace a chosen one`() = runTest {
        val vm = viewModel()

        vm.selectModel(otherModel)
        vm.restoreModel(testModel)

        assertEquals(otherModel.name, assertNotNull(vm.selectedModel).name)
    }

    /** Restored without a printer, so nothing is identified and the picker stays open. */
    @Test
    fun `a remembered model does not let the collapsed card assert an identity`() = runTest {
        val vm = viewModel()

        vm.restoreModel(testModel)

        assertNull(vm.identifiedModel)
        assertTrue(vm.modelPickerExpanded)
    }

    @Test
    fun `an identity survives a rescan`() = runTest {
        val found = printer(product = "EPSON L3150 Series")
        val vm = viewModel(discover = { discovery(found) })

        vm.start()
        advanceUntilIdle()
        assertEquals("L3150", assertNotNull(vm.identifiedModel).name)

        vm.scan()
        advanceUntilIdle()

        assertEquals("L3150", assertNotNull(vm.identifiedModel).name)
    }

    /** Nothing selected means nothing has named itself — the pin must not outlive its printer. */
    @Test
    fun `an identity does not outlive the printer it came from`() = runTest {
        val a = printer(product = "EPSON L3150 Series")
        val b = printer(product = "EPSON XP-245 Series", link = Link.Network("192.168.1.50"))
        val c = printer(product = "EPSON L3110 Series", link = Link.Network("192.168.1.51"))
        var visible = listOf(a, b, c)
        val vm = viewModel(discover = { discovery(*visible.toTypedArray()) })

        vm.start()
        advanceUntilIdle()
        vm.select(vm.devices.first { it.device.link == a.device.link })
        assertEquals("L3150", assertNotNull(vm.identifiedModel).name)

        // Two left, so nothing is auto-selected in its place.
        visible = listOf(b, c)
        vm.scan()
        advanceUntilIdle()

        assertNull(vm.selectedDevice)
        assertNull(vm.identifiedModel)
    }
}

/** Snapshots taken because someone asked, rather than because a reset was about to write. */
class ViewModelSnapshotTest {

    @Test
    fun `saves what the last live read returned, and never writes to do it`() = runTest {
        val hardware = ScriptedPrinter(serial = "UNIT-A", memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        assertTrue(vm.canSaveSnapshot, vm.snapshotBlockedReason ?: "")
        vm.saveSnapshot()
        advanceUntilIdle()

        val file = assertNotNull(dir.listFiles()?.singleOrNull { it.name.endsWith(".json") })
        val saved = assertNotNull(EepromBackup.load(file))
        assertEquals(listOf(58, 59), saved.entries.map { it.address })
        assertEquals(listOf(0x19, 0x0F), saved.entries.map { it.value })
        assertEquals("UNIT-A", saved.printerSerial)
        assertTrue(hardware.writes.isEmpty(), "a snapshot writes a file, not EEPROM")
        assertEquals(file, assertNotNull(vm.selectedSnapshot).file)
    }

    /** The load-bearing one for this feature. */
    @Test
    fun `values from a dry run cannot be saved`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(backupDir = dir)

        vm.select(printer())
        vm.dryRun = true
        vm.readCounters()
        advanceUntilIdle()

        assertNotNull(vm.readReport)
        assertFalse(vm.canSaveSnapshot)

        vm.saveSnapshot()
        advanceUntilIdle()

        assertTrue(dir.listFiles().orEmpty().isEmpty(), "nothing may be saved from a simulated read")
        assertTrue(vm.said("simulated EEPROM"), vm.lastLine)
    }

    /** Same rule the live run refuses on: an address with no byte has nothing to put back. */
    @Test
    fun `refuses when an address a reset would write did not answer`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = ScriptedPrinter(silent = setOf(59)), backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        vm.saveSnapshot()
        advanceUntilIdle()

        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertTrue(vm.said("did not answer the read"), vm.lastLine)
    }

    /**
     * The dry read. A saved snapshot is bytes on disk, so opening one is a read that cannot fail
     * and cannot involve hardware — which is the point of keeping it.
     */
    @Test
    fun `selecting a snapshot reads it back without touching the printer`() = runTest {
        val hardware = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()
        vm.saveSnapshot()
        advanceUntilIdle()

        val quiet = hardware.packets
        vm.selectSnapshot(vm.snapshots.single())

        val report = assertNotNull(vm.snapshotReport)
        assertEquals(listOf(58, 59), report.readings.map { it.address })
        assertEquals(listOf(0x19, 0x0F), report.readings.map { it.value })
        assertEquals(2, report.answered)
        assertEquals(quiet, hardware.packets, "reading a snapshot must send nothing")
    }

    /** A file that will not parse is listed rather than hidden — it is a recovery point that isn't. */
    @Test
    fun `an unreadable file is listed, without contents`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        File(dir, "TEST-1-20260727T004500Z.json").writeText("{ not a backup")
        val vm = viewModel(backupDir = dir)

        vm.refreshSnapshots()
        advanceUntilIdle()

        assertNull(vm.snapshots.single().backup)
    }

    /** The restore gate reaches this screen too. */
    @Test
    fun `a snapshot cannot be restored while another model is selected`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        vm.selectSnapshot(
            ResetViewModel.SavedSnapshot(
                file = File(dir, "OTHER-9.json"),
                backup = EepromBackup(
                    model = "OTHER-9",
                    createdAt = "20260727T004500Z",
                    printerSerial = null,
                    entries = listOf(EepromBackup.Entry(20, 0x19, 0)),
                ),
            ),
        )

        assertContains(assertNotNull(vm.snapshotRestoreBlockedReason), "OTHER-9")

        vm.restoreSelectedSnapshot()
        advanceUntilIdle()

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("Refusing to write one model's bytes to another"), vm.lastLine)
    }
}

/** Comparing a saved snapshot against a second sample. */
class ViewModelComparisonTest {

    private fun saved(createdAt: String, value: Int, serial: String? = null) = EepromBackup(
        model = "TEST-1",
        createdAt = createdAt,
        printerSerial = serial,
        entries = listOf(
            EepromBackup.Entry(58, value, 0),
            EepromBackup.Entry(59, 0x0F, 0),
        ),
    )

    /**
     * The tab's one guarantee. Opening a file is a read that cannot fail because it involves no
     * hardware, and a comparison that reached for the printer on its own would quietly revoke that.
     */
    @Test
    fun `selecting a snapshot starts no comparison and sends nothing`() = runTest {
        val hardware = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()
        vm.saveSnapshot()
        advanceUntilIdle()

        val quiet = hardware.packets
        vm.selectSnapshot(vm.snapshots.single())

        assertEquals(ResetViewModel.CompareTarget.None, vm.compareTarget)
        assertNull(vm.comparison)
        assertEquals(quiet, hardware.packets, "opening a snapshot must send nothing")
    }

    /**
     * The reason this outlives the run: a snapshot taken before a reset still proves the reset
     * landed, after the in-memory "before" has been thrown away by the next read.
     */
    @Test
    fun `a snapshot taken before a reset verifies it afterwards`() = runTest {
        val hardware = ScriptedPrinter(memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()
        vm.saveSnapshot()
        advanceUntilIdle()
        val taken = assertNotNull(vm.selectedSnapshot)

        vm.run()
        advanceUntilIdle()

        // What the run itself compared against is gone the moment the counters are read again.
        vm.readCounters()
        advanceUntilIdle()
        assertNull(vm.beforeReport)

        vm.selectSnapshot(taken)
        vm.compareWithCurrentReading()

        val result = assertNotNull(vm.comparison)
        assertTrue(result.afterIsAtResetValue, result.summary)
        assertEquals(0x19, result.bytes.first { it.address == 58 }.before)
        assertEquals(0x00, result.bytes.first { it.address == 58 }.after)
    }

    /** The same rule that stops a dry run being saved or calibrated from. */
    @Test
    fun `a simulated reading cannot be compared against`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        saved("20260727T004500Z", 0x19).save(dir)
        val vm = viewModel(backupDir = dir)

        vm.select(printer())
        vm.dryRun = true
        vm.readCounters()
        advanceUntilIdle()
        vm.refreshSnapshots()
        advanceUntilIdle()

        vm.selectSnapshot(vm.snapshots.single())
        vm.compareWithCurrentReading()

        assertEquals(ResetViewModel.CompareTarget.None, vm.compareTarget)
        assertNull(vm.comparison)
        assertTrue(vm.said("simulated EEPROM"), vm.lastLine)
    }

    /** And the read that would produce one is refused for the same reason, before it is taken. */
    @Test
    fun `reading for a comparison is blocked while the run is a dry one`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        saved("20260727T004500Z", 0x19).save(dir)
        val vm = viewModel(backupDir = dir)

        vm.select(printer())
        vm.dryRun = true
        vm.refreshSnapshots()
        advanceUntilIdle()
        vm.selectSnapshot(vm.snapshots.single())

        assertContains(assertNotNull(vm.compareReadBlockedReason), "Dry run")
        assertFalse(vm.canReadForComparison)
    }

    /**
     * Ordered by when each sample was taken, not by which one was clicked. Otherwise the sign of
     * every delta would depend on the order the user happened to open two files in.
     */
    @Test
    fun `the older snapshot is the before side whichever one is selected`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val older = saved("20260701T090000Z", 0x10).save(dir)
        val newer = saved("20260727T004500Z", 0x20).save(dir)
        val vm = viewModel(backupDir = dir)

        vm.refreshSnapshots()
        advanceUntilIdle()

        // Selected newer, compared against older: the older one is still "before".
        vm.selectSnapshot(vm.snapshots.first { it.file == newer })
        vm.compareWithSnapshot(older)

        val forward = assertNotNull(vm.comparison)
        assertEquals(older.name, forward.before.label)
        assertEquals(0x10, forward.bytes.first { it.address == 58 }.before)
        assertEquals(0x20, forward.bytes.first { it.address == 58 }.after)

        // And the other way round, which must produce exactly the same comparison.
        vm.selectSnapshot(vm.snapshots.first { it.file == older })
        vm.compareWithSnapshot(newer)

        val reverse = assertNotNull(vm.comparison)
        assertEquals(older.name, reverse.before.label)
        assertEquals(0x20, reverse.bytes.first { it.address == 58 }.after)
    }

    /** A comparison is against one specific pair, so changing either end ends it. */
    @Test
    fun `changing the selection clears the comparison`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val older = saved("20260701T090000Z", 0x10).save(dir)
        saved("20260727T004500Z", 0x20).save(dir)
        val vm = viewModel(backupDir = dir)

        vm.refreshSnapshots()
        advanceUntilIdle()
        vm.selectSnapshot(vm.snapshots.first())
        vm.compareWithSnapshot(older)
        assertNotNull(vm.comparison)

        vm.selectSnapshot(vm.snapshots.last())

        assertEquals(ResetViewModel.CompareTarget.None, vm.compareTarget)
        assertNull(vm.comparison)
    }

    /** Only snapshots of the same model are offered — the rest are not comparable at all. */
    @Test
    fun `another model's snapshot is not offered as a candidate`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        saved("20260701T090000Z", 0x10).save(dir)
        EepromBackup(
            model = "OTHER-9",
            createdAt = "20260702T090000Z",
            printerSerial = null,
            entries = listOf(EepromBackup.Entry(20, 0x19, 0)),
        ).save(dir)
        val vm = viewModel(backupDir = dir)

        vm.refreshSnapshots()
        advanceUntilIdle()
        vm.selectSnapshot(vm.snapshots.first { it.backup?.model == "TEST-1" })

        assertTrue(vm.compareCandidates.isEmpty(), "${vm.compareCandidates.map { it.file.name }}")
    }

    /**
     * The Reset tab hands off rather than growing a second comparison view, so what it offers has
     * to be a real pairing — a model with something saved, and a reading worth comparing.
     */
    @Test
    fun `the reset tab offers a comparison only when one is possible`() = runTest {
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = ScriptedPrinter(memory = mapOf(58 to 0x19)), backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        // Nothing saved yet.
        assertFalse(vm.canOfferComparison)

        vm.saveSnapshot()
        advanceUntilIdle()

        assertTrue(vm.canOfferComparison)

        vm.compareCurrentReadingWithNewestSnapshot()
        assertEquals(ResetViewModel.Tab.SNAPSHOTS, vm.tab)
        assertEquals(ResetViewModel.CompareTarget.CurrentReading, vm.compareTarget)
        assertNotNull(vm.comparison)
    }
}

class ViewModelScanTest {

    /**
     * The whole chain: what discovery returned, run through the matcher against the real database,
     * selected, and the model adopted — which is what gives the reset path a write key.
     */
    @Test
    fun `matches a lone printer against the database and adopts its model`() = runTest {
        val found = printer(serial = "UNIT-A", product = "EPSON L3150 Series")
        val vm = viewModel(discover = { discovery(found) })

        vm.start()
        advanceUntilIdle()

        assertEquals(1, vm.devices.size)
        assertEquals(found.device.id, assertNotNull(vm.selectedDevice).device.id)
        assertEquals(MatchedPrinter.Confidence.EXACT, vm.devices.single().confidence)
        assertEquals("L3150", assertNotNull(vm.selectedModel).name)
    }

    @Test
    fun `does not pick for the user when there is more than one`() = runTest {
        val usb = printer(serial = "UNIT-A")
        val net = printer(serial = "UNIT-B", link = Link.Network("192.168.1.50"))
        val vm = viewModel(discover = { discovery(usb, net) })

        vm.start()
        advanceUntilIdle()

        assertEquals(2, vm.devices.size)
        assertNull(vm.selectedDevice)
        assertTrue(vm.said("1 on USB, 1 on the network"), vm.lastLine)
    }

    /** A rescan returns new objects for the same printers; the selection is by identity, not object. */
    @Test
    fun `keeps the selection across a rescan`() = runTest {
        val usb = printer(serial = "UNIT-A")
        val net = printer(serial = "UNIT-B", link = Link.Network("192.168.1.50"))
        val vm = viewModel(discover = { discovery(usb, net) })

        vm.start()
        advanceUntilIdle()
        vm.select(vm.devices.first { it.device.isNetwork })
        val chosen = assertNotNull(vm.selectedDevice).device.id

        vm.scan()
        advanceUntilIdle()

        assertEquals(chosen, assertNotNull(vm.selectedDevice).device.id)
    }

    /** A printer that has gone away must not stay selected as the target of a write. */
    @Test
    fun `does not keep a selection whose printer vanished`() = runTest {
        val usb = printer(serial = "UNIT-A")
        val net = printer(serial = "UNIT-B", link = Link.Network("192.168.1.50"))
        var visible = listOf(usb, net)
        val vm = viewModel(discover = { discovery(*visible.toTypedArray()) })

        vm.start()
        advanceUntilIdle()
        vm.select(vm.devices.first { it.device.isNetwork })

        visible = listOf(usb)
        vm.scan()
        advanceUntilIdle()

        assertEquals(usb.device.id, assertNotNull(vm.selectedDevice).device.id)
    }

    /**
     * Once there are two sources, a missing libusb is no longer the same thing as no printers — it
     * must not replace a list that has something on it.
     */
    @Test
    fun `a failed source is a footnote when the other one found something`() = runTest {
        val net = printer(serial = "UNIT-B", link = Link.Network("192.168.1.50"))
        val vm = viewModel(
            discover = {
                PrinterDiscovery.Result(
                    printers = listOf(net.device),
                    usb = UsbPrinterScanner.ScanResult.LibraryMissing("not found", "brew install libusb"),
                    network = PrinterDiscovery.NetworkOutcome.Ok(discovered = 1, saved = 0),
                )
            },
        )

        vm.start()
        advanceUntilIdle()

        assertIs<ResetViewModel.ScanState.Done>(vm.scanState)
        assertEquals(1, vm.devices.size)
        assertContains(assertNotNull(vm.usbNote), "libusb")
    }

    @Test
    fun `reports the reason when nothing was found at all`() = runTest {
        val vm = viewModel(
            discover = {
                PrinterDiscovery.Result(
                    printers = emptyList(),
                    usb = UsbPrinterScanner.ScanResult.LibraryMissing("not found", "brew install libusb"),
                    network = PrinterDiscovery.NetworkOutcome.Ok(discovered = 0, saved = 0),
                )
            },
        )

        vm.start()
        advanceUntilIdle()

        assertIs<ResetViewModel.ScanState.LibraryMissing>(vm.scanState)
        assertTrue(vm.devices.isEmpty())
    }
}

/** The gate in front of a calibration. */
class ViewModelCalibrationTest {

    /** Shaped like the real ET-2820 so the bundled counter layout resolves for it. */
    private val et2820 = PrinterModel(
        name = "ET-2820",
        readKey = 1,
        writeKey = "Zvubnpsj",
        padGroups = listOf(PadGroup("Waste", "main", listOf(48, 49), listOf(0, 0))),
    )

    /** The bytes a real ET-2820 returned: 0x0F19, the 3865 the committed calibration was measured from. */
    private fun scripted() = ScriptedPrinter(memory = mapOf(48 to 0x19, 49 to 0x0F))

    /** A read, then the form opened on it — which is the only way a user reaches this state. */
    private fun TestScope.readied(dryRun: Boolean): ResetViewModel {
        val vm = viewModel(transport = scripted())
        vm.counterSpecs = CounterSpecs.loadBundled()
        vm.database = PrinterDatabase.loadBundled()
        vm.select(printer().copy(model = et2820))
        vm.dryRun = dryRun
        vm.readCounters()
        advanceUntilIdle()
        vm.openCalibration()
        return vm
    }

    @Test
    fun `a dry run cannot be calibrated from`() = runTest {
        val vm = readied(dryRun = true)

        assertContains(assertNotNull(vm.calibrationBlockedReason), "simulated EEPROM")

        vm.setCalibrationServiceRequired(listOf(48, 49), true)
        assertFalse(vm.canSubmitCalibration)

        vm.applyCalibrationToSession()
        assertFalse(vm.calibrationApplied, "invented bytes must not become a maximum")
    }

    private fun ResetViewModel.percentShown(addresses: List<Int>): Double? =
        decodedCounters.first { it.spec.addresses == addresses }.percent

    /**
     * The whole loop on one model, against the reading the committed calibration came from: a live
     * read, a reference percentage, and the same 6346 the file already holds.
     */
    @Test
    fun `a live reading re-derives the maximum this model was calibrated with`() = runTest {
        val vm = readied(dryRun = false)

        assertNull(vm.calibrationBlockedReason)
        vm.setCalibrationPercent(listOf(48, 49), "60.90")

        val measured = vm.calibrationMeasurements.single()
        assertEquals(3865L, measured.value)
        assertEquals(6346, measured.max)
    }

    /**
     * Applying reaches the counters on screen through the layouts themselves, so there is no second
     * path by which a percentage can appear.
     */
    @Test
    fun `applying a measurement moves the percentage on screen`() = runTest {
        val vm = readied(dryRun = false)
        assertEquals(60.90, assertNotNull(vm.percentShown(listOf(48, 49))), 0.005)

        vm.setCalibrationServiceRequired(listOf(48, 49), true)
        vm.applyCalibrationToSession()

        assertTrue(vm.calibrationApplied)
        assertEquals(100.0, assertNotNull(vm.percentShown(listOf(48, 49))), 0.005)
    }

    /** A fresh reading invalidates whatever was typed against the previous one. */
    @Test
    fun `re-reading clears the form`() = runTest {
        val vm = readied(dryRun = false)
        vm.setCalibrationPercent(listOf(48, 49), "60.90")
        assertTrue(vm.canSubmitCalibration)

        vm.readCounters()
        advanceUntilIdle()

        assertFalse(vm.canSubmitCalibration)
        assertEquals("", vm.calibrationInput(listOf(48, 49)).percent)
    }

    @Test
    fun `the entry names the model on the form and carries the measured maximum`() = runTest {
        val vm = readied(dryRun = false)
        vm.setCalibrationServiceRequired(listOf(48, 49), true)

        val entry = vm.calibrationEntry()
        assertContains(entry, """"models": ["ET-2820"]""")
        assertContains(entry, """"max": 3865""")
        assertContains(entry, """"at": "service required"""")
    }

    /**
     * The exact SKU, not the family. 120 models share this layout, and a maximum filed under the
     * family cannot afterwards be told from one measured on any of them.
     */
    @Test
    fun `the form starts from what the printer named itself and can be corrected`() = runTest {
        val vm = readied(dryRun = false)

        // select() matched EXACTLY, so this printer named itself — the strongest identification.
        assertEquals("ET-2820", vm.calibrationModel)
        assertNull(vm.calibrationModelWarning)

        // Every model on this layout is on offer, its own series first.
        assertTrue(vm.calibrationLayoutSiblings.size > 100, "the ET-2820 layout is widely shared")
        assertContains(vm.calibrationModelCandidates, "ET-2825")

        vm.setCalibrationServiceRequired(listOf(48, 49), true)
        vm.calibrationModel = "ET-2825"
        assertContains(vm.calibrationEntry(), """"models": ["ET-2825"]""")
        assertContains(assertNotNull(vm.calibrationModelWarning), "names itself ET-2820")
    }

    @Test
    fun `a family name is refused as the thing being calibrated`() = runTest {
        val vm = readied(dryRun = false)
        vm.calibrationModel = "ET-2820 Series"

        assertContains(assertNotNull(vm.calibrationModelWarning), "names a family, not a unit")
    }

    @Test
    fun `nothing can be filed without a model name`() = runTest {
        val vm = readied(dryRun = false)
        vm.setCalibrationServiceRequired(listOf(48, 49), true)
        assertTrue(vm.canSubmitCalibration)

        vm.calibrationModel = "  "
        assertFalse(vm.canSubmitCalibration)
    }
}
