@file:OptIn(ExperimentalCoroutinesApi::class)

package nl.redlabs.epsonreset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PadGroup
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.device.ModelChoices
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.history.CounterJournal
import nl.redlabs.epsonreset.net.SavedPrinters
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.DeviceId
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.Maintenance
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.ui.MaintenanceState
import nl.redlabs.epsonreset.ui.ResetViewModel
import nl.redlabs.epsonreset.ui.SnapshotState
import nl.redlabs.epsonreset.usb.UsbPrinterScanner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
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

/**
 * A printer that will only name its family, and a family whose two members share nothing that
 * matters — which is what makes the question worth putting to the user.
 */
private fun classPrinter(product: String = "EPSON TEST Series") = MatchedPrinter(
    device = DetectedPrinter(
        link = Link.Usb(1, 4, 1, 0x81.toByte(), 0x02, true),
        product = product,
        serial = "X4KP0219",
    ),
    model = testModel,
    confidence = MatchedPrinter.Confidence.CLASS_ONLY,
    candidates = listOf(testModel, otherModel),
)

private fun usbDevice(serial: String? = null, product: String? = "EPSON TEST Series") = DetectedPrinter(
    link = Link.Usb(1, 4, 1, 0x81.toByte(), 0x02, true),
    product = product,
    serial = serial,
)

/** A USB printer wearing the unit name its own network entry gave, which is what the join produces. */
private fun crossCheckedPrinter(product: String = "EPSON TEST Series") = MatchedPrinter(
    device = usbDevice(serial = "51574552303132333435", product = product).copy(
        crossCheck = DetectedPrinter.CrossCheck("OTHER-9", Link.Network("192.168.2.39")),
    ),
    model = otherModel,
    confidence = MatchedPrinter.Confidence.EXACT,
)

/** The two models above, as the database has to hold them for a remembered name to resolve. */
private val classDatabase = PrinterDatabase.parse(
    """
    {"TEST-1": {"rkey": 1, "wkey": "Zvubnpsj",
       "pad_groups": [{"kind": "main", "addresses": [58, 59], "reset": [0, 0]}]},
     "OTHER-9": {"rkey": 99, "wkey": "Qwertyui",
       "pad_groups": [{"kind": "main", "addresses": [20, 21], "reset": [0, 0]}]}}
    """.trimIndent(),
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
    io: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    discover: () -> PrinterDiscovery.Result = { discovery() },
    connectionTest: (DetectedPrinter, PrinterModel?) -> ConnectionTest.Result = ConnectionTest::run,
    loadSavedPrinters: () -> List<SavedPrinters.Saved> = { emptyList() },
    addSavedPrinter: (SavedPrinters.Saved) -> List<SavedPrinters.Saved> = { listOf(it) },
    removeSavedPrinter: (Link.Network) -> List<SavedPrinters.Saved> = { emptyList() },
    backupDir: File = createTempDirectory("vm-test").toFile(),
    openFailure: PrinterTransports.OpenResult.Failed? = null,
    choicesFile: File = File(createTempDirectory("vm-test").toFile(), "model-choices.txt"),
    historyFile: File = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl"),
) = ResetViewModel(
    scope = this,
    io = io,
    transports = { openFailure ?: PrinterTransports.OpenResult.Ok(transport) },
    discover = discover,
    connectionTest = connectionTest,
    loadSavedPrinters = loadSavedPrinters,
    addSavedPrinter = addSavedPrinter,
    removeSavedPrinter = removeSavedPrinter,
    backupDir = { backupDir },
    choicesFile = { choicesFile },
    historyFile = { historyFile },
)

private val ResetViewModel.lastLine: String get() = log.last().text
private fun ResetViewModel.said(fragment: String) = log.any { it.text.contains(fragment) }

class ViewModelCounterHistoryTest {

    @Test
    fun `selecting a known printer loads its existing history before another read`() = runTest {
        val file = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl")
        CounterJournal(file).append(
            "UNIT0001",
            CounterReader.Report(
                "TEST-1",
                listOf(
                    CounterReader.Reading(58, 0x19, 0, "Waste"),
                    CounterReader.Reading(59, 0x0F, 0, "Waste"),
                ),
            ),
        )
        val vm = viewModel(historyFile = file)

        vm.select(printer(serial = "UNIT0001"))
        advanceUntilIdle()

        assertEquals(1, assertNotNull(vm.history.view).samples.size)
    }

    @Test
    fun `a partly encoded USB descriptor loads history recorded with the plain serial`() = runTest {
        val file = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl")
        CounterJournal(file).append(
            "QWER012345",
            CounterReader.Report(
                "TEST-1",
                listOf(
                    CounterReader.Reading(58, 0x19, 0, "Waste"),
                    CounterReader.Reading(59, 0x0F, 0, "Waste"),
                ),
            ),
        )
        val vm = viewModel(historyFile = file)

        vm.select(printer(serial = "515745523031323345"))
        advanceUntilIdle()

        val history = assertNotNull(vm.history.view)
        assertEquals("QWER012345", history.serial)
        assertEquals(1, history.samples.size)
    }

