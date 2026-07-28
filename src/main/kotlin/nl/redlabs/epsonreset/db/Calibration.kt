package nl.redlabs.epsonreset.db

import java.net.URLEncoder
import java.time.LocalDate

/** Turning one printer's reading into a counter maximum somebody else can use. */
object Calibration {

    /**
     * What a maximum was derived from. The two differ in strength, and the arithmetic differs with
     * them.
     */
    sealed interface Basis {

        /** The printer is declaring the pad at end of life *right now*. */
        data object ServiceRequired : Basis

        /**
         * Another tool reported a percentage for this same reading, so `max = value /
         * (percent/100)`.
         */
        data class Reference(val percent: String, val reportedValue: String = "") : Basis
    }

    /** How a fresh measurement relates to what the app already believes about this counter. */
    enum class Agreement {
        /** Nothing on file. This is the gap the whole mechanism exists to close. */
        FILLS_A_GAP,

        /** There is a maximum already, and this observation is consistent with it. */
        CONFIRMS,

        /** There is one, and this observation contradicts it. A finding, not a merge. */
        DISAGREES,
    }

    /** One counter's maximum, with everything needed to re-check it later. */
    data class Measured(
        val addresses: List<Int>,
        val description: String,
        val value: Long,
        val basis: Basis,
        val max: Int,
        /** Every integer maximum consistent with the observation. */
        val range: IntRange,
        /** What the app already had for this counter, if anything. */
        val existingMax: Int? = null,
    ) {
        /** The percentage this calibration would put on screen. */
        val percent: Double get() = value * 100.0 / max

        /** The percentage the app shows today, when it shows one at all. */
        val existingPercent: Double?
            get() = existingMax?.takeIf { it > 0 }?.let { value * 100.0 / it }

        val agreement: Agreement
            get() = when {
                existingMax == null -> Agreement.FILLS_A_GAP
                existingMax in range -> Agreement.CONFIRMS
                else -> Agreement.DISAGREES
            }

        /** True when this looks like one of Epson's limit bytes rather than a counter. */
        val looksLikeLimitByte: Boolean
            get() = addresses.size == 1 && value == LIMIT_BYTE

        /**
         * True when the window spans more than 1% of the maximum — the cue to type another decimal
         * rather than to trust the figure.
         */
        val isCoarse: Boolean get() = range.last - range.first > max / 100

        val addressLabel: String get() = addresses.joinToString(", ")

        val basisLabel: String
            get() = when (basis) {
                Basis.ServiceRequired -> "printer declared service required"
                is Basis.Reference -> buildString {
                    append("reference tool reported ${basis.percent}%")
                    basis.reportedValue.trim().takeIf { it.isNotEmpty() }
                        ?.let { append(" at a count of $it, matching this read") }
                }
            }
    }

    sealed interface Outcome {
        data class Ok(val measured: Measured) : Outcome

        /** [reason] completes "cannot calibrate this counter — …". */
        data class Rejected(val reason: String) : Outcome
    }

    /** Derives the maximum [value] implies on this counter, or says why it can't. */
    fun measure(spec: CounterSpec, value: Long?, basis: Basis): Outcome {
        if (!spec.isSingleValue) {
            return Outcome.Rejected(
                "this group is wider than one integer, so it has no single maximum to measure",
            )
        }
        if (value == null) return Outcome.Rejected("this counter did not decode to a value")
        if (value <= 0) return Outcome.Rejected("a counter reading 0 pins nothing — 0 is 0% of any maximum")
        if (value > MAX_PLAUSIBLE) return Outcome.Rejected("that reading is too large to be a pad counter")

        return when (basis) {
            // The counter reached the limit, so it is the limit. One integer, no window.
            Basis.ServiceRequired -> accept(spec, value, basis, value..value)

            is Basis.Reference -> {
                // A percentage is only about this counter if the tool that printed it was reading
                // this counter.
                val theirs = basis.reportedValue.trim()
                if (theirs.isNotEmpty()) {
                    val reported = theirs.toLongOrNull()
                        ?: return Outcome.Rejected("\"$theirs\" is not a counter value")
                    if (reported != value) {
                        return Outcome.Rejected(
                            "the other tool reads $reported where this reads $value, so the two are " +
                                "not looking at the same counter. Its percentage cannot calibrate " +
                                "this one — but the disagreement is worth reporting on its own",
                        )
                    }
                }

                val percent = parsePercent(basis.percent)
                    ?: return Outcome.Rejected(
                        "\"${basis.percent}\" is not a percentage — type what the other tool showed, " +
                            "digits and all (60.90, not 61)",
                    )
                if (percent.scaled <= 0) {
                    return Outcome.Rejected("0% pins no maximum — every maximum is 0% at a reading of 0")
                }

                val window = window(value, percent)
                if (window.first > MAX_PLAUSIBLE) {
                    return Outcome.Rejected(
                        "a percentage that small implies a maximum of ${window.first}, which is not " +
                            "a pad counter — check the figure",
                    )
                }
                accept(spec, value, basis, window)
            }
        }
    }

