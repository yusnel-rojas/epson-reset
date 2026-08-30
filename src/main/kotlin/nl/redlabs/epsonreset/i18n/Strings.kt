package nl.redlabs.epsonreset.i18n

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.util.Locale

/**
 * Resolving text outside the composition, mainly log lines at the moment they are written. Blocking is
 * what compose resources itself does on desktop — the catalog is read off the classpath.
 */
object Strings {

    private var cachedLocale: Locale? = null
    private var cachedEnvironment: ResourceEnvironment? = null

    /**
     * `getSystemResourceEnvironment()` is expensive and a log-heavy run calls this hundreds of times.
     * Keyed on the locale rather than invalidated by hand, so a language switch needs no notification.
     */
    @Synchronized
    fun environment(): ResourceEnvironment {
        val locale = Locale.getDefault()
        cachedEnvironment?.takeIf { cachedLocale == locale }?.let { return it }
        return getSystemResourceEnvironment().also {
            cachedLocale = locale
            cachedEnvironment = it
        }
    }

    fun get(resource: StringResource, vararg args: Any): String =
        runBlocking { getString(environment(), resource, *args.map { it.toString() }.toTypedArray()) }

    fun plural(resource: PluralStringResource, quantity: Int, vararg args: Any): String = runBlocking {
        getPluralString(environment(), resource, quantity, *args.map { it.toString() }.toTypedArray())
    }

    fun resolveNow(text: UiText): String = when (text) {
        is UiText.Str -> runBlocking { getString(environment(), text.resource, *text.args.flatten()) }

        is UiText.Plural -> runBlocking {
            getPluralString(environment(), text.resource, text.quantity, *text.args.flatten())
        }

        is UiText.Raw -> text.text
    }

    private fun List<Any>.flatten(): Array<Any> =
        map { if (it is UiText) resolveNow(it) else it.toString() }.toTypedArray()
}

/** Looks up on every call; prefer the `@Composable` [resolve], which remembers, inside a composition. */
fun UiText.resolveNow(): String = Strings.resolveNow(this)
