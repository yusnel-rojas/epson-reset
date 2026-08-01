package nl.redlabs.epsonreset.ui

import nl.redlabs.epsonreset.protocol.CounterReader

/**
 * A run of EEPROM writes as the counter table draws it: what each address is to be given, and how
 * far each write has got.
 *
 * A reset and a restore differ only in where the target byte comes from — the model's reset values
 * for one, a saved snapshot for the other. Everything after that is the same question on screen
 * ("this address holds X and is about to hold Y"), so both hand the table one of these instead of
 * each growing its own version of the byte strip.
 */
data class WritePlan(
    /** Named in the legend, so the right-hand byte of every chip says what it is. */
    val targetLabel: String,
    /** Address to the byte this run will write. Empty keeps each reading's own reset target. */
    val targets: Map<Int, Int> = emptyMap(),
    /** How far each address has got. Empty before anything has been sent — a plan, not a run. */
    val states: Map<Int, ResetViewModel.CounterByteState> = emptyMap(),
) {
    /**
     * [base] retargeted at this plan. An address whose write was acknowledged is drawn already
     * holding its target: the printer took the byte, and proving it by reading back is the verify
     * step's job rather than something the table should sit blank waiting for.
     */
    fun applyTo(base: CounterReader.Report): CounterReader.Report = base.copy(
        readings = base.readings.map { reading ->
            val target = targets[reading.address] ?: reading.expectedAfterReset
            val landed = when (states[reading.address]) {
                ResetViewModel.CounterByteState.ACKNOWLEDGED,
                ResetViewModel.CounterByteState.VERIFIED,
                -> true

                else -> false
            }
            reading.copy(
                value = if (landed) target else reading.value,
                expectedAfterReset = target,
            )
        },
    )

    companion object {
        const val RESET_TARGET = "reset target"
        const val SNAPSHOT_TARGET = "saved value"

        /** No run and no retargeting: the table shows each address against its own reset value. */
        val None = WritePlan(RESET_TARGET)
    }
}
