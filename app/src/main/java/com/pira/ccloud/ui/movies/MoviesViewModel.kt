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
import com.pira.ccloud.data.repository.RankedMovieRepository
import com.pira.ccloud.data.repository.TmdbClient
import com.pira.ccloud.utils.LanguageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MoviesViewModel : ViewModel() {
    private val repository = MovieRepository()
    private val rankedRepository = RankedMovieRepository(repository)
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
    
    // False when the Popular filter couldn't reach TMDB and shows movies in IMDB order instead
    var popularityAvailable by mutableStateOf(true)
        private set
    
    // Popular needs a TMDB API key in the build
    val filterTypes: List<FilterType> = FilterType.entries.filter {
        it != FilterType.POPULAR || TmdbClient.isConfigured
    }
    
    private var loadJob: Job? = null
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
    
    fun loadMovies(page: Int = 0) {
        // A new first page replaces the list, so a slower load for the old filter must not land on it
        if (page == 0) loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            try {
                if (page == 0) {
                    isLoading = true
                } else {
                    isLoadingMore = true
                }
                errorMessage = null
                
                val lastPage: Int
                val hasMore: Boolean
                val newMovies = when (selectedFilterType) {
                    FilterType.TOP_PICKS, FilterType.POPULAR -> {
                        val shownIds = if (page == 0) emptySet() else movies.map { it.id }.toSet()
                        val ranked = rankedRepository.getRankedMovies(page, selectedGenreId, selectedFilterType, shownIds)
                        popularityAvailable = ranked.popularityAvailable
                        lastPage = ranked.lastPage
                        hasMore = ranked.hasMore
                        ranked.movies
                    }
                    else -> {
                        val result = repository.getMovies(page, selectedGenreId, selectedFilterType)
                        popularityAvailable = true
                        lastPage = page
                        hasMore = result.isNotEmpty()
                        result
                    }
                }
                
                // Filter out movies with Farsi titles
                val filteredMovies = newMovies.filter { movie ->
                    LanguageUtils.shouldDisplayTitle(movie.title)
                }
                
                // Ranked filters can return an empty batch while the server still has pages
                canLoadMore = if (selectedFilterType == FilterType.TOP_PICKS || selectedFilterType == FilterType.POPULAR) {
                    hasMore
                } else {
                    // If we get fewer movies than expected, we've reached the end
                    filteredMovies.isNotEmpty()
                }
                
                if (page == 0) {
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
            loadMovies(currentPage + 1)
        }
    }
    
    fun retry() {
        loadMovies(currentPage)
    }
    
    fun refresh() {
        loadMovies(0)
    }
}