    @Test
    fun `a successful live read is journalled under the canonical printer serial`() = runTest {
        val file = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl")
        val hardware = ScriptedPrinter(serial = "QWER012345", memory = mapOf(58 to 0x19, 59 to 0x0F))
        val vm = viewModel(transport = hardware, historyFile = file)

        vm.select(printer(serial = "51574552303132333435"))
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        val sample = CounterJournal(file).load("QWER012345").single()
        assertEquals("TEST-1", sample.report.model)
        assertEquals(2, sample.report.answered)
        assertEquals("QWER012345", assertNotNull(vm.history.view).serial)
        assertEquals(
            mapOf(
                58 to ResetViewModel.CounterByteState.READ,
                59 to ResetViewModel.CounterByteState.READ,
            ),
            vm.counterByteStates,
        )
    }

    @Test
    fun `dry runs and a disabled preference do not append history`() = runTest {
        val file = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl")
        val vm = viewModel(historyFile = file)
        vm.select(printer(serial = "UNIT0001"))

        vm.dryRun = true
        vm.readCounters()
        advanceUntilIdle()
        assertFalse(file.exists())

        vm.keepCounterHistory = false
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        assertFalse(file.exists())
        assertTrue(assertNotNull(vm.history.view).samples.isEmpty())
    }

    @Test
    fun `a live reset journals both the pre-write sample and successful verification`() = runTest {
        val file = File(createTempDirectory("vm-test").toFile(), "counter-history.jsonl")
        val hardware = ScriptedPrinter(serial = "UNIT0001", memory = mapOf(58 to 0x19, 59 to 0x0F))
        val vm = viewModel(transport = hardware, historyFile = file)

        vm.select(printer(serial = "UNIT0001"))
        vm.dryRun = false
        vm.run()
        advanceUntilIdle()

        val samples = CounterJournal(file).load("UNIT0001")
        assertEquals(2, samples.size)
        assertEquals(listOf(0x19, 0x0F), samples.first().report.readings.map { it.value })
        assertEquals(listOf(0, 0), samples.last().report.readings.map { it.value })
    }
}

class ViewModelBackupGateTest {

    @Test
    fun `a selected model supplies an immediate dry preview without pretending it was read`() = runTest {
        val vm = viewModel()

        vm.selectModel(testModel)

        assertNull(vm.readReport)
        assertEquals(listOf(0x7F, 0x7F), assertNotNull(vm.counterDisplayReport).readings.map { it.value })
    }

    @Test
    fun `live mode keeps model addresses visible while current values remain unknown`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)

        vm.changeDryRunMode(false)

        val report = assertNotNull(vm.counterDisplayReport)
        assertEquals(listOf(58, 59), report.readings.map { it.address })
        assertEquals(listOf(null, null), report.readings.map { it.value })
        assertEquals(listOf(0, 0), report.readings.map { it.expectedAfterReset })
    }

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
        assertEquals(
            mapOf(
                58 to ResetViewModel.CounterByteState.VERIFIED,
                59 to ResetViewModel.CounterByteState.VERIFIED,
            ),
            vm.counterByteStates,
        )
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

    @Test
    fun `switching to live does not carry the previous dry run result banner`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)
        vm.run()
        advanceUntilIdle()
        assertIs<ResetViewModel.RunState.Finished>(vm.runState)

        vm.changeDryRunMode(false)

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertNull(vm.readReport)
        assertTrue(vm.counterByteStates.isEmpty())
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
        assertFalse(assertIs<ResetViewModel.RunState.Finished>(vm.runState).result.success)
        assertEquals(ResetViewModel.CounterByteState.FAILED, vm.counterByteStates[58])
        assertEquals(ResetViewModel.CounterByteState.FAILED, vm.counterByteStates[59])
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

        vm.snapshot.restore(backup(model = "OTHER-9"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("Refusing to write one model's bytes to another"), vm.lastLine)
    }

    @Test
    fun `refuses when the printer reports a different serial`() = runTest {
        val vm = ready(deviceSerial = "UNIT-B")

        vm.snapshot.restore(backup(serial = "UNIT-A"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("came from UNIT-A"), vm.lastLine)
    }

    @Test
    fun `writes when the serials agree`() = runTest {
        val vm = ready(deviceSerial = "UNIT-A")

        vm.snapshot.restore(backup(serial = "UNIT-A"))
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

        vm.snapshot.restore(backup(serial = null))
        advanceUntilIdle()

        assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(vm.said("can't be tied to this exact unit"))
    }

    @Test
    fun `refuses when no printer is selected`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)
        vm.dryRun = false

        vm.snapshot.restore(backup())

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

        vm.snapshot.restore(backup(serial = "UNIT-B"))

        assertEquals(ResetViewModel.RunState.Idle, vm.runState)
        assertTrue(vm.said("came from UNIT-B"), vm.lastLine)
        assertFalse(vm.said("can't be tied to this exact unit"))
    }

    @Test
    fun `a dry run restore is not gated`() = runTest {
        val vm = viewModel()
        vm.selectModel(testModel)
        vm.dryRun = true

        vm.snapshot.restore(backup(model = "OTHER-9"))
        advanceUntilIdle()

        assertIs<ResetViewModel.RunState.Finished>(vm.runState)
        assertTrue(vm.said("A live run would refuse"))
    }
}

