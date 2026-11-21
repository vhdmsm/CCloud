package com.pira.ccloud.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pira.ccloud.data.model.Country
import com.pira.ccloud.data.model.Poster
import com.pira.ccloud.data.repository.CountryRepository
import com.pira.ccloud.data.repository.SearchRepository
import com.pira.ccloud.utils.LanguageUtils
import com.pira.ccloud.utils.SearchText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val repository = SearchRepository()
    private val countryRepository = CountryRepository()
    
    var searchResults by mutableStateOf<List<Poster>>(emptyList())
        private set
    
    var countries by mutableStateOf<List<Country>>(emptyList())
        private set
    
    var isLoading by mutableStateOf(false)
        private set
    
    var isCountriesLoading by mutableStateOf(false)
        private set
    
    var errorMessage by mutableStateOf<String?>(null)
        private set
    
    var searchQuery by mutableStateOf("")
        private set
    
    // New state to track if a search has been performed
    var hasSearched by mutableStateOf(false)
        private set
    
    private var searchJob: Job? = null
    
    init {
        loadCountries()
    }
    
    fun updateSearchQuery(query: String) {
        searchQuery = query
        // Don't auto-search anymore - only search when explicitly triggered
        // Reset search state when query changes
        if (!hasSearched) {
            searchResults = emptyList()
        }
    }
    
    // New function to explicitly trigger search
    fun triggerSearch() {
        if (searchQuery.isNotEmpty()) {
            hasSearched = true
            search(searchQuery)
        }
    }
    
    private fun search(query: String) {
        viewModelScope.launch {
            try {
                isLoading = true
                errorMessage = null
                
                val posters = searchMatchingLetters(query)
                
                // Filter out posters with Farsi titles
                val filteredPosters = posters.filter { poster ->
                    LanguageUtils.shouldDisplayTitle(poster.title)
                }
                
                searchResults = filteredPosters
            } catch (e: Exception) {
                errorMessage = e.message
                searchResults = emptyList()
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * The site's results for [query], matching Turkish letters by plain ones both ways: "Uzak
     * Şehir" also searches "Uzak Sehir", and when that finds nothing (the site keeps "Şehir"
     * where "Sehir" was typed) its longest words are searched alone and their titles kept when
     * they have every word of the query, ignoring accents.
     */
    private suspend fun searchMatchingLetters(query: String): List<Poster> {
        val found = LinkedHashMap<Int, Poster>()
        fun add(posters: List<Poster>) = posters.forEach { found.putIfAbsent(it.id, it) }

        add(repository.search(query).posters)
        val folded = SearchText.fold(query)
        if (folded != query) {
            add(runCatching { repository.search(folded).posters }.getOrDefault(emptyList()))
        }
        if (found.isEmpty()) {
            for (word in SearchText.fallbackWords(folded)) {
                val posters = runCatching { repository.search(word).posters }.getOrDefault(emptyList())
                add(posters.filter { SearchText.matches(it.title, query) })
            }
        }
        return found.values.toList()
    }
    
    private fun loadCountries() {
        viewModelScope.launch {
            try {
                isCountriesLoading = true
                countries = countryRepository.getAllCountries()
            } catch (e: Exception) {
                // Handle error silently for countries
                countries = emptyList()
            } finally {
                isCountriesLoading = false
            }
        }
    }
    
    fun clearSearch() {
        searchQuery = ""
        searchResults = emptyList()
        errorMessage = null
        hasSearched = false
    }
}