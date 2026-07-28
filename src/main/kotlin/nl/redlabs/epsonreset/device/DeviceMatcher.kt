package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.db.ModelClass
import nl.redlabs.epsonreset.db.PrinterDatabase

/** Maps a reported product string onto a database entry. */
object DeviceMatcher {

    private val NOISE = setOf("EPSON", "SERIES", "SEIKO", "PRINTER", "USB", "STYLUS")

    /**
     * What a product string resolved to, with how much of a leap it took to get there.
     *
     * [candidates] carries the family when the string named one — see
     * [MatchedPrinter.Confidence.CLASS_ONLY].
     */
    data class Resolution(
        val model: nl.redlabs.epsonreset.db.PrinterModel?,
        val confidence: MatchedPrinter.Confidence,
        val candidates: List<nl.redlabs.epsonreset.db.PrinterModel> = emptyList(),
    ) {
        companion object {
            val none = Resolution(null, MatchedPrinter.Confidence.NONE)
        }
    }

    /**
     * A cross-checked name wins, because it exists only where it is strictly better informed: the
     * printer answering SNMP with its unit, against a USB descriptor that named its family. If it
     * resolves to nothing the descriptor is still there to fall back on.
     */
    fun match(device: DetectedPrinter, db: PrinterDatabase): MatchedPrinter {
        val fromPeer = device.crossCheck?.let { resolve(it.name, db) }
            ?.takeIf { it.confidence != MatchedPrinter.Confidence.NONE }

        val resolution = fromPeer ?: resolve(device.product, db)
        return MatchedPrinter(device, resolution.model, resolution.confidence, resolution.candidates)
    }

    /** The matching itself, on the string alone. */
    fun resolve(product: String?, db: PrinterDatabase): Resolution {
        val raw = product?.takeIf { it.isNotBlank() } ?: return Resolution.none

        val cleaned = normalise(raw)
        if (cleaned.isEmpty()) return Resolution.none

        db[cleaned]?.let { return exactOrClass(it, raw, db) }

        // Try each token on its own: "WF-7710 Series" → "WF-7710".
        val tokens = cleaned.split(' ').filter { it.isNotBlank() }
        for (token in tokens) {
            db[token]?.let { return exactOrClass(it, raw, db) }
        }

        // Fall back to the longest database name contained in the cleaned string. Longest wins so
        // "L3150" beats a stray "L31" entry; ties are ambiguous and rejected.
        val contained = db.models
            .filter { it.name.length >= 3 && cleaned.contains(it.name.uppercase()) }
            .sortedByDescending { it.name.length }

        val best = contained.firstOrNull()
        if (best != null) {
            val tie = contained.count { it.name.length == best.name.length } > 1
            if (!tie) return Resolution(best, MatchedPrinter.Confidence.LIKELY)
        }

        return Resolution.none
    }

    /**
     * A name matched. Whether that settles it depends on how the printer phrased itself: "L310" is a
     * unit answering for itself and is taken at its word, while "L310 Series" is a family that here
     * happens to span two read keys, and taking *that* at its word is how the wrong key gets written.
     */
    private fun exactOrClass(
        model: nl.redlabs.epsonreset.db.PrinterModel,
        raw: String,
        db: PrinterDatabase,
    ): Resolution {
        if (!namesAClass(raw)) return Resolution(model, MatchedPrinter.Confidence.EXACT)

        val ambiguous = ModelClass.ambiguousMembers(db, model.name)
        // A family whose members agree costs the user nothing to resolve for them.
        if (ambiguous.isEmpty()) return Resolution(model, MatchedPrinter.Confidence.EXACT)

        return Resolution(model, MatchedPrinter.Confidence.CLASS_ONLY, ambiguous)
    }

    /** Whether the printer named a family rather than itself. Epson spells this "Series". */
    fun namesAClass(product: String): Boolean =
        product.split(' ', '\t', '_').any { it.equals("SERIES", ignoreCase = true) }

    /** Uppercase, drop vendor/marketing words, collapse whitespace. */
    fun normalise(product: String): String = product.uppercase()
        .replace('_', ' ')
        .split(' ', '\t')
        .filter { it.isNotBlank() && it !in NOISE }
        .joinToString(" ")
        .trim()

    /** Convenience for the UI: match everything found in one pass. */
    fun matchAll(devices: List<DetectedPrinter>, db: PrinterDatabase): List<MatchedPrinter> =
        devices.map { match(it, db) }
}
