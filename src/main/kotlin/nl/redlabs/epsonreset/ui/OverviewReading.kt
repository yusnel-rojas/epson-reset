package nl.redlabs.epsonreset.ui

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.device.ConnectionTest
import nl.redlabs.epsonreset.i18n.StatusText
import nl.redlabs.epsonreset.i18n.UiText
import nl.redlabs.epsonreset.i18n.counterName
import nl.redlabs.epsonreset.net.PrinterMib
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.overview_action_maintenance
import nl.redlabs.epsonreset.resources.overview_alert_counter_maxed_detail
import nl.redlabs.epsonreset.resources.overview_alert_counter_maxed_title
import nl.redlabs.epsonreset.resources.overview_alert_counter_nearly_detail
import nl.redlabs.epsonreset.resources.overview_alert_counter_nearly_title
import nl.redlabs.epsonreset.resources.overview_alert_error_detail
import nl.redlabs.epsonreset.resources.overview_alert_error_title
import nl.redlabs.epsonreset.resources.overview_alert_ink_low_detail
import nl.redlabs.epsonreset.resources.overview_alert_ink_low_title
import nl.redlabs.epsonreset.resources.overview_alert_not_idle
import nl.redlabs.epsonreset.resources.overview_alert_supply_detail
import nl.redlabs.epsonreset.resources.overview_alert_supply_filling
import nl.redlabs.epsonreset.resources.overview_alert_supply_low
import nl.redlabs.epsonreset.resources.overview_alert_supply_unnamed
import nl.redlabs.epsonreset.resources.overview_alert_supply_warn_level
import nl.redlabs.epsonreset.resources.overview_coverage_connection_untested
import nl.redlabs.epsonreset.resources.overview_coverage_counters_answered
import nl.redlabs.epsonreset.resources.overview_coverage_counters_none
import nl.redlabs.epsonreset.resources.overview_coverage_counters_unread
import nl.redlabs.epsonreset.resources.overview_coverage_lifetime_no
import nl.redlabs.epsonreset.resources.overview_coverage_lifetime_yes
import nl.redlabs.epsonreset.resources.overview_coverage_status_no
import nl.redlabs.epsonreset.resources.overview_coverage_status_yes
import nl.redlabs.epsonreset.resources.overview_coverage_supplies_no
import nl.redlabs.epsonreset.resources.overview_coverage_supplies_yes
import nl.redlabs.epsonreset.resources.overview_section_connection
import nl.redlabs.epsonreset.resources.overview_section_counters
import nl.redlabs.epsonreset.resources.overview_section_lifetime
import nl.redlabs.epsonreset.resources.overview_section_status
import nl.redlabs.epsonreset.resources.overview_section_supplies
import org.jetbrains.compose.resources.StringResource
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
            counterUnavailableReason: UiText? = null,
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
                        connection?.headline ?: UiText.of(Res.string.overview_coverage_connection_untested),
                    ),
                    OverviewCoverage(
                        OverviewSection.STATUS,
                        status != null,
                        if (status != null) {
                            UiText.of(Res.string.overview_coverage_status_yes)
                        } else {
                            UiText.of(Res.string.overview_coverage_status_no)
                        },
                    ),
                    OverviewCoverage(
                        OverviewSection.SUPPLIES,
                        suppliesAvailable,
                        if (suppliesAvailable) {
                            UiText.of(Res.string.overview_coverage_supplies_yes)
                        } else {
                            UiText.of(Res.string.overview_coverage_supplies_no)
                        },
                    ),
                    OverviewCoverage(
                        OverviewSection.LIFETIME_USAGE,
                        printerMib?.lifeCount != null,
                        printerMib?.lifeCount?.let { UiText.of(Res.string.overview_coverage_lifetime_yes) }
                            ?: UiText.of(Res.string.overview_coverage_lifetime_no),
                    ),
                    OverviewCoverage(
                        OverviewSection.COUNTERS,
                        countersAvailable,
                        when {
                            countersAvailable -> UiText.plural(
                                Res.plurals.overview_coverage_counters_answered,
                                counters?.answered ?: 0,
                                counters?.answered ?: 0,
                            )

                            counterUnavailableReason != null -> counterUnavailableReason
                            counters?.error != null -> UiText.raw(counters.error)
                            counters != null -> UiText.of(Res.string.overview_coverage_counters_none)
                            else -> UiText.of(Res.string.overview_coverage_counters_unread)
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
                        title = UiText.of(Res.string.overview_alert_error_title),
                        detail = UiText.of(Res.string.overview_alert_error_detail, StatusText.error(code)),
                    ),
                )
            }
            status?.let(StatusText::busy)?.let { reason ->
                add(
                    OverviewAlert(
                        OverviewAlert.Severity.ATTENTION,
                        UiText.of(Res.string.overview_alert_not_idle),
                        reason,
                    ),
                )
            }
            status?.inkLevels.orEmpty().filter { it.isLow }.forEach { ink ->
                add(
                    OverviewAlert(
                        OverviewAlert.Severity.ATTENTION,
                        UiText.of(Res.string.overview_alert_ink_low_title, StatusText.inkColour(ink)),
                        UiText.of(Res.string.overview_alert_ink_low_detail, ink.percent),
                    ),
                )
            }

            val statusHasInk = status?.inkLevels?.isNotEmpty() == true
            printerMib?.supplies.orEmpty()
                .filter { it.isWarn && (!statusHasInk || !it.isInkConsumable) }
                .forEach { supply ->
                    val name = supply.description.takeIf { it.isNotBlank() }?.let { UiText.raw(it) }
                        ?: supply.typeLabel
                        ?: UiText.of(Res.string.overview_alert_supply_unnamed, supply.index)
                    val amount = supply.percent?.let { UiText.raw("$it%") }
                        ?: supply.levelNote
                        ?: UiText.of(Res.string.overview_alert_supply_warn_level)
                    add(
                        OverviewAlert(
                            OverviewAlert.Severity.ATTENTION,
                            if (supply.isWaste) {
                                UiText.of(Res.string.overview_alert_supply_filling, name)
                            } else {
                                UiText.of(Res.string.overview_alert_supply_low, name)
                            },
                            UiText.of(Res.string.overview_alert_supply_detail, amount),
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
                    val name = counterName(counter.spec.description)

                    when (overviewCounterLevel(percent)) {
                        OverviewCounterLevel.MAXED -> add(
                            OverviewAlert(
                                OverviewAlert.Severity.ERROR,
                                UiText.of(Res.string.overview_alert_counter_maxed_title, name),
                                UiText.of(
                                    Res.string.overview_alert_counter_maxed_detail,
                                    "%,d".format(value),
                                    "%,d".format(maximum),
                                ),
                                OverviewAlert.Action.MAINTENANCE,
                            ),
                        )

                        OverviewCounterLevel.REACHING -> add(
                            OverviewAlert(
                                OverviewAlert.Severity.ATTENTION,
                                UiText.of(Res.string.overview_alert_counter_nearly_title, name),
                                UiText.of(
                                    Res.string.overview_alert_counter_nearly_detail,
                                    "%,d".format(value),
                                    "%,d".format(maximum),
                                    "%.0f%%".format(percent),
                                ),
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

enum class OverviewSection(val label: StringResource) {
    CONNECTION(Res.string.overview_section_connection),
    STATUS(Res.string.overview_section_status),
    SUPPLIES(Res.string.overview_section_supplies),
    LIFETIME_USAGE(Res.string.overview_section_lifetime),
    COUNTERS(Res.string.overview_section_counters),
}

data class OverviewCoverage(val section: OverviewSection, val available: Boolean, val detail: UiText)

data class OverviewAlert(
    val severity: Severity,
    val title: UiText,
    val detail: UiText,
    /** Where this is dealt with, when the app has somewhere to send you. */
    val action: Action? = null,
) {
    enum class Severity { ATTENTION, ERROR }

    enum class Action(val label: StringResource) { MAINTENANCE(Res.string.overview_action_maintenance) }
}
