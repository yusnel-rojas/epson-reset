package nl.redlabs.epsonreset.probe

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.device.DetectedPrinter

/** Turns a raw address sweep into something a person can act on. */
object SweepAnalysis {

    enum class Confidence { FAMILY, LIKELY, WEAK }

    data class Candidate(
        val addresses: List<Int>,
        val value: Long?,
        val confidence: Confidence,
        val why: String,
        val description: String = "Waste counter",
    ) {
        val label: String get() = addresses.joinToString(", ") { "0x%02X".format(it) }
    }

    /** Epson's limit byte: reads back as 0x5E and is written back as 0x5E on reset. */
    const val LIMIT_BYTE = 0x5E

    /** Ranked candidates for [sweep], best first. */
    fun candidates(
        sweep: DeviceInspector.Sweep,
        siblings: List<PrinterModel> = emptyList(),
        specsFor: (PrinterModel) -> List<CounterSpec> = { emptyList() },
    ): List<Candidate> {
        val values = sweep.values
        if (values.isEmpty()) return emptyList()

        val out = mutableListOf<Candidate>()
        val claimed = mutableSetOf<Int>()

        // 1. Family layouts, for the siblings whose addresses this printer actually answered.
        for (model in siblings) {
            for (spec in specsFor(model)) {
                if (spec.addresses.isEmpty()) continue
                if (!spec.addresses.all { it in values }) continue
                if (spec.addresses.any { it in claimed }) continue

                claimed += spec.addresses
                out += Candidate(
                    addresses = spec.addresses,
                    value = spec.decode(spec.addresses.associateWith { values[it] }),
                    confidence = Confidence.FAMILY,
                    why = "${model.name} shares this printer's read key and uses these addresses",
                    description = spec.description,
                )
            }
        }

        // 2 & 3. Adjacent pairs in the unclaimed remainder.
        val addresses = values.keys.sorted()
        for (address in addresses) {
            val next = address + 1
            if (address in claimed || next in claimed) continue
            if (next !in values) continue

            val lo = values.getValue(address)
            val hi = values.getValue(next)
            val value = (lo.toLong() and 0xFF) or ((hi.toLong() and 0xFF) shl 8)

            // A limit-byte pair is a boundary marker, not a counter.
            if (lo == LIMIT_BYTE && hi == LIMIT_BYTE) continue
            // Blank and saturated regions are unprogrammed EEPROM, not data.
            if (lo == 0x00 && hi == 0x00) continue
            if (lo == 0xFF || hi == 0xFF) continue
            // A counter's high byte is small — a real pad count doesn't reach five figures here.
            if (hi > 0x7F) continue
            if (value == 0L) continue

            val nearLimit = values[next + 1] == LIMIT_BYTE || values[address - 1] == LIMIT_BYTE
            claimed += address
            claimed += next
            out += Candidate(
                addresses = listOf(address, next),
                value = value,
                confidence = if (nearLimit) Confidence.LIKELY else Confidence.WEAK,
                why = if (nearLimit) {
                    "adjacent little-endian pair bordered by a 0x5E limit byte"
                } else {
                    "adjacent little-endian pair holding a plausible count"
                },
            )
        }

        return out.sortedWith(compareBy({ it.confidence.ordinal }, { it.addresses.first() }))
    }

    /**
     * A `counters-overlay.json` the user can drop next to the database cache to make the app treat
     * these addresses as this model's counters.
     */
    fun overlayJson(modelName: String, candidates: List<Candidate>): String {
        val counters = candidates.joinToString(",\n") { c ->
            val addr = c.addresses.joinToString(", ")
            """        { "addr": [$addr], "desc": "${c.description}" }"""
        }
        return """
            {
              "groups": [
                {
                  "models": ["$modelName"],
                  "counters": [
            $counters
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    /** A report to attach to an issue. */
    fun report(
        device: DetectedPrinter?,
        sweep: DeviceInspector.Sweep,
        candidates: List<Candidate>,
        keyResults: List<DeviceInspector.KeyResult> = emptyList(),
    ): String = buildString {
        appendLine("# Epson waste counter probe")
        appendLine()
        appendLine("Collected with Epson Reset's device inspector. **Read-only** — no EEPROM write")
        appendLine("was sent, so these are observations, not a verified reset layout.")
        appendLine()

        appendLine("## Device")
        appendLine()
        if (device != null) {
            appendLine("- Product: `${device.displayName}`")
            device.manufacturer?.let { appendLine("- Manufacturer: `$it`") }
            appendLine("- USB PID: `${device.pidHex}`")
            device.serial?.let { appendLine("- Serial: `$it`") }
        } else {
            appendLine("- (no device details captured)")
        }
        appendLine("- Read key used: `0x%04X`".format(sweep.readKey))
        appendLine()

        if (keyResults.isNotEmpty()) {
            appendLine("## Read keys tried")
            appendLine()
            appendLine("| key | answered | family |")
            appendLine("|---|---|---|")
            for (k in keyResults.take(10)) {
                appendLine("| `${k.hex}` | ${k.hits}/${k.probes} | ${k.exampleModels.joinToString(", ")} |")
            }
            appendLine()
            if (keyResults.count { it.answered } > 1) {
                appendLine("> More than one key answered, which suggests this firmware does not")
                appendLine("> validate the read key. Treat the sweep, not the key, as the finding.")
                appendLine()
            }
        }

        appendLine("## Candidate counters")
        appendLine()
        if (candidates.isEmpty()) {
            appendLine("None identified.")
        } else {
            appendLine("| addresses | value | confidence | basis |")
            appendLine("|---|---|---|---|")
            for (c in candidates) {
                appendLine("| ${c.label} | ${c.value ?: "—"} | ${c.confidence.name.lowercase()} | ${c.why} |")
            }
        }
        appendLine()

        appendLine("## Full sweep")
        appendLine()
        appendLine("${sweep.answered} of ${sweep.total} addresses answered.")
        appendLine()
        appendLine("```")
        appendLine(dump(sweep))
        appendLine("```")
    }

    /** 16-column dump of the sweep; `--` marks an address that did not answer. */
    fun dump(sweep: DeviceInspector.Sweep): String {
        if (sweep.requested.isEmpty()) return "(nothing swept)"
        val lo = sweep.requested.min()
        val hi = sweep.requested.max()

        return buildString {
            var row = lo - (lo % 16)
            while (row <= hi) {
                append("%04X  ".format(row))
                for (col in 0 until 16) {
                    val address = row + col
                    append(
                        when {
                            address !in sweep.requested -> "   "
                            else -> sweep.values[address]?.let { "%02X ".format(it) } ?: "-- "
                        },
                    )
                }
                appendLine()
                row += 16
            }
        }.trimEnd()
    }
}
