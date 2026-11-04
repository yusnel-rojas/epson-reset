package nl.rlabs.epsonreset.i18n

import nl.rlabs.epsonreset.protocol.Status
import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.ink_black
import nl.rlabs.epsonreset.resources.ink_cyan
import nl.rlabs.epsonreset.resources.ink_magenta
import nl.rlabs.epsonreset.resources.ink_unknown
import nl.rlabs.epsonreset.resources.ink_yellow
import nl.rlabs.epsonreset.resources.printer_error_card_loading
import nl.rlabs.epsonreset.resources.printer_error_cartridge_lever
import nl.rlabs.epsonreset.resources.printer_error_cd_tray_out
import nl.rlabs.epsonreset.resources.printer_error_cleaning_impossible
import nl.rlabs.epsonreset.resources.printer_error_cover_open
import nl.rlabs.epsonreset.resources.printer_error_cutter_error
import nl.rlabs.epsonreset.resources.printer_error_cutter_jam
import nl.rlabs.epsonreset.resources.printer_error_double_feed
import nl.rlabs.epsonreset.resources.printer_error_double_feed_error
import nl.rlabs.epsonreset.resources.printer_error_fatal
import nl.rlabs.epsonreset.resources.printer_error_front_cover_open
import nl.rlabs.epsonreset.resources.printer_error_ink_out
import nl.rlabs.epsonreset.resources.printer_error_maintenance
import nl.rlabs.epsonreset.resources.printer_error_maintenance_box_full
import nl.rlabs.epsonreset.resources.printer_error_maintenance_cover_open
import nl.rlabs.epsonreset.resources.printer_error_named
import nl.rlabs.epsonreset.resources.printer_error_no_maintenance_box
import nl.rlabs.epsonreset.resources.printer_error_no_maintenance_cartridge
import nl.rlabs.epsonreset.resources.printer_error_other_interface
import nl.rlabs.epsonreset.resources.printer_error_paper_jam
import nl.rlabs.epsonreset.resources.printer_error_paper_misc
import nl.rlabs.epsonreset.resources.printer_error_paper_out
import nl.rlabs.epsonreset.resources.printer_error_rear_cover_open
import nl.rlabs.epsonreset.resources.printer_error_strip_bin_missing
import nl.rlabs.epsonreset.resources.printer_error_tear_off
import nl.rlabs.epsonreset.resources.printer_error_tray_cover_open
import nl.rlabs.epsonreset.resources.printer_error_unknown
import nl.rlabs.epsonreset.resources.printer_error_waste_ink_full
import nl.rlabs.epsonreset.resources.printer_state_cleaning
import nl.rlabs.epsonreset.resources.printer_state_error
import nl.rlabs.epsonreset.resources.printer_state_factory
import nl.rlabs.epsonreset.resources.printer_state_self_test
import nl.rlabs.epsonreset.resources.printer_state_sentence
import nl.rlabs.epsonreset.resources.printer_state_sentence_detail
import nl.rlabs.epsonreset.resources.printer_state_shutting_down
import nl.rlabs.epsonreset.resources.printer_state_unknown

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
        0x0B to Res.string.printer_error_double_feed,
        0x0C to Res.string.printer_error_paper_misc,
        0x10 to Res.string.printer_error_waste_ink_full,
        0x11 to Res.string.printer_error_tear_off,
        0x12 to Res.string.printer_error_double_feed_error,
        0x16 to Res.string.printer_error_cleaning_impossible,
        0x1A to Res.string.printer_error_cartridge_lever,
        0x1C to Res.string.printer_error_cutter_error,
        0x1D to Res.string.printer_error_cutter_jam,
        0x22 to Res.string.printer_error_no_maintenance_cartridge,
        0x25 to Res.string.printer_error_rear_cover_open,
        0x29 to Res.string.printer_error_cd_tray_out,
        0x2A to Res.string.printer_error_card_loading,
        0x2B to Res.string.printer_error_tray_cover_open,
        0x2D to Res.string.printer_error_no_maintenance_box,
        0x2E to Res.string.printer_error_maintenance_box_full,
        0x36 to Res.string.printer_error_maintenance_cover_open,
        0x37 to Res.string.printer_error_front_cover_open,
        0x40 to Res.string.printer_error_strip_bin_missing,
        0x41 to Res.string.printer_error_maintenance,
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

    /** Named with the raw code beside it, so a screenshot or a pasted log can still be traced. */
    fun error(code: Int): UiText {
        val hex = "0x%02X".format(code)
        return ERRORS[code]?.let { UiText.of(Res.string.printer_error_named, UiText.of(it), hex) }
            ?: UiText.of(Res.string.printer_error_unknown, hex)
    }

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
