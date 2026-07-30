package nl.redlabs.epsonreset.net

/**
 * The standard Printer-MIB (RFC 3805), under `1.3.6.1.2.1.43`. It sits beside the private
 * [EpsonMib]: where the private path is firmware-specific and sometimes absent, these OIDs are the
 * ones every conforming printer answers. Two are worth reading — the lifetime page count, and the
 * supplies table that corroborates the ST2 ink block and the waste counter.
 *
 * Reading the supplies table is the one thing the app cannot do with a plain GET: it is columnar and
 * its row indices are the printer's own, so it has to be walked with [Snmp.walk] (GETNEXT).
 */
object PrinterMib {

    private val ROOT = listOf(1, 3, 6, 1, 2, 1, 43)

    /** `prtMarkerLifeCount` column — lifetime impressions. Read via GETNEXT so the row index is the
     *  printer's to choose. */
    val LIFE_COUNT_COLUMN = ROOT + listOf(10, 2, 1, 4)

    /** `prtMarkerSuppliesEntry` — one walk of this subtree yields every column of every supply. */
    val SUPPLIES_ENTRY = ROOT + listOf(11, 1, 1)

    /** `prtMarkerColorantValue` column — the colour name a supply's colorant index points at. */
    val COLORANT_VALUE = ROOT + listOf(12, 1, 1, 4)

    // prtMarkerSuppliesEntry column ids.
    private const val COL_COLORANT_INDEX = 3
    private const val COL_CLASS = 4
    private const val COL_TYPE = 5
    private const val COL_DESCRIPTION = 6
    private const val COL_MAX_CAPACITY = 8
    private const val COL_LEVEL = 9

    /** `prtMarkerSuppliesClassTC`: a receptacle is filled rather than consumed — i.e. waste. */
    private const val CLASS_RECEPTACLE = 4

    /** `prtMarkerSuppliesTypeTC` values, the inkjet-relevant subset. Unlisted codes show raw, the
     *  way [nl.redlabs.epsonreset.protocol.Status] treats unknown field types. */
    private val TYPES = mapOf(
        1 to "other",
        2 to "unknown",
        3 to "toner",
        4 to "waste toner",
        5 to "ink",
        6 to "ink cartridge",
        7 to "ink ribbon",
        8 to "waste ink",
        9 to "photo conductor",
        10 to "developer",
        14 to "waste wax",
        18 to "cleaner unit",
        20 to "transfer unit",
        21 to "toner cartridge",
    )

    private val WASTE_TYPES = setOf(4, 8, 14)

    /** Consumable ink: type 5 ink, 6 inkCartridge, 7 inkRibbon. The standard MIB's echo of the ST2
     *  ink block — and the part to leave out when that block is already on screen. */
    private val INK_CONSUMABLE_TYPES = setOf(5, 6, 7)

    /** A consumable at or below this percent is flagged low. Waste receptacles use the opposite end. */
    private const val LOW_THRESHOLD = 20

    /** A waste receptacle this full is flagged, mirroring the low-ink warning at the other extreme. */
    private const val WASTE_FULL_THRESHOLD = 80

    /** One row of `prtMarkerSuppliesTable`, decoded. */
    data class Supply(
        val index: Int,
        val description: String,
        val classCode: Int?,
        val typeCode: Int?,
        val level: Int?,
        val maxCapacity: Int?,
        val colour: String?,
    ) {
        val isWaste: Boolean get() = classCode == CLASS_RECEPTACLE || typeCode in WASTE_TYPES

        /** A plain colour ink — what the ST2 ink block already reports, so the redundant row when it
         *  is on screen. Waste and anything else (transfer units, etc.) stay, being additive. */
        val isInkConsumable: Boolean get() = !isWaste && typeCode in INK_CONSUMABLE_TYPES

        /** The type as a word, or the raw code when it isn't one this app names. */
        val typeLabel: String? get() = typeCode?.let { TYPES[it] ?: "type $it" }

        /**
         * Level as a percentage of capacity, or null when either value is one of the MIB's negative
         * sentinels (`-1` other, `-2` unknown, `-3` some-remaining) or capacity is not a real maximum.
         */
        val percent: Int?
            get() {
                val l = level ?: return null
                val max = maxCapacity ?: return null
                if (l < 0 || max <= 0) return null
                return (l * 100 / max).coerceIn(0, 100)
            }

        /** What a level says when it can't be a percentage. */
        val levelNote: String?
            get() = when (level) {
                -1 -> "unspecified"
                -2 -> "unknown"
                -3 -> "some remaining"
                else -> null
            }

        /** A consumable running low, or a waste receptacle running full — the value worth a warning. */
        val isWarn: Boolean
            get() = percent?.let { if (isWaste) it >= WASTE_FULL_THRESHOLD else it <= LOW_THRESHOLD } == true
    }

