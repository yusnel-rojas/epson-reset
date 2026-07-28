package nl.redlabs.epsonreset.device

import nl.redlabs.epsonreset.db.PrinterDatabase

/** Maps a reported product string onto a database entry. */
object DeviceMatcher {

    private val NOISE = setOf("EPSON", "SERIES", "SEIKO", "PRINTER", "USB", "STYLUS")

    /** What a product string resolved to, with how much of a leap it took to get there. */
    data class Resolution(
        val model: nl.redlabs.epsonreset.db.PrinterModel?,
        val confidence: MatchedPrinter.Confidence,
    ) {
        companion object {
            val none = Resolution(null, MatchedPrinter.Confidence.NONE)
        }
    }

    fun match(device: DetectedPrinter, db: PrinterDatabase): MatchedPrinter {
        val resolution = resolve(device.product, db)
        return MatchedPrinter(device, resolution.model, resolution.confidence)
    }

    /** The matching itself, on the string alone. */
    fun resolve(product: String?, db: PrinterDatabase): Resolution {
        val raw = product?.takeIf { it.isNotBlank() } ?: return Resolution.none

        val cleaned = normalise(raw)
        if (cleaned.isEmpty()) return Resolution.none

        db[cleaned]?.let { return Resolution(it, MatchedPrinter.Confidence.EXACT) }

        // Try each token on its own: "WF-7710 Series" → "WF-7710".
        val tokens = cleaned.split(' ').filter { it.isNotBlank() }
        for (token in tokens) {
            db[token]?.let { return Resolution(it, MatchedPrinter.Confidence.EXACT) }
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
