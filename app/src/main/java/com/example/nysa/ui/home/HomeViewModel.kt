package com.example.nysa.ui.home

import androidx.lifecycle.ViewModel
import com.example.nysa.data.toEntity
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

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading && _state.value.shelves.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "") }
            try {
                val shelves = app.cobe.homeShelves()
                // Persist what we show so library/detail render instantly later.
                app.database.titleDao().upsertAll(
                    shelves.flatMap { it.items }.map { item ->
                        item.toEntity()
                    }
                )
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
