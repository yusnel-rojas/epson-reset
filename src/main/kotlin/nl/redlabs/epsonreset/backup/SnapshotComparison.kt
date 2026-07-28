package nl.redlabs.epsonreset.backup

import nl.redlabs.epsonreset.db.CounterSpec
import nl.redlabs.epsonreset.protocol.CounterReader

/** Two samples of the same printer's counters, and what moved between them. */
object SnapshotComparison {

    /** One end of a comparison. */
    data class Side(
        /** How the user refers to this sample: a file name, or "current reading". */
        val label: String,
        val takenAt: String,
        val model: String,
        val serial: String?,
        val readings: List<CounterReader.Reading>,
    ) {
        val answered: Int get() = readings.count { it.value != null }

        internal val byAddress: Map<Int, Int?> get() = readings.associate { it.address to it.value }
    }

    /** One counter as it stood on each side. */
    data class CounterDelta(
        val spec: CounterSpec,
        val before: Long?,
        val after: Long?,
        val beforeBytes: List<Int?>,
        val afterBytes: List<Int?>,
    ) {
        /** How far the counter moved, or null when either side didn't decode. */
        val delta: Long? get() = if (before != null && after != null) after - before else null

        /** True when any constituent byte differs. */
        val moved: Boolean get() = beforeBytes != afterBytes

        val label: String
            get() = spec.description + if (spec.isUncertain) "  (layout uncertain)" else ""

        /** `3865 → 3901`, or the bytes when the group isn't one number. */
        val display: String
            get() = when {
                before != null && after != null -> "$before → $after"
                else -> "${hex(beforeBytes)} → ${hex(afterBytes)}"
            }

        val deltaLabel: String
            get() = delta?.let { if (it > 0) "+$it" else it.toString() } ?: if (moved) "changed" else "—"

        private fun hex(bytes: List<Int?>): String =
            bytes.joinToString(" ") { b -> b?.let { "%02X".format(it) } ?: "--" }
    }

    /** One address as it stood on each side. A null is an address that side never answered. */
    data class ByteDelta(val address: Int, val before: Int?, val after: Int?, val resetValue: Int, val group: String) {
        /**
         * Only a difference between two values that both exist. An address one side never answered
         * is unknown, not changed, and counting it as changed would inflate every summary that a
         * partial read appears in.
         */
        val changed: Boolean get() = before != null && after != null && before != after

        /** `0x19 → 0x00`, with a dash for a side that has no byte. */
        val transition: String get() = "${hex(before)} → ${hex(after)}"

        private fun hex(value: Int?): String = value?.let { "0x%02X".format(it) } ?: "—"
    }

    data class Result(
        val before: Side,
        val after: Side,
        val counters: List<CounterDelta>,
        val bytes: List<ByteDelta>,
        /** Addresses that moved but belong to no counter in the layout. */
        val unexplained: List<ByteDelta>,
        /** What makes this comparison less than it looks. See [compare]. */
        val notes: List<String>,
    ) {
        val movedCounters: List<CounterDelta> get() = counters.filter { it.moved }

        val changedBytes: Int get() = bytes.count { it.changed }

        /** Addresses where both sides hold a byte — the only ones a difference can be read from. */
        val comparable: Int get() = bytes.count { it.before != null && it.after != null }

        /** True when the two samples are the same bytes, and there were bytes to be the same. */
        val identical: Boolean get() = comparable > 0 && bytes.none { it.changed }

        /** True when every answered address on the later side holds its reset value. */
        val afterIsAtResetValue: Boolean
            get() = bytes.any { it.after != null } && bytes.all { it.after == null || it.after == it.resetValue }

        val summary: String
            get() = when {
                comparable == 0 ->
                    "Nothing can be compared — no address holds a byte on both sides. If one of " +
                        "these is a live reading, it answered nothing."
                identical -> "Nothing moved — every address holds the same byte on both sides."
                movedCounters.isEmpty() ->
                    "$changedBytes address(es) changed, none of them inside a known counter."
                else -> {
                    val moved = movedCounters.joinToString("; ") { "${it.spec.description} ${it.deltaLabel}" }
                    "$changedBytes address(es) changed · $moved"
                }
            }
    }

    /** Pairs two samples up, oldest first. */
    fun compare(before: Side, after: Side, specs: List<CounterSpec> = emptyList()): Result {
        val beforeBytes = before.byAddress
        val afterBytes = after.byAddress

        // The reset value and group name come from whichever side knows them — they are properties
        // of the model, identical on both sides whenever both carry the address.
        val describedBy = (after.readings + before.readings).associateBy { it.address }

        val bytes = (beforeBytes.keys + afterBytes.keys).sorted().map { address ->
            val described = describedBy[address]
            ByteDelta(
                address = address,
                before = beforeBytes[address],
                after = afterBytes[address],
                resetValue = described?.expectedAfterReset ?: 0,
                group = described?.groupDescription.orEmpty(),
            )
        }

        val decodedBefore = CounterReader.decode(before.readings, specs)
        val decodedAfter = CounterReader.decode(after.readings, specs)

        val counters = specs.indices.map { i ->
            CounterDelta(
                spec = specs[i],
                before = decodedBefore[i].value,
                after = decodedAfter[i].value,
                beforeBytes = decodedBefore[i].bytes,
                afterBytes = decodedAfter[i].bytes,
            )
        }

        val claimed = specs.flatMap { it.addresses }.toSet()
        val unexplained =
            if (specs.isEmpty()) {
                emptyList()
            } else {
                bytes.filter { it.changed && it.address !in claimed }
            }

        return Result(
            before = before,
            after = after,
            counters = counters,
            bytes = bytes,
            unexplained = unexplained,
            notes = notes(before, after, bytes),
        )
    }

    /** Everything that makes the numbers above mean less than they appear to. */
    private fun notes(before: Side, after: Side, bytes: List<ByteDelta>): List<String> = buildList {
        if (!before.model.equals(after.model, ignoreCase = true)) {
            add(
                "These samples are different models (${before.model} and ${after.model}). The same " +
                    "address is not the same counter on both, so the differences below mean nothing.",
            )
        }

        // Only when both are known. A snapshot predating serial capture has null here, and warning
        // about that on every old file would train the user to ignore the line that matters.
        val one = before.serial
        val other = after.serial
        if (one != null && other != null && one != other) {
            add(
                "Different printers: serial $one and serial $other. A difference here is two " +
                    "machines being unalike, not one machine changing.",
            )
        }

        val missing = bytes.count { it.before == null || it.after == null }
        if (missing > 0) {
            add(
                "$missing address(es) appear on only one side, or were never answered there. Those " +
                    "rows show a dash and are not counted as changed.",
            )
        }
    }
}
