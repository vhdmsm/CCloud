package com.pira.ccloud.utils

import android.app.Activity
import android.content.Context
import java.util.Locale

/**
 * The language the app shows itself in: English or Persian (right to left), or whichever of the
 * two the device uses. Texts are written in English in the code and passed through [tr], which
 * gives the Persian of [PersianStrings] when the app is in Persian.
 */
object AppLanguage {
    enum class Language(val code: String) {
        SYSTEM("system"),
        ENGLISH("en"),
        PERSIAN("fa")
    }

    private const val PREFS = "app_language"
    private const val KEY = "language"

    @Volatile
    var selected: Language = Language.SYSTEM
        private set

    /** True while texts are shown in Persian (and screens laid out right to left). */
    @Volatile
    var isPersian: Boolean = false
        private set

    fun init(context: Context) {
        val code = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, Language.SYSTEM.code)
        selected = Language.entries.firstOrNull { it.code == code } ?: Language.SYSTEM
        isPersian = resolvePersian(selected, Locale.getDefault().language)
    }

    /** Saves [language] and opens [activity] again, so every screen shows it. */
    fun set(activity: Activity, language: Language) {
        if (language == selected) return
        activity.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language.code).apply()
        selected = language
        isPersian = resolvePersian(language, Locale.getDefault().language)
        activity.recreate()
    }

    /** Whether [language] means Persian on a device whose language is [deviceLanguage]. */
    fun resolvePersian(language: Language, deviceLanguage: String): Boolean = when (language) {
        Language.PERSIAN -> true
        Language.ENGLISH -> false
        Language.SYSTEM -> deviceLanguage == "fa" || deviceLanguage == "per"
    }
}

/**
 * [english] in the app's language. With [args], the text is a format (e.g. "%s of %d")
 * and they are put in it, in the same places in both languages.
 */
fun tr(english: String, vararg args: Any?): String {
    val text = if (AppLanguage.isPersian) PersianStrings.map[english] ?: english else english
    return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
}
