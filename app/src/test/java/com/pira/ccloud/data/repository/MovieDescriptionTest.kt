package com.pira.ccloud.data.repository

import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Descriptions and genres as the app's server sends them
class MovieDescriptionTest {
    private fun movie(
        title: String = "Some Movie",
        description: String = "",
        genres: List<String> = listOf("درام"),
        country: String = "امریکا"
    ) = Movie(
        id = 1, type = "movie", title = title, description = description, year = 2020, imdb = 8.0, rating = 0.0,
        duration = null, image = "", cover = "", genres = genres.mapIndexed { i, t -> Genre(i, t) }, sources = emptyList(),
        country = listOf(Country(1, country, ""))
    )

    @Test
    fun readsOscarsAndOtherAwards() {
        val awards = MovieDescription.awards(movie(description = " جزو 250 فیلم برتر با رتبه 3\r\n\r\n برنده 6 جایزه اسکار .. برنده 11 جایزه و نامزد 20 جایزه دیگر"))
        assertEquals(OmdbClient.Awards(oscarWins = 6, oscarNominations = 0, wins = 17, nominations = 20), awards)
    }

    @Test
    fun readsWinsAndNominationsWithoutOscars() {
        val awards = MovieDescription.awards(movie(description = "موفق شد برنده 32 جایزه از جمله 5 جایزه بفتا شده و نامزد دریافت 26 جایزه دیگر نیز شود\r\nبرنده 32 جایزه و کاندیدای 26 جایزه دیگر"))
        assertEquals(OmdbClient.Awards(oscarWins = 0, oscarNominations = 0, wins = 32, nominations = 26), awards)
    }

    @Test
    fun readsPersianDigits() {
        val awards = MovieDescription.awards(movie(description = "برنده ۲ جایزه‌ی اسکار و نامزد ۵ جایزه دیگر"))
        assertEquals(OmdbClient.Awards(oscarWins = 2, oscarNominations = 0, wins = 2, nominations = 5), awards)
    }

    @Test
    fun oscarGenreCountsAsANomination() {
        val awards = MovieDescription.awards(movie(genres = listOf("انیمیشن + انیمه", "اسکار 2021")))
        assertEquals(OmdbClient.Awards(oscarWins = 0, oscarNominations = 1, wins = 0, nominations = 1), awards)
    }

    @Test
    fun noAwardsWhenTheDescriptionSaysNothing() {
        assertNull(MovieDescription.awards(movie(description = "رتبه ی اول در بین 250 فیلم برتر تاریخ سینمای جهان")))
    }

    @Test
    fun readsTheReleaseDate() {
        assertEquals("2020-09-28", MovieDescription.releaseDate(movie(description = "محصول کشور : انگلستان آمریکا\r\n\r\nتاریخ اکران : 28 Sep 2020\r\n")))
        assertNull(MovieDescription.releaseDate(movie(description = "سال انتشار : 1994")))
    }

    @Test
    fun skipsIndianTurkishAndSitePostsBeforeAnyRequest() {
        assertFalse(MovieRanking.isCandidate(movie(genres = listOf("جنایی", "هندی")), FilterType.MOST_POPULAR))
        assertFalse(MovieRanking.isCandidate(movie(genres = listOf("کمدی", "ترکی")), FilterType.MOST_POPULAR))
        assertFalse(MovieRanking.isCandidate(movie(title = "نسخه جدید ملودی باکس"), FilterType.MOST_POPULAR))
        assertTrue(MovieRanking.isCandidate(movie(), FilterType.MOST_POPULAR))
    }
}