/** The UI safety sequence in front of ink-spending maintenance. */
class ViewModelMaintenanceTest {

    @Test
    fun `cleaning is reachable only after a nozzle pattern with gaps`() = runTest {
        val hardware = ScriptedPrinter(state = Status.STATE_IDLE)
        val vm = viewModel(transport = hardware)
        vm.select(printer())

        assertFalse(vm.maintenance.cleaningEnabled)
        assertFalse(vm.maintenance.canRun(Maintenance.Operation.HEAD_CLEANING))

        vm.maintenance.run(Maintenance.Operation.NOZZLE_CHECK)
        advanceUntilIdle()

        assertEquals(
            MaintenanceState.PatternAssessment.AWAITING_ANSWER,
            vm.maintenance.patternAssessment,
        )
        assertTrue(vm.log.any { it.level == ResetViewModel.Level.TRACE }, "maintenance must reach the shared trace log")

        vm.maintenance.answerNozzleCheck(hasGaps = false)
        assertFalse(vm.maintenance.cleaningEnabled)

        vm.maintenance.run(Maintenance.Operation.NOZZLE_CHECK)
        advanceUntilIdle()
        vm.maintenance.answerNozzleCheck(hasGaps = true)

        assertTrue(vm.maintenance.cleaningEnabled)
        vm.maintenance.run(Maintenance.Operation.HEAD_CLEANING)
        advanceUntilIdle()
        assertTrue(vm.maintenance.cleaningCompleted)
    }

    @Test
    fun `nozzle evidence does not enable cleaning on another printer`() = runTest {
        val vm = viewModel(transport = ScriptedPrinter(state = Status.STATE_IDLE))
        vm.select(printer(serial = "UNIT-A"))
        vm.maintenance.run(Maintenance.Operation.NOZZLE_CHECK)
        advanceUntilIdle()
        vm.maintenance.answerNozzleCheck(hasGaps = true)
        assertTrue(vm.maintenance.cleaningEnabled)

        vm.select(printer(serial = "UNIT-B", link = Link.Usb(1, 5, 1, 0x81.toByte(), 0x02, true)))

        assertEquals(
            MaintenanceState.PatternAssessment.NOT_CHECKED,
            vm.maintenance.patternAssessment,
        )
        assertFalse(vm.maintenance.cleaningEnabled)
    }

    @Test
    fun `network maintenance is refused with a USB explanation before opening the printer`() = runTest {
        val hardware = ScriptedPrinter(state = Status.STATE_IDLE)
        val vm = viewModel(transport = hardware)
        vm.select(printer(link = Link.Network("192.168.1.50")))

        val reason = assertNotNull(vm.maintenance.blockedReason)
        assertContains(reason, "USB only")
        assertContains(reason, "SNMP control channel")

        vm.maintenance.run(Maintenance.Operation.NOZZLE_CHECK)
        advanceUntilIdle()

        assertEquals(0, hardware.packets)
        assertTrue(vm.said("Connect this printer over USB"), vm.lastLine)
    }

    @Test
    fun `the printer status busy gate is shown before maintenance`() = runTest {
        val vm = viewModel(transport = ScriptedPrinter(state = Status.STATE_CLEANING))
        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        assertContains(assertNotNull(vm.maintenance.blockedReason), "cleaning")
        assertFalse(vm.maintenance.canRun(Maintenance.Operation.NOZZLE_CHECK))
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
        vm.snapshot.restore(
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

    /**
     * A printer that names its family has not named itself. The entry that name resolves to reads
     * and writes like a printer that may not be this one, so the gate has to hold even though
     * everything visible — a matched device, a selected model, no mismatch — looks settled.
     */
    @Test
    fun `a family-level identification blocks a live run until it is settled`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        vm.select(classPrinter())
        vm.dryRun = false

        assertNotNull(vm.pendingClass)
        assertNotNull(vm.writeBlockedReason)
        assertFalse(vm.canRun)
        assertTrue(vm.modelPickerExpanded)
        assertTrue(vm.said("do not share a reset recipe"), vm.lastLine)
    }

    @Test
    fun `model confirmation is a dismissible second step and one choice settles it`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        vm.select(classPrinter())

        assertTrue(vm.modelSelectionVisible)
        assertEquals(listOf("TEST-1", "OTHER-9"), vm.scopedModelCandidates.map { it.name })

        vm.leaveModelSelection()
        assertFalse(vm.modelSelectionVisible)
        assertNotNull(vm.pendingClass, "Back returns to printers without guessing a model")

        vm.requestModelSelection()
        vm.selectModel(otherModel)
        advanceUntilIdle()

        assertFalse(vm.modelSelectionVisible)
        assertNull(vm.pendingClass)
        assertEquals("OTHER-9", assertNotNull(vm.selectedModel).name)
    }

