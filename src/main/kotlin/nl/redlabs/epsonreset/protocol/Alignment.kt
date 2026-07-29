package nl.redlabs.epsonreset.protocol

/**
 * Print-head alignment: the one maintenance operation that has to ask a question and act on the
 * answer.
 *
 * ## The shape of it
 *
 * Unlike a nozzle check, alignment is a conversation. The printer prints numbered pattern pairs, a
 * human decides which pair looks straightest, and that choice goes back. Nothing on the wire can
 * make the printer ask — the asking belongs to whoever started the job, which here is this app.
 *
 * Three commands, all captured from `escputil --align-head` driven through its own prompts against
 * a file (gutenprint 5.3.3, `escp2-et2750`):
 *
 * | Command | Bytes | Does |
 * |---|---|---|
 * | `DT` | `DT 03 00 · 00 <pass> 00` | print the pattern for one pass |
 * | `DA` | `DA 04 00 · 00 <pass> 00 <choice>` | submit the chosen pair for one pass |
 * | `SV` | `SV 00 00` | write the submitted choices into the printer |
 *
 * escputil's full run is four writes: patterns, choices, patterns again to check the result, then
 * the save.
 *
 * ## Why the save is separate
 *
 * [choices] takes effect immediately but lives in volatile settings: **powering the printer off and
 * on discards them**. [save] is what makes them permanent, and there is no undo and no backup — the
 * previous alignment is not readable, so nothing here can put it back. escputil's own warning is
 * blunt about this, and it is worth repeating rather than softening: the procedure is not approved
 * by Epson and a bad alignment degrades every subsequent print.
 *
 * So the two are separate calls, and the power cycle is the escape hatch for everything short of
 * [save]. This is the same shape as the reset path's dry run: the reversible thing is easy and the
 * irreversible thing takes its own deliberate step.
 */
object Alignment {

    /** The passes an alignment run covers, as escputil drives them. */
    val PASSES = 0..3

    /**
     * The pair numbers a pass offers. escputil prints fifteen and tells the user the middle one is
     * usually right, which is a statement about a well-aligned printer rather than a default to
     * apply — a choice nobody looked at the paper for is worse than no alignment at all.
     */
    val CHOICES = 1..15

    /** True when every pass has a choice and every choice is on the printed sheet. */
    fun isComplete(picks: Map<Int, Int>): Boolean = PASSES.all { picks[it] in CHOICES }

    /** What is wrong with [picks], or null when nothing is. */
    fun problemWith(picks: Map<Int, Int>): String? {
        val missing = PASSES.filterNot { picks.containsKey(it) }
        if (missing.isNotEmpty()) {
            return "No choice for pass ${missing.joinToString(", ") { (it + 1).toString() }}. " +
                "Every pass needs one — a pass left out keeps whatever it had."
        }

        val bad = picks.filterValues { it !in CHOICES }
        if (bad.isNotEmpty()) {
            return "Pair ${bad.values.first()} is not on the sheet; the patterns are numbered " +
                "${CHOICES.first}–${CHOICES.last}."
        }
        return null
    }

    /**
     * Prints the alignment patterns — one remote session per pass, which is escputil's own framing
     * and the reason [RemoteMode.stream] takes sessions rather than commands.
     *
     * Read-only as far as the printer's settings go: this spends a sheet and says nothing about
     * what the alignment should be.
     */
    fun patterns(): ByteArray = RemoteMode.stream(PASSES.map { pass -> listOf("DT" to listOf(0x00, pass, 0x00)) })

    /**
     * Submits one chosen pair per pass. Takes effect at once and survives until the printer is
     * switched off — see [save] for the step that makes it stick.
     *
     * @throws IllegalArgumentException when [picks] is incomplete or out of range, because a
     *   half-answered alignment writes the unanswered passes as whatever was in the map.
     */
    fun choices(picks: Map<Int, Int>): ByteArray {
        problemWith(picks)?.let { throw IllegalArgumentException(it) }
        return RemoteMode.stream(
            PASSES.map { pass -> listOf("DA" to listOf(0x00, pass, 0x00, picks.getValue(pass))) },
        )
    }

    /**
     * Writes the submitted choices into the printer permanently.
     *
     * The point of no return: there is no undo, no backup, and the previous alignment cannot be
     * read back to restore it. Everything before this is escapable with a power cycle.
     */
    fun save(): ByteArray = RemoteMode.sequenceFor("SV")

    /** What to say before [save] runs, phrased for someone about to change their printer for good. */
    const val SAVE_WARNING: String =
        "This writes the alignment into the printer permanently. There is no undo and no backup — " +
            "the previous alignment cannot be read back, so it cannot be restored. Until this step " +
            "the choices are only volatile: switching the printer off and on discards them."
}
