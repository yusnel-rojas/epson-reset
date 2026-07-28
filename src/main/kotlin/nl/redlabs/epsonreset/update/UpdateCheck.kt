package nl.redlabs.epsonreset.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Asks GitHub whether there is a newer release than the one running. */
object UpdateCheck {

    const val LATEST_RELEASE_API =
        "https://api.github.com/repos/yusnel-rojas/epson-reset/releases/latest"

    const val RELEASES_PAGE = "https://github.com/yusnel-rojas/epson-reset/releases/latest"

    /** Once a day. The app is not a daemon; anything shorter is just noise on the GitHub quota. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val TIMEOUT: Duration = Duration.ofSeconds(10)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class Release(val version: String, val page: String, val notes: String?)

    /** What a check concluded. Every outcome is a value — a failed check is not an exception. */
    sealed interface Result {
        data class Available(val release: Release) : Result
        data object UpToDate : Result

        /** Reached GitHub but could not make sense of the answer, or of our own version. */
        data class Unknown(val detail: String) : Result
        data class Failed(val detail: String) : Result
    }

    /** Runs the check. Blocking — call it off the UI thread. */
    fun check(current: String = AppVersion.current, fetch: () -> String = ::fetchLatest): Result {
        val body = runCatching(fetch).getOrElse { return Result.Failed(it.describe()) }
        val release = parse(body) ?: return Result.Unknown("no tag_name in GitHub's reply")

        return when (compare(release.version, current)) {
            null -> Result.Unknown("cannot compare ${release.version} with $current")
            1 -> Result.Available(release)
            else -> Result.UpToDate
        }
    }

    /** Reads `tag_name` out of a GitHub release object. Null if it isn't one. */
    fun parse(body: String): Release? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        Release(
            version = tag,
            page = obj["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: RELEASES_PAGE,
            notes = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Compares two `1.2.3` versions. 1 if [a] is newer, -1 if older, 0 if the same. */
    fun compare(a: String, b: String): Int? {
        val (coreA, preA) = split(a) ?: return null
        val (coreB, preB) = split(b) ?: return null

        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val x = coreA.getOrElse(i) { 0 }
            val y = coreB.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }

        return when {
            preA == preB -> 0
            preA == null -> 1 // a release beats its own pre-releases
            preB == null -> -1
            else -> preA.compareTo(preB).coerceIn(-1, 1)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) == 1

    /** `v1.2.0-rc1` → `[1,2,0]` and `rc1`. Null if the numeric core isn't numeric. */
    private fun split(version: String): Pair<List<Int>, String?>? {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null

        // Build metadata (`+sha`) says nothing about ordering, so it goes before anything else.
        val withoutBuild = trimmed.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "").takeIf { it.isNotEmpty() }

        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers to pre
    }

    /**
     * GitHub's API rejects a request without a User-Agent, so this cannot use `URL.readText()` the
     * way the database refresh does.
     */
    private fun fetchLatest(): String {
        val client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val request = HttpRequest.newBuilder(URI(LATEST_RELEASE_API))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "epson-reset/${AppVersion.current}")
            .timeout(TIMEOUT)
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // 404 is the ordinary answer for a repository with no published release yet.
        require(response.statusCode() == 200) { "GitHub answered ${response.statusCode()}" }
        return response.body()
    }

    private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: toString()
}
