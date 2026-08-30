package nl.rlabs.epsonreset.i18n

import nl.rlabs.epsonreset.resources.Res
import nl.rlabs.epsonreset.resources.counter_platen_pads
import nl.rlabs.epsonreset.resources.counter_waste
import nl.rlabs.epsonreset.resources.counter_waste_main
import nl.rlabs.epsonreset.resources.counter_waste_platen
import nl.rlabs.epsonreset.resources.counter_wastes
import org.jetbrains.compose.resources.StringResource

/**
 * The five names the bot-synced upstream layouts ship, for the screen only.
 *
 * The English string stays the value everywhere it is not being read by a person: `isUncertain`
 * looks for its "?", `PadGroup.kindFromDescription` classifies on it, the counter journal stores it,
 * and the calibration report and overlay JSON are filed upstream with it. So this translates at the
 * point of display and nothing else, and a name upstream adds later falls through unchanged rather
 * than disappearing.
 */
private val COUNTER_NAMES: Map<String, StringResource> = mapOf(
    "Waste counter" to Res.string.counter_waste,
    "Waste counters" to Res.string.counter_wastes,
    "Platen pad counters" to Res.string.counter_platen_pads,
    "Waste counter (main pad)" to Res.string.counter_waste_main,
    "Waste counter (platen pad)" to Res.string.counter_waste_platen,
)

/** The counter's name without its " (?)" marker, which callers that want it re-add themselves. */
fun counterName(description: String): UiText {
    val base = description.removeSuffix(" (?)")
    return COUNTER_NAMES[base]?.let { UiText.of(it) } ?: UiText.raw(base)
}
