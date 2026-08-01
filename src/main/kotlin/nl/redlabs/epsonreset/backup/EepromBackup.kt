package nl.redlabs.epsonreset.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.redlabs.epsonreset.AppPaths
import nl.redlabs.epsonreset.db.PrinterModel
import nl.redlabs.epsonreset.protocol.CounterReader
import nl.redlabs.epsonreset.protocol.Executor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The EEPROM bytes as they stood at one moment — before a reset wrote over them, or whenever
 * someone asked for a snapshot.
 */
data class EepromBackup(
    val model: String,
    val createdAt: String,
    val printerSerial: String?,
    val entries: List<Entry>,
) {
    /** [value] is the byte found before the run; [resetValue] is what the reset meant to put there. */
    data class Entry(val address: Int, val value: Int, val resetValue: Int)

    /** `address to value` pairs, ready for [nl.redlabs.epsonreset.protocol.SequenceGenerator.generateWrites]. */
    val writes: List<Pair<Int, Int>> get() = entries.map { it.address to it.value }

    /** Addresses the reset would actually change — the rest already held their reset value. */
    val changedByReset: Int get() = entries.count { it.value != it.resetValue }

    /** The saved bytes as if they had just been read off the printer. */
    fun readings(model: PrinterModel? = null): List<CounterReader.Reading> {
        val groups = model?.padGroups.orEmpty()
            .flatMap { group -> group.addresses.map { it to group.description } }
            .toMap()

        return entries.map {
            CounterReader.Reading(
                address = it.address,
                value = it.value,
                expectedAfterReset = it.resetValue,
                groupDescription = groups[it.address] ?: "saved snapshot",
            )
        }
    }

    /** [createdAt] as something readable. Falls back to the raw stamp for a hand-edited file. */
    val takenAt: String
        get() = runCatching {
            READABLE.format(LocalDateTime.parse(createdAt, STAMP))
        }.getOrDefault(createdAt)

    fun toJson(): String = PRETTY.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schema", SCHEMA)
            put("model", model)
            put("createdAt", createdAt)
            printerSerial?.let { put("printerSerial", it) }
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { e ->
                        add(
                            buildJsonObject {
                                put("addr", e.address)
                                put("value", e.value)
                                put("resetValue", e.resetValue)
                            },
                        )
                    }
                },
            )
        },
    )

    /** Writes to `<appdata>/backups/<model>-<timestamp>.json`. */
    fun save(dir: File = AppPaths.backups): File {
        dir.mkdirs()
        val target = freeName(dir)
        val tmp = File(dir, "${target.name}.tmp")

        tmp.writeText(toJson())
        runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()

        return target
    }

    /**
     * The stamp names a second, and two snapshots can land inside one — a restore saves the bytes
     * it is about to overwrite moments after whatever prompted it. Overwriting there would delete a
     * recovery point to make room for another, so a taken name gets a suffix instead.
     */
    private fun freeName(dir: File): File {
        val name = fileName()
        val first = File(dir, name)
        if (!first.exists()) return first

        val stem = name.removeSuffix(".json")
        var n = 2
        while (true) {
            val candidate = File(dir, "$stem-$n.json")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun fileName(): String {
        val safeModel = model.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
        val stamp = createdAt.replace(Regex("[^0-9TZ]"), "")
        return "$safeModel-$stamp.json"
    }

    companion object {
        const val SCHEMA = 1

        private val PRETTY = Json { prettyPrint = true }
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

        /** Pairs the addresses [sequence] will write against what [readings] found there. */
        fun capture(
            model: String,
            sequence: List<ByteArray>,
            readings: List<CounterReader.Reading>,
            printerSerial: String? = null,
            now: Instant = Instant.now(),
        ): Capture {
            val targets = sequence.mapNotNull { Executor.writePacketTarget(it) }
            if (targets.isEmpty()) return Capture.NothingToWrite

            val sampled = readings.associate { it.address to it.value }

            // A null value and an absent address are the same failure here: no byte to put back.
            val missing = targets.map { it.first }.distinct().filter { sampled[it] == null }
            if (missing.isNotEmpty()) return Capture.Incomplete(missing.sorted())

            // An address written twice in one run still has only one pre-write byte, and it is the
            // one to restore — so collapse to first occurrence rather than recording it twice.
            val entries = targets.distinctBy { it.first }.map { (address, resetValue) ->
                Entry(address, sampled.getValue(address)!!, resetValue)
            }

            return Capture.Ready(EepromBackup(model, STAMP.format(now), printerSerial, entries))
        }

        /** Newest first. Unparseable files are left in place but not offered. */
        fun list(dir: File = AppPaths.backups): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        fun load(file: File): EepromBackup? = runCatching { parse(file.readText()) }.getOrNull()

        /** Null when the text isn't a backup, or carries a byte outside 0..255. */
        fun parse(text: String): EepromBackup? = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val model = root["model"]?.jsonPrimitive?.contentOrNull ?: return null

            val entries = (root["entries"] ?: return null).jsonArray.map { element ->
                val o = element.jsonObject
                val entry = Entry(
                    address = o["addr"]?.jsonPrimitive?.int ?: return null,
                    value = o["value"]?.jsonPrimitive?.int ?: return null,
                    resetValue = o["resetValue"]?.jsonPrimitive?.int ?: 0,
                )
                if (entry.address < 0 || entry.value !in 0..255) return null
                entry
            }
            if (entries.isEmpty()) return null

            EepromBackup(
                model = model,
                createdAt = root["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                printerSerial = root["printerSerial"]?.jsonPrimitive?.contentOrNull,
                entries = entries,
            )
        }.getOrNull()
    }
}

/** Outcome of [EepromBackup.capture]. Only [Ready] is safe to write over. */
sealed interface Capture {
    data class Ready(val backup: EepromBackup) : Capture

    /** These addresses will be written but were not read back, so nothing could be saved for them. */
    data class Incomplete(val missing: List<Int>) : Capture

    /** The sequence holds no write packets — there is nothing to overwrite and nothing to save. */
    data object NothingToWrite : Capture
}
