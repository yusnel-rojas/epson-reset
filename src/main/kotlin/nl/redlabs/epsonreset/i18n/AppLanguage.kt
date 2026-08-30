package nl.redlabs.epsonreset.i18n

import java.util.Locale

object AppLanguage {

    val systemLocale: Locale = Locale.getDefault()

    fun apply(tag: String?) {
        Locale.setDefault(tag?.let { Locale.forLanguageTag(it) } ?: systemLocale)
    }
}
