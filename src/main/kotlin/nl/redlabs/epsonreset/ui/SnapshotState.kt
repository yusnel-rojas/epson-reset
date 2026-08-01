package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.backup.Capture
import nl.redlabs.epsonreset.backup.EepromBackup
import nl.redlabs.epsonreset.backup.SnapshotComparison
import nl.redlabs.epsonreset.backup.UnitChoice
import nl.redlabs.epsonreset.backup.UnitSelector
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import nl.redlabs.epsonreset.protocol.SequenceGenerator
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.protocol.Transport
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Saved EEPROM samples, comparisons, and the gated restore path. */
class SnapshotState(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val backupDir: () -> File,
    private val database: () -> PrinterDatabase?,
    private val selectedModel: () -> PrinterModel?,
    private val selectedDevice: () -> MatchedPrinter?,
    private val dryRun: () -> Boolean,
    private val familyBlockedReason: () -> String?,
    private val writeBlockedReason: () -> String?,
    private val busy: () -> Boolean,
    private val reading: () -> Boolean,
    private val readReport: () -> CounterReader.Report?,
    private val readWasSimulated: () -> Boolean,
    private val status: () -> Status.Report?,
    private val specsFor: (PrinterModel) -> List<CounterSpec>,
    private val performRead: suspend (PrinterModel, MatchedPrinter?, Boolean) -> Unit,
    private val selectModel: (PrinterModel) -> Boolean,
    private val updateQuery: (String) -> Unit,
    private val openSnapshotsTab: () -> Unit,
    private val openTransport: (MatchedPrinter?, Boolean) -> Transport?,
    private val transportError: () -> String,
    private val resetCancellation: () -> Unit,
    private val isCancelled: () -> Boolean,
    private val startRun: (String) -> Unit,
    private val updateProgress: (Float, String) -> Unit,
    private val finishRun: (Executor.Result, Boolean) -> Unit,
    private val info: (String) -> Unit,
    private val good: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val bad: (String) -> Unit,
    private val trace: (String) -> Unit,
) {
    /** Every snapshot on disk, newest first, each with its parsed contents. */
    val snapshots = mutableStateListOf<SavedSnapshot>()

    var selectedSnapshot by mutableStateOf<SavedSnapshot?>(null)
        private set

    var loadingSnapshots by mutableStateOf(false)
        private set

    data class SavedSnapshot(val file: File, val backup: EepromBackup?)

    /** The file the running (or last) restore is putting back, so its progress is only drawn there. */
    var restoreTarget by mutableStateOf<File?>(null)
        private set

    /** How far each address of that restore has got. */
    var restoreStates by mutableStateOf<Map<Int, ResetViewModel.CounterByteState>>(emptyMap())
        private set

    /** Whether the selected snapshot is being shown as a write rather than as saved bytes. */
    var showRestorePlan by mutableStateOf(false)
        private set

    /** What the selected snapshot is being compared against, if anything. */
    var compareTarget by mutableStateOf<CompareTarget>(CompareTarget.None)
        private set

    sealed interface CompareTarget {
        data object None : CompareTarget

        /** Another file on disk. Needs no printer, and is the pairing that survives a restart. */
        data class Snapshot(val file: File) : CompareTarget

        /** Whatever the current counter report holds — a reading already taken. */
        data object CurrentReading : CompareTarget
    }

    /** The serial to stamp a backup with, or check one against. */
    private fun identifyingSerial(device: MatchedPrinter?): String? = status()?.serial ?: device?.device?.serial

    /**
     * Writes the bytes from [backup] back to the addresses they came from. True when a run started.
     * [file] is where those bytes came from, when it is known — the panel draws the write against
     * that snapshot and nothing else.
     *
     * [saveFirst] takes the same safety net a reset takes: the bytes about to be overwritten are
     * read and saved before the first write lands, over the connection already open. Without it a
     * restore is the one EEPROM write in this application with nothing behind it.
     */
    fun restore(backup: EepromBackup, file: File? = null, saveFirst: Boolean = true): Boolean {
        if (busy()) {
            bad("Another printer operation is already in progress.")
            return false
        }
        val model = selectedModel() ?: run {
            bad("Pick the model first — a restore needs its write key.")
            return false
        }
        val device = selectedDevice()

        if (!allowedToLand(backup, model, device)) return false

        // A comparison describes two samples taken before this write. Leaving it on screen while the
        // printer is being changed underneath it presents stale arithmetic as the current state.
        compareTarget = CompareTarget.None

        // The write itself is what the panel shows from here, one address at a time.
        restoreTarget = file
        restoreStates = backup.entries.associate {
            it.address to ResetViewModel.CounterByteState.PENDING
        }
        showRestorePlan = false

        scope.launch {
            resetCancellation()
            startRun("Generating restore sequence…")

            val sequence = SequenceGenerator.generateWrites(model, backup.writes)
            info(
                "Restoring ${backup.entries.size} addresses to ${model.name} from the backup taken ${backup.createdAt}.",
            )

            val listener = object : Executor.Listener {
                override fun onPacket(index: Int, total: Int, message: String) = onMain {
                    updateProgress(index.toFloat() / total, "Packet $index / $total — $message")
                }

                // The same per-address feedback a reset draws. Nothing is read back afterwards, so
                // an acknowledged byte stays acknowledged rather than becoming verified.
                override fun onWrite(address: Int, value: Int, state: Executor.WriteState) = onMain {
                    val next = when (state) {
                        Executor.WriteState.WRITING -> ResetViewModel.CounterByteState.WRITING
                        Executor.WriteState.ACKNOWLEDGED -> ResetViewModel.CounterByteState.ACKNOWLEDGED
                        Executor.WriteState.FAILED -> ResetViewModel.CounterByteState.FAILED
                    }
                    restoreStates = restoreStates + (address to next)
                }

                override fun onTrace(line: String) = onMain { trace(line) }
            }

            val isDry = dryRun()
            // A dry run reads invented bytes off the fake device. Saving those would put a file in
            // the backups folder that looks like a real recovery point and is not one.
            val net = saveFirst && !isDry
            if (saveFirst && isDry) {
                info("DRY RUN — a live restore would read and save the current bytes before writing.")
            }

            var savedFirst: File? = null
            val result = withContext(io) {
                openTransport(device, isDry).use { transport ->
                    transport?.let {
                        if (net) {
                            onMain { updateProgress(0f, "Saving the bytes about to be overwritten…") }
                            val before = CounterReader.readAll(it, model, specsFor(model), null, isCancelled)
                            when (val outcome = captureSafetyNet(model, sequence, before, device)) {
                                is SafetyNet.Blocked -> return@let Executor.Result(error = outcome.reason)
                                is SafetyNet.Saved -> savedFirst = outcome.file
                            }
                        }

                        Executor.execute(
                            transport = it,
                            sequence = sequence,
                            listener = listener,
                            isCancelled = isCancelled,
                        )
                    }
                } ?: Executor.Result(error = transportError())
            }

            updateProgress(1f, "")
            savedFirst?.let { refreshNow() }
            finishRun(result, isDry)

            if (result.success) {
                good("Restore complete — ${result.writesAcknowledged}/${result.writesTotal} writes acknowledged.")
                warn("Power-cycle the printer to finalise the change.")
            } else {
                bad(result.error.ifBlank { "The restore did not complete." })
            }
        }
        return true
    }

    private sealed interface SafetyNet {
        /** Null where there was nothing to save rather than a failure to save it. */
        data class Saved(val file: File?) : SafetyNet

        data class Blocked(val reason: String) : SafetyNet
    }

    /**
     * Saves what the printer holds at the addresses [sequence] is about to write. Runs on the IO
     * thread, inside the same connection as the write it protects.
     *
     * Anything short of a complete capture blocks the restore. A safety net with holes in it is
     * worse than none: it reads as cover for a write it could not actually undo.
     */
    private fun captureSafetyNet(
        model: PrinterModel,
        sequence: List<ByteArray>,
        before: CounterReader.Report,
        device: MatchedPrinter?,
    ): SafetyNet {
        before.error?.let {
            return SafetyNet.Blocked(
                "Nothing was written — the bytes this restore would overwrite could not be read " +
                    "first ($it). Reads are unprivileged and safe to retry.",
            )
        }

        val capture = EepromBackup.capture(
            model = model.name,
            sequence = sequence,
            readings = before.readings,
            printerSerial = identifyingSerial(device),
        )

        return when (capture) {
            is Capture.NothingToWrite -> SafetyNet.Blocked("This snapshot writes nothing, so nothing was sent.")

            is Capture.Incomplete -> {
                val shown = capture.missing.take(8).joinToString(", ")
                val more = if (capture.missing.size > 8) " +${capture.missing.size - 8} more" else ""
                SafetyNet.Blocked(
                    "Stopped before writing anything: ${capture.missing.size} of the addresses this " +
                        "restore would write did not answer the read, so they could not be saved " +
                        "first ($shown$more). Reads are unprivileged and safe to retry.",
                )
            }

            is Capture.Ready -> runCatching { capture.backup.save(backupDir()) }.fold(
                onSuccess = { saved ->
                    onMain {
                        good(
                            "Saved the current bytes to ${saved.name} — ${capture.backup.entries.size} " +
                                "addresses, taken just before this restore.",
                        )
                    }
                    SafetyNet.Saved(saved)
                },
                onFailure = { e ->
                    SafetyNet.Blocked(
                        "Nothing was written — the current bytes could not be saved first " +
                            "(${e.message ?: e::class.simpleName}).",
                    )
                },
            )
        }
    }

    /** Whether this backup may be written to this printer, per [UnitSelector]. */
    private fun allowedToLand(backup: EepromBackup, model: PrinterModel, device: MatchedPrinter?): Boolean {
        if (dryRun()) {
            if (!backup.model.equals(model.name, ignoreCase = true)) {
                warn("This backup is for ${backup.model} but ${model.name} is selected. A live run would refuse.")
            }
            return true
        }

        writeBlockedReason()?.let {
            bad(it)
            return false
        }

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

    /**
     * Why a fresh snapshot cannot be read from the application-wide target. This action reads the
     * printer and writes a file, so it is gated as a read is: a model the printer disagrees with is
     * a caution, not a block — reading it is how that disagreement gets settled.
     */
    val createSnapshotBlockedReason: String?
        get() = when {
            selectedDevice() == null -> "Select a printer from the target above."
            selectedModel() == null -> "Choose the printer's model in the target above."
            else -> familyBlockedReason()
        }

    val canCreateSnapshot: Boolean
        get() = !busy() && !reading() && createSnapshotBlockedReason == null

    /** Takes a fresh real-printer reading, then saves it. This action never reuses an old report. */
    fun readAndSaveSnapshot() {
        if (busy() || reading()) {
            bad("Nothing saved — another printer operation is already in progress.")
            return
        }
        val model = selectedModel() ?: return
        val device = selectedDevice() ?: return
        createSnapshotBlockedReason?.let {
            bad("Nothing saved — $it")
            return
        }

        scope.launch {
            performRead(model, device, false)
            val currentModel = selectedModel()
            val currentDevice = selectedDevice()
            val report = readReport()
            when {
                currentDevice?.device?.id != device.device.id ->
                    bad("Nothing saved — the target printer changed while it was being read.")
                currentModel?.name != model.name ->
                    bad("Nothing saved — the target model changed while the printer was being read.")
                report == null -> bad("Nothing saved — the printer produced no reading.")
                else -> snapshotBlockedReason(model, report)?.let {
                    bad("Nothing saved — $it.")
                } ?: saveSnapshotNow(model, report)
            }
        }
    }

    /** Why the counters currently on screen cannot be saved, or null when they can. */
    val snapshotBlockedReason: String?
        get() {
            val model = selectedModel()
            val report = readReport()
            return snapshotBlockedReason(model, report)
        }

    private fun snapshotBlockedReason(model: PrinterModel?, report: CounterReader.Report?): String? = when {
        model == null -> "pick the model these counters belong to first"
        report == null -> "read the counters first — a snapshot stores bytes that were actually read"
        !report.model.equals(model.name, ignoreCase = true) ->
            "the last reading belongs to ${report.model}, not ${model.name}; read again"
        readWasSimulated() ->
            "these values came from the simulated EEPROM of a dry run, not from a printer. " +
                "Switch to Live and read again"
        report.answered == 0 -> "nothing answered the last read, so there is nothing to save"
        else -> null
    }

    val canSaveSnapshot: Boolean get() = !busy() && snapshotBlockedReason == null

    /** Saves the counters on screen as a snapshot, at whatever moment the user asks for one. */
    fun saveSnapshot() {
        if (busy()) {
            bad("Nothing saved — another printer operation is already in progress.")
            return
        }
        val model = selectedModel() ?: return
        val report = readReport() ?: return

        snapshotBlockedReason?.let {
            bad("Nothing saved — $it.")
            return
        }

        scope.launch { saveSnapshotNow(model, report) }
    }

    private suspend fun saveSnapshotNow(model: PrinterModel, report: CounterReader.Report) {
        val capture = EepromBackup.capture(
            model = model.name,
            sequence = SequenceGenerator.generate(model),
            readings = report.readings,
            printerSerial = identifyingSerial(selectedDevice()),
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
                    refreshNow()
                    selectedSnapshot = snapshots.firstOrNull { it.file == file }
                }.onFailure { e ->
                    bad("Snapshot not saved: ${e.message ?: e::class.simpleName}.")
                }
            }
        }
    }

    /** Where snapshots are kept. Shown in the panel, so it comes from the same place it is read. */
    val snapshotDir: File get() = backupDir()

    fun refreshSnapshots() {
        scope.launch { refreshNow() }
    }

    internal suspend fun refreshNow() {
        loadingSnapshots = true
        val found = withContext(io) {
            EepromBackup.list(backupDir())
                .map { SavedSnapshot(it, EepromBackup.load(it)) }
                .sortedWith(
                    compareByDescending<SavedSnapshot> { it.backup != null }
                        .thenByDescending { it.backup?.createdAt.orEmpty() },
                )
        }
        snapshots.clear()
        snapshots.addAll(found)
        selectedSnapshot = selectedSnapshot?.let { previous ->
            found.firstOrNull { it.file == previous.file }
        }
        loadingSnapshots = false
    }

    /** Opens a snapshot. Reading it is what selecting it does. */
    fun selectSnapshot(snapshot: SavedSnapshot?) {
        selectedSnapshot = snapshot
        compareTarget = CompareTarget.None
        showRestorePlan = false
        val backup = snapshot?.backup ?: return
        info(
            "Read ${snapshot.file.name} from disk — ${backup.model}, taken ${backup.takenAt}, " +
                "${backup.entries.size} addresses (${backup.changedByReset} differ from their " +
                "reset value). No printer was involved.",
        )
    }

    /** The database entry a snapshot belongs to, when there still is one. */
    private val selectedSnapshotModel: PrinterModel?
        get() = selectedSnapshot?.backup?.let { database()?.get(it.model) }

    /** The selected snapshot read back as if it had come off a printer. */
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

    /**
     * The selected snapshot drawn as a write instead of as saved bytes: what the printer holds now
     * on the left, the saved byte as the target on the right, and — once a restore is running — how
     * far each address has got. The same table a reset uses, given a different set of targets.
     *
     * This is also the answer to "what would restoring change", which a comparison cannot give:
     * a comparison is two samples in time order, and the current reading is always the later one.
     */
    val liveRestore: LiveRestore?
        get() {
            val selected = selectedSnapshot ?: return null
            val backup = selected.backup ?: return null
            val running = restoreTarget == selected.file && restoreStates.isNotEmpty()
            if (!running && !showRestorePlan) return null

            val model = selectedSnapshotModel
            val plan = WritePlan(
                targetLabel = WritePlan.SNAPSHOT_TARGET,
                targets = backup.entries.associate { it.address to it.value },
                states = if (running) restoreStates else emptyMap(),
            )
            // The left-hand byte is the printer's, not the file's — a restore's "from" is whatever is
            // in the printer now. Where no live reading covers an address it stays unknown rather
            // than borrowing the saved byte and claiming nothing would change.
            val live = currentReadingOf(backup.model)
            val report = CounterReader.Report(
                backup.model,
                backup.readings(model).map { it.copy(value = live[it.address]) },
            )
            val applied = plan.applyTo(report)
            return LiveRestore(
                report = applied,
                counters = model?.let { CounterReader.decode(applied.readings, specsFor(it)) }.orEmpty(),
                plan = plan,
                running = running,
                haveCurrent = live.isNotEmpty(),
                // Counted off the untouched report against the plan's targets: once a write lands,
                // applied.value *is* the target, and the question was how many differed to begin
                // with. The report's own expectedAfterReset is the model's reset value, not this.
                differing = report.readings.count { it.value != null && it.value != plan.targets[it.address] },
                comparable = report.readings.count { it.value != null },
            )
        }

    /** The selected snapshot as a write, ready for the counter table. */
    data class LiveRestore(
        val report: CounterReader.Report,
        val counters: List<CounterReader.DecodedCounter>,
        val plan: WritePlan,
        /** True once bytes are going out; false while this is only what a restore would do. */
        val running: Boolean,
        /** Whether the left-hand column is a real reading rather than unknowns. */
        val haveCurrent: Boolean,
        /** Addresses whose saved byte is not what the printer holds — what this write would change. */
        val differing: Int,
        /** Addresses where both sides are known, so the count above means something. */
        val comparable: Int,
    )

    /** The live reading of [model], by address, or empty when there is none worth trusting. */
    private fun currentReadingOf(model: String): Map<Int, Int?> = readReport()
        ?.takeIf { !readWasSimulated() && it.model.equals(model, ignoreCase = true) }
        ?.readings
        ?.associate { it.address to it.value }
        .orEmpty()

    /** Whether the panel can be asked what a restore would change: it needs the printer's side. */
    val canPreviewRestore: Boolean
        get() = selectedSnapshot?.backup?.let { currentReadingOf(it.model).isNotEmpty() } == true

    fun previewRestore() {
        if (!canPreviewRestore) {
            bad("Read the printer first — what a restore would change is measured against it.")
            return
        }
        compareTarget = CompareTarget.None
        showRestorePlan = true
    }

    fun clearRestorePlan() {
        showRestorePlan = false
    }

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
            val model = selectedModel()
                ?: return "Choose ${backup.model} in the target above so there is a model to read."
            if (!model.name.equals(backup.model, ignoreCase = true)) {
                return "This snapshot is a ${backup.model} but ${model.name} is selected. The same " +
                    "address is a different counter on each, so there is nothing to compare."
            }
            if (selectedDevice() == null) {
                return "No printer is selected — choose one from the printer menu above."
            }
            if (dryRun()) {
                return "Dry run invents a byte for every address, so comparing against it would " +
                    "show differences that are not real. Switch to Live to read this printer."
            }
            return null
        }

    val canReadForComparison: Boolean get() = !busy() && !reading() && compareReadBlockedReason == null

    /** Compares the selected snapshot against another file. Touches no printer. */
    fun compareWithSnapshot(file: File) {
        compareTarget = CompareTarget.Snapshot(file)
    }

    /** Compares against the reading already in memory. */
    fun compareWithCurrentReading() {
        if (readReport() == null) {
            bad("There is no current reading to compare against — read the counters first.")
            return
        }
        if (readWasSimulated()) {
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
        if (busy() || reading()) {
            bad("Cannot read for comparison — another printer operation is already in progress.")
            return
        }
        val model = selectedModel() ?: return
        compareReadBlockedReason?.let {
            bad("Cannot read for comparison — $it")
            return
        }

        scope.launch {
            performRead(model, selectedDevice(), false)
            if (readReport()?.answered?.let { it > 0 } == true) {
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
                    val report = readReport() ?: return null
                    val other = SnapshotComparison.Side(
                        label = "Current reading",
                        takenAt = "read just now",
                        model = report.model,
                        serial = identifyingSerial(selectedDevice()),
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
                    if (otherBackup.createdAt >= backup.createdAt) {
                        SnapshotComparison.compare(before = base, after = other, specs = specs)
                    } else {
                        SnapshotComparison.compare(before = other, after = base, specs = specs)
                    }
                }
            }
        }

    /** Snapshots taken from the model in the application-wide target, newest first. */
    val snapshotsForSelectedModel: List<SavedSnapshot>
        get() {
            val model = selectedModel() ?: return emptyList()
            return snapshots.filter { it.backup?.model.equals(model.name, ignoreCase = true) }
        }

    /** True when the current live reading could be compared against something on disk. */
    val canOfferComparison: Boolean
        get() = readReport()?.answered?.let { it > 0 } == true &&
            !readWasSimulated() &&
            snapshotsForSelectedModel.isNotEmpty()

    /** Opens the newest compatible snapshot, already paired with the reading on screen. */
    fun compareCurrentReadingWithNewestSnapshot() {
        val snapshot = snapshotsForSelectedModel.firstOrNull() ?: return
        selectSnapshot(snapshot)
        compareWithCurrentReading()
        openSnapshotsTab()
    }

    /** Why the selected snapshot cannot be written back right now, or null when it can. */
    val snapshotRestoreBlockedReason: String?
        get() {
            if (busy()) return "Another printer operation is already in progress."
            val backup = selectedSnapshot?.backup ?: return "Select a snapshot."
            val model = selectedModel()
                ?: return "Choose ${backup.model} in the target above — a restore needs its write key."
            if (!model.name.equals(backup.model, ignoreCase = true)) {
                return "This snapshot is a ${backup.model}; ${model.name} is selected. " +
                    "Switch the selection before writing one model's bytes into another."
            }
            return if (dryRun()) null else writeBlockedReason()
        }

    /** Puts the application-wide target on the model the selected snapshot came from. */
    fun useSnapshotModel() {
        val backup = selectedSnapshot?.backup ?: return
        val model = database()?.get(backup.model) ?: run {
            bad("'${backup.model}' is not in the database, so its write key is unavailable.")
            return
        }
        if (!selectModel(model)) return
        updateQuery(model.name)
        info("Selected ${model.name} — the model this snapshot was taken from.")
    }

    /** Writes the selected snapshot back. Gated exactly as any other restore is. */
    fun restoreSelectedSnapshot(saveFirst: Boolean = true) {
        val selected = selectedSnapshot ?: return
        restore(selected.backup ?: return, selected.file, saveFirst)
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /** `use` over a nullable transport, so the open-failure path stays a single expression. */
    private inline fun <T> Transport?.use(block: (Transport?) -> T?): T? = try {
        block(this)
    } finally {
        this?.close()
    }
}