    /** The maximum is taken from the **bottom** of the window. */
    private fun accept(spec: CounterSpec, value: Long, basis: Basis, window: LongRange): Outcome = Outcome.Ok(
        Measured(
            addresses = spec.addresses,
            description = spec.description,
            value = value,
            basis = basis,
            max = window.first.toInt(),
            // The top end can run away when a tiny percentage is typed; the maximum itself is
            // already known to fit, and a bound past what a counter can hold says nothing.
            range = window.first.toInt()..window.last.coerceAtMost(MAX_PLAUSIBLE).toInt(),
            existingMax = spec.max,
        ),
    )

    /** A percentage as typed: `scaled / 10^decimals`, so `60.90` is `6090` with 2 decimals. */
    private data class Percent(val scaled: Long, val decimals: Int)

    private val PERCENT = Regex("""\d{1,3}(\.\d{1,4})?""")

    /**
     * A maximum is held as an Int ([CounterSpec.max]), so nothing past this can be one — and a
     * reading or an implied maximum that far out is a typo rather than a pad counter.
     */
    private const val MAX_PLAUSIBLE = Int.MAX_VALUE.toLong()

    /**
     * Epson's limit byte, the same 0x5E that [nl.redlabs.epsonreset.probe.SweepAnalysis.LIMIT_BYTE]
     * uses to find the edges of a counter.
     */
    private const val LIMIT_BYTE = 0x5EL

    private fun parsePercent(raw: String): Percent? {
        val text = raw.trim().removeSuffix("%").trim().replace(',', '.')
        if (!PERCENT.matches(text)) return null
        val decimals = text.substringAfter('.', "").length
        val scaled = text.replace(".", "").toLongOrNull() ?: return null
        return Percent(scaled, decimals)
    }

    /** Every integer maximum consistent with a percentage printed to a given number of decimals. */
    private fun window(value: Long, percent: Percent): LongRange {
        val unit = pow10(percent.decimals)
        val numerator = value * 100L * 2L * unit
        val low = ceilDiv(numerator, 2 * percent.scaled + 1)
        val high = numerator / (2 * percent.scaled - 1)

        // The window can be narrower than 1 and fall between two integers, in which case no
        // integer satisfies it and the nearest one is the honest answer.
        if (low > high) {
            val nearest = Math.round(value * 100.0 * unit / percent.scaled)
            return nearest..nearest
        }
        return low..high
    }

    private fun pow10(n: Int): Long {
        var out = 1L
        repeat(n) { out *= 10 }
        return out
    }

    /** Both operands are positive here, so this needs none of the sign handling floorDiv has. */
    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    // ---------------------------------------------------------------- artefacts

