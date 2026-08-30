package nl.redlabs.epsonreset

import nl.redlabs.epsonreset.i18n.UiText
import nl.redlabs.epsonreset.i18n.counterName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The counter names come from bot-synced upstream data, so the set can change without anyone here
 * touching a catalogue. An unmapped name still displays — in English — which is easy to miss on a
 * screen nobody reads in Spanish, so the sync is checked rather than trusted.
 */
class CounterNameTest {

    private val marker = Regex("""\s*\(\?\)$""")

    private fun shippedNames(): Set<String> {
        val root = File("data")
        assertTrue(root.isDirectory, "missing data directory")

        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .flatMap { Regex(""""desc"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(it.readText()) }
            .map { it.groupValues[1].replace(marker, "") }
            .toSet()
    }

    @Test
    fun `every name upstream ships has a translation`() {
        val untranslated = shippedNames().filter { counterName(it) is UiText.Raw }
        assertEquals(emptyList(), untranslated, "add these to i18n/CounterText.kt and both catalogues")
    }

    /** The fallback is what keeps a new upstream name visible rather than blank. */
    @Test
    fun `an unknown name falls through unchanged`() {
        assertEquals(UiText.raw("Ink level counter"), counterName("Ink level counter"))
    }

    /** The uncertainty marker is the caller's to re-add, so the lookup must not be thrown by it. */
    @Test
    fun `the uncertainty marker is stripped before lookup`() {
        assertEquals(counterName("Waste counters"), counterName("Waste counters (?)"))
    }
}
