package nl.redlabs.epsonreset.ui

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status
import java.time.Instant

/** One user-requested, read-only view of what the selected printer reported. Never persisted. */
data class OverviewSnapshot(
    val targetId: String,
    val printerName: String,
    val linkKind: String,
    val model: String?,
    val refreshedAt: Instant,
    val connection: ConnectionTest.Result?,
    val status: Status.Report?,
    val printerMib: PrinterMib.Reading?,
    val counters: CounterReader.Report?,
    val coverage: List<OverviewCoverage>,
    val alerts: List<OverviewAlert>,
) {
    val firmware: String? get() = connection?.firmware

    companion object {
        fun create(
            targetId: String,
            printerName: String,
            linkKind: String,
            model: String?,
            refreshedAt: Instant,
            connection: ConnectionTest.Result?,
            status: Status.Report?,
            printerMib: PrinterMib.Reading?,
            counters: CounterReader.Report?,
            specs: List<CounterSpec>,
            counterUnavailableReason: String? = null,
        ): OverviewSnapshot {
            val suppliesAvailable =
                status?.inkLevels?.isNotEmpty() == true ||
                    printerMib?.supplies?.isNotEmpty() == true
            val countersAvailable = counters?.answered?.let { it > 0 } == true

            return OverviewSnapshot(
                targetId = targetId,
                printerName = printerName,
                linkKind = linkKind,
                model = model,
                refreshedAt = refreshedAt,
                connection = connection,
                status = status,
                printerMib = printerMib,
                counters = counters,
                coverage = listOf(
                    OverviewCoverage(
                        OverviewSection.CONNECTION,
                        connection?.opened == true && connection.answered,
                        connection?.headline ?: "Connection was not tested.",
                    ),
                    OverviewCoverage(
                        OverviewSection.STATUS,
                        status != null,
                        if (status != null) "Printer status reported." else "Status was not reported.",
                    ),
                    OverviewCoverage(
                        OverviewSection.SUPPLIES,
                        suppliesAvailable,
                        if (suppliesAvailable) "Supply information reported." else "Supplies were not reported.",
                    ),
                    OverviewCoverage(
                        OverviewSection.LIFETIME_USAGE,
                        printerMib?.lifeCount != null,
                        printerMib?.lifeCount?.let { "Lifetime page count reported." }
                            ?: "Lifetime page count was not reported.",
                    ),
                    OverviewCoverage(
                        OverviewSection.COUNTERS,
                        countersAvailable,
                        when {
                            countersAvailable -> "${counters?.answered} counter address(es) answered."
                            counterUnavailableReason != null -> counterUnavailableReason
                            counters?.error != null -> counters.error
                            counters != null -> "No counter address answered."
                            else -> "Counters were not read."
                        },
                    ),
                ),
                alerts = alerts(status, printerMib, counters, specs),
            )
        }

        private fun alerts(
            status: Status.Report?,
            printerMib: PrinterMib.Reading?,
            counters: CounterReader.Report?,
            specs: List<CounterSpec>,
        ): List<OverviewAlert> = buildList {
            status?.errorCode?.let { code ->
                add(
                    OverviewAlert(
                        severity = OverviewAlert.Severity.ERROR,
                        title = "Printer error reported",
                        detail = "Status error code 0x%02X.".format(code),
                    ),
                )
            }
            status?.busyReason?.let { reason ->
                add(OverviewAlert(OverviewAlert.Severity.ATTENTION, "Printer is not idle", reason))
            }
            status?.inkLevels.orEmpty().filter { it.isLow }.forEach { ink ->
                add(
                    OverviewAlert(
                        OverviewAlert.Severity.ATTENTION,
                        "${ink.colour} ink is low",
                        "The printer reports ${ink.percent}% remaining.",
                    ),
                )
            }

            val statusHasInk = status?.inkLevels?.isNotEmpty() == true
            printerMib?.supplies.orEmpty()
                .filter { it.isWarn && (!statusHasInk || !it.isInkConsumable) }
                .forEach { supply ->
                    val name = supply.description.ifBlank { supply.typeLabel ?: "Supply ${supply.index}" }
                    val amount = supply.percent?.let { "$it%" } ?: supply.levelNote ?: "a warning level"
                    add(
                        OverviewAlert(
                            OverviewAlert.Severity.ATTENTION,
                            if (supply.isWaste) "$name is filling up" else "$name is low",
                            "The printer reports $amount.",
                        ),
                    )
                }

            counters?.let { report ->
                CounterReader.decode(report.readings, specs).forEach { counter ->
                    val value = counter.value ?: return@forEach
                    val maximum = counter.spec.max?.takeIf { it > 0 } ?: return@forEach
                    if (value >= maximum) {
                        add(
                            OverviewAlert(
                                OverviewAlert.Severity.ATTENTION,
                                "${counter.spec.description} reached its measured maximum",
                                "$value / $maximum reported.",
                            ),
                        )
                    }
                }
            }
        }
    }
}

enum class OverviewSection(val label: String) {
    CONNECTION("Connection"),
    STATUS("Status"),
    SUPPLIES("Supplies"),
    LIFETIME_USAGE("Lifetime usage"),
    COUNTERS("Counters"),
}

data class OverviewCoverage(val section: OverviewSection, val available: Boolean, val detail: String)

data class OverviewAlert(val severity: Severity, val title: String, val detail: String) {
    enum class Severity { ATTENTION, ERROR }
}
