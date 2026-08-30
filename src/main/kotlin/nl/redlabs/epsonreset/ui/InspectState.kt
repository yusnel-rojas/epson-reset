package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.PrinterDatabase
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.MatchedPrinter
import nl.redlabs.epsonreset.i18n.Strings
import nl.redlabs.epsonreset.i18n.UiText
import nl.redlabs.epsonreset.i18n.resolveNow
import nl.redlabs.epsonreset.probe.DeviceInspector
import nl.redlabs.epsonreset.probe.SweepAnalysis
import nl.redlabs.epsonreset.protocol.Transport
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.inspect_keys_answered
import nl.redlabs.epsonreset.resources.inspect_many_keys
import nl.redlabs.epsonreset.resources.inspect_no_address
import nl.redlabs.epsonreset.resources.inspect_no_key_answered
import nl.redlabs.epsonreset.resources.inspect_no_reply
import nl.redlabs.epsonreset.resources.inspect_nothing_swept
import nl.redlabs.epsonreset.resources.inspect_other_operation
import nl.redlabs.epsonreset.resources.inspect_sweep_failed
import nl.redlabs.epsonreset.resources.inspect_sweep_read
import nl.redlabs.epsonreset.resources.inspect_sweeping
import nl.redlabs.epsonreset.resources.inspect_sweeping_progress
import nl.redlabs.epsonreset.resources.inspect_trying_keys
import nl.redlabs.epsonreset.resources.inspect_trying_keys_log
import kotlin.coroutines.CoroutineContext

