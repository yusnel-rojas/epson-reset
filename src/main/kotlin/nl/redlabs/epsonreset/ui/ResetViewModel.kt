package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.Diag
import nl.redlabs.epsonreset.backup.Capture
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.db.CapabilitySummary
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.ModelCapabilities
import nl.redlabs.epsonreset.db.ModelCapability
import nl.redlabs.epsonreset.db.ModelClass
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.db.ResetScope
import nl.redlabs.epsonreset.db.ValueSupport
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.device.ModelChoices
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.device.Serials
import nl.redlabs.epsonreset.history.CounterJournal
import nl.redlabs.epsonreset.net.NetworkAddress
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.net.SavedPrinters
import nl.redlabs.epsonreset.prefs.PreferencesStore
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.update.AppVersion
import nl.redlabs.epsonreset.usb.LibUsb
import nl.redlabs.epsonreset.usb.UsbPrinterScanner
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Everything the window renders from. Mutated on the UI thread; */
class ResetViewModel(
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val transports: (DetectedPrinter) -> PrinterTransports.OpenResult = PrinterTransports::open,
    private val discover: () -> PrinterDiscovery.Result = {
        PrinterDiscovery.scan(crossCheck = PreferencesStore.current().crossCheckOverSnmp)
    },
    private val connectionTest: (DetectedPrinter, PrinterModel?) -> ConnectionTest.Result = ConnectionTest::run,
    private val loadSavedPrinters: () -> List<SavedPrinters.Saved> = SavedPrinters::load,
    private val addSavedPrinter: (SavedPrinters.Saved) -> List<SavedPrinters.Saved> = SavedPrinters::add,
    private val removeSavedPrinter: (Link.Network) -> List<SavedPrinters.Saved> = SavedPrinters::remove,
    private val backupDir: () -> File = { AppPaths.backups },
    private val choicesFile: () -> File = { AppPaths.modelChoices },
    private val historyFile: () -> File = { AppPaths.counterHistory },
    private val now: () -> Instant = Instant::now,
) {

    /** The model database. */
    var database by mutableStateOf<PrinterDatabase?>(null)
        internal set
    var databaseError by mutableStateOf<String?>(null)
        private set

    var scanState by mutableStateOf<ScanState>(ScanState.Idle)
        private set
    var devices = mutableStateListOf<MatchedPrinter>()
        private set
    var selectedDevice by mutableStateOf<MatchedPrinter?>(null)
        private set

    /**
     * What the selected printer is taken to be, and how that was arrived at — distinct from the
     * effective [selectedModel], which can also be chosen for a dry run with no printer attached.
     *
     * The two travel together because everything downstream cites the identification when it acts on
     * it, and a citation that names the wrong source is worse than none: a USB descriptor and an
     * SNMP answer are not equally strong, and the descriptor is the one that usually names a family.
     */
    var identity by mutableStateOf<Identity?>(null)
        private set

    val identifiedModel: PrinterModel? get() = identity?.model

    /**
     * The printer named a family whose members disagree about what a reset writes, and which of them
     * is on the desk has not been settled. Null once it has been, either by the user or by an answer
     * remembered from a previous session.
     */
    var pendingClass by mutableStateOf<PendingClass?>(null)
        private set

    /**
     * The family string the identity was settled *against*, when a person settled it rather than the
     * printer naming a unit outright. Kept so changing the answer re-answers the same question
     * instead of reading as a mismatch with a name the printer never claimed.
     */
    val confirmedClass: String? get() = identity?.takeIf { it.via == Identity.Via.CONFIRMED }?.reported

    /** Model choices that belong to the selected printer's reported family, never the whole database. */
    val scopedModelCandidates: List<PrinterModel>
        get() = pendingClass?.candidates ?: confirmedClass?.let { reported ->
            database?.let { DeviceMatcher.resolve(reported, it).candidates }
        } ?: selectedDevice?.model?.let { model ->
            database?.let { ModelClass.members(it, model.name) }
        }.orEmpty()

    /** What the printer would only say, and everything it could have meant. */
    data class PendingClass(val reported: String, val candidates: List<PrinterModel>)

    /**
     * A model, the words it was derived from, and the channel those words arrived on.
     *
     * [reported] is kept verbatim because it is the evidence: `ET-2820 Series` resolving to the
     * `ET-2820` entry is a family answering for eight units, and only the original string says so.
     */
    data class Identity(val model: PrinterModel, val via: Via, val reported: String) {
        enum class Via(val label: String) {
            /** iProduct off the USB descriptor. Names a family far more often than a unit. */
            USB_DESCRIPTOR("its USB descriptor"),

            /** `@EJL ID` asked over the open USB channel. */
            USB_EJL("an EJL query over USB"),

            /** An mDNS advertisement, or a name cached from one. */
            NETWORK_ADVERT("its network advertisement"),

            /** The Epson MIB over SNMP — the one source that reliably gives a unit. */
            SNMP("SNMP"),

            /** The same printer's SNMP answer, borrowed from its network entry onto its USB one. */
            SNMP_CROSS_LINK("SNMP on its network address"),

            /** A person read the label on the printer, because nothing else could tell. */
            CONFIRMED("you"),
        }

        /** Whether the words behind this were a family name rather than a unit's. */
        val namesAFamily: Boolean get() = DeviceMatcher.namesAClass(reported)
    }

    /** Why one source came up empty, when the other one didn't. */
    var usbNote by mutableStateOf<String?>(null)
        private set
    var networkNote by mutableStateOf<String?>(null)
        private set

    /** What the user is typing into "add a printer by address". */
    var networkAddressInput by mutableStateOf("")

    /**
     * Addresses the user added by hand, as opposed to ones a browse turned up. Only these can be
     * forgotten — removing a discovered printer would just find it again on the next scan.
     */
    var savedNetworkPrinters by mutableStateOf<Set<Link.Network>>(emptySet())
        private set

    fun isSaved(printer: MatchedPrinter): Boolean = printer.device.link in savedNetworkPrinters

    val canAddNetworkPrinter: Boolean
        get() = !printerOperationRunning && NetworkAddress.parse(networkAddressInput) != null

    var testing by mutableStateOf(false)
        private set

    /** Result of the last connection test, shown against the selected device. */
    var lastTest by mutableStateOf<ConnectionTest.Result?>(null)
        private set

    val canTestConnection: Boolean get() = !printerOperationRunning && selectedDevice != null

    var query by mutableStateOf("")
    var selectedModel by mutableStateOf<PrinterModel?>(null)
        private set

    /** The user asked to pick the model by hand, over the top of a printer that named itself. */
    var manualModelRequested by mutableStateOf(false)

    /** The target menu is on its contextual second step rather than its normal printer list. */
    var modelSelectionVisible by mutableStateOf(false)
        private set

    /** Whether the model identity is unresolved or has been explicitly overridden. */
    val modelPickerExpanded: Boolean
        get() = manualModelRequested || identifiedModel == null || modelMismatch != null ||
            pendingClass != null

    fun requestModelSelection() {
        if (!canChangeTarget || selectedDevice == null) return
        manualModelRequested = true
        modelSelectionVisible = true
    }

    fun leaveModelSelection() {
        manualModelRequested = false
        modelSelectionVisible = false
    }

    /** The user opened the secondary add-by-address step from the scan control. */
    var addByAddressRequested by mutableStateOf(false)

    var tab by mutableStateOf(Tab.COUNTERS)

    /** The settings window, which is a window rather than a tab — it is an errand, not a screen. */
    var settingsOpen by mutableStateOf(false)

    /**
     * Mirrors of preferences with a switch in that window. Restored and persisted in
     * App.kt alongside the rest, so the view model stays out of the preferences file.
     */
    var crossCheckOverSnmp by mutableStateOf(true)
    var checkForUpdates by mutableStateOf(true)
    var keepCounterHistory by mutableStateOf(true)
    var developerMode by mutableStateOf(false)

    /**
     * Turns the cross-cutting [Diag] logger on or off and points it at this log. Called from App.kt
     * on restore and whenever the Developer toggle changes. Lines arrive as TRACE — captured by the
     * log's Copy, and shown live in the panel while Developer mode is on.
     */
    fun applyDeveloperMode(on: Boolean) {
        Diag.wire { line -> onMain { trace(line) } }
        Diag.enabled = on
        if (on) trace("[DBG] Developer mode on — verbose transport and scan diagnostics enabled.")
    }

    /** Every family answer on file, for the window that lists them. Loaded on open. */
    var rememberedChoices by mutableStateOf<List<ModelChoices.Choice>>(emptyList())
        private set

    fun openSettings() {
        settingsOpen = true
        refreshRememberedChoices()
        history.refreshStats()
    }

    private fun refreshRememberedChoices() {
        scope.launch {
            rememberedChoices = withContext(io) { ModelChoices.load(choicesFile()) }
        }
    }

    /**
     * Drops one remembered answer by key. When it is the printer in front of us, the identity it
     * was propping up has to go with it — otherwise the window says the answer is forgotten while
     * the rest of the app carries on citing it.
     */
    fun forgetRememberedChoice(key: String) {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing remembered models.")
            return
        }
        if (key in printerKeys) {
            forgetModelChoice()
            refreshRememberedChoices()
            return
        }

        scope.launch {
            withContext(io) { ModelChoices.forget(choicesFile(), key) }
            info("Forgot the remembered model for $key.")
            refreshRememberedChoices()
        }
    }

    fun forgetAllRememberedChoices() {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing remembered models.")
            return
        }
        val known = rememberedChoices
        if (known.isEmpty()) return

        scope.launch {
            withContext(io) { ModelChoices.save(choicesFile(), emptyList()) }
            if (identity?.via == Identity.Via.CONFIRMED) identity = null
            info("Forgot ${known.size} remembered model choice(s).")
            refreshRememberedChoices()
        }
    }

    var logCollapsed by mutableStateOf(false)

    var matrixQuery by mutableStateOf("")
    var matrixFilter by mutableStateOf(MatrixFilter.ALL)

    /** Generates and logs the full packet sequence without opening the device. Default on. */
    var dryRun by mutableStateOf(true)

    var runState by mutableStateOf<RunState>(RunState.Idle)
        private set

    /** What [runState] is about. A restore and a reset drive the same executor, so the state alone
     *  cannot say which one a panel is looking at. */
    var runKind by mutableStateOf(RunKind.RESET)
        private set

    /**
     * The outcome of the run that just ended, until it has been acknowledged. A dialog says it once
     * and closes; a card that has to be noticed to be dismissed outlives what it describes and ends
     * up claiming a stale result over a fresh reading.
     */
    var completion by mutableStateOf<Completion?>(null)
        private set

    data class Completion(val kind: RunKind, val result: Executor.Result, val wasDryRun: Boolean)

    fun dismissCompletion() {
        completion = null
    }
    var progress by mutableStateOf(0f)
        private set
    var progressLabel by mutableStateOf("")
        private set
    val log = mutableStateListOf<LogLine>()

    /** Per-model counter layouts (grouped addresses), from counters.json + user overlay. */
    var counterSpecs by mutableStateOf<CounterSpecs?>(null)
        internal set

    /** Latest counter sample. On a live reset this becomes the post-reset reading. */
    var readReport by mutableStateOf<CounterReader.Report?>(null)
        private set

    /** Whether [readReport] came off the fake EEPROM rather than a printer. */
    var readWasSimulated by mutableStateOf(false)
        private set

    /** [readReport] grouped into real counters. Empty when no layout is known for the model. */
    val decodedCounters: List<CounterReader.DecodedCounter>
        get() {
            val report = readReport ?: return emptyList()
            val model = selectedModel ?: return emptyList()
            return CounterReader.decode(report.readings, specsFor(model))
        }

    /**
     * What the Counters screen can show right now. The model supplies the layout even before a live
     * read; dry-run mode additionally has a deterministic fake starting value.
     */
    val counterDisplayReport: CounterReader.Report?
        get() {
            val model = selectedModel ?: return null
            val base = readReport?.takeIf { readWasSimulated == dryRun }
                ?: CounterReader.layout(model, specsFor(model), defaultValue = if (dryRun) 0x7F else null)
            if (counterByteStates.isEmpty()) return base
            return writePlan.applyTo(base)
        }

    /**
     * The reset as the counter table draws it. It needs no targets of its own: a reset writes each
     * address its own reset value, which every reading already carries.
     */
    val writePlan: WritePlan
        get() = WritePlan(WritePlan.RESET_TARGET, states = counterByteStates)

    val displayDecodedCounters: List<CounterReader.DecodedCounter>
        get() {
            val model = selectedModel ?: return emptyList()
            val report = counterDisplayReport ?: return emptyList()
            return CounterReader.decode(report.readings, specsFor(model))
        }

    enum class CounterByteState { PENDING, READING, READ, WRITING, ACKNOWLEDGED, VERIFIED, FAILED }

    /** Per-address state for the current or most recent reset attempt. */
    var counterByteStates by mutableStateOf<Map<Int, CounterByteState>>(emptyMap())
        private set

    fun specsFor(model: PrinterModel): List<CounterSpec> = counterSpecs?.get(model.name) ?: emptyList()

    val history = CounterHistoryState(
        scope = scope,
        io = io,
        journal = CounterJournal(historyFile()),
        enabled = { keepCounterHistory },
        specsFor = { counterSpecs?.get(it) ?: emptyList() },
        now = now,
        info = { info(it) },
        bad = { bad(it) },
    )

    /** Pre-reset sample, kept so the UI can show what actually changed. */
    var beforeReport by mutableStateOf<CounterReader.Report?>(null)
        private set

    /** Backup written by the last real run, offered as the recovery path if it went wrong. */
    var lastBackup by mutableStateOf<File?>(null)
        private set

    var reading by mutableStateOf(false)
        private set

    /** Printer's own status block: serial, ink levels, state. Null until a read runs. */
    var status by mutableStateOf<Status.Report?>(null)
        private set

    /** Standard Printer-MIB readout — lifetime pages and supply levels. Network reads only. */
    var printerMib by mutableStateOf<PrinterMib.Reading?>(null)
        private set

    val calibration = CalibrationState(
        scope = scope,
        io = io,
        database = { database },
        selectedModel = { selectedModel },
        identifiedModel = { identifiedModel },
        identity = { identity },
        selectedDevice = { selectedDevice },
        lastTest = { lastTest },
        status = { status },
        readReport = { readReport },
        readWasSimulated = { readWasSimulated },
        decodedCounters = { decodedCounters },
        counterSpecs = { counterSpecs },
        updateCounterSpecs = { counterSpecs = it },
        specsFor = { specsFor(it) },
        info = { info(it) },
        good = { good(it) },
        warn = { warn(it) },
    )

    val inspect = InspectState(
        scope = scope,
        io = io,
        database = { database },
        selectedDevice = { selectedDevice },
        openTransport = { device, isDry -> openTransport(device, isDry) },
        transportError = { transportError },
        specsFor = { specsFor(it) },
        otherOperationRunning = {
            runState == RunState.Running ||
                reading ||
                testing ||
                maintenance.running != null ||
                scanState is ScanState.Scanning
        },
        resetCancellation = { cancelFlag.set(false) },
        isCancelled = { cancelFlag.get() },
        updateProgress = { value, label ->
            progress = value
            progressLabel = label
        },
        trace = { trace(it) },
        info = { info(it) },
        good = { good(it) },
        warn = { warn(it) },
        bad = { bad(it) },
    )

    val snapshot = SnapshotState(
        scope = scope,
        io = io,
        backupDir = backupDir,
        database = { database },
        selectedModel = { selectedModel },
        selectedDevice = { selectedDevice },
        dryRun = { dryRun },
        familyBlockedReason = { familyBlockedReason },
        writeBlockedReason = { writeBlockedReason },
        busy = { busy },
        reading = { reading },
        readReport = { readReport },
        readWasSimulated = { readWasSimulated },
        status = { status },
        specsFor = { specsFor(it) },
        performRead = { model, device, isDry -> performRead(model, device, isDry) },
        selectModel = { selectModel(it) },
        updateQuery = { query = it },
        openSnapshotsTab = { tab = Tab.SNAPSHOTS },
        openTransport = { device, isDry -> openTransport(device, isDry) },
        transportError = { transportError },
        resetCancellation = { cancelFlag.set(false) },
        isCancelled = { cancelFlag.get() },
        startRun = { label ->
            runKind = RunKind.RESTORE
            completion = null
            runState = RunState.Running
            progress = 0f
            progressLabel = label
        },
        updateProgress = { value, label ->
            progress = value
            progressLabel = label
        },
        finishRun = { result, wasDryRun ->
            runState = RunState.Finished(result, wasDryRun)
            completion = Completion(RunKind.RESTORE, result, wasDryRun)
        },
        info = { info(it) },
        good = { good(it) },
        warn = { warn(it) },
        bad = { bad(it) },
        trace = { trace(it) },
    )

    val maintenance = MaintenanceState(
        scope = scope,
        io = io,
        selectedDevice = { selectedDevice },
        status = { status },
        otherOperationRunning = { busy },
        transports = transports,
        trace = { trace(it) },
        info = { info(it) },
        good = { good(it) },
        warn = { warn(it) },
        bad = { bad(it) },
    )

    /** Operation feedback rendered by the fixed status bar instead of shifting individual panels. */
    val globalProgressLabel: String?
        get() = progressLabel.takeIf { it.isNotBlank() }
            ?: maintenance.running?.let { "Sending ${it.label.lowercase()}…" }

    /** Null means that the active operation has no meaningful percentage and should be indeterminate. */
    val globalProgressValue: Float?
        get() = progress.takeIf { progressLabel.isNotBlank() }

    private val cancelFlag = AtomicBoolean(false)

    sealed interface ScanState {
        data object Idle : ScanState
        data object Scanning : ScanState
        data object Stopped : ScanState
        data object Done : ScanState
        data class LibraryMissing(val detail: String, val hint: String) : ScanState
        data class Failed(val message: String) : ScanState
    }

    sealed interface RunState {
        data object Idle : RunState
        data object Running : RunState
        data class Finished(val result: Executor.Result, val wasDryRun: Boolean) : RunState
    }

    /** Which operation the current [runState] belongs to. */
    enum class RunKind { RESET, RESTORE }

    /** Progress and log state is written from executor callbacks running on the IO thread. */
    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    enum class Level { INFO, GOOD, WARN, BAD, TRACE }

    data class LogLine(val time: String, val level: Level, val text: String)

    val searchResults: List<PrinterModel>
        get() = database?.search(query) ?: emptyList()

    enum class Tab { COUNTERS, MAINTENANCE, SNAPSHOTS, INSPECT, MODELS }

    enum class MatrixFilter(val label: String) {
        ALL("All"),
        RESETTABLE("Resettable"),
        PLATEN_ONLY("Platen only"),
        DECODED("Decoded values"),
        WITH_LIMIT("Has a limit"),
    }

    // Deriving a capability per model is cheap but not free, and the matrix asks for the list on
    // every recomposition.
    private var capabilityKey: Pair<PrinterDatabase?, CounterSpecs?>? = null
    private var capabilityCache: List<ModelCapability> = emptyList()

    val capabilities: List<ModelCapability>
        get() {
            val db = database ?: return emptyList()
            val specs = counterSpecs
            val key = db to specs
            if (capabilityKey != key) {
                capabilityCache = ModelCapabilities.of(db, specs)
                capabilityKey = key
            }
            return capabilityCache
        }

    val capabilitySummary: CapabilitySummary?
        get() = database?.let { ModelCapabilities.summarise(capabilities, it, counterSpecs) }

    /** Substring match, not the ranked search — the matrix is a table to scan, not a picker. */
    val matrixResults: List<ModelCapability>
        get() {
            val q = matrixQuery.trim()
            return capabilities.filter { c ->
                (q.isEmpty() || c.name.contains(q, ignoreCase = true)) && when (matrixFilter) {
                    MatrixFilter.ALL -> true
                    MatrixFilter.RESETTABLE -> c.canReset
                    MatrixFilter.PLATEN_ONLY -> c.scope == ResetScope.PLATEN_ONLY
                    MatrixFilter.DECODED -> c.values == ValueSupport.DECODED
                    MatrixFilter.WITH_LIMIT -> c.hasLimit
                }
            }
        }

    fun selectModelAndShowCounters(capability: ModelCapability) {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing the target.")
            return
        }
        selectModel(capability.model)
        query = capability.name
        tab = Tab.COUNTERS
    }

    private val printerOperationRunning: Boolean
        get() = runState == RunState.Running ||
            reading ||
            testing ||
            inspect.inspecting ||
            maintenance.running != null

    /** Operations cannot start during a scan, but the target menu remains usable so it can stop it. */
    private val busy: Boolean get() = printerOperationRunning || scanState is ScanState.Scanning

    /** A running operation owns the printer/model pair until its result has been published. */
    val canChangeTarget: Boolean get() = !printerOperationRunning

    val canScan: Boolean get() = !printerOperationRunning

    /** The selected model contradicts what the printer said it is, or null when they agree. */
    val modelMismatch: String?
        get() {
            val said = identifiedModel ?: return null
            val chosen = selectedModel ?: return null
            if (chosen.name.equals(said.name, ignoreCase = true)) return null
            return "This printer identifies itself as ${said.name}, but ${chosen.name} is " +
                "selected. A live run would write ${chosen.name}'s write key and addresses into " +
                "a ${said.name}."
        }

    /**
     * Why the printer's family has not been narrowed to one model. Unlike a mismatch, this blocks
     * reading too: without a model there is no layout and no read key to ask with.
     */
    val familyBlockedReason: String?
        get() = pendingClass?.let {
            "This printer reports itself as \"${it.reported}\", which names a family of " +
                "${it.candidates.size} models that do not share a reset recipe. Choose the " +
                "model printed on the printer."
        }

    /** Why the model half of the current target is not settled, independent of transport limits. */
    val targetModelBlockedReason: String?
        get() = familyBlockedReason ?: modelMismatch

    /** Why a live write to the selected printer is not allowed, or null when it is. */
    val writeBlockedReason: String?
        get() {
            // Ahead of the link question, and on both links: writing one model's bytes into
            // another is wrong over USB for exactly the same reason it is wrong over SNMP.
            targetModelBlockedReason?.let { return it }

            val selected = selectedDevice ?: return null
            if (!selected.device.reachable) {
                return "The selected printer was not reached. Rescan or test its connection " +
                    "before using live mode."
            }

            if (!selected.device.isNetwork) return null

            val test = lastTest ?: return null
            if (!test.overNetwork || test.reach != ConnectionTest.Reach.STATUS_ONLY) return null

            return test.refusal
                ?: "This printer answered identity and status over the network but refused " +
                "counter access. Connect it over USB to reset."
        }

    val canRun: Boolean
        get() = !busy &&
            selectedModel?.hasResettableCounters == true &&
            (dryRun || (selectedDevice?.device?.reachable == true && writeBlockedReason == null))

    /**
     * Reading carries only the read key and never writes, so the write-only blocks — above all a
     * model mismatch — do not apply: reading a printer the descriptor named a different unit for is
     * exactly how you find out which it is. It still needs a settled model (for the layout and read
     * key) and, in Live, a printer that was reached; the family question must be answered first.
     */
    val canRead: Boolean
        get() = !busy &&
            selectedModel?.hasResettableCounters == true &&
            pendingClass == null &&
            (dryRun || selectedDevice?.device?.reachable == true)

    /** Switches preview sources without carrying fake values into Live or live values into Dry run. */
    fun changeDryRunMode(value: Boolean) {
        if (dryRun == value) return
        dryRun = value
        readReport = null
        beforeReport = null
        readWasSimulated = false
        counterByteStates = emptyMap()
        if (runState is RunState.Finished) runState = RunState.Idle
        calibration.resetForm()
    }

    /**
     * Startup sequence. The scan has to wait for the database, or matching runs against nothing and
     * reports a connected printer as unknown.
     */
    fun start() {
        scope.launch {
            loadDatabaseNow()
            // Before the scan, because a current reading can offer a comparison the moment it lands
            // and that offer depends on knowing what is already on disk. Reading a directory is
            // cheap;
            snapshot.refreshNow()
            scanNow(beginScan())
        }
    }

    fun loadDatabase() {
        scope.launch { loadDatabaseNow() }
    }

    private suspend fun loadDatabaseNow() {
        run {
            val loaded = withContext(io) { runCatching { PrinterDatabase.load() } }
            loaded.onSuccess {
                database = it
                info("Loaded ${it.size} printer models (${it.source.name.lowercase()}).")

                val specs = withContext(io) { runCatching { CounterSpecs.load() } }
                specs.onSuccess { s ->
                    counterSpecs = s
                    info("Counter layouts for ${s.modelCount} models.")
                    if (s.overlayLoaded) info("User overlay applied from ${AppPaths.counterOverlay}.")
                    s.overlayError?.let { e -> warn("Overlay ignored — $e") }
                }.onFailure { e -> warn("Counter layouts unavailable: ${e.message}") }
            }.onFailure {
                databaseError = it.message ?: it.toString()
                bad("Could not load the printer database: ${it.message}")
            }
        }
    }

    /**
     * How the last database download went, for the window that started it. The log has it too, but
     * a result that only exists behind another panel is a result the person who asked did not get.
     */
    var databaseUpdateStatus by mutableStateOf<Outcome?>(null)
        private set

    /** A short result to show where the action was taken. */
    data class Outcome(val text: String, val ok: Boolean)

    fun refreshDatabaseFromNetwork() {
        scope.launch {
            databaseUpdateStatus = Outcome("Downloading…", ok = true)
            info("Downloading the latest printer database…")
            val result = withContext(io) {
                runCatching {
                    val text = java.net.URI(PrinterDatabase.OTA_URL).toURL().readText()
                    // Parse before writing, so a truncated download can't replace a good cache.
                    val parsed = PrinterDatabase.parse(text, PrinterDatabase.Source.CACHED)
                    require(parsed.models.isNotEmpty()) { "downloaded database contained no models" }
                    AppPaths.database.writeText(text)
                    parsed
                }
            }
            result.onSuccess {
                database = it
                databaseUpdateStatus = Outcome("Updated — ${it.size} models.", ok = true)
                good("Database updated — ${it.size} models.")
            }.onFailure {
                // Plain here, specific in the log. What went wrong is usually a URL and an HTTP
                // code, which is the maintainer's problem rather than something the reader can act
                // on — and the one thing they do need to know is that nothing was lost.
                databaseUpdateStatus =
                    Outcome("Could not download the database. Keeping the current copy.", ok = false)
                warn("Database update failed: ${it.message}. Keeping the current copy.")
            }
        }
    }

    fun scan() {
        if (scanState is ScanState.Scanning) {
            stopScan()
            return
        }
        if (!canScan) {
            warn("Wait for the current printer operation to finish before scanning again.")
            return
        }
        val generation = beginScan()
        scope.launch { scanNow(generation) }
    }

    private var scanGeneration = 0L

    private fun beginScan(): Long {
        scanGeneration += 1
        return scanGeneration
    }

    fun stopScan() = stopScan(report = true)

    private fun stopScan(report: Boolean) {
        if (scanState !is ScanState.Scanning) return
        scanGeneration += 1
        scanState = ScanState.Stopped
        if (report) info("Printer scan stopped.")
    }

    /** Scans both buses. */
    private suspend fun scanNow(generation: Long): Boolean {
        if (generation != scanGeneration) return false
        scanState = ScanState.Scanning
        val db = database
        val discovery = withContext(io) { discover() }
        if (generation != scanGeneration) return false
        savedNetworkPrinters = withContext(io) {
            loadSavedPrinters().map { it.link }.toSet()
        }
        if (generation != scanGeneration) return false

        val matched =
            if (db != null) {
                DeviceMatcher.matchAll(discovery.printers, db)
            } else {
                discovery.printers.map { MatchedPrinter(it, null, MatchedPrinter.Confidence.NONE) }
            }

        devices.clear()
        devices.addAll(matched)

        usbNote = when (val usb = discovery.usb) {
            is UsbPrinterScanner.ScanResult.Ok -> null
            is UsbPrinterScanner.ScanResult.LibraryMissing -> "libusb is not installed — ${usb.hint}"
            is UsbPrinterScanner.ScanResult.Failed -> "USB scan failed — ${usb.message}"
        }

        networkNote = when (val net = discovery.network) {
            is PrinterDiscovery.NetworkOutcome.Ok -> null
            is PrinterDiscovery.NetworkOutcome.Unavailable -> "${net.detail} ${net.hint}"
            PrinterDiscovery.NetworkOutcome.Skipped -> null
        }

        // The scan-wide states still exist for the case they were written for — nothing found and
        // a reason for it. With printers on the list, a source that failed is a footnote.
        scanState = when {
            matched.isNotEmpty() -> ScanState.Done
            discovery.usb is UsbPrinterScanner.ScanResult.LibraryMissing ->
                (discovery.usb as UsbPrinterScanner.ScanResult.LibraryMissing).let {
                    ScanState.LibraryMissing(it.detail, it.hint)
                }
            discovery.usb is UsbPrinterScanner.ScanResult.Failed ->
                ScanState.Failed((discovery.usb as UsbPrinterScanner.ScanResult.Failed).message)
            else -> ScanState.Done
        }

        usbNote?.let { warn("$it Dry runs still work.") }
        networkNote?.let { warn("Network discovery unavailable — $it") }

        val previousSelection = selectedDevice
        val refreshedSelection = previousSelection?.let { previous ->
            matched.firstOrNull { it.device.id == previous.device.id }
        }
        if (previousSelection != null && refreshedSelection == null) {
            clearSelectedTarget()
        } else {
            selectedDevice = refreshedSelection
        }
        refreshIdentity()

        if (matched.isEmpty()) {
            warn("No Epson printers found on USB or the network.")
        } else {
            val usbCount = matched.count { !it.device.isNetwork }
            val netCount = matched.size - usbCount
            info("Found ${matched.size} Epson device(s) — $usbCount on USB, $netCount on the network.")
            if (selectedDevice == null) matched.singleOrNull()?.let { select(it) }
        }
        return true
    }

    private fun clearSelectedTarget() {
        selectedDevice = null
        lastTest = null
        status = null
        printerMib = null
        readReport = null
        beforeReport = null
        readWasSimulated = false
        counterByteStates = emptyMap()
        identity = null
        pendingClass = null
        manualModelRequested = false
        modelSelectionVisible = false
        selectedModel = null
        query = ""
        history.clearSelection()
    }

    fun select(device: MatchedPrinter) {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing printers.")
            return
        }
        stopScan(report = false)
        selectedDevice = device
        lastTest = null
        // These all belong to the printer that was selected before this one, not to this one. In
        // particular, never let an unmatched printer inherit the previous printer's model.
        status = null
        printerMib = null
        readReport = null
        beforeReport = null
        readWasSimulated = false
        counterByteStates = emptyMap()
        identity = null
        pendingClass = null
        manualModelRequested = false
        modelSelectionVisible = false
        selectedModel = null
        query = ""
        refreshIdentity()
        device.model?.let {
            stageModel(it)
            val how = when (device.confidence) {
                MatchedPrinter.Confidence.EXACT -> "matched exactly"
                MatchedPrinter.Confidence.LIKELY -> "matched (likely — please confirm)"
                MatchedPrinter.Confidence.CLASS_ONLY -> "matched to a family, not to a unit"
                MatchedPrinter.Confidence.NONE -> "unmatched"
            }
            info("${device.device.displayName} on ${device.device.link.kind} → ${it.name} ($how).")
        } ?: warn(
            "${device.device.displayName} did not match any database entry — pick the model manually.",
        )

        if (device.confidence == MatchedPrinter.Confidence.CLASS_ONLY) {
            classReported(device.device.product.orEmpty(), device.candidates)
        } else if (device.confidence != MatchedPrinter.Confidence.EXACT) {
            modelSelectionVisible = true
        }

        noteCrossCheck(device)
    }

    /**
     * The other link's answer, once it is established that both links are the same printer.
     *
     * Usually it is free money: the descriptor named a family, SNMP named the unit inside it, and
     * they agree on every byte a reset writes. Where they *don't* agree the borrowed name is no
     * longer a refinement but a contradiction, and settling it silently in favour of either one
     * would be writing a key on the strength of a guess about which link is lying.
     */
    private fun noteCrossCheck(device: MatchedPrinter) {
        val cross = device.device.crossCheck ?: return
        val db = database ?: return
        val fromPeer = DeviceMatcher.resolve(cross.name, db).model ?: return
        val fromDescriptor = DeviceMatcher.resolve(device.device.product, db).model

        if (fromDescriptor != null && ModelClass.recipeOf(fromDescriptor) != ModelClass.recipeOf(fromPeer)) {
            identity = null
            modelSelectionVisible = true
            warn(
                "This printer's descriptor matches ${fromDescriptor.name}, but the same serial " +
                    "answers SNMP at ${cross.link.where} as ${fromPeer.name} — and the two do not " +
                    "write the same bytes (rkey ${fromDescriptor.readKey} against " +
                    "${fromPeer.readKey}). Pick the one on the label before running live.",
            )
            return
        }

        identity = Identity(fromPeer, Identity.Via.SNMP_CROSS_LINK, cross.name)
        stageModel(fromPeer)
        pendingClass = null
        manualModelRequested = false
        modelSelectionVisible = false
        info(
            "The same serial answers SNMP at ${cross.link.where} as ${fromPeer.name}. Using that: " +
                "the descriptor says only \"${device.device.product}\", which names a family.",
        )
    }

    /**
     * The printer would only name its family. Either we were told which member it is once already,
     * or we have to ask — and until it is answered a live run has no business proceeding, because
     * the entry the family name resolved to is a guess wearing an exact match's clothes.
     */
    private fun classReported(reported: String, candidates: List<PrinterModel>) {
        if (candidates.isEmpty()) return

        rememberedModel(reported)?.let { remembered ->
            pendingClass = null
            identity = Identity(remembered, Identity.Via.CONFIRMED, reported)
            stageModel(remembered)
            modelSelectionVisible = false
            info("\"$reported\" names a family; this printer was confirmed as ${remembered.name} before.")
            return
        }

        identity = null
        pendingClass = PendingClass(reported, candidates)
        modelSelectionVisible = true
        selectedModel = null
        query = ""
        warn(
            "This printer reports \"$reported\", which covers ${candidates.size} models that do not " +
                "share a reset recipe. Pick the model printed on the printer itself — another " +
                "member's key would be written to this one otherwise.",
        )
    }

    /**
     * Keys this printer could have been remembered under, best first. The serial outlives both a
     * DHCP lease and a change of USB port, which is exactly what the connection does not.
     */
    private val printerKeys: List<String>
        get() {
            val reported = (selectedDevice?.device?.serial ?: lastTest?.serial)?.takeIf { it.isNotBlank() }
            return listOfNotNull(
                // Canonical first, so a choice made on one link is found from the other. The raw
                // form follows it, because pins written before serials were canonicalised are
                // filed under whatever the descriptor happened to say.
                Serials.canonical(reported),
                reported,
                selectedDevice?.device?.id,
            ).distinct()
        }

    private fun rememberedModel(reported: String): PrinterModel? {
        val db = database ?: return null
        val name = ModelChoices.lookup(choicesFile(), printerKeys, reported) ?: return null
        return db[name]
    }

    /**
     * Re-derives the identity from the selected printer — from the scan, which is the descriptor a
     * USB device carries or the name a network one advertises. Neither is SNMP: that only happens
     * when a connection test actually asks, in [reportTest].
     */
    private fun refreshIdentity() {
        val device = selectedDevice ?: run {
            identity = null
            return
        }
        val fromScan = device.model.takeIf { device.confidence == MatchedPrinter.Confidence.EXACT }
            ?: return

        identity = Identity(
            model = fromScan,
            via = if (device.device.isNetwork) {
                Identity.Via.NETWORK_ADVERT
            } else {
                Identity.Via.USB_DESCRIPTOR
            },
            reported = device.device.product.orEmpty(),
        )
        stageModel(fromScan)
        pendingClass = null
        manualModelRequested = false
    }

    /** Adds a printer by address, then immediately asks it what it is. */
    fun addNetworkPrinter() {
        val link = NetworkAddress.parse(networkAddressInput) ?: run {
            warn("'$networkAddressInput' is not an address or hostname.")
            return
        }
        if (printerOperationRunning) {
            warn("Wait for the current printer operation to finish before adding a printer.")
            return
        }
        stopScan(report = false)

        scope.launch {
            networkAddressInput = ""
            info("Added ${link.where}. Asking what is there…")
            withContext(io) { addSavedPrinter(SavedPrinters.Saved(link)) }

            // Listed and selected before the probe, not after.
            if (!scanNow(beginScan())) return@launch
            val added = devices.firstOrNull { it.device.link == link }
            added?.let { select(it) }

            testing = true
            try {
                val probe = withContext(io) {
                    connectionTest(added?.device ?: DetectedPrinter(link), selectedModel)
                }
                reportTest(probe, link)
            } finally {
                testing = false
            }
        }
    }

    /** Drops a hand-added address. Discovered printers come back on the next scan regardless. */
    fun forgetNetworkPrinter(printer: MatchedPrinter) {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before forgetting a printer.")
            return
        }
        stopScan(report = false)
        val link = printer.device.link as? Link.Network ?: return
        scope.launch {
            val remaining = withContext(io) { removeSavedPrinter(link) }
            savedNetworkPrinters = remaining.map { it.link }.toSet()
            devices.removeAll { it.device.link == link }
            if (selectedDevice?.device?.link == link) clearSelectedTarget()
            info("Forgot ${link.where}.")
        }
    }

    /** Checks the selected printer answers, without writing. */
    fun testConnection() {
        if (!canTestConnection) {
            warn("Wait for the current printer operation to finish before testing the connection.")
            return
        }
        stopScan(report = false)
        val device = selectedDevice ?: return
        testing = true

        scope.launch {
            try {
                info("Testing ${device.device.displayName} on ${device.device.link.kind}…")
                val result = withContext(io) { connectionTest(device.device, selectedModel) }
                reportTest(result, device.device.link as? Link.Network)
            } finally {
                testing = false
            }
        }
    }

    /** Logs a test result and keeps what it taught us. */
    private suspend fun reportTest(result: ConnectionTest.Result, link: Link.Network?) {
        lastTest = result
        result.status?.let { status = it }

        // A saved address starts as merely remembered. A connection test is fresh evidence either
        // way, so keep both the list and the app-wide target chip honest without requiring a rescan.
        selectedDevice?.takeIf { it.device.link == link }?.let { selected ->
            val reachable = result.opened && (result.answered || result.identity != null || result.status != null)
            val updated = selected.copy(
                device = selected.device.copy(
                    accessNote = if (reachable) null else "Saved address did not answer the connection test.",
                    reachable = reachable,
                ),
            )
            val index = devices.indexOfFirst { it.device.id == selected.device.id }
            if (index >= 0) devices[index] = updated
            selectedDevice = updated
        }

        if (result.usable) good(result.headline) else warn(result.headline)
        result.advice?.let { info(it) }
        result.model?.let { info("Reports itself as: $it") }
        result.firmware?.let { info("Firmware: $it") }
        result.serial?.let { info("Serial: $it") }

        val reported = result.model
        if (reported != null && link != null) {
            withContext(io) { addSavedPrinter(SavedPrinters.Saved(link, reported)) }
        }

        // An identified printer should land on its database entry the same way a USB one does,
        // rather than leaving the user to retype a name the printer just told us. The two halves
        // are separate on purpose.
        val db = database
        if (reported != null && db != null) {
            val resolution = DeviceMatcher.resolve(reported, db)
            if (resolution.confidence == MatchedPrinter.Confidence.EXACT) {
                resolution.model?.let {
                    // Where the answer came from, not where answers usually come from: over the
                    // network this is the Epson MIB, and over USB it is an EJL query on the
                    // channel — SNMP is not involved in the second at all.
                    val via =
                        if (result.overNetwork) Identity.Via.SNMP else Identity.Via.USB_EJL
                    identity = Identity(it, via, reported)
                    stageModel(it)
                    pendingClass = null
                    manualModelRequested = false
                    modelSelectionVisible = false
                    info("Matched to ${it.name} from what the printer reported.")
                }
            }
            if (resolution.confidence == MatchedPrinter.Confidence.CLASS_ONLY) {
                classReported(reported, resolution.candidates)
            }
            // Not while a family is outstanding: filling the selection in with one member and
            // saying it was matched is the sentence the prompt exists to stop being said.
            if (resolution.confidence != MatchedPrinter.Confidence.EXACT &&
                selectedModel == null && pendingClass == null
            ) {
                resolution.model?.let {
                    stageModel(it)
                    info("Matched to ${it.name} from what the printer reported.")
                }
            }
        }
    }

    private fun stageModel(model: PrinterModel) {
        val changed = selectedModel?.name != model.name
        selectedModel = model
        query = model.name
        if (changed) {
            readReport = null
            beforeReport = null
            readWasSimulated = false
            counterByteStates = emptyMap()
        }
        // No device selected means nothing to show; a selected device with no serial still opens the
        // panel, where select() explains why its reads cannot be joined.
        val device = selectedDevice
        if (device == null) {
            history.clearSelection()
        } else {
            history.select(identifyingSerial(device), model.name)
        }
    }

    fun selectModel(model: PrinterModel): Boolean {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing models.")
            return false
        }
        stopScan(report = false)
        stageModel(model)

        val pending = pendingClass
        val settled = confirmedClass
        when {
            pending != null -> confirmClass(pending.reported, model)
            // Changing a hand-made answer re-answers the same question. Left alone it would instead
            // read as a mismatch against a name the printer never actually claimed.
            settled != null && !model.name.equals(identifiedModel?.name, ignoreCase = true) ->
                confirmClass(settled, model)
        }

        if (runState is RunState.Finished) runState = RunState.Idle
        modelSelectionVisible = false
        // Every other decision that could end badly is in the log; this one belongs there too.
        modelMismatch?.let { warn("$it Dry runs still work; a live run will refuse.") }
        return true
    }

    /** The user settled which member of a family this printer is. Keep it, so it is asked once. */
    private fun confirmClass(reported: String, model: PrinterModel) {
        pendingClass = null
        identity = Identity(model, Identity.Via.CONFIRMED, reported)
        manualModelRequested = false
        modelSelectionVisible = false
        query = model.name

        val key = printerKeys.firstOrNull()
        if (key == null) {
            info(
                "Using ${model.name} for this printer, for this session — nothing identifies it " +
                    "well enough to remember the choice.",
            )
            return
        }

        scope.launch {
            withContext(io) { ModelChoices.pin(choicesFile(), ModelChoices.Choice(key, reported, model.name)) }
            info("Remembered: the printer at $key reporting \"$reported\" is a ${model.name}.")
        }
    }

    /** Drops a remembered answer and asks again — for when the wrong member was confirmed. */
    fun forgetModelChoice() {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing the target.")
            return
        }
        val reported = confirmedClass ?: return
        val db = database ?: return
        val key = printerKeys.firstOrNull()

        identity = null
        selectedModel = null
        query = ""
        readReport = null
        beforeReport = null
        readWasSimulated = false
        counterByteStates = emptyMap()
        scope.launch {
            if (key != null) withContext(io) { ModelChoices.forget(choicesFile(), key) }
            val candidates = DeviceMatcher.resolve(reported, db).candidates
            if (candidates.isEmpty()) {
                info("Forgot the choice for this printer.")
                return@launch
            }
            pendingClass = PendingClass(reported, candidates)
            modelSelectionVisible = true
            info("Forgot the choice for this printer. It reports \"$reported\" — pick again.")
        }
    }

    /** Puts back the model the last session ended on. */
    fun restoreModel(model: PrinterModel) {
        if (!canChangeTarget) return
        if (selectedModel != null || identifiedModel != null) return
        stageModel(model)
    }

    /** Puts the selection back on what the printer said it was, and re-locks the picker. */
    fun useIdentifiedModel() {
        if (!canChangeTarget) {
            warn("Wait for the current printer operation to finish before changing the target.")
            return
        }
        val model = identifiedModel ?: return
        manualModelRequested = false
        modelSelectionVisible = false
        if (selectedModel?.name != model.name) {
            stageModel(model)
            if (runState is RunState.Finished) runState = RunState.Idle
            info("Back to ${model.name}, which is what this printer reports itself as.")
        }
    }

    fun cancel() {
        cancelFlag.set(true)
        warn("Cancelling after the current packet…")
    }

    /** Samples the model's counter addresses without writing anything. */
    fun readCounters() {
        if (busy) {
            warn("Wait for the current printer operation to finish before reading counters.")
            return
        }
        val model = selectedModel ?: return
        val device = selectedDevice
        val isDry = dryRun

        scope.launch { performRead(model, device, isDry) }
    }

    /** The read itself, so the Snapshot tab can ask for one without duplicating it. */
    private suspend fun performRead(model: PrinterModel, device: MatchedPrinter?, isDry: Boolean) {
        cancelFlag.set(false)
        reading = true
        beforeReport = null
        readReport = CounterReader.layout(model, specsFor(model))
        readWasSimulated = isDry
        counterByteStates = readReport?.readings.orEmpty().associate { it.address to CounterByteState.PENDING }
        progress = 0f
        progressLabel = "Reading counters…"

        val listener = object : CounterReader.Listener {
            override fun onProgress(done: Int, total: Int, address: Int) = onMain {
                progress = done.toFloat() / total
                progressLabel = "Reading address $address ($done / $total)"
                val current = counterByteStates[address]
                if (current != CounterByteState.READ && current != CounterByteState.FAILED) {
                    counterByteStates = counterByteStates + (address to CounterByteState.READING)
                }
            }

            override fun onReading(reading: CounterReader.Reading) = onMain {
                val current = counterByteStates[reading.address]
                if (current == CounterByteState.READ || current == CounterByteState.FAILED) return@onMain
                readReport = readReport?.copy(
                    readings = readReport?.readings.orEmpty().map { existing ->
                        if (existing.address == reading.address) reading else existing
                    },
                )
                val next = if (reading.value != null) CounterByteState.READ else CounterByteState.FAILED
                counterByteStates = counterByteStates + (reading.address to next)
            }

            override fun onTrace(line: String) = onMain { trace(line) }
        }

        var read: Status.Report? = null
        val report = withContext(io) {
            openTransport(device, isDry).use { transport ->
                transport?.let {
                    val counters =
                        CounterReader.readAll(it, model, specsFor(model), listener) { cancelFlag.get() }
                    // Reuse the channel the counter read already opened.
                    read = CounterReader.readStatus(it, listener)
                    counters
                }
            } ?: CounterReader.Report(model.name, emptyList(), transportError)
        }

        readReport = report
        readWasSimulated = isDry
        val finalReadings = report.readings.associateBy { it.address }
        counterByteStates = counterByteStates.mapValues { (address, state) ->
            finalReadings[address]?.let { if (it.value != null) CounterByteState.READ else CounterByteState.FAILED }
                ?: state.takeIf { it == CounterByteState.PENDING } ?: CounterByteState.FAILED
        }
        // A percentage typed against the previous reading would silently pair with this one.
        calibration.resetForm()
        status = read
        if (!isDry) history.acceptLive(report, identifyingSerial(device, read?.serial))
        read?.let { s ->
            s.inkLevels.takeIf { it.isNotEmpty() }?.let { levels ->
                info("Ink: " + levels.joinToString(", ") { "${it.colour} ${it.percent}%" })
            }
        }

        // The standard Printer-MIB is SNMP, so it needs a network address — the printer's own, or the
        // serial-matched network twin a USB unit was cross-checked against (the same peer it borrows
        // its model name from). Never on a dry run, which has no host to ask. It uses the same GET
        // path the rest of the app does (port 161, community "public"), not the passthrough above.
        val snmpHost = device?.device?.snmpLink?.host?.takeIf { !isDry }
        printerMib = snmpHost?.let { host ->
            withContext(io) { PrinterMib.read(host) }
        }
        printerMib?.lifeCount?.let { info("Lifetime pages: $it") }

        reading = false
        progress = 0f
        progressLabel = ""

        describe(report, isDry)
    }

    private fun describe(report: CounterReader.Report, isDry: Boolean) {
        when {
            report.error != null -> bad("Read failed: ${report.error}")
            report.answered == 0 ->
                bad("The printer did not answer any of the ${report.total} read requests.")
            report.answered < report.total ->
                warn("Read ${report.answered} of ${report.total} addresses; the rest did not answer.")
            report.allAtResetValue && !isDry ->
                good("Read ${report.answered} addresses — all already at their reset values.")
            else -> good("Read ${report.answered} of ${report.total} addresses.")
        }
        if (isDry) info("DRY RUN — values came from a simulated EEPROM, not your printer.")
    }

    fun run() {
        if (busy) {
            warn("Wait for the current printer operation to finish before starting a reset.")
            return
        }
        val model = selectedModel ?: return
        val device = selectedDevice
        val isDry = dryRun

        // Enforced here as well as in canRun: the button being disabled is a hint, not a gate.
        if (!isDry) {
            writeBlockedReason?.let {
                bad(it)
                return
            }
        }

        scope.launch {
            cancelFlag.set(false)
            runKind = RunKind.RESET
            completion = null
            runState = RunState.Running
            progress = 0f
            progressLabel = "Generating sequence…"

            val sequence = SequenceGenerator.generate(model)
            counterByteStates = sequence.mapNotNull(Executor::writePacketTarget)
                .associate { (address, _) -> address to CounterByteState.PENDING }
            info(
                "Generated ${sequence.size} packets for ${model.name} " +
                    "(${model.writeCount} EEPROM writes).",
            )
            if (isDry) {
                info("DRY RUN — nothing will be written to the printer.")
                modelMismatch?.let { warn("$it A live run would refuse.") }
            }

            val listener = object : Executor.Listener {
                override fun onPacket(index: Int, total: Int, message: String) = onMain {
                    progress = index.toFloat() / total
                    progressLabel = "Packet $index / $total — $message"
                }

                override fun onWrite(address: Int, value: Int, state: Executor.WriteState) = onMain {
                    val current = counterByteStates[address]
                    if (current == CounterByteState.VERIFIED || current == CounterByteState.FAILED) return@onMain
                    val next = when (state) {
                        Executor.WriteState.WRITING -> CounterByteState.WRITING
                        Executor.WriteState.ACKNOWLEDGED -> CounterByteState.ACKNOWLEDGED
                        Executor.WriteState.FAILED -> CounterByteState.FAILED
                    }
                    counterByteStates = counterByteStates + (address to next)
                }

                override fun onTrace(line: String) = onMain { trace(line) }
            }

            // One connection for the whole read-reset-read cycle: re-opening between phases would
            // mean claiming the interface three times, and the before/after pair has to come from
            // the same session to be worth comparing.
            var before: CounterReader.Report? = null
            var after: CounterReader.Report? = null
            var sampledStatus: Status.Report? = null

            var backupFile: File? = null

            val result = withContext(io) {
                openTransport(device, isDry).use { transport ->
                    transport?.let {
                        onMain { progressLabel = "Reading counters before reset…" }
                        before = CounterReader.readAll(it, model, specsFor(model)) { cancelFlag.get() }
                        onMain {
                            beforeReport = before
                            readReport = before
                            readWasSimulated = isDry
                        }

                        // A read that failed for a reason the printer gave — a refusal, most likely
                        // — is reported as that reason.
                        val readFailure = before?.error
                        if (readFailure != null) return@let Executor.Result(error = readFailure)

                        // Ask who this is while the channel is open.
                        val sampled = CounterReader.readStatus(it)
                        sampledStatus = sampled
                        onMain { sampled?.let { s -> status = s } }

                        // The same block answers a second question worth asking before the first
                        // write: what is the printer doing right now.
                        val blocker = if (isDry) null else sampled?.writeBlocker
                        if (blocker != null) {
                            return@let Executor.Result(
                                error = "$blocker Clear it and run again — nothing was written.",
                            )
                        }

                        // Snapshot before the first write lands, not after the run finishes: a
                        // backup that only survives a clean run is useless for the partial write it
                        // exists to cover.
                        onMain { progressLabel = "Backing up the bytes about to be written…" }
                        val capture = EepromBackup.capture(
                            model = model.name,
                            sequence = sequence,
                            readings = before?.readings.orEmpty(),
                            printerSerial = identifyingSerial(device, sampled?.serial),
                        )

                        when (val outcome = prepareBackup(capture, isDry)) {
                            is BackupOutcome.Blocked -> return@let Executor.Result(error = outcome.reason)
                            is BackupOutcome.Saved -> backupFile = outcome.file
                        }

                        val executed = Executor.execute(
                            transport = it,
                            sequence = sequence,
                            listener = listener,
                            isCancelled = { cancelFlag.get() },
                        )

                        if (executed.success) {
                            onMain { progressLabel = "Verifying…" }
                            after = CounterReader.readAll(it, model, specsFor(model)) { cancelFlag.get() }
                        }
                        executed
                    }
                } ?: Executor.Result(error = transportError)
            }

            progress = 1f
            progressLabel = ""
            beforeReport = before
            readReport = after ?: before
            readWasSimulated = isDry
            if (result.success) {
                val verifiedByAddress = after?.readings?.associateBy { it.address }.orEmpty()
                counterByteStates = counterByteStates.mapValues { (address, _) ->
                    if (verifiedByAddress[address]?.isAtResetValue == true) {
                        CounterByteState.VERIFIED
                    } else {
                        CounterByteState.FAILED
                    }
                }
            }
            val verificationFailure = if (!result.success) {
                null
            } else {
                when {
                    after == null -> "Writes were acknowledged, but the counters could not be read back."
                    after?.error != null -> "Writes were acknowledged, but read-back failed: ${after?.error}"
                    after?.answered != after?.total ->
                        "Writes were acknowledged, but only ${after?.answered}/${after?.total} addresses were read back."
                    after?.allAtResetValue != true ->
                        "Writes were acknowledged, but read-back found values that did not reset."
                    else -> null
                }
            }
            val finalResult = verificationFailure?.let { result.copy(success = false, error = it) } ?: result
            if (!isDry) {
                before?.let { history.acceptLive(it, identifyingSerial(device, sampledStatus?.serial)) }
                after?.let { history.acceptLive(it, identifyingSerial(device, sampledStatus?.serial)) }
            }
            // The counters have just been zeroed, so nothing on this reading measures a maximum.
            calibration.resetForm()
            lastBackup = backupFile
            runState = RunState.Finished(finalResult, isDry)
            completion = Completion(RunKind.RESET, finalResult, isDry)

            reportVerification(before, after)

            // A run that stopped partway is the case the backup was taken for, so the filename goes
            // in front of the user rather than into a log they have to scroll.
            if (!finalResult.success && backupFile != null && finalResult.writesAcknowledged > 0) {
                warn(
                    "${finalResult.writesAcknowledged} write(s) were acknowledged before this stopped. The pre-write " +
                        "bytes are saved in ${backupFile?.name} — restore it to put them back.",
                )
            }

            when {
                finalResult.success && isDry ->
                    good(
                        "Dry run complete — ${finalResult.writesTotal} writes generated and verified against the fake device.",
                    )
                finalResult.success -> {
                    good("Reset complete — all ${finalResult.writesTotal} EEPROM values verified by read-back.")
                    warn("Power-cycle the printer now to finalise the change.")
                }
                else -> bad(finalResult.error.ifBlank { "The reset did not complete." })
            }
        }
    }

    /** The serial to stamp a backup with, or check one against. */
    private fun identifyingSerial(device: MatchedPrinter?, fresh: String? = null): String? =
        fresh ?: status?.serial ?: device?.device?.serial

    private sealed interface BackupOutcome {
        /** [file] is null on a dry run, where nothing is written so nothing needs saving. */
        data class Saved(val file: File?) : BackupOutcome
        data class Blocked(val reason: String) : BackupOutcome
    }

    /** Saves the pre-write snapshot, or refuses the run. */
    private fun prepareBackup(capture: Capture, isDry: Boolean): BackupOutcome = when (capture) {
        is Capture.NothingToWrite ->
            BackupOutcome.Blocked("The sequence contains no EEPROM write packets — nothing was reset.")

        is Capture.Incomplete -> {
            val shown = capture.missing.take(8).joinToString(", ")
            val more = if (capture.missing.size > 8) " +${capture.missing.size - 8} more" else ""
            if (isDry) {
                onMain {
                    warn(
                        "Dry run: ${capture.missing.size} address(es) did not answer, so a real run would stop here ($shown$more).",
                    )
                }
                BackupOutcome.Saved(null)
            } else {
                BackupOutcome.Blocked(
                    "Stopped before writing anything: ${capture.missing.size} of the addresses this " +
                        "reset would write could not be read back, so they cannot be backed up " +
                        "($shown$more). Reads are unprivileged and safe to retry.",
                )
            }
        }

        is Capture.Ready ->
            if (isDry) {
                onMain {
                    info("DRY RUN — ${capture.backup.entries.size} addresses would be backed up; no file written.")
                }
                BackupOutcome.Saved(null)
            } else {
                runCatching { capture.backup.save(backupDir()) }.fold(
                    onSuccess = { file ->
                        onMain {
                            good(
                                "Backed up ${capture.backup.entries.size} addresses to ${file.name} " +
                                    "(${capture.backup.changedByReset} will change).",
                            )
                        }
                        BackupOutcome.Saved(file)
                    },
                    onFailure = { e ->
                        BackupOutcome.Blocked(
                            "Stopped before writing anything: the backup could not be saved " +
                                "(${e.message ?: e::class.simpleName}).",
                        )
                    },
                )
            }
    }

    /**
     * Did the bytes actually change? A printer can acknowledge every
     * write and still not have committed them, so the read-back is the real proof.
     */
    private fun reportVerification(before: CounterReader.Report?, after: CounterReader.Report?) {
        if (after == null || after.readings.isEmpty()) return

        if (after.answered == 0) {
            warn("Could not read the counters back, so the reset is unverified.")
            return
        }

        val changed = if (before == null) {
            emptyList()
        } else {
            val old = before.readings.associate { it.address to it.value }
            after.readings.filter { it.value != null && old[it.address] != it.value }
        }

        if (after.allAtResetValue) {
            good(
                "Verified by read-back: all ${after.answered} addresses now hold their reset values (${changed.size} changed).",
            )
        } else {
            val stuck = after.readings.filter { it.value != null && !it.isAtResetValue }
            warn(
                "Read-back shows ${stuck.size} address(es) not at the reset value: " +
                    stuck.take(6).joinToString(", ") { "${it.address}=${it.hex}" },
            )
        }
    }

    private var transportError: String = "Could not open the printer."

    /** Null means opening failed; [transportError] carries the reason. */
    private fun openTransport(device: MatchedPrinter?, isDry: Boolean): Transport? {
        if (isDry) return FakeTransport()

        val target = device?.device ?: run {
            transportError = "No printer selected."
            return null
        }

        return when (val opened = transports(target)) {
            is PrinterTransports.OpenResult.Ok -> opened.transport
            is PrinterTransports.OpenResult.Failed -> {
                transportError = opened.detail
                null
            }
        }
    }

    /** `use` over a nullable transport, so the open-failure path stays a single expression. */
    private inline fun <T> Transport?.use(block: (Transport?) -> T?): T? = try {
        block(this)
    } finally {
        this?.close()
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun add(level: Level, text: String) {
        log += LogLine(LocalDateTime.now().format(clock), level, text)
        if (log.size > MAX_LOG_LINES) repeat(log.size - MAX_LOG_LINES) { log.removeAt(0) }
    }

    fun info(text: String) = add(Level.INFO, text)
    fun good(text: String) = add(Level.GOOD, text)
    fun warn(text: String) = add(Level.WARN, text)
    fun bad(text: String) = add(Level.BAD, text)
    fun trace(text: String) = add(Level.TRACE, text)

    fun clearLog() = log.clear()

    /**
     * The log for the clipboard, with the header a bug report otherwise has to ask for: what the
     * app is, what it's running on, whether libusb loaded, and what it thinks it's talking to.
     * Everything here is knowable without a printer being reachable, which is the case the header
     * exists for. TRACE lines are always included, whether or not the panel is showing them.
     */
    fun exportLog(): String = buildString {
        appendLine("# Epson Reset ${AppVersion.display}")
        appendLine(
            "# %s %s (%s), Java %s".format(
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
            ),
        )
        // Reading `instance` first is what forces the lazy load, so this can't report a library
        // that was simply never asked for as one that failed.
        appendLine("# libusb: " + if (LibUsb.instance != null) "loaded" else "not loaded — ${LibUsb.loadError}")
        if (System.getProperty("os.name").lowercase().contains("win")) {
            appendLine("# usb backend: Windows spooler (driverless), libusb as fallback")
        }
        appendLine(
            "# database: " + (
                database?.let { "${it.size} models, ${it.source.name.lowercase()}" }
                    ?: databaseError?.let { "failed — $it" }
                    ?: "not loaded"
                ),
        )
        counterSpecs?.let {
            appendLine("# layouts: ${it.modelCount} models" + if (it.overlayLoaded) ", user overlay applied" else "")
        }
        appendLine(
            "# printer: " + (
                selectedDevice?.let { "${it.device.displayName} on ${it.device.link.kind} (${it.device.link.where})" }
                    ?: "none selected"
                ),
        )
        // Both names, when they differ: a mismatch between what the printer says it is and what
        // the user picked is the first thing to suspect in a report about wrong values.
        val chosen = selectedModel?.name
        val reported = identifiedModel?.name
        appendLine(
            "# model: " + (chosen ?: "none") +
                if (reported != null && reported != chosen) " (printer reports $reported)" else "",
        )
        appendLine("# mode: " + if (dryRun) "dry run" else "live")
        appendLine()
        log.joinTo(this, "\n") { "${it.time}  [${it.level}] ${it.text}" }
    }

    private companion object {
        const val MAX_LOG_LINES = 4000
    }
}
