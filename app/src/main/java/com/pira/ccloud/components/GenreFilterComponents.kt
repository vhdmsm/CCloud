package com.pira.ccloud.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Genre

@Composable
fun GenreFilterSection(
    genres: List<Genre>,
    selectedGenreId: Int,
    selectedFilterType: FilterType,
    onGenreSelected: (Int) -> Unit,
    onFilterTypeSelected: (FilterType) -> Unit,
    filterTypes: List<FilterType> = listOf(FilterType.DEFAULT, FilterType.BY_YEAR, FilterType.BY_IMDB)
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "Filters",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        // Filter row with filter type on left and genre selector on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Filter type selector on the left
            FilterTypeSelector(
                selectedFilterType = selectedFilterType,
                onFilterTypeSelected = onFilterTypeSelected,
                filterTypes = filterTypes
            )
            
            // Genre selector on the right
            GenreSelector(
                genres = genres,
                selectedGenreId = selectedGenreId,
                onGenreSelected = onGenreSelected
            )
        }
    }
}

@Composable
fun FilterTypeSelector(
    selectedFilterType: FilterType,
    onFilterTypeSelected: (FilterType) -> Unit,
    filterTypes: List<FilterType> = listOf(FilterType.DEFAULT, FilterType.BY_YEAR, FilterType.BY_IMDB)
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .width(150.dp)
            .height(36.dp)
            .focusHighlight(shape = RoundedCornerShape(18.dp), focusedScale = 1.06f, outlineWidth = 2.dp)
            .clickable { expanded = true },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sort: ${filterTypeShortLabel(selectedFilterType)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Filter options",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.height(16.dp)
                )
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filterTypes.forEachIndexed { index, filterType ->
                    // Server sorts, then single-field sorts, then combined sorts
                    val group = filterTypeGroup(filterType)
                    if (index > 0 && group != filterTypeGroup(filterTypes[index - 1])) {
                        HorizontalDivider()
                    }
                    if ((index == 0 || group != filterTypeGroup(filterTypes[index - 1])) && group.isNotEmpty()) {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(filterTypeMenuLabel(filterType)) },
                        onClick = {
                            onFilterTypeSelected(filterType)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun filterTypeMenuLabel(filterType: FilterType): String = when (filterType) {
    FilterType.DEFAULT -> "Default"
    FilterType.BY_YEAR -> "By Year"
    FilterType.BY_IMDB -> "By IMDB"
    FilterType.MOST_POPULAR -> "Most Popular"
    FilterType.TOP_RATED -> "Top Rated"
    FilterType.STAR_CAST -> "Famous Actors"
    FilterType.MOST_AWARDED -> "Most Awards"
    FilterType.NEWEST -> "Newest Release Date"
    FilterType.TOP_PICKS -> "Top Picks (Rating + Year)"
    FilterType.POPULAR_CAST -> "Popular + Actors"
    FilterType.BEST_OVERALL -> "Best Overall (All)"
}

// Fits the 150dp sort button
private fun filterTypeShortLabel(filterType: FilterType): String = when (filterType) {
    FilterType.DEFAULT -> "Default"
    FilterType.BY_YEAR -> "By Year"
    FilterType.BY_IMDB -> "By IMDB"
    FilterType.MOST_POPULAR -> "Popular"
    FilterType.TOP_RATED -> "Top Rated"
    FilterType.STAR_CAST -> "Actors"
    FilterType.MOST_AWARDED -> "Awards"
    FilterType.NEWEST -> "Newest"
    FilterType.TOP_PICKS -> "Top Picks"
    FilterType.POPULAR_CAST -> "Pop+Actors"
    FilterType.BEST_OVERALL -> "Best"
}

private fun filterTypeGroup(filterType: FilterType): String = when {
    !filterType.isRanked -> ""
    filterType in listOf(FilterType.TOP_PICKS, FilterType.POPULAR_CAST, FilterType.BEST_OVERALL) -> "Combined"
    else -> "Single field"
}

@Composable
fun GenreSelector(
    genres: List<Genre>,
    selectedGenreId: Int,
    onGenreSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    // Find the selected genre title
    val selectedGenreTitle = if (selectedGenreId == 0) {
        "All Genres"
    } else {
        genres.find { it.id == selectedGenreId }?.title ?: "All Genres"
    }
    
    Card(
        modifier = Modifier
            .width(150.dp)
            .height(36.dp)
            .focusHighlight(shape = RoundedCornerShape(18.dp), focusedScale = 1.06f, outlineWidth = 2.dp)
            .clickable { expanded = true },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedGenreTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondary,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Genre options",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.height(16.dp)
                )
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("All Genres") },
                    onClick = {
                        onGenreSelected(0)
                        expanded = false
                    }
                )
                
                genres.forEach { genre ->
                    DropdownMenuItem(
                        text = { Text(genre.title) },
                        onClick = {
                            onGenreSelected(genre.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}