    /** The entry to add to `calibrations.json`, in the shape that file already uses. */
    fun entryJson(
        model: String,
        measured: List<Measured>,
        note: String = "",
        /** How many models share this layout, so the note can say what was left out and why. */
        sharedLayout: Int = 0,
        date: String = LocalDate.now().toString(),
    ): String {
        val maxima = measured.joinToString(",\n") { m ->
            """
            |    {
            |      "addr": [${m.addresses.joinToString(", ")}],
            |      "max": ${m.max},
            |      "observed": { "value": ${m.value}, ${observedBasis(m.basis)} },
            |      "range": [${m.range.first}, ${m.range.last}]
            |    }
            """.trimMargin()
        }

        val scope = if (sharedLayout > 1) {
            "Measured on one unit. The other ${sharedLayout - 1} models sharing this EEPROM layout " +
                "are deliberately not included: a shared layout does not prove identical pad capacity."
        } else {
            DEFAULT_NOTE
        }

        val fullNote = listOf(scope, note.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        return """
        |{
        |  "models": ["${escape(model)}"],
        |  "measured": "${escape(model)}, $date",
        |  "note": "${escape(fullNote)}",
        |  "maxima": [
        |$maxima
        |  ]
        |}
        """.trimMargin()
    }

    /**
     * One entry wrapped as a whole `calibrations.json`, which is what
     * [CounterSpecs.applyCalibrations] parses.
     */
    fun asCalibrationsFile(entry: String): String = "{\n  \"calibrations\": [\n$entry\n  ]\n}"

    private const val DEFAULT_NOTE =
        "Measured on one unit. Deliberately not extended to the models sharing this EEPROM " +
            "layout: a shared layout does not prove identical pad capacity."

    /** How the observation is recorded alongside the maximum. */
    private fun observedBasis(basis: Basis): String = when (basis) {
        Basis.ServiceRequired -> """"at": "service required""""
        is Basis.Reference -> """"percent": ${basis.percent.trim().removeSuffix("%").trim().replace(',', '.')}"""
    }

    /**
     * A `counters-overlay.json` carrying these maxima, so the contributor's own printer shows the
     * percentage before anything is merged.
     */
    fun overlayJson(model: String, specs: List<CounterSpec>, measured: List<Measured>): String {
        val byAddress = measured.associate { it.addresses to it.max }

        val counters = specs.joinToString(",\n") { spec ->
            val max = byAddress[spec.addresses] ?: spec.max
            val fields = buildString {
                append("""{ "addr": [${spec.addresses.joinToString(", ")}], """)
                append(""""desc": "${escape(spec.description)}"""")
                spec.min?.let { append(""", "min": $it""") }
                max?.let { append(""", "max": $it""") }
                append(" }")
            }
            "        $fields"
        }

        return """
        |{
        |  "groups": [
        |    {
        |      "models": ["${escape(model)}"],
        |      "counters": [
        |$counters
        |      ]
        |    }
        |  ]
        |}
        """.trimMargin()
    }

    /** What the app knows about the printer the reading came from. */
    data class Context(
        val model: String,
        /** The unit this printer is taken to be — see [confirmedAgainst] for where that came from. */
        val identifiedAs: String? = null,
        /**
         * Set when [identifiedAs] is the contributor's answer rather than the firmware's: the family
         * string the printer *would* give, which named no unit. A reviewer weighs the two
         * differently, so the report must not present one as the other.
         */
        val confirmedAgainst: String? = null,
        /**
         * The channel [identifiedAs] arrived on — "SNMP", "its USB descriptor", and so on. A USB
         * descriptor names a family far more often than a unit, so which one answered decides how
         * much the name is worth; the report used to assert SNMP regardless.
         */
        val identifiedVia: String? = null,
        /** What that channel said verbatim, which is where a family name shows itself. */
        val reportedAs: String? = null,
        /** The database entry whose layout was read — often the family, not the unit. */
        val layoutOf: String? = null,
        /** How many models share that layout. */
        val sharedLayout: Int = 0,
        val printer: String? = null,
        val transport: String? = null,
        val firmware: String? = null,
        val appVersion: String? = null,
        /** Colour to level, straight from the printer's status block. */
        val inkLevels: List<Pair<String, Int>> = emptyList(),
        /** Raw ST2 fields, name to hex, as corroboration — see [report]. */
        val statusFields: List<Pair<String, String>> = emptyList(),
        val note: String = "",
    )

    /**
     * How the printer's own name arrived, and what it said. A reviewer needs both: `ET-2820` off a
     * USB descriptor reading `ET-2820 Series` is a family standing in for a unit, and nothing else
     * on the report would show that.
     */
    private fun via(context: Context): String {
        val channel = context.identifiedVia ?: return ""
        val verbatim = context.reportedAs
            ?.takeIf { !it.equals(context.identifiedAs, ignoreCase = true) }
            ?.let { ", which answered `$it`" }
            .orEmpty()
        return " — via $channel$verbatim"
    }

    /** The report to file. */
    fun report(context: Context, measured: List<Measured>): String = buildString {
        appendLine("# Waste counter calibration: ${context.model}")
        appendLine()
        appendLine(headline(measured))
        appendLine("Collected with Epson Reset from a **live** read.")
        appendLine()

        appendLine("## Printer")
        appendLine()
        appendLine("- Filed against: `${context.model}`")
        appendLine(
            when {
                context.identifiedAs == null ->
                    "- The printer never named itself, so the model above is the contributor's, " +
                        "not the firmware's"

                // The firmware named a family and a person named the unit. That is a weaker claim
                // than SNMP giving the unit outright and a stronger one than a free-typed name, and
                // it is the only way a unit is ever established for these printers.
                context.confirmedAgainst != null ->
                    "- The printer names only `${context.confirmedAgainst}`, which is a family; " +
                        "the contributor confirmed the unit as `${context.identifiedAs}`" +
                        if (context.identifiedAs.equals(context.model, ignoreCase = true)) {
                            ""
                        } else {
                            ", then filed it as `${context.model}` instead"
                        }

                context.identifiedAs.equals(context.model, ignoreCase = true) ->
                    "- The printer names itself `${context.identifiedAs}`" + via(context)
                else ->
                    "- **The printer names itself `${context.identifiedAs}`**" + via(context) +
                        ", and the contributor filed it as `${context.model}` instead"
            },
        )
        context.printer?.let { appendLine("- USB/DNS-SD descriptor: `$it`") }
        context.transport?.let { appendLine("- Connection: $it") }
        context.firmware?.let { appendLine("- Firmware: `$it`") }
        context.appVersion?.let { appendLine("- Epson Reset: $it") }
        appendLine()

        // The question a second, disagreeing submission will raise, so the first one should already
        // carry the answer to "which of these models was it, exactly?".
        if (context.sharedLayout > 1) {
            val layout = context.layoutOf ?: context.model
            appendLine(
                "`$layout`'s counter layout is shared by **${context.sharedLayout} models** in " +
                    "`counters.json`. This entry claims one of them. If a measurement from a " +
                    "sibling ever disagrees, that is the evidence for splitting the group — which " +
                    "only works because both name their unit.",
            )
            appendLine()
        }

        appendLine("## Measurements")
        appendLine()
        appendLine("`app has` is what this app was showing before the measurement — blank where it")
        appendLine("had no maximum at all, which is the gap being filled.")
        appendLine()
        appendLine("| addresses | value | app has | measured | consistent range | basis | verdict |")
        appendLine("|---|---|---|---|---|---|---|")
        for (m in measured) {
            appendLine(
                "| ${m.addressLabel} | ${m.value} | ${m.existingMax ?: "— none"} | ${m.max} | " +
                    "${m.range.first}–${m.range.last} | ${m.basisLabel} | ${verdict(m)} |",
            )
        }
        appendLine()

        if (measured.any { it.agreement == Agreement.DISAGREES }) {
            appendLine("> **A maximum on file is contradicted here.** Both figures came from one")
            appendLine("> printer each, so this is a finding rather than a correction: either pad")
            appendLine("> capacity varies across units of this model, or one of the two readings")
            appendLine("> was taken against a tool reporting something else.")
            appendLine()
        }

        if (measured.any { it.agreement == Agreement.CONFIRMS }) {
            appendLine("> A maximum already on file is confirmed by a second unit. Nothing to")
            appendLine("> merge, but worth recording: every figure in calibrations.json otherwise")
            appendLine("> rests on exactly one printer.")
            appendLine()
        }

        if (measured.any { it.looksLikeLimitByte }) {
            appendLine("> **One of these bytes reads 94 (0x5E)**, which is Epson's limit marker —")
            appendLine("> the value those bytes always hold and the value a reset writes back to")
            appendLine("> them. It may be a counter that happens to be there; it may be that")
            appendLine("> the grouping has a marker byte listed as a counter.")
            appendLine()
        }

        if (measured.any { it.isCoarse }) {
            appendLine("> Some ranges are wide: the reference percentage was given to few decimals,")
            appendLine("> so it brackets the maximum loosely rather than pinning it.")
            appendLine()
        }

        if (measured.any { it.basis == Basis.ServiceRequired }) {
            appendLine("> A service-required measurement is the counter value at the moment the")
            appendLine("> printer declared the pad full, so it needs no reference tool. It is exact")
            appendLine("> to within the last job's increment.")
            appendLine()
        }

        // Read off the same status block as the serial and the state, and free to include. A pad
        // fills as ink is used, so what the tanks report is context for the counts above.
        if (context.inkLevels.isNotEmpty()) {
            appendLine("## Ink levels")
            appendLine()
            appendLine("As the printer reports them; no database or calibration involved.")
            appendLine()
            appendLine("| colour | level |")
            appendLine("|---|---|")
            for ((colour, level) in context.inkLevels) appendLine("| $colour | $level% |")
            appendLine()
        }

        if (context.note.isNotBlank()) {
            appendLine("## Notes from the contributor")
            appendLine()
            appendLine(context.note.trim())
            appendLine()
        }

        if (context.statusFields.isNotEmpty()) {
            appendLine("## Status block, undecoded")
            appendLine()
            appendLine("Raw `@BDC ST2` fields as read, with no interpretation attached.")
            appendLine()
            appendLine("| field | bytes |")
            appendLine("|---|---|")
            for ((name, hex) in context.statusFields) appendLine("| $name | `$hex` |")
            appendLine()
        }

        appendLine("## Entry")
        appendLine()
        appendLine("```json")
        appendLine(entryJson(context.model, measured, context.note, context.sharedLayout))
        appendLine("```")
    }

    /**
     * What this submission is, in one line — because the three kinds need different things done
     * with them, and a maintainer should not have to read a table to find out which arrived.
     */
    fun headline(measured: List<Measured>): String {
        val kinds = measured.map { it.agreement }.toSet()
        return when {
            Agreement.DISAGREES in kinds ->
                "A measurement that **contradicts** a maximum already in `calibrations.json`."
            kinds == setOf(Agreement.CONFIRMS) ->
                "A second printer **confirming** maxima already in `calibrations.json`."
            Agreement.FILLS_A_GAP in kinds ->
                "A measured maximum for `calibrations.json`, so this model can show percentages " +
                    "instead of raw counts."
            else -> "A waste counter measurement for `calibrations.json`."
        }
    }

    /** One word for what a measurement does to what is already on file. */
    fun verdict(measured: Measured): String = when (measured.agreement) {
        Agreement.FILLS_A_GAP -> "**new** — no maximum was known"
        Agreement.CONFIRMS -> "confirms the ${measured.existingMax} on file"
        Agreement.DISAGREES -> "**disagrees** with the ${measured.existingMax} on file"
    }

    // ---------------------------------------------------------------- submission

    const val ISSUE_BASE = "https://github.com/yusnel-rojas/epson-reset/issues/new"

    const val TEMPLATE = "calibration.yml"

    /**
     * Servers and browsers both give up on a long URL, at limits nobody agrees on. 6000 is under
     * every one of them and well over a typical submission.
     */
    const val MAX_URL = 6000

    /** Where to send the user, and whether the form arrives filled in. */
    data class Submission(val url: String, val prefilled: Boolean)

    /** A prefilled issue on the calibration form, shedding fields until it fits. */
    fun submission(model: String, entry: String, evidence: String): Submission {
        val base = "$ISSUE_BASE?template=${encode(TEMPLATE)}&title=${encode("Calibration: $model")}" +
            "&model=${encode(model)}"

        val withEverything = "$base&entry=${encode(entry)}&evidence=${encode(evidence)}"
        if (withEverything.length <= MAX_URL) return Submission(withEverything, prefilled = true)

        val withEntry = "$base&entry=${encode(entry)}"
        if (withEntry.length <= MAX_URL) return Submission(withEntry, prefilled = true)

        return Submission(base, prefilled = false)
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
