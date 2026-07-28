package nl.redlabs.epsonreset.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.redlabs.epsonreset.AppPaths

/** Loads and searches the printer database (~1590 models, ~1475 of them resettable). */
class PrinterDatabase private constructor(val models: List<PrinterModel>, val source: Source) {
    enum class Source { BUNDLED, CACHED }

    private val byName = models.associateBy { it.name.lowercase() }

    val size: Int get() = models.size

    operator fun get(name: String): PrinterModel? = byName[name.lowercase()]

    /**
     * Case-insensitive substring search, ranked so exact and prefix hits float above the incidental
     * ones — typing "L3150" should not bury it under "XP-L31500".
     */
    fun search(query: String, limit: Int = 200): List<PrinterModel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return models.take(limit)

        return models.asSequence()
            .mapNotNull { model ->
                val n = model.name.lowercase()
                val rank = when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                rank to model
            }
            .sortedWith(compareBy({ it.first }, { it.second.name }))
            .map { it.second }
            .take(limit)
            .toList()
    }

    companion object {
        // Our own generated copy, which the sync workflow keeps current.
        const val OTA_URL =
            "https://raw.githubusercontent.com/yusnel-rojas/epson-reset/main/src/main/resources/database.json"

        /** Newest schema this parser was written against; newer loads best-effort with a warning. */
        const val MAX_SUPPORTED_SCHEMA = 3

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Cached download if it parses, otherwise the copy baked into the jar. */
        fun load(): PrinterDatabase {
            AppPaths.database.takeIf { it.isFile }?.let { f ->
                runCatching { parse(f.readText(), Source.CACHED) }
                    .getOrNull()
                    ?.takeIf { it.models.isNotEmpty() }
                    ?.let { return it }
            }
            return loadBundled()
        }

        /** The copy baked into the jar, ignoring any cached download. */
        fun loadBundled(): PrinterDatabase {
            val bundled = PrinterDatabase::class.java.getResourceAsStream("/database.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("database.json missing from resources")
            return parse(bundled, Source.BUNDLED)
        }

        fun parse(text: String, source: Source = Source.BUNDLED): PrinterDatabase {
            val root = json.parseToJsonElement(text).jsonObject
            // Schema 3 wraps the entries in "models"; earlier files are a bare map.
            val modelsRoot = (root["models"] as? JsonObject) ?: root

            val models = modelsRoot.entries.mapNotNull { (name, element) ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                if (name == "schema_version") return@mapNotNull null
                runCatching { parseModel(name, obj) }.getOrNull()
            }.sortedBy { it.name }

            return PrinterDatabase(models, source)
        }

        private fun parseModel(name: String, obj: JsonObject): PrinterModel {
            val groups = mutableListOf<PadGroup>()

            val padGroups = obj["pad_groups"]?.takeIf { it is kotlinx.serialization.json.JsonArray }
            if (padGroups != null) {
                for (element in padGroups.jsonArray) {
                    val g = element as? JsonObject ?: continue
                    val kind = g.str("kind") ?: ""
                    groups += padGroup(
                        // Our generated files omit `desc` — it never varies independently of
                        // `kind`, and dropping 2827 copies of two fixed strings takes 14% off the
                        // file. Entries that do carry one (a stale OTA cache) keep it.
                        description = g.str("desc") ?: labelFor(kind),
                        kind = kind,
                        addresses = g.ints("addresses"),
                        resets = g.ints("reset"),
                    )
                }
            } else if (obj["addresses"] != null) {
                // Pre-pad_groups entry: one implicit group covering every address.
                val addresses = obj.ints("addresses")
                if (addresses.isNotEmpty()) {
                    groups += padGroup("Waste counters", "", addresses, obj.ints("reset"))
                }
            }

            return PrinterModel(
                name = name,
                readKey = obj.int("rkey") ?: 0,
                writeKey = obj.str("wkey") ?: "",
                writeKey1 = obj.str("wkey1") ?: "",
                readLength = obj.int("rlen") ?: 2,
                writeLength = obj.int("wlen") ?: 2,
                memHigh = obj.int("mem_high") ?: 0x7FF,
                padGroups = groups,
            )
        }

        /** The human label for a pad kind, for entries that don't spell it out. */
        private fun labelFor(kind: String): String = when (kind.lowercase()) {
            "platen" -> "Platen Pad Counter"
            "main" -> "Main Pad Counter"
            else -> "Waste counters"
        }

        /** Reset values are padded with 0x00 so every address has a partner. */
        private fun padGroup(description: String, kind: String, addresses: List<Int>, resets: List<Int>) = PadGroup(
            description = description,
            kind = kind,
            addresses = addresses,
            resetValues = resets + List((addresses.size - resets.size).coerceAtLeast(0)) { 0 },
        )

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        private fun JsonObject.ints(key: String): List<Int> = (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.int }
            ?: emptyList()
    }
}
