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
import nl.redlabs.epsonreset.i18n.Strings
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.cal_answers_family
import nl.redlabs.epsonreset.resources.cal_applied
import nl.redlabs.epsonreset.resources.cal_applied_item
import nl.redlabs.epsonreset.resources.cal_derived_from
import nl.redlabs.epsonreset.resources.cal_issue_opened
import nl.redlabs.epsonreset.resources.cal_name_is_family
import nl.redlabs.epsonreset.resources.cal_name_the_model
import nl.redlabs.epsonreset.resources.cal_name_unknown
import nl.redlabs.epsonreset.resources.cal_no_browser
import nl.redlabs.epsonreset.resources.cal_no_file_manager
import nl.redlabs.epsonreset.resources.cal_no_overlay
import nl.redlabs.epsonreset.resources.cal_overlay_removed
import nl.redlabs.epsonreset.resources.cal_overrides_confirmed
import nl.redlabs.epsonreset.resources.cal_overrides_strongest
import nl.redlabs.epsonreset.resources.cal_reload_failed
import nl.redlabs.epsonreset.resources.cal_remove_failed
import nl.redlabs.epsonreset.resources.cal_reverted
import nl.redlabs.epsonreset.resources.cal_too_long
import nl.redlabs.epsonreset.resources.cal_why_dry_run
import nl.redlabs.epsonreset.resources.cal_why_no_model
import nl.redlabs.epsonreset.resources.cal_why_no_report
import nl.redlabs.epsonreset.resources.cal_why_nothing_answered
import nl.redlabs.epsonreset.resources.cal_why_nothing_decoded
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
                name.isEmpty() -> Strings.get(Res.string.cal_name_the_model)

                name.contains("series", ignoreCase = true) ->
                    Strings.get(Res.string.cal_name_is_family, name)

                database()?.let { it[name] == null } == true ->
                    Strings.get(Res.string.cal_name_unknown, name)

                id != null && id.via == ResetViewModel.Identity.Via.CONFIRMED && differs ->
                    Strings.get(Res.string.cal_overrides_confirmed, identified, id.reported, name)

                id != null && id.namesAFamily && differs ->
                    Strings.get(
                        Res.string.cal_answers_family,
                        id.reported,
                        id.via.label,
                        identified,
                        name,
                    )

                id != null && id.namesAFamily ->
                    Strings.get(Res.string.cal_derived_from, id.reported, id.via.label)

                differs ->
                    Strings.get(Res.string.cal_overrides_strongest, identified, id?.via?.label.toString(), name)

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
                selectedModel() == null -> Strings.get(Res.string.cal_why_no_model)
                report == null ->
                    Strings.get(Res.string.cal_why_no_report)
                readWasSimulated() ->
                    Strings.get(Res.string.cal_why_dry_run)
                report.answered == 0 -> Strings.get(Res.string.cal_why_nothing_answered)
                calibratableCounters.isEmpty() ->
                    Strings.get(Res.string.cal_why_nothing_decoded)
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
            Strings.get(
                Res.string.cal_applied,
                selected.name,
                measurements.joinToString("; ") {
                    Strings.get(
                        Res.string.cal_applied_item,
                        it.addressLabel,
                        it.max,
                        "%.2f".format(it.percent),
                    )
                },
            ),
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
                    Strings.get(Res.string.cal_reverted),
                )
            }.onFailure { e -> warn(Strings.get(Res.string.cal_reload_failed, e.message.orEmpty())) }
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
                    warn(Strings.get(Res.string.cal_no_overlay, file))
                    return@onSuccess
                }
                info(Strings.get(Res.string.cal_overlay_removed, file))
                revertSession()
            }.onFailure { e -> warn(Strings.get(Res.string.cal_remove_failed, e.message.orEmpty())) }
        }
    }

    /** Opens the data directory in the file manager. */
    fun openDataDirectory() {
        val dir = AppPaths.dataDir
        if (!Browser.openDirectory(dir)) warn(Strings.get(Res.string.cal_no_file_manager, dir))
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
            warn(Strings.get(Res.string.cal_too_long))
        }

        if (Browser.open(submission.url)) {
            info(
                Strings.get(Res.string.cal_issue_opened, selectedModel()?.name.toString()),
            )
        } else {
            toClipboard(report())
            warn(
                Strings.get(Res.string.cal_no_browser, Calibration.ISSUE_BASE),
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
