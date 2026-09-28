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
        refresh()
    }
    
    fun selectFilterType(filterType: FilterType) {
        selectedFilterType = filterType
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
                
                val lastPage: Int
                val hasMore: Boolean
                val newMovies = if (selectedFilterType.isRanked) {
                    if (!append) handledIds = emptySet()
                    val ranked = rankedRepository.getRankedMovies(page, selectedGenreId, selectedFilterType, handledIds)
                    handledIds = handledIds + ranked.handledIds
                    rankingNotice = ranked.notice
                    rankingAttribution = ranked.attribution
                    lastPage = ranked.lastPage
                    hasMore = ranked.hasMore
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
