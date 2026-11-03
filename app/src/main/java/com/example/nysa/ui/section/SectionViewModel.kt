package com.example.nysa.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.NySaApp
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.STATUS_ALL
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.model.matchesStatus
import com.example.nysa.data.source.FilterOption
import com.example.nysa.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SectionUiState(
    val subType: String = "all",          // all | series | movie
    val genreId: Int = 0,
    val countryId: Int = 0,               // Phim tab only
    val year: Int = 0,
    val status: Int = STATUS_ALL,
    val items: List<TitleItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String = "",
    /** Filter values accumulated from REAL loaded data (years; anime genres). */
    val yearOptions: List<Int> = emptyList(),
    val genreOptions: List<FilterOption> = emptyList(),
    val countryOptions: List<FilterOption> = emptyList()
)

/**
 * One ViewModel per section tab (Phim / Anime). All state — items,
 * page, filters, scroll-fed options — lives here, not in the
 * composition, so switching tabs never reloads (verdict 1.3.14).
 *
 * Filter law (user): each tab's filters contain only values that
 * exist in that tab's own catalog —
 *  - Phim: genres/regions come from the source's taxonomy tables
 *    (the phim pool spans them), years accumulate from loaded items.
 *  - Anime: the pool is small and country-gated, so genres AND years
 *    accumulate strictly from anime items actually loaded.
 * Status filtering is local (the API has no reliable status field —
 * completion is derived from episode counts in the model).
 */
class SectionViewModel(
    private val app: NySaApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(SectionUiState())
    val state: StateFlow<SectionUiState> = _state.asStateFlow()

    private var generation = 0

    init {
        viewModelScope.launch {
            // Seed taxonomy-driven options for the Phim tab.
            if (family == Family.PHIM) {
                val genres = runCatching { app.cobe.categories() }.getOrDefault(emptyList())
                    .filter { it.id != "24" } // Hoạt hình belongs to the Anime tab only
                val regions = runCatching { app.cobe.regions() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(genreOptions = genres, countryOptions = regions)
                }
            }
        }
        reload()
    }

    fun setSubType(subType: String) {
        if (_state.value.subType == subType) return
        _state.update { it.copy(subType = subType) }
        reload()
    }

    fun applyFilters(genreId: Int, countryId: Int, year: Int, status: Int) {
        _state.update {
            it.copy(genreId = genreId, countryId = countryId, year = year, status = status)
        }
        reload()
    }

    fun reload() {
        val gen = ++generation
        _state.update {
            it.copy(items = emptyList(), page = 0, hasMore = true, loading = true, error = "")
        }
        loadPage(gen, 1)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        loadPage(generation, s.page + 1)
    }

    private fun loadPage(gen: Int, page: Int) {
        viewModelScope.launch {
            val s = _state.value
            try {
                val type = when (s.subType) {
                    "series" -> "series"
                    "movie" -> "movie"
                    else -> null
                }
                val result = when (family) {
                    Family.PHIM -> app.cobe.phimPage(
                        type = type,
                        genreId = s.genreId,
                        countryId = s.countryId,
                        year = s.year,
                        page = page
                    )
                    Family.ANIME -> app.cobe.animePage(
                        type = type ?: "series",
                        genreId = s.genreId,
                        year = s.year,
                        page = page
                    )
                }
                if (gen != generation) return@launch
                val visible = result.items.filter { it.matchesStatus(s.status) }
                app.database.titleDao().upsertAll(result.items.map { it.toEntity() })
                _state.update { cur ->
                    val merged = if (page == 1) visible else cur.items + visible
                    cur.copy(
                        items = merged.distinctBy { it.slug },
                        page = page,
                        hasMore = result.hasMore,
                        loading = false,
                        loadingMore = false,
                        yearOptions = (cur.yearOptions + result.items.map { it.year })
                            .filter { it > 0 }.distinct().sortedDescending(),
                        genreOptions = if (family == Family.ANIME) {
                            val seen = LinkedHashMap<String, String>()
                            cur.genreOptions.forEach { seen[it.id] = it.name }
                            result.items.forEach { item ->
                                item.genreIds.zip(item.genres).forEach { (id, name) ->
                                    if (id != 24) seen.putIfAbsent(id.toString(), name)
                                }
                            }
                            seen.map { (id, name) -> FilterOption(id, name) }
                        } else {
                            cur.genreOptions
                        }
                    )
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        hasMore = if (page == 1) true else it.hasMore,
                        error = if (page == 1 && it.items.isEmpty()) {
                            "Không tải được mục này — nguồn chưa phản hồi. Bấm để thử lại."
                        } else ""
                    )
                }
            }
        }
    }
}