    /** A family has no effective model until the user picks the unit printed on the label. */
    @Test
    fun `an unsettled family does not stage a guessed model even for a dry run`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        vm.select(classPrinter())
        vm.dryRun = true

        assertNull(vm.selectedModel)
        assertFalse(vm.canRun)
        assertEquals(listOf("TEST-1", "OTHER-9"), vm.scopedModelCandidates.map { it.name })
    }

    @Test
    fun `picking a member settles the family and lifts the block`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        vm.select(classPrinter())
        vm.selectModel(otherModel)
        vm.dryRun = false
        advanceUntilIdle()

        assertNull(vm.pendingClass)
        assertNull(vm.writeBlockedReason)
        assertTrue(vm.canRun)
        assertEquals("OTHER-9", assertNotNull(vm.identifiedModel).name)
        // The name came from the user, and the card that shows it says so.
        assertEquals("EPSON TEST Series", vm.confirmedClass)
    }

    @Test
    fun `the answer is remembered for the next session`() = runTest {
        val choices = File(createTempDirectory("vm-test").toFile(), "model-choices.txt")

        val first = viewModel(choicesFile = choices)
        first.database = classDatabase
        first.select(classPrinter())
        first.selectModel(otherModel)
        advanceUntilIdle()

        val second = viewModel(choicesFile = choices)
        second.database = classDatabase
        second.select(classPrinter())

        assertNull(second.pendingClass)
        assertNull(second.writeBlockedReason)
        assertEquals("OTHER-9", assertNotNull(second.identifiedModel).name)
        assertTrue(second.said("confirmed as OTHER-9 before"), second.lastLine)
    }

    /** A printer swapped onto the same port reports something else, and inherits nothing. */
    @Test
    fun `a remembered answer is not applied to a printer reporting a different family`() = runTest {
        val choices = File(createTempDirectory("vm-test").toFile(), "model-choices.txt")

        val first = viewModel(choicesFile = choices)
        first.database = classDatabase
        first.select(classPrinter())
        first.selectModel(otherModel)
        advanceUntilIdle()

        val second = viewModel(choicesFile = choices)
        second.database = classDatabase
        second.select(classPrinter(product = "EPSON OTHER Series"))

        assertNotNull(second.pendingClass)
    }

    /** A wrong answer has to be revocable, or the pin is worse than the guess it replaced. */
    @Test
    fun `forgetting the choice asks again`() = runTest {
        val choices = File(createTempDirectory("vm-test").toFile(), "model-choices.txt")

        val vm = viewModel(choicesFile = choices)
        vm.database = classDatabase
        vm.select(classPrinter())
        vm.selectModel(otherModel)
        advanceUntilIdle()

        vm.forgetModelChoice()
        advanceUntilIdle()

        assertNull(vm.confirmedClass)
        assertTrue(ModelChoices.load(choices).isEmpty())
    }

    /**
     * The printer answering SNMP on its own network address is the one source that names the unit
     * a USB descriptor only gestures at, so the family question never has to be put to the user.
     */
    @Test
    fun `a name borrowed from the network entry settles the family and is cited as such`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        vm.select(crossCheckedPrinter())

        val identity = assertNotNull(vm.identity)
        assertEquals("OTHER-9", identity.model.name)
        assertEquals(ResetViewModel.Identity.Via.SNMP_CROSS_LINK, identity.via)
        assertNull(vm.pendingClass, "SNMP answered it, so there is nothing to ask")
        assertFalse(vm.modelSelectionVisible, "a cross-checked unit does not need the model step")
    }

    /**
     * The links disagreeing about what to write is not a refinement, it is a contradiction — and
     * picking a side silently would be writing a key on a guess about which link is lying.
     */
    @Test
    fun `links that disagree on the recipe stop rather than pick a side`() = runTest {
        val vm = viewModel()
        vm.database = classDatabase

        // The descriptor resolves to TEST-1 (rkey 1); the network entry says OTHER-9 (rkey 99).
        vm.select(crossCheckedPrinter(product = "TEST-1"))
        vm.dryRun = false

        assertNull(vm.identity, "neither name may stand while they contradict each other")
        assertTrue(vm.modelPickerExpanded)
        assertTrue(vm.said("do not write the same bytes"), vm.lastLine)
    }

    /** The pin is keyed on the serial, and a serial is the same serial in either spelling. */
    @Test
    fun `an answer given over USB is found again over the network`() = runTest {
        val choices = File(createTempDirectory("vm-test").toFile(), "model-choices.txt")

        val overUsb = viewModel(choicesFile = choices)
        overUsb.database = classDatabase
        overUsb.select(classPrinter().copy(device = usbDevice(serial = "51574552303132333435")))
        overUsb.selectModel(otherModel)
        advanceUntilIdle()

        val overNetwork = viewModel(choicesFile = choices)
        overNetwork.database = classDatabase
        overNetwork.select(
            classPrinter().copy(
                device = DetectedPrinter(
                    link = Link.Network("192.168.2.39"),
                    product = "EPSON TEST Series",
                    serial = "QWER012345",
                ),
            ),
        )

        assertNull(overNetwork.pendingClass, "the same printer, so the same answer")
        assertEquals("OTHER-9", assertNotNull(overNetwork.identifiedModel).name)
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
        assertNull(vm.selectedModel)
    }

    @Test
    fun `an unmatched printer does not inherit the previous printer model or reading`() = runTest {
        val vm = viewModel()
        vm.select(printer())
        vm.dryRun = true
        vm.readCounters()
        advanceUntilIdle()
        assertNotNull(vm.selectedModel)
        assertNotNull(vm.readReport)

        val unknown = printer(product = "EPSON Mystery").copy(
            model = null,
            confidence = MatchedPrinter.Confidence.NONE,
        )
        vm.select(unknown)

        assertNull(vm.selectedModel)
        assertNull(vm.readReport)
        assertTrue(vm.modelPickerExpanded)
    }
}

