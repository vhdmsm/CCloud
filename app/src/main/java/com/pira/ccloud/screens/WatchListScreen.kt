package com.pira.ccloud.screens

import com.pira.ccloud.utils.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.pira.ccloud.formatTime
import com.pira.ccloud.components.FocusWhenReady
import com.pira.ccloud.components.focusHighlight
import com.pira.ccloud.data.model.WatchHistoryItem
import com.pira.ccloud.data.model.WatchStatus
import com.pira.ccloud.navigation.AppScreens
import com.pira.ccloud.utils.PlaybackPositions
import com.pira.ccloud.utils.StorageUtils
import com.pira.ccloud.utils.WatchHistory

/** The Watching or Seen section: the movies and series with that status, the most recent first. */
@Composable
fun WatchListScreen(status: WatchStatus, navController: NavController, showTitle: Boolean = true) {
    val context = LocalContext.current
    var items by remember(status) { mutableStateOf(WatchHistory.load(context, status)) }
    // Read again when shown, e.g. back from a movie that was finished in the meantime
    LifecycleResumeEffect(status) {
        items = WatchHistory.load(context, status)
        onPauseOrDispose { }
    }
    fun reload() {
        items = WatchHistory.load(context, status)
    }

    val firstItemFocus = remember { FocusRequester() }
    FocusWhenReady(firstItemFocus, items.isNotEmpty())

    Column(modifier = Modifier.fillMaxSize()) {
        // The phone shows the section's name on its tab instead
        if (showTitle) {
            Text(
                text = if (status == WatchStatus.WATCHING) tr("Watching") else tr("Seen"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (status == WatchStatus.WATCHING) {
                        tr("Movies and series you start playing show up here.")
                    } else {
                        tr("Movies you finish, and series whose last episode you finish, show up here.")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(items, key = { _, entry -> "${entry.item.type}:${entry.item.id}" }) { index, entry ->
                    WatchItemCard(
                        entry = entry,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                        onClick = {
                            // The details page reads the movie / series from the device, as from the favorites
                            StorageUtils.saveFavoriteToDatabase(context, entry.item)
                            val route = when (entry.item.type) {
                                "series" -> AppScreens.SingleSeries.route.replace("{seriesId}", entry.item.id.toString())
                                else -> AppScreens.SingleMovie.route.replace("{movieId}", entry.item.id.toString())
                            }
                            navController.navigate(route)
                        },
                        onMove = {
                            if (entry.status == WatchStatus.SEEN) {
                                WatchHistory.markWatching(context, entry.item)
                            } else {
                                WatchHistory.markSeen(context, entry.item)
                            }
                            reload()
                        },
                        onRemove = {
                            WatchHistory.remove(context, entry.item.id, entry.item.type)
                            reload()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchItemCard(
    entry: WatchHistoryItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val item = entry.item
    var showMenu by remember { mutableStateOf(false) }
    // Where a movie being watched was left
    val resumeAt = remember(entry) {
        if (entry.status == WatchStatus.WATCHING && item.type == "movie") {
            PlaybackPositions.get(context, PlaybackPositions.movieKey(item.id))
        } else {
            null
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .focusHighlight(focusedScale = 1.03f)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(context).data(item.image).crossfade(true).build()
                ),
                contentDescription = item.title,
                modifier = Modifier
                    .height(80.dp)
                    .width(60.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "${if (item.type == "series") tr("Series") else tr("Movie")} • ${item.year}",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp)
                    )
                    Text(text = String.format("%.1f", item.imdb))
                }
                resumeAt?.let {
                    Text(
                        text = tr("Continue from %s", formatTime(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = tr("More options"))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (entry.status == WatchStatus.SEEN) tr("Move to Watching") else tr("Mark as seen")) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (entry.status == WatchStatus.SEEN) Icons.Default.PlayCircle else Icons.Default.CheckCircle,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Remove")) },
                        onClick = {
                            showMenu = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}
