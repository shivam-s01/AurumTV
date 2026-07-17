package com.aurum.musictv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.remote.AurumApi
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

    fun search(query: String) {
        if (query == lastQuery) return
        lastQuery = query
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val results = runCatching { AurumApi.search(query) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(isLoading = false, results = results)
        }
    }
}
