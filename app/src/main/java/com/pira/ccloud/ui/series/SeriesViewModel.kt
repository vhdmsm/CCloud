package com.pira.ccloud.ui.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.model.Series
import com.pira.ccloud.data.repository.GenreRepository
import com.pira.ccloud.data.repository.MovieRanking
import com.pira.ccloud.data.repository.RankedMovieRepository
import com.pira.ccloud.data.repository.SeriesRepository
import com.pira.ccloud.utils.LanguageUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeriesViewModel : ViewModel() {
    private val repository = SeriesRepository()
    private val genreRepository = GenreRepository()
    
    // The series of the pages read, to turn the ranked list (ranked as movies) back into series
    private val seriesById = ConcurrentHashMap<Int, Series>()
    private val rankedRepository = RankedMovieRepository(
        fetchPage = { page, genreId, filterType ->
            repository.getSeries(page, genreId, filterType).map { seriesItem ->
                seriesById[seriesItem.id] = seriesItem
                seriesItem.toMovie()
            }
        },
        series = true
    )
    
    var series by mutableStateOf<List<Series>>(emptyList())
        private set
    
    var isLoading by mutableStateOf(false)
        private set
    
    var isLoadingMore by mutableStateOf(false)
        private set
    
    var errorMessage by mutableStateOf<String?>(null)
        private set
    
    var currentPage by mutableStateOf(0)
        private set
    
    var canLoadMore by mutableStateOf(true)
        private set
    
    var genres by mutableStateOf<List<Genre>>(emptyList())
        private set
    
    var selectedGenreId by mutableStateOf(0)
        private set
    
    // Best Overall on opening when its data sources are set up in the build, else the server's order
    var selectedFilterType by mutableStateOf(
        if (MovieRanking.isAvailable(FilterType.BEST_OVERALL)) FilterType.BEST_OVERALL else FilterType.DEFAULT
    )
        private set
    
    // Shown above the list when a ranked sort is missing data
    var rankingNotice by mutableStateOf<String?>(null)
        private set
    
    // Credit for the data source (Watchmode) when its data is used
    var rankingAttribution by mutableStateOf<String?>(null)
        private set
    
    // While a ranked list is still getting data (it's shown and re-ranked meanwhile)
    var rankingProgress by mutableStateOf<String?>(null)
        private set
    
    // Sorts whose data source isn't set up in the build are left out
    val filterTypes: List<FilterType> = FilterType.entries.filter { MovieRanking.isAvailable(it) }
    
    private var loadJob: Job? = null
    // Series the ranked sorts already dealt with (shown or skipped), so the next load doesn't take them again
    private var handledIds: Set<Int> = emptySet()
    // Bumped on every load, so only the latest one updates the loading and error state
    private var loadGeneration = 0
    
    init {
        loadGenres()
        loadSeries()
    }
    
    fun loadGenres() {
        viewModelScope.launch {
            try {
                genres = genreRepository.getGenres()
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }
    
    fun selectGenre(genreId: Int) {
        selectedGenreId = genreId
        showNewList()
    }
    
    fun selectFilterType(filterType: FilterType) {
        selectedFilterType = filterType
        showNewList()
    }
    
    // The old list (another sort or genre) mustn't stay up while the new one loads
    private fun showNewList() {
        series = emptyList()
        rankingNotice = null
        rankingAttribution = null
        refresh()
    }
    
    private fun Movie.toSeries(): Series? = seriesById[id]?.copy(imdb = imdb)
    
    // [append] adds to the list; otherwise the list is replaced (a ranked list's next load may start
    // on the page before, to read the rest of a page that ran into the next year)
    fun loadSeries(page: Int = 0, append: Boolean = page > 0) {
        // A new first page replaces the list, so a slower load for the old filter must not land on it
        if (!append) loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            try {
                if (!append) {
                    isLoading = true
                } else {
                    isLoadingMore = true
                }
                errorMessage = null
                rankingProgress = null
                
                val lastPage: Int
                val hasMore: Boolean
                val newSeries = if (selectedFilterType.isRanked) {
                    if (!append) handledIds = emptySet()
                    // Earlier batches stay above the one loading; a first page replaces the list
                    val shownBefore = if (append) series else emptyList()
                    val ranked = rankedRepository.getRankedMovies(page, selectedGenreId, selectedFilterType, handledIds) { update ->
                        // The batch shows as soon as the server's list is read and is re-ranked as data comes in
                        series = shownBefore + update.movies.mapNotNull { it.toSeries() }
                            .filter { LanguageUtils.shouldDisplayTitle(it.title) }
                        rankingNotice = update.notice
                        rankingAttribution = update.attribution
                        rankingProgress = update.progress
                    }
                    handledIds = handledIds + ranked.handledIds
                    rankingNotice = ranked.notice
                    rankingAttribution = ranked.attribution
                    rankingProgress = null
                    lastPage = ranked.lastPage
                    hasMore = ranked.hasMore
                    if (append) series = shownBefore
                    ranked.movies.mapNotNull { it.toSeries() }
                } else {
                    val result = repository.getSeries(page, selectedGenreId, selectedFilterType)
                    rankingNotice = null
                    rankingAttribution = null
                    lastPage = page
                    hasMore = result.isNotEmpty()
                    result
                }
                
                // Filter out series with Farsi titles
                val filteredSeries = newSeries.filter { seriesItem ->
                    LanguageUtils.shouldDisplayTitle(seriesItem.title)
                }
                
                // Ranked filters can return an empty batch while the server still has pages
                canLoadMore = if (selectedFilterType.isRanked) hasMore else filteredSeries.isNotEmpty()
                
                if (!append) {
                    series = filteredSeries
                } else {
                    series = series + filteredSeries
                }
                
                currentPage = lastPage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) errorMessage = e.message
            } finally {
                if (generation == loadGeneration) {
                    isLoading = false
                    isLoadingMore = false
                    rankingProgress = null
                }
            }
        }
    }
    
    fun loadMoreSeries() {
        if (!isLoading && !isLoadingMore && canLoadMore) {
            loadSeries(currentPage + 1, append = true)
        }
    }
    
    // A failed first load starts over; a failed load-more tries the same pages again
    fun retry() {
        if (series.isEmpty()) refresh() else loadSeries(currentPage + 1, append = true)
    }
    
    fun refresh() {
        loadSeries(0)
    }
}
