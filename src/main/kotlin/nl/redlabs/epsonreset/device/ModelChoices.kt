package nl.redlabs.epsonreset.device

import java.io.File

/**
 * Which unit a user confirmed their printer to be, for printers that will only name their family.
 *
 * Kept out of database.json on purpose. That file is replaced wholesale by the OTA sync, and this is
 * one person's answer about one printer on their desk rather than anything true of a model.
 */
object ModelChoices {

    /**
     * [key] identifies the printer — its serial when it has one, and its connection otherwise.
     * [reported] is what it called itself when the choice was made: if that changes, a different
     * printer is answering to the same key and the choice no longer applies to it.
     */
    data class Choice(val key: String, val reported: String, val model: String)

    fun load(file: File): List<Choice> =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let { parse(it) } ?: emptyList()

    /** The model confirmed for any of [keys], provided the printer still reports [reported]. */
    fun lookup(file: File, keys: List<String>, reported: String): String? {
        val entries = load(file).filter { it.reported.equals(reported, ignoreCase = true) }
        return keys.firstNotNullOfOrNull { key ->
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        }?.model
    }

    /** Records [choice], replacing any earlier answer for the same printer. */
    fun pin(file: File, choice: Choice): List<Choice> {
        val updated = load(file).filterNot { it.key.equals(choice.key, ignoreCase = true) } + choice
        save(file, updated)
        return updated
    }

    fun forget(file: File, key: String): List<Choice> {
        val updated = load(file).filterNot { it.key.equals(key, ignoreCase = true) }
        save(file, updated)
        return updated
    }

    fun save(file: File, entries: List<Choice>) {
        runCatching { file.writeText(format(entries)) }
    }

    /** One choice per line, tab separated. Blank lines and `#` comments are skipped. */
    fun parse(text: String): List<Choice> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val fields = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (fields.size < 3) null else Choice(fields[0], fields[1], fields[2])
        }
        .distinctBy { it.key.lowercase() }
        .toList()

    fun format(entries: List<Choice>): String = buildString {
        appendLine("# Epson Reset — models confirmed by hand.")
        appendLine("# Printers that name only their family leave the exact unit unsettled; this is")
        appendLine("# where the answer is kept so it is asked for once rather than every session.")
        appendLine("# One per line, TAB separated: printer serial (or connection), what the printer")
        appendLine("# reported, the model chosen. For example:")
        appendLine("#   X4TY123456\tL3110 Series\tL3115")
        entries.forEach { appendLine("${it.key}\t${it.reported}\t${it.model}") }
    }
}
