package nl.redlabs.epsonreset.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.redlabs.epsonreset.AppPaths

/** One waste counter: an ordered run of EEPROM addresses holding a single little-endian value. */
data class CounterSpec(
    val addresses: List<Int>,
    val description: String,
    val resetValues: List<Int> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
) {
    /** Whether this run of addresses really is one integer. */
    val isSingleValue: Boolean get() = addresses.size <= MAX_COUNTER_BYTES

    /**
     * Assembles the counter's value from the bytes read at each address, least-significant first.
     */
    fun decode(bytes: Map<Int, Int?>): Long? {
        if (!isSingleValue) return null

        var value = 0L
        for ((i, address) in addresses.withIndex()) {
            val b = bytes[address] ?: return null
            value = value or ((b.toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** Percentage of the counter's limit, when this model actually declares one. */
    fun percentOf(value: Long): Double? {
        val limit = max?.takeIf { it > 0 } ?: return null
        return (value.toDouble() / limit.toDouble() * 100.0).coerceIn(0.0, 999.0)
    }

    /** `true` when the description marks this entry as guesswork. */
    val isUncertain: Boolean get() = description.contains("?")

    private companion object {
        /** A waste counter wider than 32 bits doesn't exist; beyond this it's not one value. */
        const val MAX_COUNTER_BYTES = 4
    }
}

/** Per-model counter layouts, from the bundled `counters.json` plus an optional user overlay. */
class CounterSpecs private constructor(
    private val byModel: Map<String, List<CounterSpec>>,
    val overlayLoaded: Boolean,
    val overlayError: String? = null,
) {
    val modelCount: Int get() = byModel.size

    /** Every model the layout data knows, lowercased. Some aren't in the reset database at all. */
    val modelNames: Set<String> get() = byModel.keys

    operator fun get(model: String): List<CounterSpec>? = byModel[model.lowercase()]

    /**
     * These layouts with one more calibration layered on, for a maximum measured in this session.
     */
    fun withCalibration(text: String): CounterSpecs {
        val merged = LinkedHashMap(byModel)
        applyCalibrations(merged, text)
        return CounterSpecs(merged, overlayLoaded, overlayError)
    }

    /** Every address any counter for this model touches, in declaration order, de-duplicated. */
    fun addressesFor(model: String): List<Int> = get(model)?.flatMap { it.addresses }?.distinct() ?: emptyList()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun resource(name: String): String? =
            CounterSpecs::class.java.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }

        /** Bundled layouts and calibrations, ignoring any user overlay. */
        fun loadBundled(): CounterSpecs {
            val counters = resource("/counters.json")
                ?: return CounterSpecs(emptyMap(), overlayLoaded = false)

            val merged = LinkedHashMap(parseGroups(counters))
            resource("/calibrations.json")?.let { applyCalibrations(merged, it) }
            return CounterSpecs(merged, overlayLoaded = false)
        }

        fun load(): CounterSpecs {
            val counters = resource("/counters.json")
                ?: return CounterSpecs(emptyMap(), overlayLoaded = false)

            val merged = LinkedHashMap<String, List<CounterSpec>>()
            merged.putAll(parseGroups(counters))
            resource("/calibrations.json")?.let { applyCalibrations(merged, it) }

            // A user overlay is additive and overriding — later wins, so a corrected model
            // replaces the bundled entry outright rather than merging counter-by-counter.
            var overlayLoaded = false
            var overlayError: String? = null
            val file = AppPaths.counterOverlay
            if (file.isFile) {
                runCatching { parseGroups(file.readText()) }
                    .onSuccess {
                        merged.putAll(it)
                        overlayLoaded = true
                    }
                    .onFailure { overlayError = it.message ?: it.toString() }
            }

            return CounterSpecs(merged, overlayLoaded, overlayError)
        }

        /** Layers measured maxima onto already-parsed layouts, matching on the address list. */
        fun applyCalibrations(target: MutableMap<String, List<CounterSpec>>, text: String) {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val entries = root["calibrations"] as? JsonArray ?: return

            for (element in entries) {
                val entry = element as? JsonObject ?: continue
                val models = (entry["models"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: continue

                val maxima = (entry["maxima"] as? JsonArray)?.mapNotNull { m ->
                    val obj = m as? JsonObject ?: return@mapNotNull null
                    val addr = (obj["addr"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: return@mapNotNull null
                    val max = obj["max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                    addr to max
                }?.toMap() ?: continue

                for (model in models) {
                    val key = model.lowercase()
                    val existing = target[key] ?: continue
                    target[key] = existing.map { spec ->
                        maxima[spec.addresses]?.let { spec.copy(max = it) } ?: spec
                    }
                }
            }
        }

        fun parseGroups(text: String): Map<String, List<CounterSpec>> {
            val root = json.parseToJsonElement(text).jsonObject
            val groups = root["groups"] as? JsonArray ?: return emptyMap()

            val out = LinkedHashMap<String, List<CounterSpec>>()
            for (element in groups) {
                val group = element as? JsonObject ?: continue
                val models = (group["models"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: continue

                val counters = (group["counters"] as? JsonArray)
                    ?.mapNotNull { parseCounter(it as? JsonObject ?: return@mapNotNull null) }
                    ?: continue
                if (counters.isEmpty()) continue

                for (model in models) out[model.lowercase()] = counters
            }
            return out
        }

        private fun parseCounter(obj: JsonObject): CounterSpec? {
            val addresses = (obj["addr"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: return null
            if (addresses.isEmpty()) return null

            return CounterSpec(
                addresses = addresses,
                description = obj["desc"]?.jsonPrimitive?.contentOrNull ?: "Waste counter",
                resetValues = (obj["reset"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList(),
                min = (obj["min"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                max = (obj["max"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }

        /** Template written on demand so the overlay format is discoverable without docs. */
        fun overlayTemplate(): String = """
            {
              "groups": [
                {
                  "models": ["MY-MODEL"],
                  "counters": [
                    { "addr": [48, 49], "desc": "Waste counter", "max": 8450 },
                    { "addr": [50, 51], "desc": "Waste counter (platen)" }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