/** Snapshots taken because someone asked, rather than because a reset was about to write. */
class ViewModelSnapshotTest {

    @Test
    fun `the snapshots tab takes a fresh live reading before it saves`() = runTest {
        val hardware = ScriptedPrinter(serial = "UNIT-A", memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = true

        assertTrue(vm.snapshot.canCreateSnapshot, vm.snapshot.createSnapshotBlockedReason ?: "")
        assertNull(vm.readReport)
        vm.snapshot.readAndSaveSnapshot()
        advanceUntilIdle()

        val saved = assertNotNull(dir.listFiles()?.singleOrNull()?.let(EepromBackup::load))
        assertEquals(listOf(0x19, 0x0F), saved.entries.map { it.value })
        assertFalse(vm.readWasSimulated, "the explicit snapshot action reads the real printer")
        assertTrue(hardware.packets > 0)
        assertTrue(hardware.writes.isEmpty())
    }

    @Test
    fun `saves what the last live read returned, and never writes to do it`() = runTest {
        val hardware = ScriptedPrinter(serial = "UNIT-A", memory = mapOf(58 to 0x19, 59 to 0x0F))
        val dir = createTempDirectory("vm-test").toFile()
        val vm = viewModel(transport = hardware, backupDir = dir)

        vm.select(printer())
        vm.dryRun = false
        vm.readCounters()
        advanceUntilIdle()

        assertTrue(vm.snapshot.canSaveSnapshot, vm.snapshot.snapshotBlockedReason ?: "")
        vm.snapshot.saveSnapshot()
        advanceUntilIdle()

        val file = assertNotNull(dir.listFiles()?.singleOrNull { it.name.endsWith(".json") })
        val saved = assertNotNull(EepromBackup.load(file))
        assertEquals(listOf(58, 59), saved.entries.map { it.address })
        assertEquals(listOf(0x19, 0x0F), saved.entries.map { it.value })
        assertEquals("UNIT-A", saved.printerSerial)
        assertTrue(hardware.writes.isEmpty(), "a snapshot writes a file, not EEPROM")
        assertEquals(file, assertNotNull(vm.snapshot.selectedSnapshot).file)
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
        assertFalse(vm.snapshot.canSaveSnapshot)

        vm.snapshot.saveSnapshot()
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

        vm.snapshot.saveSnapshot()
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
        vm.snapshot.saveSnapshot()
        advanceUntilIdle()

        val quiet = hardware.packets
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.single())

        val report = assertNotNull(vm.snapshot.snapshotReport)
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

        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()

        assertNull(vm.snapshot.snapshots.single().backup)
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

        vm.snapshot.selectSnapshot(
            SnapshotState.SavedSnapshot(
                file = File(dir, "OTHER-9.json"),
                backup = EepromBackup(
                    model = "OTHER-9",
                    createdAt = "20260727T004500Z",
                    printerSerial = null,
                    entries = listOf(EepromBackup.Entry(20, 0x19, 0)),
                ),
            ),
        )

        assertContains(assertNotNull(vm.snapshot.snapshotRestoreBlockedReason), "OTHER-9")

        vm.snapshot.restoreSelectedSnapshot()
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
        vm.snapshot.saveSnapshot()
        advanceUntilIdle()

        val quiet = hardware.packets
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.single())

        assertEquals(SnapshotState.CompareTarget.None, vm.snapshot.compareTarget)
        assertNull(vm.snapshot.comparison)
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
        vm.snapshot.saveSnapshot()
        advanceUntilIdle()
        val taken = assertNotNull(vm.snapshot.selectedSnapshot)

        vm.run()
        advanceUntilIdle()

        // What the run itself compared against is gone the moment the counters are read again.
        vm.readCounters()
        advanceUntilIdle()
        assertNull(vm.beforeReport)

        vm.snapshot.selectSnapshot(taken)
        vm.snapshot.compareWithCurrentReading()

