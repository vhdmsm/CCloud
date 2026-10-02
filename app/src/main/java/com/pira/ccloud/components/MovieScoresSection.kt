package com.pira.ccloud.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.repository.MovieInsights
import com.pira.ccloud.data.repository.MovieRanking
import com.pira.ccloud.data.repository.OmdbClient
import java.util.Locale

/**
 * Loads the movie's (or with [series], the series') awards, current IMDb rating and criteria;
 * null while loading or on failure.
 */
@Composable
fun rememberMovieInsight(movie: Movie, series: Boolean = false): MovieInsights.Insight? {
    val insight by produceState<MovieInsights.Insight?>(initialValue = null, movie.id, series) {
        value = try {
            MovieInsights.load(movie, series)
        } catch (e: Exception) {
            null
        }
    }
    return insight
}

/** The movie's (or series') awards and the value of each ranking criterion, below its description. */
@Composable
fun MovieScoresSection(movie: Movie, insight: MovieInsights.Insight?, modifier: Modifier = Modifier, series: Boolean = false) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Awards & Scores",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        val data = insight
        if (data == null) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val info = data.facts.info
            val rating = data.facts.rating
            ScoreRow("Awards", awardsText(data.facts.awards, data.awardsFromDescription))
            data.releaseDate?.let { ScoreRow("Release date", it) }
            ScoreRow(
                "IMDB",
                when {
                    rating != null -> format(
                        "%.1f from %,d votes (OMDb)\n(Top Rated score %.2f, trusted %d%%)",
                        rating.imdb, rating.votes, data.topRated, (MovieRanking.ratingConfidence(data.facts) * 100).toInt()
                    )
                    movie.imdb > 0 -> format(
                        "%.1f (the site's, may be old)\n(Top Rated score %.2f, trusted %d%%)",
                        movie.imdb, data.topRated, (MovieRanking.ratingConfidence(data.facts) * 100).toInt()
                    )
                    else -> "—"
                }
            )
            if (info != null) {
                ScoreRow("Popularity", percentileText(info.popularityPercentile) + format("  (score %.2f)", info.popularity))
                ScoreRow("How well known", percentileText(info.relevancePercentile) + format("  (score %.2f)", info.reach))
                if (info.actors.isNotEmpty()) {
                    ScoreRow(
                        "Lead actors",
                        info.actors.joinToString("\n") { "${it.name}: ${percentileText(it.percentile)}" } +
                            format("\n(score %.2f)", info.castPopularity ?: 0.0)
                    )
                }
            }
            val parts = data.bestOverall
            ScoreRow(
                "Best Overall",
                format(
                    "%.2f = rating %.2f + awards %.2f + popularity %.2f + actors %.2f",
                    parts.total, parts.rating, parts.awards, parts.popularity, parts.actors
                ) + if (parts.awardsSpread) "\n(recent: the awards' share is spread over rating, popularity and actors, and its awards are added on top)" else ""
            )
            if (series) {
                // Series are ranked with their start year weighed in
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val startYear = MovieRanking.startYearScore(movie.year, currentYear)
                val weight = MovieRanking.SERIES_START_YEAR_WEIGHT
                ScoreRow(
                    "Series rank",
                    format(
                        "%.2f = %.1f × Best Overall %.2f + %.1f × start year %.2f",
                        (1 - weight) * parts.total + weight * startYear, 1 - weight, parts.total, weight, startYear
                    ) + "\n(started ${movie.year}: ${MovieRanking.SERIES_MIN_START_YEAR} counts 0, $currentYear counts 1)"
                )
            }
            if (data.incomplete) {
                Text(
                    text = "Some data isn't available right now (monthly limit, no connection or no key)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info != null) {
                Text(
                    text = "Movie data from Watchmode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f)
        )
    }
}

private fun awardsText(awards: OmdbClient.Awards?, fromDescription: Boolean): String {
    if (awards == null) return "None found"
    if (awards.summary.isNotEmpty()) return awards.summary + "  (OMDb)"
    val otherWins = awards.wins - awards.oscarWins
    val otherNominations = awards.nominations - awards.oscarNominations
    val parts = listOfNotNull(
        awards.oscarWins.takeIf { it > 0 }?.let { "Won $it Oscar" + if (it > 1) "s" else "" },
        awards.oscarNominations.takeIf { it > 0 }?.let { "$it Oscar nomination" + if (it > 1) "s" else "" },
        otherWins.takeIf { it > 0 }?.let { "$it other win" + if (it > 1) "s" else "" },
        otherNominations.takeIf { it > 0 }?.let { "$it other nomination" + if (it > 1) "s" else "" }
    )
    if (parts.isEmpty()) return "None found"
    return parts.joinToString(" · ") + if (fromDescription) "  (description)" else "  (OMDb)"
}

// Watchmode's percentile out of 100 (100 = the very top), with the decimals that matter near the top
private fun percentileText(percentile: Double): String = when {
    percentile <= 0.0 -> "—"
    percentile >= 100.0 -> "100 / 100 (the very top)"
    percentile >= 99.0 -> format("%.3f / 100", percentile)
    else -> format("%.1f / 100", percentile)
}

private fun format(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)
