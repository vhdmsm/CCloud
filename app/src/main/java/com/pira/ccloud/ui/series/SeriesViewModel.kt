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
import com.pira.ccloud.data.repository.RankedListStore
import com.pira.ccloud.data.repository.RankedMovieRepository
import com.pira.ccloud.data.repository.SeriesRepository
import com.pira.ccloud.utils.LanguageUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeriesViewModel : ViewModel() {
    companion object {
        // Genres hidden from the series list: Turkish, Indian, animation + anime
        private val HIDDEN_GENRE_IDS = setOf(35, 38, 3)
        // Series that started before this year are hidden (the series ranking scores from it too)
        private const val MIN_START_YEAR = MovieRanking.SERIES_MIN_START_YEAR
        // Genres about older series, where the start year limit doesn't apply (classic, top 250)
        private val ALL_YEARS_GENRE_IDS = setOf(26, 32)
        // Pages fetched at most in one load when the filters leave pages empty
        private const val MAX_PAGES_PER_LOAD = 5
        // After this, the site's newest-first list kept for the ranked sorts is read again in the
        // background (the kept one shows meanwhile)
        private const val SITE_LIST_MAX_AGE_MS = 2L * 60 * 60 * 1000
    }
    
    private val repository = SeriesRepository()
    private val genreRepository = GenreRepository()
    
    // The series of the pages read, to turn the ranked list (ranked as movies) back into series
    private val seriesById = ConcurrentHashMap<Int, Series>()
    private val rankedRepository = RankedMovieRepository(
        fetchPage = { page, genreId, filterType ->
            // The ranked sorts read the whole newest-first list (about 240 pages), kept on the device
            // (SiteListCache)
            val pageSeries = if (filterType == FilterType.BY_YEAR) {
                repository.getSeriesCached(page, genreId, filterType, SITE_LIST_MAX_AGE_MS)
            } else {
                repository.getSeries(page, genreId, filterType)
            }
            pageSeries.map { seriesItem ->
                seriesById[seriesItem.id] = seriesItem
                seriesItem.toMovie()
            }
        },
        series = true,
        // Hidden series are dropped before their data is looked up (no Watchmode/OMDb credits spent)
        isWanted = { movie -> seriesById[movie.id]?.let { isShown(it) } ?: true },
        minYear = { if (appliesYearLimit()) MIN_START_YEAR else null }
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
    // The list shown is the one saved when it was last ranked; it's being ranked again
    private var showingSaved = false
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
        showingSaved = false
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
                val filteredSeries: List<Series>
                val listKey = "${selectedFilterType.name}-$selectedGenreId"
                if (selectedFilterType.isRanked) {
                    if (!append) handledIds = emptySet()
                    // The list as last ranked shows at once while it's ranked again (until then it
                    // doesn't load more)
                    val saved = if (!append) RankedListStore.series(listKey) else null
                    if (saved != null && series.isEmpty()) {
                        series = saved.series.filter { isShown(it) }
                        rankingNotice = saved.notice
                        rankingAttribution = saved.attribution
                        showingSaved = true
                        canLoadMore = false
                    }
                    val updatesSaved = saved != null
                    // Earlier batches stay above the one loading; a first page replaces the list
                    val shownBefore = if (append) series else emptyList()
                    val ranked = rankedRepository.getRankedMovies(page, selectedGenreId, selectedFilterType, handledIds) { update ->
                        // The batch shows as soon as the server's list is read and is re-ranked as data
                        // comes in (a saved list stays until the new one is done). Ranking runs off the
                        // main thread; the list is updated on it (in order, and not after this load is
                        // cancelled)
                        launch {
                            if (updatesSaved) {
                                rankingProgress = update.progress?.let { "Updating the list: $it" }
                            } else {
                                series = shownBefore + update.movies.mapNotNull { it.toSeries() }.filter { isShown(it) }
                                rankingNotice = update.notice
                                rankingAttribution = update.attribution
                                rankingProgress = update.progress
                            }
                        }
                    }
                    handledIds = handledIds + ranked.handledIds
                    rankingNotice = ranked.notice
                    rankingAttribution = ranked.attribution
                    rankingProgress = null
                    lastPage = ranked.lastPage
                    if (append) series = shownBefore
                    filteredSeries = ranked.movies.mapNotNull { it.toSeries() }.filter { isShown(it) }
                    // Ranked filters can return an empty batch while the server still has pages
                    canLoadMore = ranked.hasMore
                } else {
                    rankingNotice = null
                    rankingAttribution = null
                    // Keep fetching while the filters leave a page empty, so scrolling doesn't stop early
                    var loadedPage = page
                    var pageSeries = emptyList<Series>()
                    var reachedEnd = false
                    for (attempt in 0 until MAX_PAGES_PER_LOAD) {
                        val newSeries = repository.getSeries(loadedPage, selectedGenreId, selectedFilterType)
                        if (newSeries.isEmpty()) {
                            reachedEnd = true
                            break
                        }
                        pageSeries = newSeries.filter { isShown(it) }
                        // Sorted newest first: once a whole page started before the limit, the rest did too
                        if (selectedFilterType == FilterType.BY_YEAR && appliesYearLimit() &&
                            newSeries.all { it.year in 1 until MIN_START_YEAR }
                        ) {
                            reachedEnd = true
                        }
                        if (pageSeries.isNotEmpty() || reachedEnd) break
                        loadedPage++
                    }
                    lastPage = loadedPage
                    filteredSeries = pageSeries
                    canLoadMore = !reachedEnd
                }
                
                if (!append) {
                    series = filteredSeries
                    showingSaved = false
                    // Shown at once the next time the app opens
                    if (selectedFilterType.isRanked && filteredSeries.isNotEmpty()) {
                        val notice = rankingNotice
                        val attribution = rankingAttribution
                        launch { RankedListStore.saveSeries(listKey, filteredSeries, notice, attribution) }
                    }
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
    
    private fun appliesYearLimit(): Boolean = selectedGenreId !in ALL_YEARS_GENRE_IDS
    
    private fun isShown(seriesItem: Series): Boolean {
        // Filter out series with Farsi titles
        if (!LanguageUtils.shouldDisplayTitle(seriesItem.title)) return false
        // A hidden genre picked on purpose (e.g. "Turkish") still shows its series
        val hiddenGenreIds = HIDDEN_GENRE_IDS - selectedGenreId
        if (seriesItem.genres.any { it.id in hiddenGenreIds }) return false
        // Year 0 means unknown, keep those
        if (appliesYearLimit() && seriesItem.year in 1 until MIN_START_YEAR) return false
        return true
    }
    
    fun loadMoreSeries() {
        if (!isLoading && !isLoadingMore && canLoadMore) {
            loadSeries(currentPage + 1, append = true)
        }
    }
    
    // A failed first load (or one that was updating a saved list) starts over; a failed load-more
    // tries the same pages again
    fun retry() {
        if (series.isEmpty() || showingSaved) refresh() else loadSeries(currentPage + 1, append = true)
    }
    
    fun refresh() {
        loadSeries(0)
    }
}
