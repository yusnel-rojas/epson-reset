package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.backup.Capture
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.backup.SnapshotComparison
import nl.redlabs.epsonreset.backup.UnitChoice
import nl.redlabs.epsonreset.backup.UnitSelector
import nl.redlabs.epsonreset.db.Calibration
import nl.redlabs.epsonreset.db.CapabilitySummary
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.ModelCapabilities
import nl.redlabs.epsonreset.db.ModelCapability
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.db.ResetScope
import nl.redlabs.epsonreset.db.ValueSupport
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.DeviceMatcher
import nl.redlabs.epsonreset.device.Link
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.device.PrinterDiscovery
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.net.NetworkAddress
import nl.redlabs.epsonreset.net.SavedPrinters
import nl.redlabs.epsonreset.probe.DeviceInspector
import nl.redlabs.epsonreset.probe.SweepAnalysis
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.FakeTransport
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.update.AppVersion
import nl.redlabs.epsonreset.usb.UsbPrinterScanner
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Everything the window renders from. Mutated on the UI thread; */
class ResetViewModel(
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val transports: (DetectedPrinter) -> PrinterTransports.OpenResult = PrinterTransports::open,
    private val discover: () -> PrinterDiscovery.Result = { PrinterDiscovery.scan() },
    private val backupDir: () -> File = { AppPaths.backups },
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

    /** The model the selected printer named *itself* as — not the one the user picked. */
    var identifiedModel by mutableStateOf<PrinterModel?>(null)
        private set

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
        get() = !busy && NetworkAddress.parse(networkAddressInput) != null

    var testing by mutableStateOf(false)
        private set

    /** Result of the last connection test, shown against the selected device. */
    var lastTest by mutableStateOf<ConnectionTest.Result?>(null)
        private set

    val canTestConnection: Boolean get() = !busy && !testing && selectedDevice != null

    var query by mutableStateOf("")
    var selectedModel by mutableStateOf<PrinterModel?>(null)
        private set

    /** The user asked to pick the model by hand, over the top of a printer that named itself. */
    var manualModelRequested by mutableStateOf(false)

    /** Whether the sidebar shows the model search list rather than the one selected model. */
    val modelPickerExpanded: Boolean
        get() = manualModelRequested || identifiedModel == null || modelMismatch != null

    /** The user asked for the add-by-address field. It also appears on its own — see the panel. */
    var addByAddressRequested by mutableStateOf(false)

    var tab by mutableStateOf(Tab.RESET)

    var logCollapsed by mutableStateOf(false)

    var matrixQuery by mutableStateOf("")
    var matrixFilter by mutableStateOf(MatrixFilter.ALL)

    /** Generates and logs the full packet sequence without opening the device. Default on. */
    var dryRun by mutableStateOf(true)

    var runState by mutableStateOf<RunState>(RunState.Idle)
        private set
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

    fun specsFor(model: PrinterModel): List<CounterSpec> = counterSpecs?.get(model.name) ?: emptyList()

    /** Pre-reset sample, kept so the UI can show what actually changed. */
    var beforeReport by mutableStateOf<CounterReader.Report?>(null)
        private set

    /** Backup written by the last real run, offered as the recovery path if it went wrong. */
    var lastBackup by mutableStateOf<File?>(null)
        private set

    /** Every snapshot on disk, newest first, each with its parsed contents. */
    val snapshots = mutableStateListOf<SavedSnapshot>()

    var selectedSnapshot by mutableStateOf<SavedSnapshot?>(null)
        private set

    var loadingSnapshots by mutableStateOf(false)
        private set

    data class SavedSnapshot(val file: File, val backup: EepromBackup?)

    /** What the selected snapshot is being compared against, if anything. */
    var compareTarget by mutableStateOf<CompareTarget>(CompareTarget.None)
        private set

    sealed interface CompareTarget {
        data object None : CompareTarget

        /** Another file on disk. Needs no printer, and is the pairing that survives a restart. */
        data class Snapshot(val file: File) : CompareTarget

        /** Whatever [readReport] holds — a reading already taken, not one this triggers. */
        data object CurrentReading : CompareTarget
    }

    var reading by mutableStateOf(false)
        private set

    /** Printer's own status block: serial, ink levels, state. Null until a read runs. */
    var status by mutableStateOf<Status.Report?>(null)
        private set

    // Read-only exploration for printers the database doesn't cover. All the protocol work lives in
    // DeviceInspector, which cannot emit a write.

    var inspectKeys = mutableStateListOf<DeviceInspector.KeyResult>()
        private set

    /** The key the sweep will use — discovered, or typed in by someone who already knows it. */
    var inspectKey by mutableStateOf<Int?>(null)
        private set

    var inspectSweep by mutableStateOf<DeviceInspector.Sweep?>(null)
        private set

    var inspectCandidates = mutableStateListOf<SweepAnalysis.Candidate>()
        private set

    var inspecting by mutableStateOf(false)
        private set

    /** Name the user gives the unknown printer; seeded from the USB descriptor. */
    var inspectModelName by mutableStateOf("")

    /**
     * Requires real hardware: the fake EEPROM answers every key and returns the same byte at every
     * address, so a simulated run reports keys "found" and hundreds of candidate counters, all of
     * them fiction — indistinguishable from a successful discovery.
     */
    val canInspect: Boolean get() = !inspecting && database != null && selectedDevice != null

    /** Highest address the sweep will reach. 0x1FF covers every mem_high in the database. */
    var inspectRangeEnd by mutableStateOf(0x1FF)

    /** Models sharing [inspectKey] — the family whose layout the analysis leans on. */
    val inspectSiblings: List<PrinterModel>
        get() {
            val db = database ?: return emptyList()
            val key = inspectKey ?: return emptyList()
            return DeviceInspector.siblingsOf(db, key)
        }

    private val cancelFlag = AtomicBoolean(false)

    sealed interface ScanState {
        data object Idle : ScanState
        data object Scanning : ScanState
        data object Done : ScanState
        data class LibraryMissing(val detail: String, val hint: String) : ScanState
        data class Failed(val message: String) : ScanState
    }

    sealed interface RunState {
        data object Idle : RunState
        data object Running : RunState
        data class Finished(val result: Executor.Result, val wasDryRun: Boolean) : RunState
    }

    /** Progress and log state is written from executor callbacks running on the IO thread. */
    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    enum class Level { INFO, GOOD, WARN, BAD, TRACE }

    data class LogLine(val time: String, val level: Level, val text: String)

    val searchResults: List<PrinterModel>
        get() = database?.search(query) ?: emptyList()

    enum class Tab { RESET, MODELS, INSPECT, SNAPSHOTS }

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

    fun selectModelAndShowReset(capability: ModelCapability) {
        selectModel(capability.model)
        query = capability.name
        tab = Tab.RESET
    }

    private val busy: Boolean get() = runState == RunState.Running || reading

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

    /** Why a live write to the selected printer is not allowed, or null when it is. */
    val writeBlockedReason: String?
        get() {
            // Ahead of the link question, and on both links: writing one model's bytes into
            // another is wrong over USB for exactly the same reason it is wrong over SNMP.
            modelMismatch?.let { return it }

            if (selectedDevice?.device?.isNetwork != true) return null

            val test = lastTest ?: return null
            if (!test.overNetwork || test.reach != ConnectionTest.Reach.STATUS_ONLY) return null

            return test.refusal
                ?: "This printer answered identity and status over the network but refused " +
                "counter access. Connect it over USB to reset."
        }

    val canRun: Boolean
        get() = !busy &&
            selectedModel?.hasResettableCounters == true &&
            (dryRun || (selectedDevice != null && writeBlockedReason == null))

    /** Reading has the same prerequisites but never writes, so it needs no confirmation. */
    val canRead: Boolean get() = canRun

    /**
     * Startup sequence. The scan has to wait for the database, or matching runs against nothing and
     * reports a connected printer as unknown.
     */
    fun start() {
        scope.launch {
            loadDatabaseNow()
            // Before the scan, because the Reset tab offers a comparison the moment a read lands
            // and that offer depends on knowing what is already on disk. Reading a directory is
            // cheap;
            refreshSnapshotsNow()
            scanNow()
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

    fun refreshDatabaseFromNetwork() {
        scope.launch {
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
                good("Database updated — ${it.size} models.")
            }.onFailure {
                warn("Database update failed: ${it.message}. Keeping the current copy.")
            }
        }
    }

    fun scan() {
        scope.launch { scanNow() }
    }

    /** Scans both buses. */
    private suspend fun scanNow() {
        scanState = ScanState.Scanning
        val db = database
        val discovery = withContext(io) { discover() }
        savedNetworkPrinters = withContext(io) {
            SavedPrinters.load().map { it.link }.toSet()
        }

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

        selectedDevice = selectedDevice?.let { previous ->
            matched.firstOrNull { it.device.id == previous.device.id }
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
    }

    fun select(device: MatchedPrinter) {
        selectedDevice = device
        lastTest = null
        // Both belong to the printer that was selected before this one, not to this one.
        identifiedModel = null
        manualModelRequested = false
        refreshIdentity()
        device.model?.let {
            selectedModel = it
            query = it.name
            val how = when (device.confidence) {
                MatchedPrinter.Confidence.EXACT -> "matched exactly"
                MatchedPrinter.Confidence.LIKELY -> "matched (likely — please confirm)"
                MatchedPrinter.Confidence.NONE -> "unmatched"
            }
            info("${device.device.displayName} on ${device.device.link.kind} → ${it.name} ($how).")
        } ?: warn(
            "${device.device.displayName} did not match any database entry — pick the model manually.",
        )
    }

    /** Re-derives [identifiedModel] from the selected printer. */
    private fun refreshIdentity() {
        val device = selectedDevice ?: run {
            identifiedModel = null
            return
        }
        val fromScan = device.model.takeIf { device.confidence == MatchedPrinter.Confidence.EXACT }
        identifiedModel = fromScan ?: identifiedModel
    }

    /** Adds a printer by address, then immediately asks it what it is. */
    fun addNetworkPrinter() {
        val link = NetworkAddress.parse(networkAddressInput) ?: run {
            warn("'$networkAddressInput' is not an address or hostname.")
            return
        }

        scope.launch {
            networkAddressInput = ""
            info("Added ${link.where}. Asking what is there…")
            withContext(io) { SavedPrinters.add(SavedPrinters.Saved(link)) }

            // Listed and selected before the probe, not after.
            scanNow()
            val added = devices.firstOrNull { it.device.link == link }
            added?.let { select(it) }

            val probe = withContext(io) {
                ConnectionTest.run(added?.device ?: DetectedPrinter(link), selectedModel)
            }
            reportTest(probe, link)
        }
    }

    /** Drops a hand-added address. Discovered printers come back on the next scan regardless. */
    fun forgetNetworkPrinter(printer: MatchedPrinter) {
        val link = printer.device.link as? Link.Network ?: return
        scope.launch {
            withContext(io) { SavedPrinters.remove(link) }
            info("Forgot ${link.where}.")
            scanNow()
        }
    }

    /** Checks the selected printer answers, without writing. */
    fun testConnection() {
        val device = selectedDevice ?: return

        scope.launch {
            testing = true
            info("Testing ${device.device.displayName} on ${device.device.link.kind}…")
            val result = withContext(io) { ConnectionTest.run(device.device, selectedModel) }
            reportTest(result, device.device.link as? Link.Network)
            testing = false
        }
    }

    /** Logs a test result and keeps what it taught us. */
    private suspend fun reportTest(result: ConnectionTest.Result, link: Link.Network?) {
        lastTest = result
        result.status?.let { status = it }

        if (result.usable) good(result.headline) else warn(result.headline)
        result.advice?.let { info(it) }
        result.model?.let { info("Reports itself as: $it") }
        result.firmware?.let { info("Firmware: $it") }
        result.serial?.let { info("Serial: $it") }

        val reported = result.model
        if (reported != null && link != null) {
            withContext(io) { SavedPrinters.add(SavedPrinters.Saved(link, reported)) }
        }

        // An identified printer should land on its database entry the same way a USB one does,
        // rather than leaving the user to retype a name the printer just told us. The two halves
        // are separate on purpose.
        val db = database
        if (reported != null && db != null) {
            val resolution = DeviceMatcher.resolve(reported, db)
            if (resolution.confidence == MatchedPrinter.Confidence.EXACT) {
                identifiedModel = resolution.model
            }
            if (selectedModel == null) {
                resolution.model?.let {
                    selectedModel = it
                    query = it.name
                    info("Matched to ${it.name} from what the printer reported.")
                }
            }
        }
    }

    fun selectModel(model: PrinterModel) {
        selectedModel = model
        if (runState is RunState.Finished) runState = RunState.Idle
        // Every other decision that could end badly is in the log; this one belongs there too.
        modelMismatch?.let { warn("$it Dry runs still work; a live run will refuse.") }
    }

    /** Puts back the model the last session ended on. */
    fun restoreModel(model: PrinterModel) {
        if (selectedModel != null || identifiedModel != null) return
        selectedModel = model
        query = model.name
    }

    /** Puts the selection back on what the printer said it was, and re-locks the picker. */
    fun useIdentifiedModel() {
        val model = identifiedModel ?: return
        manualModelRequested = false
        if (selectedModel?.name != model.name) {
            selectedModel = model
            query = model.name
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
        val model = selectedModel ?: return
        val device = selectedDevice
        val isDry = dryRun

        scope.launch { performRead(model, device, isDry) }
    }

    /** The read itself, so the Snapshot tab can ask for one without duplicating it. */
    private suspend fun performRead(model: PrinterModel, device: MatchedPrinter?, isDry: Boolean) {
        cancelFlag.set(false)
        reading = true
        progress = 0f
        progressLabel = "Reading counters…"

        val listener = object : CounterReader.Listener {
            override fun onProgress(done: Int, total: Int, address: Int) = onMain {
                progress = done.toFloat() / total
                progressLabel = "Reading address $address ($done / $total)"
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

        beforeReport = null
        readReport = report
        readWasSimulated = isDry
        // A percentage typed against the previous reading would silently pair with this one.
        resetCalibrationForm()
        status = read
        read?.let { s ->
            s.inkLevels.takeIf { it.isNotEmpty() }?.let { levels ->
                info("Ink: " + levels.joinToString(", ") { "${it.colour} ${it.percent}%" })
            }
        }
        reading = false
        progress = 0f
        progressLabel = ""

        describe(report, isDry)
    }

    val canExportInspection: Boolean get() = inspectSweep?.answered?.let { it > 0 } == true

    fun chooseInspectKey(key: Int?) {
        inspectKey = key
        inspectSweep = null
        inspectCandidates.clear()
    }

    private fun inspectorListener() = object : DeviceInspector.Listener {
        override fun onProgress(done: Int, total: Int, label: String) = onMain {
            progress = if (total == 0) 0f else done.toFloat() / total
            progressLabel = "$label ($done / $total)"
        }

        override fun onTrace(line: String) = onMain { trace(line) }
        override fun onNote(text: String) = onMain { info(text) }
    }

    /** Tries the database's known read keys against the attached printer. */
    fun discoverReadKey() {
        val db = database ?: return
        val device = selectedDevice ?: return

        scope.launch {
            cancelFlag.set(false)
            inspecting = true
            inspectKeys.clear()
            progressLabel = "Trying read keys…"
            info("Trying ${DeviceInspector.candidateKeys(db).size} known read keys — read-only, nothing is written.")

            val results = withContext(io) {
                openTransport(device, isDry = false).use { transport ->
                    transport?.let {
                        DeviceInspector.discoverKey(
                            transport = it,
                            db = db,
                            listener = inspectorListener(),
                            isCancelled = { cancelFlag.get() },
                        )
                    }
                } ?: emptyList()
            }

            inspectKeys.clear()
            inspectKeys.addAll(results)

            val answered = results.filter { it.answered }
            when {
                results.isEmpty() -> bad("No reply at all — the printer never opened a D4 channel. $transportError")
                answered.isEmpty() -> warn("None of the known read keys produced a reading.")
                else -> {
                    chooseInspectKey(answered.first().readKey)
                    good("${answered.size} key(s) answered. Using ${answered.first().hex}.")
                    if (answered.size > 1) {
                        warn(
                            "More than one key answered, so this firmware probably doesn't check the " +
                                "read key. The sweep is the useful result, not the key.",
                        )
                    }
                }
            }

            inspecting = false
            progress = 0f
            progressLabel = ""
        }
    }

    /** Reads every address up to [inspectRangeEnd] with the chosen key. Never writes. */
    fun sweepAddresses() {
        val key = inspectKey ?: return
        val device = selectedDevice ?: return
        val end = inspectRangeEnd

        scope.launch {
            cancelFlag.set(false)
            inspecting = true
            progressLabel = "Sweeping…"
            info("Sweeping 0x0000–0x%04X with key 0x%04X — read-only.".format(end, key))

            val addresses = (0..end).toList()
            val result = withContext(io) {
                openTransport(device, isDry = false).use { transport ->
                    transport?.let {
                        DeviceInspector.sweep(
                            transport = it,
                            readKey = key,
                            addresses = addresses,
                            listener = inspectorListener(),
                            isCancelled = { cancelFlag.get() },
                        )
                    }
                } ?: DeviceInspector.Sweep(key, addresses, emptyMap(), transportError)
            }

            inspectSweep = result
            val found = SweepAnalysis.candidates(
                sweep = result,
                siblings = inspectSiblings,
                specsFor = { specsFor(it) },
            )
            inspectCandidates.clear()
            inspectCandidates.addAll(found)

            when {
                result.error != null -> bad("Sweep failed: ${result.error}")
                result.answered == 0 -> bad("No address answered. The key is probably wrong.")
                else -> good(
                    "Read ${result.answered} of ${result.total} addresses; ${found.size} candidate counter(s).",
                )
            }

            inspecting = false
            progress = 0f
            progressLabel = ""
        }
    }

    /** The overlay that makes this app read the discovered addresses on this model. */
    fun inspectionOverlay(): String = SweepAnalysis.overlayJson(
        inspectModelName.ifBlank { selectedDevice?.device?.displayName ?: "MY-MODEL" },
        inspectCandidates,
    )

    /** A report to file, so a fix reaches every tool built on the same data rather than only this one. */
    fun inspectionReport(): String {
        val sweep = inspectSweep ?: return "Nothing has been swept yet."
        return SweepAnalysis.report(
            device = selectedDevice?.device,
            sweep = sweep,
            candidates = inspectCandidates,
            keyResults = inspectKeys.filter { it.answered },
        )
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
            runState = RunState.Running
            progress = 0f
            progressLabel = "Generating sequence…"

            val sequence = SequenceGenerator.generate(model)
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

                override fun onTrace(line: String) = onMain { trace(line) }
            }

            // One connection for the whole read-reset-read cycle: re-opening between phases would
            // mean claiming the interface three times, and the before/after pair has to come from
            // the same session to be worth comparing.
            var before: CounterReader.Report? = null
            var after: CounterReader.Report? = null

            var backupFile: File? = null

            val result = withContext(io) {
                openTransport(device, isDry).use { transport ->
                    transport?.let {
                        onMain { progressLabel = "Reading counters before reset…" }
                        before = CounterReader.readAll(it, model, specsFor(model)) { cancelFlag.get() }

                        // A read that failed for a reason the printer gave — a refusal, most likely
                        // — is reported as that reason.
                        val readFailure = before?.error
                        if (readFailure != null) return@let Executor.Result(error = readFailure)

                        // Ask who this is while the channel is open.
                        val sampled = CounterReader.readStatus(it)
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
            readReport = after
            readWasSimulated = isDry
            // The counters have just been zeroed, so nothing on this reading measures a maximum.
            resetCalibrationForm()
            lastBackup = backupFile
            runState = RunState.Finished(result, isDry)

            reportVerification(before, after)

            // A run that stopped partway is the case the backup was taken for, so the filename goes
            // in front of the user rather than into a log they have to scroll.
            if (!result.success && backupFile != null && result.writesVerified > 0) {
                warn(
                    "${result.writesVerified} write(s) landed before this stopped. The pre-write " +
                        "bytes are saved in ${backupFile?.name} — restore it to put them back.",
                )
            }

            when {
                result.success && isDry ->
                    good(
                        "Dry run complete — ${result.writesTotal} writes generated and verified against the fake device.",
                    )
                result.success -> {
                    good("Reset complete — ${result.writesVerified}/${result.writesTotal} EEPROM writes verified.")
                    warn("Power-cycle the printer now to finalise the change.")
                }
                else -> bad(result.error.ifBlank { "The reset did not complete." })
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

    /** Writes the bytes from [backup] back to the addresses they came from. */
    fun restore(backup: EepromBackup) {
        val model = selectedModel ?: run {
            bad("Pick the model first — a restore needs its write key.")
            return
        }
        val device = selectedDevice

        if (!allowedToLand(backup, model, device)) return

        scope.launch {
            cancelFlag.set(false)
            runState = RunState.Running
            progress = 0f
            progressLabel = "Generating restore sequence…"

            val sequence = SequenceGenerator.generateWrites(model, backup.writes)
            info(
                "Restoring ${backup.entries.size} addresses to ${model.name} from the backup taken ${backup.createdAt}.",
            )

            val listener = object : Executor.Listener {
                override fun onPacket(index: Int, total: Int, message: String) = onMain {
                    progress = index.toFloat() / total
                    progressLabel = "Packet $index / $total — $message"
                }

                override fun onTrace(line: String) = onMain { trace(line) }
            }

            val result = withContext(io) {
                openTransport(device, dryRun).use { transport ->
                    transport?.let {
                        Executor.execute(
                            transport = it,
                            sequence = sequence,
                            listener = listener,
                            isCancelled = { cancelFlag.get() },
                        )
                    }
                } ?: Executor.Result(error = transportError)
            }

            progress = 1f
            progressLabel = ""
            runState = RunState.Finished(result, dryRun)

            if (result.success) {
                good("Restore complete — ${result.writesVerified}/${result.writesTotal} writes verified.")
                warn("Power-cycle the printer to finalise the change.")
            } else {
                bad(result.error.ifBlank { "The restore did not complete." })
            }
        }
    }

    /** Whether this backup may be written to this printer, per [UnitSelector]. */
    private fun allowedToLand(backup: EepromBackup, model: PrinterModel, device: MatchedPrinter?): Boolean {
        if (dryRun) {
            if (!backup.model.equals(model.name, ignoreCase = true)) {
                warn("This backup is for ${backup.model} but ${model.name} is selected. A live run would refuse.")
            }
            return true
        }

        writeBlockedReason?.let {
            bad(it)
            return false
        }

        // The device carries the serial the scan found, which over the network is nothing at all.
        val candidate = device?.let {
            MatchedPrinter(it.device.copy(serial = identifyingSerial(it)), model, it.confidence)
        }

        return when (val choice = UnitSelector.choose(backup, listOfNotNull(candidate))) {
            is UnitChoice.NoSuchModel -> {
                bad(
                    if (candidate == null) {
                        "Select the printer to restore to first."
                    } else {
                        "That backup is for ${choice.model}, but the selected printer is " +
                            "${model.name}. Refusing to write one model's bytes to another."
                    },
                )
                false
            }

            is UnitChoice.WrongUnit -> {
                bad(
                    "That backup came from ${choice.wanted}; this printer reports " +
                        "${choice.connected.joinToString(", ")}. Refusing to write.",
                )
                false
            }

            // Unreachable with a single candidate, but the rule owns the enumeration, not this.
            is UnitChoice.Ambiguous -> {
                bad("${choice.count} ${choice.model} units match and none can be told apart by serial.")
                false
            }

            is UnitChoice.Write -> {
                choice.unconfirmed?.let {
                    warn(
                        "This backup can't be tied to this exact unit — $it. Check you're pointed at the right printer.",
                    )
                }
                true
            }
        }
    }

    fun loadBackup(file: File): EepromBackup? = EepromBackup.load(file)

    // ── Snapshots ────────────────────────────────────────────────────────────────────────────

    /** Why the counters currently on screen cannot be saved, or null when they can. */
    val snapshotBlockedReason: String?
        get() {
            val report = readReport
            return when {
                selectedModel == null -> "pick the model these counters belong to first"
                report == null -> "read the counters first — a snapshot stores bytes that were actually read"
                readWasSimulated ->
                    "these values came from the simulated EEPROM of a dry run, not from a printer. " +
                        "Switch to Live and read again"
                report.answered == 0 -> "nothing answered the last read, so there is nothing to save"
                else -> null
            }
        }

    val canSaveSnapshot: Boolean get() = !busy && snapshotBlockedReason == null

    /** Saves the counters on screen as a snapshot, at whatever moment the user asks for one. */
    fun saveSnapshot() {
        val model = selectedModel ?: return
        val report = readReport ?: return

        snapshotBlockedReason?.let {
            bad("Nothing saved — $it.")
            return
        }

        scope.launch {
            val capture = EepromBackup.capture(
                model = model.name,
                sequence = SequenceGenerator.generate(model),
                readings = report.readings,
                printerSerial = identifyingSerial(selectedDevice),
            )

            when (capture) {
                is Capture.NothingToWrite ->
                    bad("${model.name} has no resettable addresses, so a snapshot would have nothing to put back.")

                is Capture.Incomplete -> {
                    val shown = capture.missing.take(8).joinToString(", ")
                    val more = if (capture.missing.size > 8) " +${capture.missing.size - 8} more" else ""
                    bad(
                        "Nothing saved: ${capture.missing.size} of the addresses a reset would write " +
                            "did not answer the read ($shown$more), so a restore could not put them " +
                            "back. Reads are unprivileged and safe to retry.",
                    )
                }

                is Capture.Ready -> {
                    val saved = withContext(io) { runCatching { capture.backup.save(backupDir()) } }
                    saved.onSuccess { file ->
                        good(
                            "Snapshot saved as ${file.name} — ${capture.backup.entries.size} addresses, " +
                                "${capture.backup.changedByReset} differ from their reset value.",
                        )
                        refreshSnapshotsNow()
                        selectedSnapshot = snapshots.firstOrNull { it.file == file }
                    }.onFailure { e ->
                        bad("Snapshot not saved: ${e.message ?: e::class.simpleName}.")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ calibration

    /** What the user has said about one counter on the calibration form. */
    data class CalibrationInput(
        val percent: String = "",
        val reportedValue: String = "",
        val serviceRequired: Boolean = false,
    )

    /** Keyed by the counter's address list, which is what identifies a counter across a re-read. */
    val calibrationInputs = mutableStateMapOf<List<Int>, CalibrationInput>()

    /** Whether the contribution form is open. */
    var calibrationDialogOpen by mutableStateOf(false)

    /** Anything the contributor wants to add — where the percentage came from, usually. */
    var calibrationNote by mutableStateOf("")

    /** The model this measurement is filed against — its own field, not [selectedModel]. */
    var calibrationModel by mutableStateOf("")

    /** Whether the measured maxima are already layered onto this session's layouts. */
    var calibrationApplied by mutableStateOf(false)
        private set

    fun calibrationInput(addresses: List<Int>): CalibrationInput = calibrationInputs[addresses] ?: CalibrationInput()

    fun setCalibrationPercent(addresses: List<Int>, text: String) {
        calibrationInputs[addresses] = calibrationInput(addresses).copy(percent = text)
    }

    fun setCalibrationReportedValue(addresses: List<Int>, text: String) {
        calibrationInputs[addresses] = calibrationInput(addresses).copy(reportedValue = text)
    }

    fun setCalibrationServiceRequired(addresses: List<Int>, on: Boolean) {
        calibrationInputs[addresses] = calibrationInput(addresses).copy(serviceRequired = on)
    }

    private fun resetCalibrationForm() {
        calibrationInputs.clear()
        calibrationNote = ""
        calibrationModel = ""
        calibrationApplied = false
    }

    /** Opens the form, starting from the best answer to "which model is this?" available. */
    fun openCalibration() {
        if (calibrationModel.isBlank()) {
            calibrationModel = identifiedModel?.name ?: selectedModel?.name.orEmpty()
        }
        calibrationDialogOpen = true
    }

    /** Every model whose counter layout is the one being measured. */
    val calibrationLayoutSiblings: List<PrinterModel>
        get() {
            val db = database ?: return emptyList()
            val layout = selectedModel?.let { addressLayout(it) }?.takeIf { it.isNotEmpty() }
                ?: return emptyList()
            return db.models.filter { addressLayout(it) == layout }
        }

    /** A model's layout as the address groups alone. */
    private fun addressLayout(model: PrinterModel): List<List<Int>> = specsFor(model).map { it.addresses }

    /** What to offer in the model picker, best answer first. */
    val calibrationModelCandidates: List<String>
        get() {
            val anchor = (identifiedModel?.name ?: selectedModel?.name).orEmpty()
            val siblings = calibrationLayoutSiblings
                .map { it.name }
                .sortedWith(
                    compareByDescending<String> { it.commonPrefixWith(anchor, true).length }
                        .thenBy { it },
                )

            return (listOfNotNull(identifiedModel?.name, selectedModel?.name) + siblings)
                .distinct()
                .take(MAX_MODEL_CANDIDATES)
        }

    /** What is doubtful about the name on the form, or null when nothing is. */
    val calibrationModelWarning: String?
        get() {
            val name = calibrationModel.trim()
            val identified = identifiedModel?.name
            return when {
                name.isEmpty() -> "Name the model this printer actually is."

                // A family entry covers several SKUs, so a maximum filed under one is attributed
                // to all of them and to none of them.
                name.contains("series", ignoreCase = true) ->
                    "'$name' names a family, not a unit. Pick the exact model — a maximum filed " +
                        "against a family cannot later be told from one measured on any of its members."

                // Only worth saying when there is a database to have missed it.
                database?.let { it[name] == null } == true ->
                    "'$name' is not in the database. Fine if it is a model nobody has added yet; " +
                        "worth a second look otherwise."

                identified != null && !name.equals(identified, ignoreCase = true) ->
                    "This printer names itself $identified over SNMP, which is the strongest " +
                        "identification there is. Filing it as $name overrides that."

                else -> null
            }
        }

    /** The name a submission is filed under. */
    private val calibrationModelName: String get() = calibrationModel.trim()

    /** The counters a maximum could be attached to: one number each, and not sitting at zero. */
    val calibratableCounters: List<CounterReader.DecodedCounter>
        get() = decodedCounters.filter { it.spec.isSingleValue && (it.value ?: 0L) > 0L }

    /** Why a calibration cannot be measured from what is on screen, or null when it can. */
    val calibrationBlockedReason: String?
        get() {
            val report = readReport
            return when {
                selectedModel == null -> "pick the model these counters belong to first"
                report == null ->
                    "read the counters first — a calibration is a reading with a maximum attached"
                readWasSimulated ->
                    "these values came from the simulated EEPROM of a dry run, not from a printer. " +
                        "Switch to Live and read again"
                report.answered == 0 -> "nothing answered the last read, so there is nothing to measure"
                calibratableCounters.isEmpty() ->
                    "none of this model's counters decoded to a single non-zero number, so there is " +
                        "nothing to put a maximum on"
                else -> null
            }
        }

    /** One counter's line on the form. A null [outcome] means the user hasn't answered for it yet. */
    data class CalibrationRow(val counter: CounterReader.DecodedCounter, val outcome: Calibration.Outcome?)

    val calibrationRows: List<CalibrationRow>
        get() = calibratableCounters.map { counter ->
            val input = calibrationInput(counter.spec.addresses)
            val basis = when {
                input.serviceRequired -> Calibration.Basis.ServiceRequired
                input.percent.isNotBlank() ->
                    Calibration.Basis.Reference(input.percent, input.reportedValue)
                // Most contributions calibrate one counter and leave the rest alone, so silence
                // is a normal answer rather than an error.
                else -> return@map CalibrationRow(counter, null)
            }
            CalibrationRow(counter, Calibration.measure(counter.spec, counter.value, basis))
        }

    val calibrationMeasurements: List<Calibration.Measured>
        get() = calibrationRows.mapNotNull { (it.outcome as? Calibration.Outcome.Ok)?.measured }

    val canSubmitCalibration: Boolean
        get() = calibrationBlockedReason == null &&
            calibrationMeasurements.isNotEmpty() &&
            calibrationModelName.isNotEmpty()

    fun calibrationEntry(): String = Calibration.entryJson(
        model = calibrationModelName,
        measured = calibrationMeasurements,
        note = calibrationNote,
        sharedLayout = calibrationLayoutSiblings.size,
    )

    fun calibrationReport(): String = Calibration.report(calibrationContext(), calibrationMeasurements)

    fun calibrationOverlay(): String {
        val model = selectedModel ?: return ""
        // The layout comes from the selection (that is what was read), the name from the form (that
        // is what the printer is). An overlay keyed to a family name would not match the unit.
        return Calibration.overlayJson(calibrationModelName, specsFor(model), calibrationMeasurements)
    }

    /**
     * Layers the measured maxima onto this session's layouts, so the contributor's own counters
     * start showing percentages immediately.
     */
    fun applyCalibrationToSession() {
        if (!canSubmitCalibration) return
        val specs = counterSpecs ?: return
        val model = selectedModel ?: return

        counterSpecs = specs.withCalibration(Calibration.asCalibrationsFile(calibrationEntry()))
        calibrationApplied = true

        good(
            "Applied to ${model.name} for this session: " +
                calibrationMeasurements.joinToString("; ") {
                    "addr ${it.addressLabel} max ${it.max} → %.2f%%".format(it.percent)
                } + ". Save the overlay to keep it after a restart.",
        )
    }

    /** Opens the calibration issue form, prefilled. */
    fun openCalibrationIssue(toClipboard: (String) -> Unit) {
        if (!canSubmitCalibration) return

        val submission = Calibration.submission(
            model = calibrationModelName,
            entry = calibrationEntry(),
            evidence = calibrationReport(),
        )

        if (!submission.prefilled) {
            toClipboard(calibrationReport())
            warn("Too long to prefill — the report is on the clipboard, paste it into the form.")
        }

        if (Browser.open(submission.url)) {
            info(
                "Opened a calibration issue for ${selectedModel?.name}. Nothing has been sent: the " +
                    "form is yours to read and submit.",
            )
        } else {
            toClipboard(calibrationReport())
            warn(
                "Could not open a browser. The report is on the clipboard — file it at " +
                    Calibration.ISSUE_BASE,
            )
        }
    }

    private fun calibrationContext(): Calibration.Context = Calibration.Context(
        model = calibrationModelName,
        identifiedAs = identifiedModel?.name,
        layoutOf = selectedModel?.name,
        sharedLayout = calibrationLayoutSiblings.size,
        printer = selectedDevice?.device?.displayName,
        transport = selectedDevice?.device?.let { if (it.isNetwork) "network (SNMP)" else "USB" },
        firmware = lastTest?.firmware,
        appVersion = AppVersion.display,
        inkLevels = status?.inkLevels.orEmpty().map { it.colour to it.percent },
        statusFields = calibrationStatusFields,
        note = calibrationNote,
    )

    /** The printer's own state block, as bytes, for the submission to carry. */
    private val calibrationStatusFields: List<Pair<String, String>>
        get() {
            val report = status ?: return emptyList()
            return listOf(0x01, 0x02, 0x04).mapNotNull { type ->
                report[type]?.let { it.name to it.hex }
            }
        }

    /** Where snapshots are kept. Shown in the panel, so it comes from the same place it is read. */
    val snapshotDir: File get() = backupDir()

    fun refreshSnapshots() {
        scope.launch { refreshSnapshotsNow() }
    }

    private suspend fun refreshSnapshotsNow() {
        loadingSnapshots = true
        val found = withContext(io) {
            EepromBackup.list(backupDir()).map { SavedSnapshot(it, EepromBackup.load(it)) }
        }
        snapshots.clear()
        snapshots.addAll(found)
        // By file, not by object: a refresh rebuilds every row, and the selection is about which
        // snapshot is on screen rather than which instance was clicked.
        selectedSnapshot = selectedSnapshot?.let { previous ->
            found.firstOrNull { it.file == previous.file }
        }
        loadingSnapshots = false
    }

    /** Opens a snapshot. Reading it is what selecting it does — see [snapshotReport]. */
    fun selectSnapshot(snapshot: SavedSnapshot?) {
        selectedSnapshot = snapshot
        // A comparison is against one specific file. Carrying it across a change of selection would
        // silently re-point it at a pair the user never asked for.
        compareTarget = CompareTarget.None
        val backup = snapshot?.backup ?: return
        info(
            "Read ${snapshot.file.name} from disk — ${backup.model}, taken ${backup.takenAt}, " +
                "${backup.entries.size} addresses (${backup.changedByReset} differ from their " +
                "reset value). No printer was involved.",
        )
    }

    /** The database entry a snapshot belongs to, when there still is one. */
    private val selectedSnapshotModel: PrinterModel?
        get() = selectedSnapshot?.backup?.let { database?.get(it.model) }

    /** The selected snapshot read back as if it had come off a printer — the dry read. */
    val snapshotReport: CounterReader.Report?
        get() = selectedSnapshot?.backup?.let {
            CounterReader.Report(it.model, it.readings(selectedSnapshotModel))
        }

    /** [snapshotReport] grouped into real counters. Empty when the model's layout is unknown. */
    val snapshotCounters: List<CounterReader.DecodedCounter>
        get() {
            val backup = selectedSnapshot?.backup ?: return emptyList()
            val model = selectedSnapshotModel ?: return emptyList()
            return CounterReader.decode(backup.readings(model), specsFor(model))
        }

    // ── Comparing two samples ────────────────────────────────────────────────────────────────

    /** Snapshots that could be compared against the selected one: same model, and readable. */
    val compareCandidates: List<SavedSnapshot>
        get() {
            val selected = selectedSnapshot ?: return emptyList()
            val model = selected.backup?.model ?: return emptyList()
            return snapshots.filter {
                it.file != selected.file && it.backup?.model.equals(model, ignoreCase = true)
            }
        }

    /** Why the printer cannot be read for a comparison right now, or null when it can. */
    val compareReadBlockedReason: String?
        get() {
            val backup = selectedSnapshot?.backup ?: return "Select a snapshot first."
            val model = selectedModel
                ?: return "Select ${backup.model} on the Reset tab so there is a model to read."
            if (!model.name.equals(backup.model, ignoreCase = true)) {
                return "This snapshot is a ${backup.model} but ${model.name} is selected. The same " +
                    "address is a different counter on each, so there is nothing to compare."
            }
            if (selectedDevice == null) return "No printer is selected — connect one on the Reset tab."
            if (dryRun) {
                return "Dry run invents a byte for every address, so comparing against it would " +
                    "show differences that are not real. Switch to Live to read this printer."
            }
            return null
        }

    val canReadForComparison: Boolean get() = !busy && !reading && compareReadBlockedReason == null

    /** Compares the selected snapshot against another file. Touches no printer. */
    fun compareWithSnapshot(file: File) {
        compareTarget = CompareTarget.Snapshot(file)
    }

    /** Compares against the reading already in memory. */
    fun compareWithCurrentReading() {
        if (readReport == null) {
            bad("There is no current reading to compare against — read the counters first.")
            return
        }
        if (readWasSimulated) {
            bad(
                "That reading came from a dry run's simulated EEPROM, not from a printer. " +
                    "Switch to Live and read again before comparing.",
            )
            return
        }
        compareTarget = CompareTarget.CurrentReading
    }

    fun clearComparison() {
        compareTarget = CompareTarget.None
    }

    /** Reads the printer now and compares the selected snapshot against it. */
    fun readForComparison() {
        val model = selectedModel ?: return
        compareReadBlockedReason?.let {
            bad("Cannot read for comparison — $it")
            return
        }

        scope.launch {
            performRead(model, selectedDevice, isDry = false)
            // Only on a read that produced something. A failed read leaves the previous reading in
            // place, and pairing the snapshot with *that* would date the comparison silently.
            if (readReport?.answered?.let { it > 0 } == true) {
                compareTarget = CompareTarget.CurrentReading
            } else {
                warn("Nothing answered the read, so there is nothing to compare against.")
            }
        }
    }

    /** The selected snapshot against [compareTarget], oldest sample first. */
    val comparison: SnapshotComparison.Result?
        get() {
            val selected = selectedSnapshot ?: return null
            val backup = selected.backup ?: return null
            val model = selectedSnapshotModel
            val specs = model?.let { specsFor(it) } ?: emptyList()

            val base = SnapshotComparison.Side(
                label = selected.file.name,
                takenAt = backup.takenAt,
                model = backup.model,
                serial = backup.printerSerial,
                readings = backup.readings(model),
            )

            return when (val target = compareTarget) {
                is CompareTarget.None -> null

                is CompareTarget.CurrentReading -> {
                    val report = readReport ?: return null
                    val other = SnapshotComparison.Side(
                        label = "Current reading",
                        takenAt = "read just now",
                        model = report.model,
                        serial = identifyingSerial(selectedDevice),
                        readings = report.readings,
                    )
                    SnapshotComparison.compare(before = base, after = other, specs = specs)
                }

                is CompareTarget.Snapshot -> {
                    val found = snapshots.firstOrNull { it.file == target.file }
                    val otherBackup = found?.backup ?: return null
                    val other = SnapshotComparison.Side(
                        label = found.file.name,
                        takenAt = otherBackup.takenAt,
                        model = otherBackup.model,
                        serial = otherBackup.printerSerial,
                        readings = otherBackup.readings(model),
                    )
                    // The stamp is `yyyyMMdd'T'HHmmss'Z'`, so it sorts as text. A hand-edited file
                    // with an unparseable stamp lands wherever it lands;
                    if (otherBackup.createdAt >= backup.createdAt) {
                        SnapshotComparison.compare(before = base, after = other, specs = specs)
                    } else {
                        SnapshotComparison.compare(before = other, after = base, specs = specs)
                    }
                }
            }
        }

    // ── The hand-off from the Reset tab ──────────────────────────────────────────────────────

    /** Snapshots taken from the model currently selected on the Reset tab, newest first. */
    val snapshotsForSelectedModel: List<SavedSnapshot>
        get() {
            val model = selectedModel ?: return emptyList()
            return snapshots.filter { it.backup?.model.equals(model.name, ignoreCase = true) }
        }

    /** True when the reading on the Reset tab could be compared against something on disk. */
    val canOfferComparison: Boolean
        get() = readReport?.answered?.let { it > 0 } == true &&
            !readWasSimulated &&
            snapshotsForSelectedModel.isNotEmpty()

    /**
     * Opens the newest snapshot for this model on the Snapshot tab, already paired with the reading
     * on screen.
     */
    fun compareCurrentReadingWithNewestSnapshot() {
        val snapshot = snapshotsForSelectedModel.firstOrNull() ?: return
        selectSnapshot(snapshot)
        compareWithCurrentReading()
        tab = Tab.SNAPSHOTS
    }

    /** Why the selected snapshot cannot be written back right now, or null when it can. */
    val snapshotRestoreBlockedReason: String?
        get() {
            val backup = selectedSnapshot?.backup ?: return "Select a snapshot."
            val model = selectedModel
                ?: return "Select ${backup.model} on the Reset tab — a restore needs its write key."
            if (!model.name.equals(backup.model, ignoreCase = true)) {
                return "This snapshot is a ${backup.model}; ${model.name} is selected. " +
                    "Switch the selection before writing one model's bytes into another."
            }
            return if (dryRun) null else writeBlockedReason
        }

    /** Puts the Reset tab's selection on the model the selected snapshot came from. */
    fun useSnapshotModel() {
        val backup = selectedSnapshot?.backup ?: return
        val model = database?.get(backup.model) ?: run {
            bad("'${backup.model}' is not in the database, so its write key is unavailable.")
            return
        }
        selectModel(model)
        query = model.name
        info("Selected ${model.name} — the model this snapshot was taken from.")
    }

    /** Writes the selected snapshot back. Gated exactly as any other restore is. */
    fun restoreSelectedSnapshot() {
        val backup = selectedSnapshot?.backup ?: return
        restore(backup)
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

    fun exportLog(): String = log.joinToString("\n") { "${it.time}  [${it.level}] ${it.text}" }

    private companion object {
        const val MAX_LOG_LINES = 4000

        /**
         * How many models the calibration picker offers. A layout can be shared by 120 of them,
         * which is a list nobody scrolls;
         */
        const val MAX_MODEL_CANDIDATES = 24
    }
}
