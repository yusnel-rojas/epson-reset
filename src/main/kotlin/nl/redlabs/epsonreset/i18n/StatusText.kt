package nl.redlabs.epsonreset.i18n

import nl.redlabs.epsonreset.protocol.Status
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.ink_black
import nl.redlabs.epsonreset.resources.ink_cyan
import nl.redlabs.epsonreset.resources.ink_magenta
import nl.redlabs.epsonreset.resources.ink_unknown
import nl.redlabs.epsonreset.resources.ink_yellow
import nl.redlabs.epsonreset.resources.printer_error_cover_open
import nl.redlabs.epsonreset.resources.printer_error_fatal
import nl.redlabs.epsonreset.resources.printer_error_ink_out
import nl.redlabs.epsonreset.resources.printer_error_maintenance
import nl.redlabs.epsonreset.resources.printer_error_other_interface
import nl.redlabs.epsonreset.resources.printer_error_paper_jam
import nl.redlabs.epsonreset.resources.printer_error_paper_out
import nl.redlabs.epsonreset.resources.printer_error_unknown
import nl.redlabs.epsonreset.resources.printer_state_cleaning
import nl.redlabs.epsonreset.resources.printer_state_error
import nl.redlabs.epsonreset.resources.printer_state_factory
import nl.redlabs.epsonreset.resources.printer_state_self_test
import nl.redlabs.epsonreset.resources.printer_state_sentence
import nl.redlabs.epsonreset.resources.printer_state_sentence_detail
import nl.redlabs.epsonreset.resources.printer_state_shutting_down
import nl.redlabs.epsonreset.resources.printer_state_unknown

/**
 * The printer's status codes in the reader's language. [Status] keeps its own English spellings for the
 * calibration report, the CLI probes and the tests; the screen is derived here from the same codes.
 */
object StatusText {

    private val INK_COLOURS = mapOf(
        0x00 to Res.string.ink_black,
        0x01 to Res.string.ink_cyan,
        0x02 to Res.string.ink_magenta,
        0x03 to Res.string.ink_yellow,
    )

    private val ERRORS = mapOf(
        0x00 to Res.string.printer_error_fatal,
        0x01 to Res.string.printer_error_other_interface,
        0x02 to Res.string.printer_error_cover_open,
        0x04 to Res.string.printer_error_paper_jam,
        0x05 to Res.string.printer_error_ink_out,
        0x06 to Res.string.printer_error_paper_out,
        0x10 to Res.string.printer_error_maintenance,
    )

    private val STATES = mapOf(
        0x00 to Res.string.printer_state_error,
        Status.STATE_SELF_TEST to Res.string.printer_state_self_test,
        Status.STATE_CLEANING to Res.string.printer_state_cleaning,
        0x08 to Res.string.printer_state_factory,
        0x0A to Res.string.printer_state_shutting_down,
    )

    fun inkColour(level: Status.InkLevel): UiText = INK_COLOURS[level.colourCode]?.let { UiText.of(it) }
        ?: UiText.of(Res.string.ink_unknown, level.colourCode)

    fun error(code: Int): UiText = ERRORS[code]?.let { UiText.of(it) }
        ?: UiText.of(Res.string.printer_error_unknown, "0x%02X".format(code))

    /**
     * Mirrors [Status.Report.busyReason]: the same codes, resolved the same way. The detail is a
     * second argument rather than a pre-parenthesized fragment, so a translator can move the
     * bracket — or drop it — without the frame fighting them.
     */
    fun busy(report: Status.Report): UiText? {
        val code = report.state ?: return null
        if (code == Status.STATE_IDLE) return null

        val state = STATES[code]?.let { UiText.of(it) }
            ?: UiText.of(Res.string.printer_state_unknown, "0x%02X".format(code))

        return report.errorCode
            ?.let { UiText.of(Res.string.printer_state_sentence_detail, state, error(it)) }
            ?: UiText.of(Res.string.printer_state_sentence, state)
    }
}
