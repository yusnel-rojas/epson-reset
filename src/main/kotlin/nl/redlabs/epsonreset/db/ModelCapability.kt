package nl.redlabs.epsonreset.db

/** What this app can actually do for one model. */
data class ModelCapability(
    val model: PrinterModel,
    val canReset: Boolean,
    val canRead: Boolean,
    val values: ValueSupport,
    val hasLimit: Boolean,
    val scope: ResetScope,
    val counterCount: Int,
) {
    val name: String get() = model.name

    /** EEPROM writes a reset would perform. 0 when there is nothing to reset. */
    val writeCount: Int get() = model.writeCount
}

/** How far a reading can be interpreted for a model. */
enum class ValueSupport {
    /** Every counter is a certain, ≤4-byte group: readings decode to real numbers. */
    DECODED,

    /**
     * A layout exists but part of it is guesswork — the entry is marked "(?)", or a group is
     * wider than 4 bytes and therefore isn't one integer (the ET-2820's 6-address entry mixes a
     * counter with limit bytes).
     */
    UNCERTAIN,

    /** No layout at all: readings can only be shown as loose hex bytes. */
    RAW,
}

/** How much of the waste system a reset actually clears. */
enum class ResetScope {
    /** Platen and main pad counters both live in EEPROM. */
    FULL,

    /**
     * Only the platen counter is in EEPROM. The main waste box counter sits on a chip, so a reset
     * here does not empty the box — it still needs servicing.
     */
    PLATEN_ONLY,

    /** Nothing to reset. */
    NONE,
}

/** Aggregate counts for the matrix header — the shape of the whole database at a glance. */
data class CapabilitySummary(
    val total: Int,
    val resettable: Int,
    val readable: Int,
    val decoded: Int,
    val uncertain: Int,
    val withLimit: Int,
    val platenOnly: Int,
    /** Models the layout data knows that the reset database doesn't, so they can't be selected. */
    val layoutOnly: Int,
)

object ModelCapabilities {

    fun of(database: PrinterDatabase, specs: CounterSpecs?): List<ModelCapability> =
        database.models.map { model -> of(model, specs?.get(model.name).orEmpty()) }

    fun of(model: PrinterModel, specs: List<CounterSpec>): ModelCapability {
        val resettable = model.hasResettableCounters

        return ModelCapability(
            model = model,
            canReset = resettable,
            // CounterReader samples the pad-group addresses *and* any address a layout names, so a
            // model with only a layout is still readable even though there is nothing to write.
            canRead = resettable || specs.isNotEmpty(),
            values = when {
                specs.isEmpty() -> ValueSupport.RAW
                specs.any { it.isUncertain || !it.isSingleValue } -> ValueSupport.UNCERTAIN
                else -> ValueSupport.DECODED
            },
            hasLimit = specs.any { (it.max ?: 0) > 0 },
            scope = when {
                !resettable -> ResetScope.NONE
                model.isPlatenOnly -> ResetScope.PLATEN_ONLY
                else -> ResetScope.FULL
            },
            counterCount = specs.size,
        )
    }

    fun summarise(
        capabilities: List<ModelCapability>,
        database: PrinterDatabase,
        specs: CounterSpecs?,
    ): CapabilitySummary {
        val known = database.models.map { it.name.lowercase() }.toSet()

        return CapabilitySummary(
            total = capabilities.size,
            resettable = capabilities.count { it.canReset },
            readable = capabilities.count { it.canRead },
            decoded = capabilities.count { it.values == ValueSupport.DECODED },
            uncertain = capabilities.count { it.values == ValueSupport.UNCERTAIN },
            withLimit = capabilities.count { it.hasLimit },
            platenOnly = capabilities.count { it.scope == ResetScope.PLATEN_ONLY },
            layoutOnly = specs?.modelNames?.count { it !in known } ?: 0,
        )
    }
}
