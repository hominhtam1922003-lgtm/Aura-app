package com.example.nysa.ui.home

import androidx.lifecycle.ViewModel
import com.example.nysa.data.toEntity
import com.example.nysa.data.toItem
import androidx.lifecycle.viewModelScope
import com.example.nysa.NySaApp
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

class HomeViewModel(private val app: NySaApp) : ViewModel() {

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
                val cached = app.listCache.homeShelves() ?: return@runCatching
                if (_state.value.shelves.isNotEmpty()) return@runCatching
                val rows = app.database.titleDao()
                    .getByIds(cached.flatMap { it.second })
                    .associateBy { it.id }
                val shelves = cached.mapNotNull { (name, ids) ->
                    val items = ids.mapNotNull { rows[it]?.toItem() }
                    if (items.isEmpty()) null else Shelf(name, items)
                }
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
                val shelves = app.cobe.homeShelves()
                // Persist what we show so library/detail render instantly later.
                app.database.titleDao().upsertAll(
                    shelves.flatMap { it.items }.map { item ->
                        item.toEntity()
                    }
                )
                // 1.0.15: persist the shelf structure too, so the next
                // cold start can repaint before the network answers.
                runCatching {
                    app.listCache.saveHomeShelves(
                        shelves.map { shelf ->
                            shelf.name to shelf.items.map { it.id }
                        }
                    )
                }
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
