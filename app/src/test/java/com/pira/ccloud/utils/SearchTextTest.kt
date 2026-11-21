package com.pira.ccloud.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun foldsTurkishLetters() {
        assertEquals("Uzak Sehir", SearchText.fold("Uzak Şehir"))
        assertEquals("cigoou IiSG", SearchText.fold("çığööü İıŞĞ"))
        assertEquals("Amelie", SearchText.fold("Amélie"))
    }

    @Test
    fun matchesEitherWay() {
        assertTrue(SearchText.matches("Uzak Şehir", "uzak sehir"))
        assertTrue(SearchText.matches("Uzak Sehir", "Uzak Şehir"))
        assertTrue(SearchText.matches("Kızılcık Şerbeti", "kizilcik"))
        assertFalse(SearchText.matches("Uzak Şehir", "yakin sehir"))
        assertFalse(SearchText.matches("Uzak Şehir", "  "))
    }

    @Test
    fun picksTheLongestWordsToSearchAlone() {
        assertEquals(listOf("Sehir", "Uzak"), SearchText.fallbackWords("Uzak Sehir"))
        assertEquals(listOf("Kizilcik", "Serbeti"), SearchText.fallbackWords("Kizilcik Serbeti of"))
    }
}
