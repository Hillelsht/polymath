package com.hillelsht.smart.domain.model

/**
 * The languages the app can present its UI and content in.
 *
 * [tag] is a wire format: it is both the Android locale language code and the value persisted
 * by [com.hillelsht.smart.util.LocalePrefs] and used to pick a language-scoped content pack, so
 * renaming one orphans a saved preference and every pack path built from it.
 */
enum class Language(val tag: String, val displayName: String) {
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    ;

    companion object {
        val default = ENGLISH

        fun fromTag(tag: String?): Language = entries.firstOrNull { it.tag == tag } ?: default
    }
}
