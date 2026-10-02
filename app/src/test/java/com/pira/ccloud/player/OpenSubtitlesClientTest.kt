package com.pira.ccloud.player

import com.pira.ccloud.player.OpenSubtitlesClient.ReleaseInfo
import com.pira.ccloud.player.OpenSubtitlesClient.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Which search results are kept and in what order (search results as OpenSubtitles returned them)
class OpenSubtitlesClientTest {
    private fun result(id: Int, release: String, title: String, year: Int?, downloads: Int = 0, matchesFile: Boolean = false) =
        Result(id, release, downloads, matchesFile, hearingImpaired = false, title = title, year = year)

    @Test
    fun keepsOnlyTheMovieAndPrefersTheClosestYear() {
        // The file says 2026, OpenSubtitles knows the movie as 2025; a 2017 movie has the same title
        val release = ReleaseInfo("The.Snare.2026.480p.WEB.DL.SoftSub.simba", "The Snare", 2026, null, null)
        val found = listOf(
            result(1, "THE COUPLE ACROSS THE STREET 2026 WEBRip", "The Couple Across the Street", 2026, downloads = 2185),
            result(2, "The.Snare.2017.1080p.WEB-DL.DD5.1.H264-FGT", "The Snare", 2017, downloads = 16826),
            result(3, "The Snare (2025)", "The Snare", 2025, downloads = 196),
            result(4, "Hanzo.The.Razor.The.Snare.1973.DVDRip.XviD-AEN", "Hanzo the Razor: The Snare", 1973, downloads = 2529),
            // Found again by the search for another year
            result(3, "The Snare (2025)", "The Snare", 2025, downloads = 196)
        )
        assertEquals(listOf(3, 2), OpenSubtitlesClient.pickResults(release, found).map { it.fileId })
    }

    @Test
    fun theSameFileComesFirstWhateverItsTitle() {
        val release = ReleaseInfo("Julian.2025.720p.WEB-DL", "Julian", 2025, null, null)
        val found = listOf(
            result(1, "Julian.2025.1080p.WEB-DL", "Julian", 2025),
            result(2, "Julian.2025.720p.HMAX.WEB-DL", "Julian (2025)", 2025, matchesFile = true)
        )
        assertEquals(listOf(2, 1), OpenSubtitlesClient.pickResults(release, found).map { it.fileId })
    }

    @Test
    fun matchesTheSameSourceWrittenDifferently() {
        // WEB.DL in the file name, WEB-DL in the subtitle's release: the WEB-DL one wins over a WEBRip
        val release = ReleaseInfo("Movie.2025.720p.WEB.DL.SoftSub", "Movie", 2025, null, null)
        val found = listOf(
            result(1, "Movie.2025.720p.WEBRip.x264", "Movie", 2025, downloads = 900),
            result(2, "Movie.2025.720p.WEB-DL.DDP5.1", "Movie", 2025, downloads = 10)
        )
        assertEquals(listOf(2, 1), OpenSubtitlesClient.pickResults(release, found).map { it.fileId })
    }

    @Test
    fun fallsBackToTitlesContainingAllWords() {
        // No exact title: one that has all the words (with a subtitle) is used, a partial one is not
        val release = ReleaseInfo("Mission.Impossible.2025.1080p", "Mission Impossible", 2025, null, null)
        val found = listOf(
            result(1, "Mission.Impossible.The.Final.Reckoning.2025", "Mission: Impossible - The Final Reckoning", 2025),
            result(2, "Impossible.Love.2025", "Impossible Love", 2025)
        )
        assertEquals(listOf(1), OpenSubtitlesClient.pickResults(release, found).map { it.fileId })
    }

    @Test
    fun showsNothingRatherThanAnotherMovie() {
        val release = ReleaseInfo("The.Snare.2026.480p", "The Snare", 2026, null, null)
        val found = listOf(result(1, "The Dangers in My Heart The Movie 2026", "The Dangers in My Heart: The Movie", 2026))
        assertTrue(OpenSubtitlesClient.pickResults(release, found).isEmpty())
    }
}
