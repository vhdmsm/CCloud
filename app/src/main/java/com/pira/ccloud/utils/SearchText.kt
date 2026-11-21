package com.pira.ccloud.utils

import java.text.Normalizer

/** Search text that matches Turkish (and other accented) letters by their plain Latin ones. */
object SearchText {
    // Letters that don't split into a base letter and an accent
    private val plainLetters = mapOf(
        'ı' to 'i', 'İ' to 'I', 'ş' to 's', 'Ş' to 'S', 'ğ' to 'g', 'Ğ' to 'G',
        'ç' to 'c', 'Ç' to 'C', 'ö' to 'o', 'Ö' to 'O', 'ü' to 'u', 'Ü' to 'U'
    )
    private val accents = Regex("\\p{Mn}+")

    /** [text] with Ş→S, ç→c, ğ→g, ı/İ→i, ö→o, ü→u and other accents dropped (é→e), case kept. */
    fun fold(text: String): String {
        val mapped = buildString(text.length) { text.forEach { append(plainLetters[it] ?: it) } }
        return accents.replace(Normalizer.normalize(mapped, Normalizer.Form.NFD), "")
    }

    private fun key(text: String): String = fold(text).lowercase().replace(Regex("\\s+"), " ").trim()

    /** Whether [title] has every word of [query], ignoring case and accents ("uzak sehir" finds "Uzak Şehir"). */
    fun matches(title: String, query: String): Boolean {
        val words = key(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val titleKey = key(title)
        return words.all { it in titleKey }
    }

    /**
     * The words of [query] to search one by one when the whole query finds nothing (the site
     * may store "Şehir" where "Sehir" was typed): the longest few, of at least 3 letters.
     */
    fun fallbackWords(query: String, max: Int = 3): List<String> =
        query.trim().split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }
            .take(max)
}
