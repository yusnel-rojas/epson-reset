package nl.redlabs.epsonreset.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.redlabs.epsonreset.AppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        // Our own generated copy, which the sync workflow keeps current. The reset data alone, not
        // the spliced printers.json the app bundles: this file is what replaces the cache, and the
        // cache holds reset recipes.
        const val OTA_URL =
            "https://raw.githubusercontent.com/yusnel-rojas/epson-reset/main/data/reinkpy/database.json"

        /** Newest schema this parser was written against; newer loads best-effort with a warning. */
        const val MAX_SUPPORTED_SCHEMA = 3

        /** A remote file far smaller than the shipped database is almost certainly truncated or wrong. */
        const val MIN_DOWNLOADED_MODELS = 1000

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
            val bundled = PrinterDatabase::class.java.getResourceAsStream(CounterSpecs.PRINTER_DATA)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("${CounterSpecs.PRINTER_DATA} missing from resources")
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

        /**
         * Strict boundary for data downloaded after the app was built. The ordinary parser remains
         * backwards-compatible with hand-written and legacy fixtures; a network replacement gets no
         * such benefit of the doubt because its addresses and byte values directly drive EEPROM writes.
         */
        fun parseDownloaded(text: String, minimumModels: Int = MIN_DOWNLOADED_MODELS): PrinterDatabase {
            val root = json.parseToJsonElement(text).jsonObject
            val schema = root["schema_version"]?.jsonPrimitive?.intOrNull
            require(schema == null || schema in 1..MAX_SUPPORTED_SCHEMA) {
                "unsupported database schema ${schema ?: "unknown"}"
            }

            val modelsRoot = when (val wrapped = root["models"]) {
                null -> root
                is JsonObject -> wrapped
                else -> error("database models must be an object")
            }
            val entries = modelsRoot.entries.filter { it.key != "schema_version" }
            require(entries.size >= minimumModels) {
                "downloaded database contained ${entries.size} models; expected at least $minimumModels"
            }
            require(entries.map { it.key.lowercase() }.toSet().size == entries.size) {
                "downloaded database contains duplicate model names"
            }

            entries.forEach { (name, element) ->
                require(name.isNotBlank()) { "downloaded database contains a blank model name" }
                val model = element as? JsonObject ?: error("$name is not an object")
                validateModel(name, model)
            }

            return parse(text, Source.CACHED).also { parsed ->
                require(parsed.size == entries.size) {
                    "downloaded database parsed ${parsed.size} of ${entries.size} models"
                }
            }
        }

        /** Validates and atomically replaces the cached database, leaving the previous file on failure. */
        fun cacheDownloaded(
            text: String,
            target: File = AppPaths.database,
            minimumModels: Int = MIN_DOWNLOADED_MODELS,
        ): PrinterDatabase {
            val parsed = parseDownloaded(text, minimumModels)
            val parent = target.absoluteFile.parentFile
            parent.mkdirs()
            val tmp = Files.createTempFile(parent.toPath(), "${target.name}.", ".tmp")
            try {
                Files.writeString(tmp, text)
                runCatching {
                    Files.move(
                        tmp,
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.recoverCatching {
                    Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            } finally {
                Files.deleteIfExists(tmp)
            }
            return parsed
        }

        private fun validateModel(name: String, model: JsonObject) {
            val readKey = model.optionalInt("rkey", 0, name)
            require(readKey in 0..0xFFFF) { "$name has rkey outside 0..65535" }
            val memHigh = model.optionalInt("mem_high", 0x7FF, name)
            require(memHigh in 0..0xFFFF) { "$name has mem_high outside 0..65535" }
            model.optionalInt("rlen", 2, name).also { require(it > 0) { "$name has an invalid rlen" } }
            model.optionalInt("wlen", 2, name).also { require(it > 0) { "$name has an invalid wlen" } }

            val groups = when (val value = model["pad_groups"]) {
                null -> {
                    if (model["addresses"] == null) emptyList() else listOf(model)
                }
                is JsonArray -> value.mapIndexed { index, element ->
                    element as? JsonObject ?: error("$name pad group $index is not an object")
                }
                else -> error("$name pad_groups must be an array")
            }

            val seen = mutableSetOf<Int>()
            groups.forEachIndexed { index, group ->
                val addresses = group.requiredInts("addresses", "$name pad group $index")
                val resets = group.requiredInts("reset", "$name pad group $index")
                require(addresses.size == resets.size) {
                    "$name pad group $index has ${addresses.size} addresses but ${resets.size} reset values"
                }
                addresses.forEach { address ->
                    require(address in 0..memHigh) {
                        "$name address $address is outside 0..$memHigh"
                    }
                    require(seen.add(address)) { "$name repeats address $address" }
                }
                resets.forEach { value ->
                    require(value in 0..255) { "$name reset value $value is outside 0..255" }
                }
            }
        }

        private fun JsonObject.optionalInt(key: String, default: Int, context: String): Int {
            val value = this[key] ?: return default
            return value.jsonPrimitive.intOrNull ?: error("$context $key must be an integer")
        }

        private fun JsonObject.requiredInts(key: String, context: String): List<Int> {
            val array = this[key] as? JsonArray ?: error("$context $key must be an array")
            return array.mapIndexed { index, element ->
                element.jsonPrimitive.intOrNull ?: error("$context $key[$index] must be an integer")
            }
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
