package nl.rlabs.epsonreset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.rlabs.epsonreset.db.CounterSpec
import nl.rlabs.epsonreset.device.Serials
import nl.rlabs.epsonreset.history.CounterJournal
import nl.rlabs.epsonreset.history.CounterProjection
import nl.rlabs.epsonreset.i18n.Strings
import nl.rlabs.epsonreset.protocol.CounterReader
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.history_delete_failed
import nl.rlabs.epsonreset.resources.history_delete_failed_detail
import nl.rlabs.epsonreset.resources.history_deleted
import nl.rlabs.epsonreset.resources.history_deleted_log
import nl.rlabs.epsonreset.resources.history_no_serial
import nl.rlabs.epsonreset.resources.history_not_inspected
import nl.rlabs.epsonreset.resources.history_read_failed
import nl.rlabs.epsonreset.resources.history_sample_not_recorded
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** The selected printer's local read history and the settings actions that govern it. */
class CounterHistoryState(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val journal: CounterJournal,
    private val enabled: () -> Boolean,
    private val specsFor: (String) -> List<CounterSpec>,
    private val now: () -> Instant,
    private val info: (String) -> Unit,
    private val bad: (String) -> Unit,
) {
    data class View(
        val serial: String,
        val model: String,
        val samples: List<CounterJournal.Sample>,
        val trends: List<CounterProjection.Trend>,
    )

    var view by mutableStateOf<View?>(null)
        private set

    var stats by mutableStateOf(CounterJournal.Stats(0, 0, 0L))
        private set

    var unavailableReason by mutableStateOf<String?>(null)
        private set

    var actionStatus by mutableStateOf<String?>(null)
        private set

    /** Whether [actionStatus] reports success. A translated sentence cannot be re-read for its tone. */
    var actionOk by mutableStateOf(false)
        private set

    private var requestedTarget: Pair<String, String>? = null
    private var requestVersion = 0L

    /** Loads existing history as soon as the printer-and-model target is settled. */
    fun select(rawSerial: String?, model: String) {
        val lookupSerial = rawSerial?.trim()?.takeIf { it.isNotEmpty() }
        val serial = Serials.canonical(lookupSerial)
        if (serial == null) {
            requestVersion++
            requestedTarget = null
            view = null
            unavailableReason = Strings.get(Res.string.history_no_serial)
            return
        }

        val target = serial to model
        val version = ++requestVersion
        requestedTarget = target
        unavailableReason = null
        scope.launch {
            val loaded = withContext(io) {
                runCatching { journal.load(lookupSerial!!, model) to journal.stats() }
            }
            if (requestedTarget != target || requestVersion != version) return@launch
            loaded.onSuccess { (samples, latestStats) ->
                stats = latestStats
                show(samples.lastOrNull()?.serial ?: serial, model, samples)
            }.onFailure(::showReadFailure)
        }
    }

    fun clearSelection() {
        requestVersion++
        requestedTarget = null
        view = null
        unavailableReason = null
    }

    /** Records eligible reads when enabled, then always exposes any existing history. */
    suspend fun acceptLive(report: CounterReader.Report, rawSerial: String?) {
        // A quick reject so a failed or empty read never touches the panel; CounterJournal.append is
        // the authority on what is actually stored.
        if (report.error != null || report.answered == 0) return
        val lookupSerial = rawSerial?.trim()?.takeIf { it.isNotEmpty() }
        val serial = Serials.canonical(lookupSerial)
        if (serial == null) {
            requestVersion++
            requestedTarget = null
            view = null
            unavailableReason = Strings.get(Res.string.history_no_serial)
            return
        }

        val target = serial to report.model
        val version = ++requestVersion
        requestedTarget = target
        val shouldAppend = enabled()
        val loaded = withContext(io) {
            runCatching {
                val appendFailure = if (shouldAppend) {
                    runCatching { journal.append(lookupSerial, report, now()) }.exceptionOrNull()
                } else {
                    null
                }
                Triple(journal.load(lookupSerial!!, report.model), journal.stats(), appendFailure)
            }
        }

        if (requestedTarget != target || requestVersion != version) return
        loaded.onSuccess { (samples, latestStats, appendFailure) ->
            unavailableReason = null
            stats = latestStats
            show(samples.lastOrNull()?.serial ?: serial, report.model, samples)
            appendFailure?.let {
                actionStatus =
                    Strings.get(Res.string.history_sample_not_recorded, it.message ?: it::class.simpleName.orEmpty())
                actionOk = false
                bad(actionStatus!!)
            }
        }.onFailure(::showReadFailure)
    }

    fun refreshStats() {
        scope.launch {
            val result = withContext(io) { runCatching { journal.stats() } }
            result.onSuccess { stats = it }.onFailure {
                actionStatus =
                    Strings.get(Res.string.history_not_inspected, it.message ?: it::class.simpleName.orEmpty())
                actionOk = false
            }
        }
    }

    fun deleteAll() {
        requestVersion++
        scope.launch {
            val deleted = withContext(io) { runCatching { journal.deleteAll() } }
            deleted.onSuccess { ok ->
                if (ok) {
                    stats = CounterJournal.Stats(0, 0, 0L)
                    view = view?.copy(samples = emptyList(), trends = emptyList())
                    actionStatus = Strings.get(Res.string.history_deleted)
                    actionOk = true
                    info(Strings.get(Res.string.history_deleted_log))
                } else {
                    actionStatus = Strings.get(Res.string.history_delete_failed)
                    actionOk = false
                    bad(actionStatus!!)
                }
            }.onFailure {
                actionStatus =
                    Strings.get(Res.string.history_delete_failed_detail, it.message ?: it::class.simpleName.orEmpty())
                actionOk = false
                bad(actionStatus!!)
            }
        }
    }

    private fun show(serial: String, model: String, samples: List<CounterJournal.Sample>) {
        view = View(
            serial = serial,
            model = model,
            samples = samples,
            trends = CounterProjection.calculate(samples, specsFor(model)),
        )
    }

    private fun showReadFailure(error: Throwable) {
        unavailableReason =
            Strings.get(Res.string.history_read_failed, error.message ?: error::class.simpleName.orEmpty())
        bad(unavailableReason!!)
    }
}
