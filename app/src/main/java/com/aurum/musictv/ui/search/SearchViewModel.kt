package com.aurum.musictv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.remote.AurumApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val isLoading: Boolean = false,
    val results: List<Song> = emptyList(),
)

class SearchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var lastQuery: String? = null

    /** Cancels any in-flight search before starting a new one — without
     *  this, fast typing could let an older, slower network response land
     *  AFTER a newer one and stomp the results shown on screen with stale
     *  data (a real risk for "live" search where every keystroke can fire
     *  a call). Only ever one search in flight at a time. */
    private var searchJob: Job? = null

    fun search(query: String) {
        if (query == lastQuery) return
        lastQuery = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val results = runCatching { AurumApi.search(query) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(isLoading = false, results = results)
        }
    }

    /** Called when the search box is cleared — drops any in-flight
     *  search and the stale results with it, so going back to an empty
     *  query doesn't briefly flash the last query's results. */
    fun clear() {
        searchJob?.cancel()
        lastQuery = null
        _uiState.value = SearchUiState()
    }
}