        val result = assertNotNull(vm.snapshot.comparison)
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
        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()

        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.single())
        vm.snapshot.compareWithCurrentReading()

        assertEquals(SnapshotState.CompareTarget.None, vm.snapshot.compareTarget)
        assertNull(vm.snapshot.comparison)
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
        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.single())

        assertContains(assertNotNull(vm.snapshot.compareReadBlockedReason), "Dry run")
        assertFalse(vm.snapshot.canReadForComparison)
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

        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()

        // Selected newer, compared against older: the older one is still "before".
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.first { it.file == newer })
        vm.snapshot.compareWithSnapshot(older)

        val forward = assertNotNull(vm.snapshot.comparison)
        assertEquals(older.name, forward.before.label)
        assertEquals(0x10, forward.bytes.first { it.address == 58 }.before)
        assertEquals(0x20, forward.bytes.first { it.address == 58 }.after)

        // And the other way round, which must produce exactly the same comparison.
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.first { it.file == older })
        vm.snapshot.compareWithSnapshot(newer)

        val reverse = assertNotNull(vm.snapshot.comparison)
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

        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.first())
        vm.snapshot.compareWithSnapshot(older)
        assertNotNull(vm.snapshot.comparison)

        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.last())

        assertEquals(SnapshotState.CompareTarget.None, vm.snapshot.compareTarget)
        assertNull(vm.snapshot.comparison)
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

        vm.snapshot.refreshSnapshots()
        advanceUntilIdle()
        vm.snapshot.selectSnapshot(vm.snapshot.snapshots.first { it.backup?.model == "TEST-1" })

        assertTrue(vm.snapshot.compareCandidates.isEmpty(), "${vm.snapshot.compareCandidates.map { it.file.name }}")
    }

    /**
     * The Counters tab hands off rather than growing a second comparison view, so what it offers has
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
        assertFalse(vm.snapshot.canOfferComparison)

        vm.snapshot.saveSnapshot()
        advanceUntilIdle()

        assertTrue(vm.snapshot.canOfferComparison)

        vm.snapshot.compareCurrentReadingWithNewestSnapshot()
        assertEquals(ResetViewModel.Tab.SNAPSHOTS, vm.tab)
        assertEquals(SnapshotState.CompareTarget.CurrentReading, vm.snapshot.compareTarget)
        assertNotNull(vm.snapshot.comparison)
    }
}

class ViewModelScanTest {

    @Test
    fun `an unreachable saved printer allows simulation but not live controls`() = runTest {
        val unreachable = printer(
            serial = null,
            link = Link.Network("192.168.1.50"),
        ).let { it.copy(device = it.device.copy(reachable = false)) }
        val vm = viewModel()
        vm.select(unreachable)

        vm.dryRun = false
        assertFalse(vm.canRead)
        assertFalse(vm.canRun)
        assertContains(assertNotNull(vm.writeBlockedReason), "was not reached")

        vm.dryRun = true
        assertTrue(vm.canRead)
        assertTrue(vm.canRun)
    }

    @Test
    fun `forget removes a saved printer without starting another scan`() = runTest {
        val link = Link.Network("192.168.1.50")
        val remembered = printer(serial = null, link = link)
        var saved = listOf(SavedPrinters.Saved(link, remembered.device.product))
        var scans = 0
        val vm = viewModel(
            discover = {
                scans++
                discovery(remembered)
            },
            loadSavedPrinters = { saved },
            removeSavedPrinter = { removed ->
                saved = saved.filterNot { it.link == removed }
                saved
            },
        )

        vm.start()
        advanceUntilIdle()
        assertEquals(1, scans)
        val selected = assertNotNull(vm.selectedDevice)
        assertTrue(vm.isSaved(selected))

        vm.forgetNetworkPrinter(selected)
        advanceUntilIdle()

        assertEquals(1, scans, "forget must not start discovery")
        assertTrue(saved.isEmpty())
        assertTrue(vm.devices.isEmpty())
        assertNull(vm.selectedDevice)
        assertNull(vm.selectedModel)
        assertFalse(vm.isSaved(selected))
        assertIs<ResetViewModel.ScanState.Done>(vm.scanState)
    }

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
    fun `a successful test promotes a remembered address from not reached to reachable`() = runTest {
        val remembered = printer(
            serial = null,
            link = Link.Network("192.168.1.50"),
        ).let {
            it.copy(
                device = it.device.copy(
                    accessNote = "Saved address did not answer this scan.",
                    reachable = false,
                ),
            )
        }
        val vm = viewModel(
            discover = { discovery(remembered) },
            connectionTest = { _, _ ->
                ConnectionTest.Result(
                    opened = true,
                    identity = DeviceId.Id(mapOf("MDL" to "EPSON TEST-1")),
                    answered = true,
                    status = null,
                    overNetwork = true,
                    reach = ConnectionTest.Reach.STATUS_ONLY,
                )
            },
        )

        vm.start()
        advanceUntilIdle()
        assertFalse(assertNotNull(vm.selectedDevice).device.reachable)

        vm.testConnection()
        advanceUntilIdle()

        assertTrue(assertNotNull(vm.selectedDevice).device.reachable)
        assertTrue(vm.devices.single().device.reachable)
        assertNull(vm.devices.single().device.accessNote)
    }

    @Test
    fun `the target menu stays available during a scan and a manual choice cancels stale results`() = runTest {
        val enteredDiscovery = CountDownLatch(1)
        val releaseDiscovery = CountDownLatch(1)
        val latePrinter = printer(serial = "TOO-LATE")
        val vm = viewModel(
            io = Dispatchers.Default,
            discover = {
                enteredDiscovery.countDown()
                check(releaseDiscovery.await(2, TimeUnit.SECONDS))
                discovery(latePrinter)
            },
        )

        try {
            vm.scan()
            runCurrent()
            assertTrue(enteredDiscovery.await(2, TimeUnit.SECONDS))
            assertIs<ResetViewModel.ScanState.Scanning>(vm.scanState)
            assertTrue(vm.canChangeTarget)
            assertTrue(vm.canScan)

            vm.selectModel(otherModel)

            assertIs<ResetViewModel.ScanState.Stopped>(vm.scanState)
            assertEquals(otherModel, vm.selectedModel)
        } finally {
            releaseDiscovery.countDown()
        }
        advanceUntilIdle()

        assertTrue(vm.devices.isEmpty(), "a stopped scan must not publish a late result")
        assertEquals(otherModel, vm.selectedModel)
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
    private fun TestScope.readied(dryRun: Boolean, product: String = "EPSON TEST-1"): ResetViewModel {
        val vm = viewModel(transport = scripted())
        vm.counterSpecs = CounterSpecs.loadBundled()
        vm.database = PrinterDatabase.loadBundled()
        vm.select(printer(product = product).copy(model = et2820))
        vm.dryRun = dryRun
        vm.readCounters()
        advanceUntilIdle()
        vm.calibration.open()
        return vm
    }

    @Test
    fun `a dry run cannot be calibrated from`() = runTest {
        val vm = readied(dryRun = true)

        assertContains(assertNotNull(vm.calibration.blockedReason), "simulated EEPROM")

        vm.calibration.setServiceRequired(listOf(48, 49), true)
        assertFalse(vm.calibration.canSubmit)

        vm.calibration.applyToSession()
        assertFalse(vm.calibration.applied, "invented bytes must not become a maximum")
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

        assertNull(vm.calibration.blockedReason)
        vm.calibration.setPercent(listOf(48, 49), "60.90")

        val measured = vm.calibration.measurements.single()
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

        vm.calibration.setServiceRequired(listOf(48, 49), true)
        vm.calibration.applyToSession()

        assertTrue(vm.calibration.applied)
        assertEquals(100.0, assertNotNull(vm.percentShown(listOf(48, 49))), 0.005)
    }

    /**
     * A maximum is the divisor behind every percentage for that counter, so applying a wrong one
     * makes every reading of it wrong. Getting back out of that must not require knowing that a
     * restart would have done it.
     */
    @Test
    fun `a maximum applied to the session can be taken back off`() = runTest {
        val vm = readied(dryRun = false)
        val before = assertNotNull(vm.percentShown(listOf(48, 49)))

        vm.calibration.setServiceRequired(listOf(48, 49), true)
        vm.calibration.applyToSession()
        assertEquals(100.0, assertNotNull(vm.percentShown(listOf(48, 49))), 0.005)

        vm.calibration.revertSession()
        advanceUntilIdle()

        assertFalse(vm.calibration.applied)
        assertEquals(before, assertNotNull(vm.percentShown(listOf(48, 49))), 0.005)
        assertTrue(vm.said("back to what is on disk"), vm.lastLine)
    }

    /** A fresh reading invalidates whatever was typed against the previous one. */
    @Test
    fun `re-reading clears the form`() = runTest {
        val vm = readied(dryRun = false)
        vm.calibration.setPercent(listOf(48, 49), "60.90")
        assertTrue(vm.calibration.canSubmit)

        vm.readCounters()
        advanceUntilIdle()

        assertFalse(vm.calibration.canSubmit)
        assertEquals("", vm.calibration.input(listOf(48, 49)).percent)
    }

    @Test
    fun `the entry names the model on the form and carries the measured maximum`() = runTest {
        val vm = readied(dryRun = false)
        vm.calibration.setServiceRequired(listOf(48, 49), true)

        val entry = vm.calibration.entry()
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
        assertEquals("ET-2820", vm.calibration.model)
        assertNull(vm.calibration.modelWarning)

        // Every model on this layout is on offer, its own series first.
        assertTrue(vm.calibration.layoutSiblings.size > 100, "the ET-2820 layout is widely shared")
        assertContains(vm.calibration.modelCandidates, "ET-2825")

        vm.calibration.setServiceRequired(listOf(48, 49), true)
        vm.calibration.model = "ET-2825"
        assertContains(vm.calibration.entry(), """"models": ["ET-2825"]""")
        assertContains(assertNotNull(vm.calibration.modelWarning), "names itself ET-2820")
    }

    @Test
    fun `a family name is refused as the thing being calibrated`() = runTest {
        val vm = readied(dryRun = false)
        vm.calibration.model = "ET-2820 Series"

        assertContains(assertNotNull(vm.calibration.modelWarning), "names a family, not a unit")
    }

    /**
     * A USB printer is identified from its descriptor. SNMP is a network thing and is not involved,
     * so citing it on the connection the resets actually run over was simply false.
     */
    @Test
    fun `a USB identification is not attributed to SNMP`() = runTest {
        val vm = readied(dryRun = false)

        assertEquals(ResetViewModel.Identity.Via.USB_DESCRIPTOR, assertNotNull(vm.identity).via)

        vm.calibration.model = "ET-2825"
        val warning = assertNotNull(vm.calibration.modelWarning)
        assertContains(warning, "its USB descriptor")
        assertFalse(warning.contains("SNMP"), "nothing here came over SNMP: $warning")
    }

    /**
     * The case the descriptor actually produces. `ET-2820 Series` fills the box with `ET-2820`, and
     * leaving it there files a unit's measurement under a family — so the doubt has to be raised
     * while the box still holds the prefilled name, not only once it is edited.
     */
    @Test
    fun `a family named by the descriptor is queried even when the prefilled name is kept`() = runTest {
        val vm = readied(dryRun = false, product = "EPSON ET-2820 Series")

        assertEquals("ET-2820", vm.calibration.model)
        val warning = assertNotNull(vm.calibration.modelWarning)
        assertContains(warning, "EPSON ET-2820 Series")
        assertContains(warning, "its USB descriptor")
        assertContains(warning, "covers several units")
    }

    /** Correcting a family-derived name is the point of the field, not an override of a good answer. */
    @Test
    fun `correcting a family-derived name is not reported as overriding the printer`() = runTest {
        val vm = readied(dryRun = false, product = "EPSON ET-2820 Series")

        vm.calibration.model = "ET-2825"
        val warning = assertNotNull(vm.calibration.modelWarning)
        assertContains(warning, "is a family rather than a unit")
        assertContains(warning, "better name to file")
        assertFalse(warning.contains("overrides"), "the descriptor named no unit to override: $warning")
    }

    /**
     * A unit the user settled by hand fills the form the same way a printer-named one does, but it
     * is not the firmware's word and the form must not say it is — that field decides what the
     * submission is worth to whoever reviews it.
     */
    @Test
    fun `a hand-confirmed unit is not passed off as the printer's own answer`() = runTest {
        val vm = readied(dryRun = false)
        vm.select(classPrinter().copy(model = et2820))
        vm.selectModel(et2820)
        advanceUntilIdle()
        vm.calibration.open()

        vm.calibration.model = "ET-2825"
        val warning = assertNotNull(vm.calibration.modelWarning)
        assertContains(warning, "You confirmed this printer as ET-2820")
        assertFalse(warning.contains("SNMP"), "the printer named a family, not a unit: $warning")
    }

    @Test
    fun `nothing can be filed without a model name`() = runTest {
        val vm = readied(dryRun = false)
        vm.calibration.setServiceRequired(listOf(48, 49), true)
        assertTrue(vm.calibration.canSubmit)

        vm.calibration.model = "  "
        assertFalse(vm.calibration.canSubmit)
    }
}

