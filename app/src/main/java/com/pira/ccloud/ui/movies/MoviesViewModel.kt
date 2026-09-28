package com.pira.ccloud.ui.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pira.ccloud.data.model.FilterType
import com.pira.ccloud.data.model.Genre
import com.pira.ccloud.data.model.Movie
import com.pira.ccloud.data.repository.GenreRepository
import com.pira.ccloud.data.repository.MovieRepository
import com.pira.ccloud.data.repository.OmdbClient
import com.pira.ccloud.data.repository.RankedMovieRepository
import com.pira.ccloud.data.repository.WatchmodeClient
import com.pira.ccloud.utils.LanguageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MoviesViewModel : ViewModel() {
    private val repository = MovieRepository()
    private val rankedRepository = RankedMovieRepository(repository::getMovies)
    private val genreRepository = GenreRepository()
    
    var movies by mutableStateOf<List<Movie>>(emptyList())
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
    
    var selectedFilterType by mutableStateOf(FilterType.DEFAULT)
        private set
    
    // Shown above the list when a ranked sort is missing movie or award data
    var rankingNotice by mutableStateOf<String?>(null)
        private set
    
    // Credit for the data source (Watchmode) when its data is used
    var rankingAttribution by mutableStateOf<String?>(null)
        private set
    
    // While a ranked list is still getting movie data (it's shown and re-ranked meanwhile)
    var rankingProgress by mutableStateOf<String?>(null)
        private set
    
    // Sorts whose data source isn't set up in the build are left out
    val filterTypes: List<FilterType> = FilterType.entries.filter {
        (!it.needsMovieData || WatchmodeClient.isConfigured) &&
            (!it.needsOmdb || OmdbClient.isConfigured)
    }
    
    private var loadJob: Job? = null
    // Movies the ranked sorts already dealt with (shown or skipped), so the next load doesn't take them again
    private var handledIds: Set<Int> = emptySet()
    // Bumped on every load, so only the latest one updates the loading and error state
    private var loadGeneration = 0
    
    init {
        loadGenres()
        loadMovies()
        // How much of the month's Watchmode credits is left, shown under the ranked sorts (no credits used)
        if (WatchmodeClient.isConfigured) viewModelScope.launch { WatchmodeClient.refreshQuotas() }
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
        movies = emptyList()
        rankingNotice = null
        rankingAttribution = null
        refresh()
    }
    
    // [append] adds to the list; otherwise the list is replaced (a ranked list's next load may start
    // on the page before, to read the rest of a page that ran into the next year)
    fun loadMovies(page: Int = 0, append: Boolean = page > 0) {
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
                val newMovies = if (selectedFilterType.isRanked) {
                    if (!append) handledIds = emptySet()
                    // Earlier batches stay above the one loading; a first page replaces the list
                    val shownBefore = if (append) movies else emptyList()
                    val ranked = rankedRepository.getRankedMovies(page, selectedGenreId, selectedFilterType, handledIds) { update ->
                        // The batch shows as soon as the server's list is read and is re-ranked as data comes in
                        movies = shownBefore + update.movies.filter { LanguageUtils.shouldDisplayTitle(it.title) }
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
                    if (append) movies = shownBefore
                    ranked.movies
                } else {
                    val result = repository.getMovies(page, selectedGenreId, selectedFilterType)
                    rankingNotice = null
                    rankingAttribution = null
                    lastPage = page
                    hasMore = result.isNotEmpty()
                    result
                }
                
                // Filter out movies with Farsi titles
                val filteredMovies = newMovies.filter { movie ->
                    LanguageUtils.shouldDisplayTitle(movie.title)
                }
                
                // Ranked filters can return an empty batch while the server still has pages
                canLoadMore = if (selectedFilterType.isRanked) {
                    hasMore
                } else {
                    // If we get fewer movies than expected, we've reached the end
                    filteredMovies.isNotEmpty()
                }
                
                if (!append) {
                    movies = filteredMovies
                } else {
                    movies = movies + filteredMovies
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
    
    fun loadMoreMovies() {
        if (!isLoading && !isLoadingMore && canLoadMore) {
            loadMovies(currentPage + 1, append = true)
        }
    }
    
    // A failed first load starts over; a failed load-more tries the same pages again
    fun retry() {
        if (movies.isEmpty()) refresh() else loadMovies(currentPage + 1, append = true)
    }
    
    fun refresh() {
        loadMovies(0)
    }
}
