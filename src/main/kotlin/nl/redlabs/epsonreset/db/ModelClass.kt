package nl.redlabs.epsonreset.db

/**
 * A printer that calls itself "L3110 Series" has named a family, not a unit. Most of the time that
 * costs nothing — the eight L311x entries share one recipe, so whichever we pick writes the same
 * bytes. For a minority it costs everything: L310 and L3100 are one family name apart and read with
 * different keys.
 *
 * This is where a family name is turned back into the set of things it could mean, so the caller can
 * tell the two cases apart and only interrupt the user for the second.
 */
object ModelClass {

    /**
     * Everything about a model that a reset depends on. Two models with equal recipes are
     * interchangeable for our purposes, whatever else differs between them.
     */
    data class Recipe(
        val readKey: Int,
        val writeKey: String,
        val writeKey1: String,
        val readLength: Int,
        val writeLength: Int,
        val memHigh: Int,
        val groups: List<Triple<PadKind, List<Int>, List<Int>>>,
    )

    /** Deliberately not the whole [PrinterModel]: the name differs by definition, and `description` is cosmetic. */
    fun recipeOf(model: PrinterModel): Recipe = Recipe(
        readKey = model.readKey,
        writeKey = model.writeKey,
        writeKey1 = model.writeKey1,
        readLength = model.readLength,
        writeLength = model.writeLength,
        memHigh = model.memHigh,
        groups = model.padGroups.map { Triple(it.effectiveKind, it.addresses, it.resetValues) },
    )

    /**
     * Every database entry a report of [name] as a family could have meant. Two ways in:
     *
     * - names that extend it — "ET-1810" also being the start of "ET-18100", which is the trap that
     *   costs the most, since the two are unrelated printers;
     * - names differing only in the final digit — "ET-2820" for any of ET-2820…ET-2828, which is how
     *   Epson actually numbers the units inside one advertised series.
     *
     * Both are shape rules over names, because the database records no family. They are wide enough
     * to cover the real series and narrow enough that the answer is usually one entry.
     */
    fun members(db: PrinterDatabase, name: String): List<PrinterModel> {
        val upper = name.uppercase()

        val extensions = db.models.filter { it.name.uppercase().startsWith(upper) }

        val siblings = TRAILING_DIGITS.matchEntire(upper)?.let { match ->
            val stem = match.groupValues[1] + match.groupValues[2].dropLast(1)
            db.models.filter {
                val n = it.name.uppercase()
                n.length == upper.length && n.startsWith(stem) && n.last().isDigit()
            }
        } ?: emptyList()

        return (extensions + siblings).distinctBy { it.name }.sortedBy { it.name }
    }

    /**
     * The members that disagree about what a reset would write, or empty when they agree. Empty is
     * the ordinary answer — across the bundled database only about one name in fourteen has a family
     * that splits — and it is what lets a family-level report still be acted on without asking.
     */
    fun ambiguousMembers(db: PrinterDatabase, name: String): List<PrinterModel> {
        val members = members(db, name)
        val split = members.distinctBy { recipeOf(it) }.size > 1
        return if (split) members else emptyList()
    }

    private val TRAILING_DIGITS = Regex("""^(.*?)(\d{2,})$""")
}
