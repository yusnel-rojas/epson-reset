package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.device.DetectedPrinter
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.device.PrinterTransports
import nl.redlabs.epsonreset.protocol.Maintenance
import nl.redlabs.epsonreset.protocol.Status
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
                ?: return "Select a connected printer from the printer menu above first."
            if (device.isNetwork) {
                return "Maintenance commands are ESC/P2 print data and currently run over USB only. " +
                    "The network connection exposes the SNMP control channel, which can parse these " +
                    "commands but does not perform them. Connect this printer over USB."
            }
            running?.let { return "${it.label} is being sent. Wait for this operation to finish." }
            if (otherOperationRunning()) return "Another printer operation is already in progress."
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
            warn("The nozzle pattern has gaps. Cleaning is now available; it will send ink into the waste pad.")
        } else {
            good(
                "The nozzle pattern has no gaps. Nothing needs cleaning; an unnecessary cycle " +
                    "would only fill the pad.",
            )
        }
    }

    /** Runs one confirmed operation, using a fresh USB transport for every protocol phase. */
    fun run(operation: Maintenance.Operation) {
        val target = selectedDevice()?.device ?: run {
            bad("Select a connected printer from the printer menu above first.")
            return
        }

        blockedReason?.let {
            bad(it)
            return
        }
        if (operation != Maintenance.Operation.NOZZLE_CHECK && !cleaningEnabled) {
            bad("Run a nozzle check and confirm that its pattern has gaps before cleaning.")
            return
        }

        running = operation
        lastResult = null
        info("Starting ${operation.label.lowercase()} on ${target.displayName} over USB.")

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

            good("${operation.label} sent to ${target.displayName}. Verify the result at the printer.")
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