/** Read-only exploration state for printers the database does not cover. */
class InspectState(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val database: () -> PrinterDatabase?,
    private val selectedDevice: () -> MatchedPrinter?,
    private val openTransport: (MatchedPrinter?, Boolean) -> Transport?,
    private val transportError: () -> String,
    private val specsFor: (PrinterModel) -> List<CounterSpec>,
    private val otherOperationRunning: () -> Boolean,
    private val resetCancellation: () -> Unit,
    private val isCancelled: () -> Boolean,
    private val updateProgress: (Float, String) -> Unit,
    private val trace: (String) -> Unit,
    private val info: (String) -> Unit,
    private val good: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val bad: (String) -> Unit,
) {
    val keys = mutableStateListOf<DeviceInspector.KeyResult>()

    /** The key the sweep will use — discovered, or typed in by someone who already knows it. */
    var key by mutableStateOf<Int?>(null)
        private set

    var sweep by mutableStateOf<DeviceInspector.Sweep?>(null)
        private set

    val candidates = mutableStateListOf<SweepAnalysis.Candidate>()

    var inspecting by mutableStateOf(false)
        private set

    /** Name the user gives the unknown printer; seeded from the USB descriptor. */
    var modelName by mutableStateOf("")

    /** Requires real hardware; a fake EEPROM would invent keys and candidate counters. */
    val canInspect: Boolean
        get() = !inspecting && !otherOperationRunning() && database() != null && selectedDevice() != null

    /** Highest address the sweep will reach. 0x1FF covers every mem_high in the database. */
    var rangeEnd by mutableStateOf(0x1FF)

    /** Models sharing [key] — the family whose layout the analysis leans on. */
    val siblings: List<PrinterModel>
        get() {
            val db = database() ?: return emptyList()
            val selectedKey = key ?: return emptyList()
            return DeviceInspector.siblingsOf(db, selectedKey)
        }

    val canExport: Boolean get() = sweep?.answered?.let { it > 0 } == true

    fun chooseKey(selected: Int?) {
        key = selected
        sweep = null
        candidates.clear()
    }

    private fun listener() = object : DeviceInspector.Listener {
        override fun onProgress(done: Int, total: Int, label: String) = onMain {
            updateProgress(if (total == 0) 0f else done.toFloat() / total, "$label ($done / $total)")
        }

        override fun onTrace(line: String) = onMain { trace(line) }
        override fun onNote(text: String) = onMain { info(text) }
    }

    /** Tries the database's known read keys against the attached printer. */
    fun discoverReadKey() {
        if (!canInspect) {
            bad(Strings.get(Res.string.inspect_other_operation))
            return
        }
        val db = database() ?: return
        val device = selectedDevice() ?: return

        scope.launch {
            resetCancellation()
            inspecting = true
            keys.clear()
            updateProgress(0f, Strings.get(Res.string.inspect_trying_keys))
            info(Strings.get(Res.string.inspect_trying_keys_log, DeviceInspector.candidateKeys(db).size))

            val results = withContext(io) {
                openTransport(device, false).use { transport ->
                    transport?.let {
                        DeviceInspector.discoverKey(
                            transport = it,
                            db = db,
                            listener = listener(),
                            isCancelled = isCancelled,
                        )
                    }
                } ?: emptyList()
            }

            keys.clear()
            keys.addAll(results)

            val answered = results.filter { it.answered }
            when {
                results.isEmpty() -> bad(Strings.get(Res.string.inspect_no_reply, transportError()))
                answered.isEmpty() -> warn(Strings.get(Res.string.inspect_no_key_answered))
                else -> {
                    chooseKey(answered.first().readKey)
                    good(
                        Strings.plural(
                            Res.plurals.inspect_keys_answered,
                            answered.size,
                            answered.size,
                            answered.first().hex,
                        ),
                    )
                    if (answered.size > 1) {
                        warn(
                            Strings.get(Res.string.inspect_many_keys),
                        )
                    }
                }
            }

            inspecting = false
            updateProgress(0f, "")
        }
    }

    /** Reads every address up to [rangeEnd] with the chosen key. Never writes. */
    fun sweepAddresses() {
        if (!canInspect) {
            bad(Strings.get(Res.string.inspect_other_operation))
            return
        }
        val selectedKey = key ?: return
        val device = selectedDevice() ?: return
        val end = rangeEnd

        scope.launch {
            resetCancellation()
            inspecting = true
            updateProgress(0f, Strings.get(Res.string.inspect_sweeping_progress))
            info(
                Strings.get(
                    Res.string.inspect_sweeping,
                    "0x%04X".format(end),
                    "0x%04X".format(selectedKey),
                ),
            )

            val addresses = (0..end).toList()
            val result = withContext(io) {
                openTransport(device, false).use { transport ->
                    transport?.let {
                        DeviceInspector.sweep(
                            transport = it,
                            readKey = selectedKey,
                            addresses = addresses,
                            listener = listener(),
                            isCancelled = isCancelled,
                        )
                    }
                } ?: DeviceInspector.Sweep(selectedKey, addresses, emptyMap(), transportError())
            }

            sweep = result
            val found = SweepAnalysis.candidates(
                sweep = result,
                siblings = siblings,
                specsFor = specsFor,
            )
            candidates.clear()
            candidates.addAll(found)

            when {
                result.error != null -> bad(Strings.get(Res.string.inspect_sweep_failed, result.error))
                result.answered == 0 -> bad(Strings.get(Res.string.inspect_no_address))
                else -> good(
                    UiText.plural(
                        Res.plurals.inspect_sweep_read,
                        found.size,
                        result.answered,
                        result.total,
                        found.size,
                    ).resolveNow(),
                )
            }

            inspecting = false
            updateProgress(0f, "")
        }
    }

    /** The overlay that makes this app read the discovered addresses on this model. */
    fun overlay(): String = SweepAnalysis.overlayJson(
        modelName.ifBlank { selectedDevice()?.device?.displayName ?: "MY-MODEL" },
        candidates,
    )

    /** A report to file, so a fix reaches every tool built on the same data. */
    fun report(): String {
        val currentSweep = sweep ?: return Strings.get(Res.string.inspect_nothing_swept)
        return SweepAnalysis.report(
            device = selectedDevice()?.device,
            sweep = currentSweep,
            candidates = candidates,
            keyResults = keys.filter { it.answered },
        )
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
