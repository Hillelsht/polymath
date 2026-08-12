package com.hillelsht.smart.util

import android.content.Context
import android.content.res.Configuration
import com.hillelsht.smart.domain.model.Language
import java.util.Locale

/**
 * The selected UI/content language.
 *
 * A plain [android.content.SharedPreferences] read rather than Room or DataStore because
 * [com.hillelsht.smart.MainActivity] needs the answer synchronously, inside `attachBaseContext`,
 * before the first frame — a suspend or `Flow`-based store cannot answer in time for that hook.
 */
object LocalePrefs {

    private const val FILE = "locale_prefs"
    private const val KEY_LANGUAGE = "language"

    fun get(context: Context): Language {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Language.fromTag(prefs.getString(KEY_LANGUAGE, null))
    }

    fun set(context: Context, language: Language) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }
}

/** Wraps [this] in a context configured for [language], for `attachBaseContext`. */
fun Context.withLanguage(language: Language): Context {
    val locale = Locale(language.tag)
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}
