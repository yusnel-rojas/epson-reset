package nl.rlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.rlabs.epsonreset.device.DetectedPrinter
import nl.rlabs.epsonreset.device.MatchedPrinter
import nl.rlabs.epsonreset.device.PrinterTransports
import nl.rlabs.epsonreset.i18n.Strings
import nl.rlabs.epsonreset.protocol.Maintenance
import nl.rlabs.epsonreset.protocol.Status
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.maint_block_no_printer
import nl.rlabs.epsonreset.resources.maint_block_other_operation
import nl.rlabs.epsonreset.resources.maint_block_running
import nl.rlabs.epsonreset.resources.maint_block_usb_only
import nl.rlabs.epsonreset.resources.maint_log_gaps
import nl.rlabs.epsonreset.resources.maint_log_gaps_asserted
import nl.rlabs.epsonreset.resources.maint_log_needs_check
import nl.rlabs.epsonreset.resources.maint_log_no_gaps
import nl.rlabs.epsonreset.resources.maint_log_sent
import nl.rlabs.epsonreset.resources.maint_log_starting
import kotlin.coroutines.CoroutineContext

/** Guided nozzle-check and cleaning state. Cleaning is reachable only after visible evidence. */
class MaintenanceState(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val selectedDevice: () -> MatchedPrinter?,
    private val status: () -> Status.Report?,
    private val otherOperationRunning: () -> Boolean,
    private val transports: (DetectedPrinter) -> PrinterTransports.OpenResult,
    private val trace: (String) -> Unit,
    private val info: (String) -> Unit,
    private val good: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val bad: (String) -> Unit,
) {
    enum class PatternAssessment {
        NOT_CHECKED,
        AWAITING_ANSWER,
        NO_GAPS,
        GAPS,
    }

    private var recordedAssessment by mutableStateOf(PatternAssessment.NOT_CHECKED)
    private var assessmentDeviceId by mutableStateOf<String?>(null)

    /** The observation only belongs to the printer whose pattern produced it. */
    val patternAssessment: PatternAssessment
        get() = if (selectedDevice()?.device?.id == assessmentDeviceId) {
            recordedAssessment
        } else {
            PatternAssessment.NOT_CHECKED
        }

    var running by mutableStateOf<Maintenance.Operation?>(null)
        private set

    var lastResult by mutableStateOf<Maintenance.Result?>(null)
        private set

    private var cleanedDeviceId by mutableStateOf<String?>(null)

    val cleaningCompleted: Boolean get() = selectedDevice()?.device?.id == cleanedDeviceId

    /** Why no maintenance operation should start now, or null when the target is ready to check. */
    val blockedReason: String?
        get() {
            val device = selectedDevice()?.device
                ?: return Strings.get(Res.string.maint_block_no_printer)
            if (device.isNetwork) {
                return Strings.get(Res.string.maint_block_usb_only)
            }
            running?.let { return Strings.get(Res.string.maint_block_running, Strings.get(it.title)) }
            if (otherOperationRunning()) return Strings.get(Res.string.maint_block_other_operation)
            return Maintenance.blockedReason(status())
        }

    /** The evidence gate in front of both cleaning operations. */
    val cleaningEnabled: Boolean
        get() = patternAssessment == PatternAssessment.GAPS && blockedReason == null

    fun canRun(operation: Maintenance.Operation): Boolean =
        blockedReason == null && (operation == Maintenance.Operation.NOZZLE_CHECK || cleaningEnabled)

    /** Records the human observation the printer cannot ask for when the host starts the job. */
    fun answerNozzleCheck(hasGaps: Boolean) {
        if (patternAssessment != PatternAssessment.AWAITING_ANSWER) return
        recordedAssessment = if (hasGaps) PatternAssessment.GAPS else PatternAssessment.NO_GAPS
        if (hasGaps) {
            warn(Strings.get(Res.string.maint_log_gaps))
        } else {
            good(
                Strings.get(Res.string.maint_log_no_gaps),
            )
        }
    }

    /**
     * The same observation, for someone who has already looked at a pattern this app didn't print.
     *
     * The gate in front of cleaning is *somebody said there are gaps*, not *this app printed the
     * sheet* — so a person who already knows should not have to spend another sheet of paper to
     * tell it so. What stays true either way is that no cleaning is ever sent without that
     * assertion: this replaces the printout, not the answer.
     */
    fun assumeGaps() {
        val device = selectedDevice()?.device ?: run {
            bad(Strings.get(Res.string.maint_block_no_printer))
            return
        }
        assessmentDeviceId = device.id
        recordedAssessment = PatternAssessment.GAPS
        warn(Strings.get(Res.string.maint_log_gaps_asserted))
    }

    /** Back to the beginning, for a second opinion or a different printer's pattern. */
    fun clearAssessment() {
        assessmentDeviceId = null
        recordedAssessment = PatternAssessment.NOT_CHECKED
        cleanedDeviceId = null
    }

    /** Runs one confirmed operation, using a fresh USB transport for every protocol phase. */
    fun run(operation: Maintenance.Operation) {
        val target = selectedDevice()?.device ?: run {
            bad(Strings.get(Res.string.maint_block_no_printer))
            return
        }

        blockedReason?.let {
            bad(it)
            return
        }
        if (operation != Maintenance.Operation.NOZZLE_CHECK && !cleaningEnabled) {
            bad(Strings.get(Res.string.maint_log_needs_check))
            return
        }

        running = operation
        lastResult = null
        info(
            Strings.get(
                Res.string.maint_log_starting,
                Strings.get(operation.title).lowercase(),
                target.displayName,
            ),
        )

        scope.launch {
            var openError: String? = null
            val connection = Maintenance.Connection {
                when (val opened = transports(target)) {
                    is PrinterTransports.OpenResult.Ok -> opened.transport
                    is PrinterTransports.OpenResult.Failed -> {
                        openError = opened.detail
                        null
                    }
                }
            }
            val listener = object : Maintenance.Listener {
                override fun onTrace(line: String) = onMain { trace(line) }
                override fun onNote(text: String) = onMain { info(text) }
            }

            val result = withContext(io) {
                Maintenance.runInRemoteMode(connection, operation, listener = listener)
            }
            lastResult = result
            running = null

            if (result.error != null) {
                bad(openError ?: result.error)
                return@launch
            }

            good(Strings.get(Res.string.maint_log_sent, Strings.get(operation.title), target.displayName))
            when (operation) {
                Maintenance.Operation.NOZZLE_CHECK -> {
                    assessmentDeviceId = target.id
                    recordedAssessment = PatternAssessment.AWAITING_ANSWER
                }
                Maintenance.Operation.HEAD_CLEANING,
                Maintenance.Operation.POWER_CLEANING,
                -> cleanedDeviceId = target.id
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
}
