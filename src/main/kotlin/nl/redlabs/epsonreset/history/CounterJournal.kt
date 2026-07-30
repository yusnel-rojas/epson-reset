package nl.redlabs.epsonreset.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.device.Serials
import nl.redlabs.epsonreset.protocol.CounterReader
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * Successful live counter reads, one JSON object per line. A bad or half-written final line costs
 * that sample only; earlier history remains readable. Consecutive samples with unchanged values
 * are collapsed so the journal records counter changes rather than polling frequency.
 */
class CounterJournal(private val file: File = AppPaths.counterHistory) {

    data class Sample(
        val takenAt: Instant,
        val serial: String,
        val report: CounterReader.Report,
        /** Every defensible reading of the source serial, retained so a later link can disambiguate it. */
        val serialAliases: Set<String> = setOf(serial),
    )

    data class Stats(val samples: Int, val printers: Int, val bytes: Long)

    /**
     * Appends one canonical-serial sample. Null means the sample is not safe to identify or keep,
     * or its address/value set is unchanged from the newest sample for this printer and model.
     * This guard is the authority on what is eligible; callers may pre-filter to avoid pointless work,
     * but a sample that reaches here is judged here.
     */
    @Synchronized
    fun append(rawSerial: String?, report: CounterReader.Report, now: Instant = Instant.now()): Sample? {
        val sourceSerial = rawSerial ?: return null
        val serial = Serials.canonical(sourceSerial) ?: return null
        if (report.model.isBlank() || report.error != null || report.answered == 0) return null

        val newest = load(sourceSerial, report.model).lastOrNull()
        if (newest != null && sameValues(newest.report, report)) return null

        val aliases = (Serials.readings(sourceSerial) + serial).filter { it.isNotBlank() }.toSet()
        val sample = Sample(now, serial, report, aliases)
        file.parentFile?.mkdirs()
        val needsSeparator = file.isFile && file.length() > 0L && !endsWithNewline(file)
        file.appendText((if (needsSeparator) "\n" else "") + format(sample) + "\n")
        return sample
    }

    private fun sameValues(left: CounterReader.Report, right: CounterReader.Report): Boolean =
        left.readings.associate { it.address to it.value } == right.readings.associate { it.address to it.value }

    /** Oldest first, for one physical printer and optionally one model. Malformed lines are skipped. */
    @Synchronized
    fun load(rawSerial: String, model: String? = null): List<Sample> {
        val serial = rawSerial.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        if (!file.isFile) return emptyList()

        return file.useLines { lines ->
            lines.mapNotNull(::parse)
                .filter { sample -> sample.serialAliases.any { Serials.same(it, serial) } }
                .filter { model == null || it.report.model.equals(model, ignoreCase = true) }
                .toList()
        }.sortedBy { it.takenAt }
    }

    @Synchronized
    fun stats(): Stats {
        if (!file.isFile) return Stats(0, 0, 0L)
        val samples = file.useLines { lines -> lines.mapNotNull(::parse).toList() }
        return Stats(samples.size, countPrinters(samples), file.length())
    }

    /** Deletes the journal as one explicit action. Disabling collection never calls this. */
    @Synchronized
    fun deleteAll(): Boolean = !file.exists() || file.delete()

    private fun format(sample: Sample): String = JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schema", SCHEMA)
            put("takenAt", sample.takenAt.toString())
            put("serial", sample.serial)
            put("serialAliases", buildJsonArray { sample.serialAliases.sorted().forEach(::add) })
            put("model", sample.report.model)
            put(
                "readings",
                buildJsonArray {
                    sample.report.readings.forEach { reading ->
                        add(
                            buildJsonObject {
                                put("addr", reading.address)
                                if (reading.value == null) put("value", JsonNull) else put("value", reading.value)
                                put("resetValue", reading.expectedAfterReset)
                                put("group", reading.groupDescription)
                                reading.error?.let { put("error", it) }
                            },
                        )
                    }
                },
            )
        },
    )

    private fun parse(line: String): Sample? = runCatching {
        val root = JSON.parseToJsonElement(line).jsonObject
        if (root["schema"]?.jsonPrimitive?.intOrNull != SCHEMA) return null
        val takenAt = root["takenAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: return null
        val serial = root["serial"]?.jsonPrimitive?.contentOrNull?.let(Serials::canonical) ?: return null
        val aliases = root["serialAliases"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            }.getOrNull()
        }.orEmpty().toSet() + serial
        val model = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val readings = root["readings"]?.jsonArray?.map { element ->
            val entry = element.jsonObject
            val address = entry["addr"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return null
            val valueElement = entry["value"] ?: return null
            val value = if (valueElement == JsonNull) {
                null
            } else {
                valueElement.jsonPrimitive.intOrNull ?: return null
            }
            if (value != null && value !in 0..255) return null
            val resetValue = entry["resetValue"]?.jsonPrimitive?.intOrNull ?: 0
            if (resetValue !in 0..255) return null
            CounterReader.Reading(
                address = address,
                value = value,
                expectedAfterReset = resetValue,
                groupDescription = entry["group"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                error = entry["error"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: return null
        if (readings.isEmpty()) return null
        Sample(takenAt, serial, CounterReader.Report(model, readings), aliases)
    }.getOrNull()

    /** Counts connected alias sets, so USB and network spellings of one serial remain one printer. */
    private fun countPrinters(samples: List<Sample>): Int {
        val groups = mutableListOf<MutableSet<String>>()
        for (sample in samples) {
            val aliases = sample.serialAliases
                .flatMap(Serials::readings)
                .mapTo(mutableSetOf()) { it.lowercase() }
            val matching = groups.filter { group -> group.any { it in aliases } }
            if (matching.isEmpty()) {
                groups += aliases
            } else {
                val merged = matching.fold(aliases) { all, group -> all.apply { addAll(group) } }
                groups.removeAll(matching.toSet())
                groups += merged
            }
        }
        return groups.size
    }

    private fun endsWithNewline(file: File): Boolean = RandomAccessFile(file, "r").use {
        it.seek(it.length() - 1L)
        it.read() == '\n'.code
    }

    private companion object {
        const val SCHEMA = 1
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
