package com.example.nysa.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.AuraApp
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.STATUS_ALL
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.model.matchesStatus
import com.example.nysa.data.source.FilterOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 *  - Anime: the pool is small and country-gated; years accumulate
 *    from loaded items, genres from those items' detail records
 *    (list records carry no secondary genres — see probeAnimeGenres).
 * Status filtering is local (the API has no reliable status field —
 * completion is derived from episode counts in the model).
 */
class SectionViewModel(
    private val app: AuraApp,
    val family: Family
) : ViewModel() {

    private val _state = MutableStateFlow(SectionUiState())
    val state: StateFlow<SectionUiState> = _state.asStateFlow()

    private var generation = 0

    /** One-shot flag for the Anime genre probe (see probeAnimeGenres). */
    private var genreProbeDone = false

    /** True when init repainted the default view from the disk cache. */
    private var cacheRestored = false

    init {
        viewModelScope.launch {
            // Seed taxonomy-driven options for the Phim tab.
            if (family == Family.PHIM) {
                val genres = runCatching { app.contentRepository.categories() }.getOrDefault(emptyList())
                    .filter { it.id != "24" } // Hoạt hình belongs to the Anime tab only
                val regions = runCatching { app.contentRepository.regions() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(genreOptions = genres, countryOptions = regions)
                }
            }
            // 1.0.4: restore the Anime tab's probed genre facets from
            // the persisted cache so the filter opens complete; the
            // one-shot probe below only tops the list up from live
            // data. Runs alongside reload(), never blocks it.
            if (family == Family.ANIME) {
                val cached = runCatching { app.settings.animeGenreCache.first() }
                    .getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    _state.update { cur ->
                        val seen = LinkedHashMap<String, String>()
                        cur.genreOptions.forEach { seen[it.id] = it.name }
                        cached.forEach { (id, name) ->
                            if (id != "24") seen.putIfAbsent(id, name)
                        }
                        cur.copy(
                            genreOptions = seen.map { (id, name) -> FilterOption(id, name) }
                        )
                    }
                }
            }
        }
        // 1.0.15 (resilience): repaint the default view from the disk
        // cache first, then refresh from the network on top of it.
        viewModelScope.launch {
            restoreSectionCache()
            reload(keepItems = cacheRestored)
        }
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

    fun reload(keepItems: Boolean = false) {
        val gen = ++generation
        _state.update {
            it.copy(
                items = if (keepItems) it.items else emptyList(),
                page = if (keepItems) it.page else 0,
                hasMore = if (keepItems) it.hasMore else true,
                loading = true,
                error = ""
            )
        }
        loadPage(gen, 1)
    }

    private fun isDefaultFilter(s: SectionUiState): Boolean =
        s.subType == "all" && s.genreId == 0 && s.countryId == 0 &&
            s.year == 0 && s.status == STATUS_ALL

    /**
     * 1.0.15: restore the DEFAULT view (no filters) from the structural
     * list cache + Room title rows. Reconstructed items lack the genre
     * facets of a live page (TitleEntity does not store them), so
     * filter-option accumulation stays with the network pass that
     * immediately follows; the status filter is ALL here by
     * definition, so nothing restored can be mis-shown.
     */
    private suspend fun restoreSectionCache() {
        if (!isDefaultFilter(_state.value)) return
        // 1.0.16: cache+Room restore lives behind ContentRepository;
        // it returns null exactly where the old inline code bailed.
        val cached = runCatching { app.contentRepository.cachedSectionDefault(family) }
            .getOrNull() ?: return
        cacheRestored = true
        _state.update {
            it.copy(items = cached.items, page = cached.page, hasMore = cached.hasMore)
        }
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
                // 1.0.4 (mục 7, Phim only): the local filters (anime
                // dropped + status matched locally) can leave a fetched
                // page nearly empty, so keep fetching forward — at most
                // 3 extra pages — until this call yields a fair amount.
                // Anime keeps one page per call. A failure on an EXTRA
                // page keeps what was already gathered and leaves
                // hasMore on so the user can scroll on; only a
                // first-page failure reaches the error path below.
                var nextPage = page
                var extraPages = 0
                var lastHasMore = true
                var extraFailed = false
                val fetchedItems = mutableListOf<TitleItem>()
                val visibleItems = mutableListOf<TitleItem>()
                while (true) {
                    val result = try {
                        when (family) {
                            Family.PHIM -> app.contentRepository.phimPage(
                                type = type,
                                genreId = s.genreId,
                                countryId = s.countryId,
                                year = s.year,
                                page = nextPage
                            )
                            Family.ANIME -> app.contentRepository.animePage(
                                type = type ?: "series",
                                genreId = s.genreId,
                                year = s.year,
                                page = nextPage
                            )
                        }
                    } catch (e: Exception) {
                        if (extraPages == 0 && fetchedItems.isEmpty()) throw e
                        extraFailed = true
                        null
                    }
                    if (result == null) break
                    if (gen != generation) return@launch
                    app.contentRepository.cacheTitles(result.items)
                    fetchedItems += result.items
                    visibleItems += result.items.filter { it.matchesStatus(s.status) }
                    lastHasMore = result.hasMore
                    val wantMore = family == Family.PHIM &&
                        visibleItems.size < 12 && result.hasMore && extraPages < 3
                    if (!wantMore) break
                    extraPages++
                    nextPage++
                }
                if (gen != generation) return@launch
                _state.update { cur ->
                    val merged = if (page == 1) visibleItems else cur.items + visibleItems
                    cur.copy(
                        items = merged.distinctBy { it.slug },
                        page = nextPage,
                        hasMore = if (extraFailed) true else lastHasMore,
                        loading = false,
                        loadingMore = false,
                        yearOptions = (cur.yearOptions + fetchedItems.map { it.year })
                            .filter { it > 0 }.distinct().sortedDescending(),
                        genreOptions = if (family == Family.ANIME) {
                            val seen = LinkedHashMap<String, String>()
                            cur.genreOptions.forEach { seen[it.id] = it.name }
                            fetchedItems.forEach { item ->
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
                // 1.0.15: persist the default view's structure so the
                // next cold start repaints instantly (see ListCache).
                if (page == 1 && gen == generation && isDefaultFilter(s)) {
                    runCatching {
                        app.contentRepository.saveSectionDefault(
                            family,
                            _state.value.items.map { it.id },
                            _state.value.page,
                            _state.value.hasMore
                        )
                    }
                }
                // 1.0.4 (mục 5): fire the genre probe once the pool is
                // deep enough to be representative (>= 30 accumulated
                // titles) or exhausted (no more pages).
                if (family == Family.ANIME && !genreProbeDone) {
                    val acc = _state.value
                    if (acc.items.size >= 30 || !acc.hasMore) {
                        genreProbeDone = true
                        probeAnimeGenres(acc.items.take(30))
                    }
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

    /**
     * Anime genre facets: COBE list items carry ONLY the pinned
     * "Hoạt hình" category — secondary genres exist solely on the
     * by-slug records (measured live against the API: Dragon Ball
     * Daima → Hài Hước / Trẻ Em / Viễn Tưởng / Phiêu Lưu). Once per
     * ViewModel, sample up to 30 accumulated titles through the LIGHT
     * genresOf call (categories only — 1.0.4; the 1.0.3 probe paid a
     * full detail() load, episodes included, for each of 10 titles),
     * five in parallel, and merge their real genre lists into the
     * filter options. The merged list is persisted
     * (SettingsStore.animeGenreCache) so later cold starts restore it
     * in init instead of probing again. Every chip offered is backed
     * by at least one real title in the pool — no invented facets.
     */
    private fun probeAnimeGenres(sample: List<TitleItem>) {
        if (sample.isEmpty()) return
        viewModelScope.launch {
            val gate = Semaphore(5)
            val found = sample.map { item ->
                async {
                    gate.withPermit {
                        runCatching { app.contentRepository.genresOf(item.slug) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
            val seen = LinkedHashMap<String, String>()
            _state.value.genreOptions.forEach { seen[it.id] = it.name }
            val before = seen.size
            found.forEach { opt ->
                if (opt.id != "24") seen.putIfAbsent(opt.id, opt.name)
            }
            if (seen.size > before) {
                val merged = seen.map { (id, name) -> FilterOption(id, name) }
                _state.update { it.copy(genreOptions = merged) }
                runCatching {
                    app.settings.setAnimeGenreCache(merged.map { it.id to it.name })
                }
            }
        }
    }
}
