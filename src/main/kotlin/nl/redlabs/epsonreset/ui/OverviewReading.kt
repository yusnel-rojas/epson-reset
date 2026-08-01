package nl.redlabs.epsonreset.ui

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status
import java.time.Instant

/**
 * One user-requested, read-only view of what the selected printer reported. Never persisted.
 *
 * Not a "snapshot": in this app that word means an EEPROM backup written to a file, which has its
 * own tab. This is a reading taken and discarded.
 */
data class OverviewReading(
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
        ): OverviewReading {
            val suppliesAvailable =
                status?.inkLevels?.isNotEmpty() == true ||
                    printerMib?.supplies?.isNotEmpty() == true
            val countersAvailable = counters?.answered?.let { it > 0 } == true

            return OverviewReading(
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
            status?.errorDescription?.let { described ->
                add(
                    OverviewAlert(
                        severity = OverviewAlert.Severity.ERROR,
                        title = "Printer error reported",
                        detail = "The printer reports $described.",
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

            // The same threshold the counter table paints amber at. A pad at 94% used to colour a
            // row here while the headline above it said "No reported warnings" — and 90..99% is
            // the whole window this app exists to catch, because at 100% the printer has stopped.
            counters?.let { report ->
                CounterReader.decode(report.readings, specs).forEach { counter ->
                    val value = counter.value ?: return@forEach
                    val maximum = counter.spec.max?.takeIf { it > 0 } ?: return@forEach
                    val percent = counter.percent ?: return@forEach
                    val name = counter.spec.description.removeSuffix(" (?)")

                    when (overviewCounterLevel(percent)) {
                        OverviewCounterLevel.MAXED -> add(
                            OverviewAlert(
                                OverviewAlert.Severity.ERROR,
                                "$name is at its maximum",
                                "%,d of %,d — the printer may refuse to print until this is reset, "
                                    .format(value, maximum) +
                                    "and the pad or maintenance box itself needs replacing.",
                                OverviewAlert.Action.MAINTENANCE,
                            ),
                        )

                        OverviewCounterLevel.REACHING -> add(
                            OverviewAlert(
                                OverviewAlert.Severity.ATTENTION,
                                "$name is nearly full",
                                "%,d of %,d (%.0f%%).".format(value, maximum, percent),
                                OverviewAlert.Action.MAINTENANCE,
                            ),
                        )

                        OverviewCounterLevel.LOW -> Unit
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

data class OverviewAlert(
    val severity: Severity,
    val title: String,
    val detail: String,
    /** Where this is dealt with, when the app has somewhere to send you. */
    val action: Action? = null,
) {
    enum class Severity { ATTENTION, ERROR }

    enum class Action(val label: String) { MAINTENANCE("Open Maintenance") }
}
