package com.pira.ccloud.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.pira.ccloud.R
import com.pira.ccloud.data.model.WatchStatus

/**
 * The phone's Favorites tab: Favorites, Watching and Seen side by side as tabs, since the
 * bottom bar has no room for two more sections (the TV sidebar lists them on their own).
 */
@Composable
fun PhoneLibraryScreen(navController: NavController) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val titles = listOf(R.string.favorites, R.string.watching, R.string.seen)

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected, modifier = Modifier.fillMaxWidth()) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = { Text(stringResource(title)) }
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selected) {
                0 -> FavoritesScreen(navController)
                1 -> WatchListScreen(status = WatchStatus.WATCHING, navController = navController, showTitle = false)
                else -> WatchListScreen(status = WatchStatus.SEEN, navController = navController, showTitle = false)
            }
        }
    }
}
