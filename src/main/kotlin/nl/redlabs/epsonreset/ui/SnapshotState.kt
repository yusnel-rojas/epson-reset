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
    private val targetModelBlockedReason: () -> String?,
    private val writeBlockedReason: () -> String?,
    private val busy: () -> Boolean,
    private val reading: () -> Boolean,
    private val readReport: () -> CounterReader.Report?,
    private val readWasSimulated: () -> Boolean,
    private val status: () -> Status.Report?,
    private val specsFor: (PrinterModel) -> List<CounterSpec>,
    private val performRead: suspend (PrinterModel, MatchedPrinter?, Boolean) -> Unit,
    private val selectModel: (PrinterModel) -> Unit,
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

    /** Writes the bytes from [backup] back to the addresses they came from. */
    fun restore(backup: EepromBackup) {
        val model = selectedModel() ?: run {
            bad("Pick the model first — a restore needs its write key.")
            return
        }
        val device = selectedDevice()

        if (!allowedToLand(backup, model, device)) return

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

                override fun onTrace(line: String) = onMain { trace(line) }
            }

            val isDry = dryRun()
            val result = withContext(io) {
                openTransport(device, isDry).use { transport ->
                    transport?.let {
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
            finishRun(result, isDry)

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

    /** Why a fresh snapshot cannot be read from the application-wide target. */
    val createSnapshotBlockedReason: String?
        get() = when {
            selectedDevice() == null -> "Select a printer from the target above."
            selectedModel() == null -> "Choose the printer's model in the target above."
            targetModelBlockedReason() != null -> targetModelBlockedReason()
            else -> null
        }

    val canCreateSnapshot: Boolean
        get() = !busy() && !reading() && createSnapshotBlockedReason == null

    /** Takes a fresh real-printer reading, then saves it. This action never reuses an old report. */
    fun readAndSaveSnapshot() {
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
            EepromBackup.list(backupDir()).map { SavedSnapshot(it, EepromBackup.load(it)) }
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
        selectModel(model)
        updateQuery(model.name)
        info("Selected ${model.name} — the model this snapshot was taken from.")
    }

    /** Writes the selected snapshot back. Gated exactly as any other restore is. */
    fun restoreSelectedSnapshot() {
        val backup = selectedSnapshot?.backup ?: return
        restore(backup)
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
