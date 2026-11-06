package com.example.nysa.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.AuraApp
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.toEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filter: String = "all",           // all | series | movie | anime
    val results: List<TitleItem> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false
)

class SearchViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var generation = 0

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    fun setFilter(f: String) {
        _state.update { it.copy(filter = f) }
        searchNow()
    }

    init {
        // Typing debounce lives in the VM (1.0.14): the UI no longer
        // wires a snapshotFlow in, so the collector exists exactly
        // once for the VM's whole lifetime.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _state.map { it.query }.debounce(350).distinctUntilChanged()
                .collect { searchNow() }
        }
    }

    fun searchNow() {
        val q = _state.value.query.trim()
        val gen = ++generation
        if (q.isBlank()) {
            _state.update { it.copy(results = emptyList(), loading = false, searched = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val page = runCatching { app.cobe.searchKeyword(q, page = 1, limit = 36) }.getOrNull()
            if (gen != generation) return@launch
            val all = page?.items ?: emptyList()
            app.database.titleDao().upsertAll(all.map { it.toEntity() })
            // "Full" is the movie marker the source mapping sets;
            // series carry "Tập N". Family always wins over the marker.
            val filtered = when (_state.value.filter) {
                "series" -> all.filter {
                    it.family == com.example.nysa.data.model.Family.PHIM &&
                        it.latestLabel != "Full"
                }
                "movie" -> all.filter {
                    it.family == com.example.nysa.data.model.Family.PHIM &&
                        it.latestLabel == "Full"
                }
                "anime" -> all.filter { it.family == com.example.nysa.data.model.Family.ANIME }
                else -> all
            }
            _state.update {
                it.copy(results = filtered, loading = false, searched = true)
            }
            app.settings.addSearchQuery(q)
        }
    }
}