/** What lands on the clipboard when someone is filing a bug report. */
class ViewModelLogExportTest {

    /**
     * The header is the whole reason for the export: a pasted log has to say what it came from
     * even when no printer was ever reachable, which is exactly the report worth filing.
     */
    @Test
    fun `the export carries the environment even with nothing selected`() = runTest {
        val vm = viewModel()

        val export = vm.exportLog()

        assertContains(export, "# Epson Reset")
        assertContains(export, System.getProperty("os.name"))
        assertContains(export, "# libusb:")
        assertContains(export, "# printer: none selected")
        assertContains(export, "# model: none")
        assertContains(export, "# mode: dry run")
    }

    @Test
    fun `the export names the printer, the model and the mode in force`() = runTest {
        val vm = viewModel()
        vm.select(printer())
        vm.dryRun = false

        val export = vm.exportLog()

        assertContains(export, "# printer: EPSON TEST-1 on USB (bus 1.4)")
        assertContains(export, "# model: TEST-1")
        assertContains(export, "# mode: live")
    }

    /** The trace is the point of copying, so hiding it in the panel must not drop it here. */
    @Test
    fun `trace lines are exported whether or not the panel shows them`() = runTest {
        val vm = viewModel()
        vm.info("an ordinary line")
        vm.trace("-> 1B 01 40 45 4A 4C")

        val export = vm.exportLog()

        assertContains(export, "[TRACE] -> 1B 01 40 45 4A 4C")
        assertContains(export, "[INFO] an ordinary line")
    }
}
