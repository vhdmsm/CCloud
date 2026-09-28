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
import com.pira.ccloud.data.repository.OmdbClient
import java.util.Locale

/** The movie's awards and the value of each ranking criterion, below its description. */
@Composable
fun MovieScoresSection(movie: Movie, modifier: Modifier = Modifier) {
    val insight by produceState<MovieInsights.Insight?>(initialValue = null, movie.id) {
        value = try {
            MovieInsights.load(movie)
        } catch (e: Exception) {
            null
        }
    }

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
            ScoreRow("Awards", awardsText(data.facts.awards, data.awardsFromDescription))
            data.releaseDate?.let { ScoreRow("Release date", it) }
            ScoreRow(
                "IMDB",
                if (movie.imdb > 0) {
                    format("%.1f  (Top Rated score %.2f, trusted %d%%)", movie.imdb, data.topRated, ((info?.ratingConfidence ?: 0.0) * 100).toInt())
                } else {
                    "—"
                }
            )
            if (info != null) {
                ScoreRow("Popularity", topShare(info.popularityPercentile) + format("  (score %.2f)", info.popularity))
                ScoreRow("How well known", topShare(info.relevancePercentile) + format("  (score %.2f)", info.reach))
                if (info.actors.isNotEmpty()) {
                    ScoreRow(
                        "Lead actors",
                        info.actors.joinToString("\n") { "${it.name}: ${topShare(it.percentile)}" } +
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
                )
            )
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

// "top 0.15%" from a Watchmode percentile of 99.85
private fun topShare(percentile: Double): String {
    if (percentile <= 0.0) return "—"
    val share = 100.0 - percentile
    return when {
        share < 0.1 -> format("top %.3f%%", share)
        share < 10 -> format("top %.1f%%", share)
        else -> format("top %.0f%%", share)
    }
}

private fun format(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)
