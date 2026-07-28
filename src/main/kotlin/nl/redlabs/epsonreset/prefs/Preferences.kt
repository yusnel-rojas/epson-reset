package nl.redlabs.epsonreset.prefs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What the app remembers between launches. */
data class Preferences(
    val windowWidth: Int = DEFAULT_WIDTH,
    val windowHeight: Int = DEFAULT_HEIGHT,
    /** Null means "wherever the window manager wants it" — the first-launch answer. */
    val windowX: Int? = null,
    val windowY: Int? = null,
    val maximized: Boolean = false,
    /** Model name as the database spells it; resolved on load, dropped if it no longer exists. */
    val lastModel: String? = null,
    val logCollapsed: Boolean = false,
    val checkForUpdates: Boolean = true,
    /** Epoch millis of the last release check, which is what throttles it to once a day. */
    val lastUpdateCheck: Long = 0L,
) {

    /** Clamps the stored geometry back into the range the app can actually render. */
    fun sanitised(): Preferences = copy(
        windowWidth = windowWidth.coerceIn(MIN_WIDTH, MAX_DIMENSION),
        windowHeight = windowHeight.coerceIn(MIN_HEIGHT, MAX_DIMENSION),
        windowX = windowX?.takeIf { it in -MAX_DIMENSION..MAX_DIMENSION },
        windowY = windowY?.takeIf { it in -MAX_DIMENSION..MAX_DIMENSION },
        lastModel = lastModel?.takeIf { it.isNotBlank() },
        lastUpdateCheck = lastUpdateCheck.coerceAtLeast(0L),
    )

    companion object {
        const val DEFAULT_WIDTH = 1100
        const val DEFAULT_HEIGHT = 780

        /** Below this the sidebar and the main pane stop coexisting. */
        const val MIN_WIDTH = 720
        const val MIN_HEIGHT = 520

        /** Generous enough for any real display wall; small enough to catch a garbage number. */
        const val MAX_DIMENSION = 32_000

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Defaults for anything missing or unparseable, including a file that isn't JSON. */
        fun parse(text: String): Preferences {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return Preferences()

            val defaults = Preferences()
            return Preferences(
                windowWidth = root.int("windowWidth") ?: defaults.windowWidth,
                windowHeight = root.int("windowHeight") ?: defaults.windowHeight,
                windowX = root.int("windowX"),
                windowY = root.int("windowY"),
                maximized = root.bool("maximized") ?: defaults.maximized,
                lastModel = root.str("lastModel"),
                logCollapsed = root.bool("logCollapsed") ?: defaults.logCollapsed,
                checkForUpdates = root.bool("checkForUpdates") ?: defaults.checkForUpdates,
                lastUpdateCheck = root.long("lastUpdateCheck") ?: defaults.lastUpdateCheck,
            ).sanitised()
        }

        fun format(prefs: Preferences): String {
            val p = prefs.sanitised()
            val obj = buildJsonObject {
                put("windowWidth", JsonPrimitive(p.windowWidth))
                put("windowHeight", JsonPrimitive(p.windowHeight))
                p.windowX?.let { put("windowX", JsonPrimitive(it)) }
                p.windowY?.let { put("windowY", JsonPrimitive(it)) }
                put("maximized", JsonPrimitive(p.maximized))
                p.lastModel?.let { put("lastModel", JsonPrimitive(it)) }
                put("logCollapsed", JsonPrimitive(p.logCollapsed))
                put("checkForUpdates", JsonPrimitive(p.checkForUpdates))
                put("lastUpdateCheck", JsonPrimitive(p.lastUpdateCheck))
            }
            return json.encodeToString(JsonObject.serializer(), obj) + "\n"
        }

        // Read through the primitive's text rather than its type, so a hand-edited "1100" in quotes
        // still counts. A value that isn't a number at all yields null and takes the default.
        private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()

        private fun JsonObject.long(key: String): Long? =
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()

        private fun JsonObject.bool(key: String): Boolean? =
            this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
