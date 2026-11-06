package com.example.nysa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.AuraApp
import com.example.nysa.data.source.Shelf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val error: String = ""
)

class HomeViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        // 1.0.15 (resilience): repaint the last home shelves from disk
        // first — structure from ListCache, title rows from Room — then
        // let the network refresh replace them. Cold starts and source
        // outages no longer open on an empty screen.
        viewModelScope.launch {
            runCatching {
                // 1.0.16: the cache+Room restore lives behind the
                // ContentRepository boundary now; behaviour unchanged.
                val shelves = app.contentRepository.cachedHomeShelves()
                    ?: return@runCatching
                if (_state.value.shelves.isNotEmpty()) return@runCatching
                if (shelves.isNotEmpty()) {
                    _state.update { it.copy(loading = false, shelves = shelves) }
                }
            }
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.shelves.isEmpty(), error = "") }
            try {
                // 1.0.16: fetch + Room upsert + structure persist all
                // happen inside ContentRepository.homeShelves().
                val shelves = app.contentRepository.homeShelves()
                _state.update { it.copy(loading = false, shelves = shelves) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = if (it.shelves.isEmpty()) {
                            "Không tải được trang chủ — nguồn chưa phản hồi. Kéo xuống để thử lại."
                        } else ""
                    )
                }
            }
        }
    }
}
