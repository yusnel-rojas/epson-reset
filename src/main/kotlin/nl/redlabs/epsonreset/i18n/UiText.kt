package nl.redlabs.epsonreset.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A phrase chosen away from the composition and resolved when it is drawn. For text decided outside a
 * `@Composable`, where `stringResource` cannot be called.
 */
sealed class UiText {

    /** An argument may itself be a [UiText], resolved in the same language as the entry holding it. */
    data class Str(val resource: StringResource, val args: List<Any> = emptyList()) : UiText()

    /** [quantity] picks the plural form; pass it in [args] as well to show it. */
    data class Plural(val resource: PluralStringResource, val quantity: Int, val args: List<Any> = emptyList()) :
        UiText()

    /** Text that is not ours to translate: the printer's own words, a model name, a file path. */
    data class Raw(val text: String) : UiText()

    companion object {
        fun of(resource: StringResource, vararg args: Any): Str = Str(resource, args.toList())

        fun plural(resource: PluralStringResource, quantity: Int, vararg args: Any): Plural =
            Plural(resource, quantity, args.toList())

        fun raw(text: String): Raw = Raw(text)
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Str -> if (args.isEmpty()) {
        stringResource(resource)
    } else {
        stringResource(resource, *args.map { it.resolveArg() }.toTypedArray())
    }

    is UiText.Plural -> if (args.isEmpty()) {
        pluralStringResource(resource, quantity)
    } else {
        pluralStringResource(resource, quantity, *args.map { it.resolveArg() }.toTypedArray())
    }

    is UiText.Raw -> text
}

@Composable
private fun Any.resolveArg(): String = if (this is UiText) resolve() else toString()