    /** What the standard MIB reports. Null fields simply weren't answered by this printer. */
    data class Reading(val lifeCount: Long?, val supplies: List<Supply>) {
        val isEmpty: Boolean get() = lifeCount == null && supplies.isEmpty()
    }

    /**
     * Reads the lifetime page count and the supplies table over SNMP. Returns null when the printer
     * answers none of it — many do; the standard MIB is not universal — so the caller can just show
     * nothing.
     */
    fun read(host: String, community: String = "public", port: Int = Snmp.PORT, timeoutMs: Int = 2000): Reading? {
        val lifeCount = lifeCount(host, community, port, timeoutMs)
        val supplies = supplies(host, community, port, timeoutMs)

        val reading = Reading(lifeCount, supplies)
        return reading.takeUnless { it.isEmpty }
    }

    /** The first (and, on these printers, only) `prtMarkerLifeCount` row. */
    private fun lifeCount(host: String, community: String, port: Int, timeoutMs: Int): Long? =
        when (val next = Snmp.getNext(host, LIFE_COUNT_COLUMN, community, timeoutMs, port = port)) {
            is Snmp.Next.Ok -> if (next.oid.startsWith(LIFE_COUNT_COLUMN)) Snmp.intOf(next.value) else null
            else -> null
        }

    private fun supplies(host: String, community: String, port: Int, timeoutMs: Int): List<Supply> {
        val cells = Snmp.walk(host, SUPPLIES_ENTRY, community, timeoutMs, port = port)
        if (cells.isEmpty()) return emptyList()

        val colours = colorants(host, community, port, timeoutMs)

        // Each OID past the entry prefix is [column, deviceIndex, supplyIndex]. Bucket by the row's
        // full index so two supplies never merge, and remember its trailing sub-identifier for display.
        val rows = LinkedHashMap<List<Int>, MutableMap<Int, Snmp.Next.Ok>>()
        for (cell in cells) {
            val tail = cell.oid.drop(SUPPLIES_ENTRY.size)
            if (tail.size < 2) continue
            val column = tail.first()
            val rowKey = tail.drop(1)
            rows.getOrPut(rowKey) { mutableMapOf() }[column] = cell
        }

        return rows.map { (rowKey, columns) ->
            val colorantIndex = columns[COL_COLORANT_INDEX]?.let { Snmp.intOf(it.value).toInt() }
            Supply(
                index = rowKey.last(),
                description = columns[COL_DESCRIPTION]?.let { string(it.value) }.orEmpty(),
                classCode = columns[COL_CLASS]?.let { Snmp.intOf(it.value).toInt() },
                typeCode = columns[COL_TYPE]?.let { Snmp.intOf(it.value).toInt() },
                level = columns[COL_LEVEL]?.let { Snmp.intOf(it.value).toInt() },
                maxCapacity = columns[COL_MAX_CAPACITY]?.let { Snmp.intOf(it.value).toInt() },
                colour = colorantIndex?.let { colours[it] },
            )
        }
    }

    /** Colorant index → colour name, keyed by the colorant table's trailing sub-identifier. */
    private fun colorants(host: String, community: String, port: Int, timeoutMs: Int): Map<Int, String> =
        Snmp.walk(host, COLORANT_VALUE, community, timeoutMs, port = port).associate { cell ->
            cell.oid.last() to string(cell.value)
        }

    private fun string(bytes: ByteArray): String = bytes.toString(Charsets.ISO_8859_1).trim()

    private fun List<Int>.startsWith(prefix: List<Int>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix
}
