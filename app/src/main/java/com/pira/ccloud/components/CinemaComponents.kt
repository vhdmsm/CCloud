package com.pira.ccloud.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.WatchStatus
import com.pira.ccloud.navigation.AppScreens
import com.pira.ccloud.utils.DeviceUtils
import com.pira.ccloud.utils.StorageUtils
import com.pira.ccloud.utils.WatchHistory
import com.pira.ccloud.utils.tr

/*
 * The building blocks of the app's cinema look: poster cards, a featured banner at the top of a
 * list, rows of posters and section titles.
 */

private val PosterShape = RoundedCornerShape(6.dp)

/** The app's name as a red wordmark, at the top of the phone's lists and the TV's sidebar. */
@Composable
fun AppWordmark(modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text = tr("CCloud"),
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        fontSize = if (large) 26.sp else 20.sp,
        letterSpacing = 0.5.sp,
        maxLines = 1
    )
}

/**
 * The top of a phone's list: the red wordmark and the screen's name (on a TV the sidebar shows the
 * wordmark).
 */
@Composable
fun CinemaHeader(title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (remember { DeviceUtils.isTv(context) }) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppWordmark()
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

/** A bold title over a row or a list ("Continue Watching", "All Movies"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A movie or series as a tall poster (2:3) with its IMDb score on a corner, and its title and year
 * under it. On a TV the remote's focus grows the poster and outlines it.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String,
    year: Int,
    imdb: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusHighlight(shape = PosterShape, focusedScale = 1.06f)
                .clip(PosterShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageUrl).crossfade(true).build(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (imdb > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = tr("Rating"),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = String.format("%.1f", imdb),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (showDetails) {
            Text(
                text = title,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (year > 0) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** A grey poster that shimmers while the list loads. */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "posterShimmer")
    val shift by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200)),
        label = "posterShimmerShift"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val shine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val brush = Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = Offset(shift, shift),
        end = Offset(shift + 400f, shift + 400f)
    )
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(PosterShape)
                .background(brush)
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(brush)
        )
    }
}

/** The featured banner's place while the list loads. */
@Composable
fun PosterPlaceholderBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTv = remember { DeviceUtils.isTv(context) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isTv) 260.dp else 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/**
 * The featured title at the top of a list: its wide picture fading into the screen, its name,
 * year and genres, and a white button that opens it.
 */
@Composable
fun FeaturedBanner(
    title: String,
    coverUrl: String,
    year: Int,
    genres: List<String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isTv = remember { DeviceUtils.isTv(context) }
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isTv) 260.dp else 300.dp)
            .clip(RoundedCornerShape(10.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(coverUrl).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // Fades the picture into the screen, so the text under it reads on any picture
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to background.copy(alpha = 0.25f),
                        1f to background
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = if (isTv) Alignment.Start else Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val details = (listOfNotNull(year.takeIf { it > 0 }?.toString()) + genres.take(3)).joinToString("  •  ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .focusHighlight(shape = RoundedCornerShape(6.dp), focusedScale = 1.06f),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = tr("Watch Now"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * A row of the movies (or series) the user has started, newest first, at the top of a list.
 * Shows nothing when there are none.
 */
@Composable
fun ContinueWatchingRow(type: String, navController: NavController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val started = remember(type) {
        WatchHistory.load(context, WatchStatus.WATCHING)
            .filter { it.item.type == type }
            .sortedByDescending { it.updatedAt }
            .take(15)
            .map { it.item }
    }
    if (started.isEmpty()) return
    val isTv = remember { DeviceUtils.isTv(context) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(
            text = tr("Continue Watching"),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(started, key = { "${it.type}:${it.id}" }) { item ->
                PosterCard(
                    title = item.title,
                    imageUrl = item.image,
                    year = item.year,
                    imdb = item.imdb,
                    modifier = Modifier.width(if (isTv) 120.dp else 104.dp),
                    showDetails = false,
                    onClick = { openSaved(context, navController, item) }
                )
            }
        }
    }
}

// The details page reads the movie / series from the device, as from the favorites
private fun openSaved(context: android.content.Context, navController: NavController?, item: FavoriteItem) {
    StorageUtils.saveFavoriteToDatabase(context, item)
    val route = when (item.type) {
        "series" -> AppScreens.SingleSeries.route.replace("{seriesId}", item.id.toString())
        else -> AppScreens.SingleMovie.route.replace("{movieId}", item.id.toString())
    }
    navController?.navigate(route)
}
