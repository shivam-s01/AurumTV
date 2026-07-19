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
    /** Non-null when the last search finished with zero results AND the
     *  call itself failed — same problem HomeViewModel had: without this,
     *  a dead Worker/timeout and "no matches for this query" both just
     *  render as an empty results list, indistinguishable from each
     *  other. */
    val loadError: String? = null,
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
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            val result = runCatching { AurumApi.search(query) }
            val results = result.getOrDefault(emptyList())
            val loadError = when {
                result.isFailure -> "Couldn't reach server: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                results.isEmpty() -> AurumApi.lastDiagnostic.takeIf { it.isNotBlank() && !it.startsWith("OK") }
                else -> null
            }
            _uiState.value = _uiState.value.copy(isLoading = false, results = results, loadError = loadError)
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
