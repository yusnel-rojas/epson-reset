package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.db.Calibration
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.CounterSpecs
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.update.AppVersion
import kotlin.coroutines.CoroutineContext

/** State and actions for measuring counter maxima from a real printer reading. */
class CalibrationState(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val database: () -> PrinterDatabase?,
    private val selectedModel: () -> PrinterModel?,
    private val identifiedModel: () -> PrinterModel?,
    private val identity: () -> ResetViewModel.Identity?,
    private val selectedDevice: () -> MatchedPrinter?,
    private val lastTest: () -> ConnectionTest.Result?,
    private val status: () -> Status.Report?,
    private val readReport: () -> CounterReader.Report?,
    private val readWasSimulated: () -> Boolean,
    private val decodedCounters: () -> List<CounterReader.DecodedCounter>,
    private val counterSpecs: () -> CounterSpecs?,
    private val updateCounterSpecs: (CounterSpecs) -> Unit,
    private val specsFor: (PrinterModel) -> List<CounterSpec>,
    private val info: (String) -> Unit,
    private val good: (String) -> Unit,
    private val warn: (String) -> Unit,
) {
    /** What the user has said about one counter on the calibration form. */
    data class Input(val percent: String = "", val reportedValue: String = "", val serviceRequired: Boolean = false)

    /** Keyed by the counter's address list, which is what identifies a counter across a re-read. */
    val inputs = mutableStateMapOf<List<Int>, Input>()

    /** Whether the contribution form is open. */
    var dialogOpen by mutableStateOf(false)

    /** Anything the contributor wants to add — where the percentage came from, usually. */
    var note by mutableStateOf("")

    /** The model this measurement is filed against — its own field, not the selected model. */
    var model by mutableStateOf("")

    /** Whether the measured maxima are already layered onto this session's layouts. */
    var applied by mutableStateOf(false)
        private set

    fun input(addresses: List<Int>): Input = inputs[addresses] ?: Input()

    fun setPercent(addresses: List<Int>, text: String) {
        inputs[addresses] = input(addresses).copy(percent = text)
    }

    fun setReportedValue(addresses: List<Int>, text: String) {
        inputs[addresses] = input(addresses).copy(reportedValue = text)
    }

    fun setServiceRequired(addresses: List<Int>, on: Boolean) {
        inputs[addresses] = input(addresses).copy(serviceRequired = on)
    }

    /** A new printer reading cannot keep percentages entered against the previous one. */
    internal fun resetForm() {
        inputs.clear()
        note = ""
        model = ""
        applied = false
    }

    /** Opens the form, starting from the best answer to "which model is this?" available. */
    fun open() {
        if (model.isBlank()) {
            model = identifiedModel()?.name ?: selectedModel()?.name.orEmpty()
        }
        dialogOpen = true
    }

    /** Every model whose counter layout is the one being measured. */
    val layoutSiblings: List<PrinterModel>
        get() {
            val db = database() ?: return emptyList()
            val layout = selectedModel()?.let { addressLayout(it) }?.takeIf { it.isNotEmpty() }
                ?: return emptyList()
            return db.models.filter { addressLayout(it) == layout }
        }

    /** A model's layout as the address groups alone. */
    private fun addressLayout(model: PrinterModel): List<List<Int>> = specsFor(model).map { it.addresses }

    /** What to offer in the model picker, best answer first. */
    val modelCandidates: List<String>
        get() {
            val anchor = (identifiedModel()?.name ?: selectedModel()?.name).orEmpty()
            val siblings = layoutSiblings
                .map { it.name }
                .sortedWith(
                    compareByDescending<String> { it.commonPrefixWith(anchor, true).length }
                        .thenBy { it },
                )

            return (listOfNotNull(identifiedModel()?.name, selectedModel()?.name) + siblings)
                .distinct()
                .take(MAX_MODEL_CANDIDATES)
        }

    /** What is doubtful about the name on the form, or null when nothing is. */
    val modelWarning: String?
        get() {
            val name = model.trim()
            val id = identity()
            val identified = id?.model?.name
            val differs = identified != null && !name.equals(identified, ignoreCase = true)

            return when {
                name.isEmpty() -> "Name the model this printer actually is."

                name.contains("series", ignoreCase = true) ->
                    "'$name' names a family, not a unit. Pick the exact model — a maximum filed " +
                        "against a family cannot later be told from one measured on any of its members."

                database()?.let { it[name] == null } == true ->
                    "'$name' is not in the database. Fine if it is a model nobody has added yet; " +
                        "worth a second look otherwise."

                id != null && id.via == ResetViewModel.Identity.Via.CONFIRMED && differs ->
                    "You confirmed this printer as $identified — it names only " +
                        "\"${id.reported}\", which is a family. Filing it as $name replaces your " +
                        "own answer, so file the one you can read off the printer."

                id != null && id.namesAFamily && differs ->
                    "This printer answers \"${id.reported}\" (${id.via.label}), which is a family " +
                        "rather than a unit — $identified is that family's database entry, not " +
                        "this printer's own answer. If $name is what the label says, it is the " +
                        "better name to file."

                id != null && id.namesAFamily ->
                    "The name above was derived from \"${id.reported}\" (${id.via.label}), which " +
                        "covers several units. Check it against the label on the printer — a " +
                        "maximum filed against the wrong sibling cannot afterwards be told from " +
                        "one measured on the right one."

                differs ->
                    "This printer names itself $identified via ${id?.via?.label}, which is the " +
                        "strongest identification available here. Filing it as $name overrides that."

                else -> null
            }
        }

    /** The name a submission is filed under. */
    private val modelName: String get() = model.trim()

    /** The counters a maximum could be attached to: one number each, and not sitting at zero. */
    val calibratableCounters: List<CounterReader.DecodedCounter>
        get() = decodedCounters().filter { it.spec.isSingleValue && (it.value ?: 0L) > 0L }

    /** Why a calibration cannot be measured from what is on screen, or null when it can. */
    val blockedReason: String?
        get() {
            val report = readReport()
            return when {
                selectedModel() == null -> "pick the model these counters belong to first"
                report == null ->
                    "read the counters first — a calibration is a reading with a maximum attached"
                readWasSimulated() ->
                    "these values came from the simulated EEPROM of a dry run, not from a printer. " +
                        "Switch to Live and read again"
                report.answered == 0 -> "nothing answered the last read, so there is nothing to measure"
                calibratableCounters.isEmpty() ->
                    "none of this model's counters decoded to a single non-zero number, so there is " +
                        "nothing to put a maximum on"
                else -> null
            }
        }

    /** One counter's line on the form. A null outcome means the user has not answered it yet. */
    data class Row(val counter: CounterReader.DecodedCounter, val outcome: Calibration.Outcome?)

    val rows: List<Row>
        get() = calibratableCounters.map { counter ->
            val input = input(counter.spec.addresses)
            val basis = when {
                input.serviceRequired -> Calibration.Basis.ServiceRequired
                input.percent.isNotBlank() ->
                    Calibration.Basis.Reference(input.percent, input.reportedValue)
                else -> return@map Row(counter, null)
            }
            Row(counter, Calibration.measure(counter.spec, counter.value, basis))
        }

    val measurements: List<Calibration.Measured>
        get() = rows.mapNotNull { (it.outcome as? Calibration.Outcome.Ok)?.measured }

    val canSubmit: Boolean
        get() = blockedReason == null && measurements.isNotEmpty() && modelName.isNotEmpty()

    fun entry(): String = Calibration.entryJson(
        model = modelName,
        measured = measurements,
        note = note,
        sharedLayout = layoutSiblings.size,
    )

    fun report(): String = Calibration.report(context(), measurements)

    fun overlay(): String {
        val selected = selectedModel() ?: return ""
        return Calibration.overlayJson(modelName, specsFor(selected), measurements)
    }

    /** Layers the measured maxima onto this session's layouts. */
    fun applyToSession() {
        if (!canSubmit) return
        val specs = counterSpecs() ?: return
        val selected = selectedModel() ?: return

        updateCounterSpecs(specs.withCalibration(Calibration.asCalibrationsFile(entry())))
        applied = true

        good(
            "Applied to ${selected.name} for this session: " +
                measurements.joinToString("; ") {
                    "addr ${it.addressLabel} max ${it.max} → %.2f%%".format(it.percent)
                } + ". Save the overlay to keep it after a restart.",
        )
    }

    /** Puts the counter layouts back to what is on disk. */
    fun revertSession() {
        scope.launch {
            val reloaded = withContext(io) { runCatching { CounterSpecs.load() } }
            reloaded.onSuccess {
                updateCounterSpecs(it)
                applied = false
                good(
                    "Counter layouts back to what is on disk. Any maximum applied to this session " +
                        "is gone; percentages are computed from the shipped figures again.",
                )
            }.onFailure { e -> warn("Could not reload the counter layouts: ${e.message}") }
        }
    }

    /** Whether a user overlay file is in force. */
    val overlayInForce: Boolean get() = counterSpecs()?.overlayLoaded == true

    /** Deletes `counters-overlay.json` and reloads. */
    fun removeCounterOverlay() {
        scope.launch {
            val file = AppPaths.counterOverlay
            val removed = withContext(io) { runCatching { file.takeIf { it.isFile }?.delete() ?: false } }

            removed.onSuccess { deleted ->
                if (!deleted) {
                    warn("No overlay file to remove at $file.")
                    return@onSuccess
                }
                info("Removed $file.")
                revertSession()
            }.onFailure { e -> warn("Could not remove the overlay: ${e.message}") }
        }
    }

    /** Opens the data directory in the file manager. */
    fun openDataDirectory() {
        val dir = AppPaths.dataDir
        if (!Browser.openDirectory(dir)) warn("Could not open a file manager. The directory is $dir")
    }

    /** Opens the calibration issue form, prefilled. */
    fun openIssue(toClipboard: (String) -> Unit) {
        if (!canSubmit) return

        val submission = Calibration.submission(
            model = modelName,
            entry = entry(),
            evidence = report(),
        )

        if (!submission.prefilled) {
            toClipboard(report())
            warn("Too long to prefill — the report is on the clipboard, paste it into the form.")
        }

        if (Browser.open(submission.url)) {
            info(
                "Opened a calibration issue for ${selectedModel()?.name}. Nothing has been sent: the " +
                    "form is yours to read and submit.",
            )
        } else {
            toClipboard(report())
            warn(
                "Could not open a browser. The report is on the clipboard — file it at " +
                    Calibration.ISSUE_BASE,
            )
        }
    }

    private fun context(): Calibration.Context {
        val id = identity()
        return Calibration.Context(
            model = modelName,
            identifiedAs = identifiedModel()?.name,
            confirmedAgainst = id?.takeIf { it.via == ResetViewModel.Identity.Via.CONFIRMED }?.reported,
            identifiedVia = id?.takeIf { it.via != ResetViewModel.Identity.Via.CONFIRMED }?.via?.label,
            reportedAs = id?.reported?.takeIf { it.isNotBlank() },
            layoutOf = selectedModel()?.name,
            sharedLayout = layoutSiblings.size,
            printer = selectedDevice()?.device?.displayName,
            transport = selectedDevice()?.device?.let { if (it.isNetwork) "network (SNMP)" else "USB" },
            firmware = lastTest()?.firmware,
            appVersion = AppVersion.display,
            inkLevels = status()?.inkLevels.orEmpty().map { it.colour to it.percent },
            statusFields = statusFields,
            note = note,
        )
    }

    /** The printer's own state block, as bytes, for the submission to carry. */
    private val statusFields: List<Pair<String, String>>
        get() {
            val report = status() ?: return emptyList()
            return listOf(0x01, 0x02, 0x04).mapNotNull { type ->
                report[type]?.let { it.name to it.hex }
            }
        }

    private companion object {
        const val MAX_MODEL_CANDIDATES = 24
    }
}
