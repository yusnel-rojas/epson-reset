package nl.rlabs.epsonreset

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringCatalogTest {

    private data class Entry(val value: String, val isPlural: Boolean)

    private fun read(locale: String): Map<String, Entry> {
        val file = File("src/main/composeResources/$locale/strings.xml")
        assertTrue(file.isFile, "missing catalogue: ${file.path}")

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = mutableMapOf<String, Entry>()

        doc.getElementsByTagName("string").let { nodes ->
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                entries[e.getAttribute("name")] = Entry(e.textContent, isPlural = false)
            }
        }
        doc.getElementsByTagName("plurals").let { nodes ->
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val items = e.getElementsByTagName("item")
                val text = buildString {
                    for (j in 0 until items.length) append((items.item(j) as Element).textContent)
                }
                entries[e.getAttribute("name")] = Entry(text, isPlural = true)
            }
        }
        return entries
    }

    private val english = read("values")
    private val spanish = read("values-es")

    /** `%1$s`, `%2$s`… — the set, not the order: a translator may reorder them, but not invent one. */
    private fun placeholders(text: String): Set<String> = Regex("""%\d+[$]s""").findAll(text).map { it.value }.toSet()

    @Test
    fun `every english key has a spanish translation`() {
        assertEquals(emptySet(), english.keys - spanish.keys, "keys missing from values-es")
    }

    @Test
    fun `spanish has no keys the english catalogue does not`() {
        assertEquals(emptySet(), spanish.keys - english.keys, "keys in values-es with no english original")
    }

    @Test
    fun `no translation is empty`() {
        val blank = spanish.filterValues { it.value.isBlank() }.keys
        assertEquals(emptySet(), blank, "blank spanish values")
    }

    @Test
    fun `placeholders match the english original`() {
        val drifted = english.keys.intersect(spanish.keys).filter {
            placeholders(english.getValue(it).value) != placeholders(spanish.getValue(it).value)
        }
        assertEquals(emptyList(), drifted, "placeholder sets differ from values/")
    }

    @Test
    fun `a plural in english is a plural in spanish`() {
        val mismatched = english.keys.intersect(spanish.keys).filter {
            english.getValue(it).isPlural != spanish.getValue(it).isPlural
        }
        assertEquals(emptyList(), mismatched, "string and plurals disagree between catalogues")
    }

    /** `\'` reaches the screen with the backslash still attached, so it must never be written. */
    @Test
    fun `no backslash-escaped quotes`() {
        val escaped = spanish.filterValues { it.value.contains("\\'") || it.value.contains("\\\"") }.keys
        assertEquals(emptySet(), escaped, "backslash-escaped quotes are rendered literally")
    }

    /** Only positional markers are substituted, so `%%` reaches the screen as two characters. */
    @Test
    fun `no doubled percent signs`() {
        val doubled = spanish.filterValues { it.value.contains("%%") }.keys
        assertEquals(emptySet(), doubled, "a literal percent is a single %")
    }

    /**
     * The badges sit on every byte in the counter table with only the legend to define them, so two
     * of them sharing a letter is unreadable. Spanish is why this is checked rather than assumed:
     * "P" is Platen in English and Principal in Spanish, which are the two opposite categories.
     */
    @Test
    fun `the three pad badges are distinct within each catalogue`() {
        val badges = listOf("legend_main_badge", "legend_platen_badge", "legend_unclassified_badge")
        for ((locale, catalogue) in listOf("values" to english, "values-es" to spanish)) {
            val letters = badges.map { catalogue.getValue(it).value }
            assertEquals(letters.size, letters.toSet().size, "badges collide in $locale: $letters")
        }
    }
}
