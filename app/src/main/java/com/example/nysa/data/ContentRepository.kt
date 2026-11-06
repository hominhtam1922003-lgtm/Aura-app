package com.example.nysa.data

import com.example.nysa.data.local.TitleDao
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.source.CobeSource
import com.example.nysa.data.source.FilterOption
import com.example.nysa.data.source.MovieSource
import com.example.nysa.data.source.Shelf

/** A section's default view restored from the disk caches (1.0.15 structure). */
data class CachedSection(
    val items: List<TitleItem>,
    val page: Int,
    val hasMore: Boolean
)

/**
 * The list-flow boundary for the Home and Section tabs (1.0.16,
 * architecture hardening): wraps the [MovieSource], the structural
 * [ListCache] and the Room title rows behind one narrow API, so the
 * two list ViewModels never touch the source, the cache or the DAO
 * directly. A fix inside any one of the three wrapped pieces — or a
 * source swap behind [MovieSource] — cannot leak into the other
 * tabs, and the ViewModels keep only presentation state.
 *
 * Behaviour is a verbatim lift of the 1.0.15 ViewModel code paths:
 * same calls, same order, same failure tolerance (cache reads
 * degrade to null, title-row reads degrade to an empty map).
 */
class ContentRepository(
    private val source: MovieSource,
    private val listCache: ListCache,
    private val titleDao: TitleDao
) {

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    /**
     * Last home shelves repainted from disk — structure from
     * [ListCache], title rows from Room — or null when nothing usable
     * is cached.
     */
    suspend fun cachedHomeShelves(): List<Shelf>? {
        val cached = listCache.homeShelves() ?: return null
        val rows = titleDao.getByIds(cached.flatMap { it.second })
            .associateBy { it.id }
        val shelves = cached.mapNotNull { (name, ids) ->
            val items = ids.mapNotNull { rows[it]?.toItem() }
            if (items.isEmpty()) null else Shelf(name, items)
        }
        return shelves.ifEmpty { null }
    }

    /**
     * Live home shelves from the source; the shown titles are upserted
     * into Room and the shelf structure is persisted for the next cold
     * start before returning.
     */
    suspend fun homeShelves(): List<Shelf> {
        val shelves = source.homeShelves()
        // Persist what we show so library/detail render instantly later.
        titleDao.upsertAll(shelves.flatMap { it.items }.map { it.toEntity() })
        // 1.0.15: persist the shelf structure too, so the next
        // cold start can repaint before the network answers.
        runCatching {
            listCache.saveHomeShelves(
                shelves.map { shelf -> shelf.name to shelf.items.map { it.id } }
            )
        }
        return shelves
    }

    // ------------------------------------------------------------------
    // Section (Phim / Anime tabs)
    // ------------------------------------------------------------------

    suspend fun categories(): List<FilterOption> = source.categories()

    suspend fun regions(): List<FilterOption> = source.regions()

    suspend fun genresOf(slug: String): List<FilterOption> = source.genresOf(slug)

    suspend fun phimPage(
        type: String?,
        genreId: Int,
        countryId: Int,
        year: Int,
        page: Int
    ): CobeSource.Page = source.phimPage(
        type = type, genreId = genreId, countryId = countryId, year = year, page = page
    )

    suspend fun animePage(
        type: String?,
        genreId: Int,
        year: Int,
        page: Int
    ): CobeSource.Page = source.animePage(
        type = type, genreId = genreId, year = year, page = page
    )

    /** Upsert freshly fetched titles so library/detail render instantly later. */
    suspend fun cacheTitles(items: List<TitleItem>) {
        titleDao.upsertAll(items.map { it.toEntity() })
    }

    /**
     * The section's DEFAULT view (no filters) restored from the
     * structural list cache + Room title rows, or null when nothing
     * usable is cached. Reconstructed items lack genre facets (the
     * entity does not store them) — filter-option accumulation stays
     * with the network pass, exactly as in 1.0.15.
     */
    suspend fun cachedSectionDefault(family: Family): CachedSection? {
        val cached = listCache.sectionDefault(family) ?: return null
        val rows = runCatching {
            titleDao.getByIds(cached.ids).associateBy { it.id }
        }.getOrDefault(emptyMap())
        val items = cached.ids.mapNotNull { rows[it]?.toItem() }
            .distinctBy { it.slug }
        if (items.isEmpty()) return null
        return CachedSection(items = items, page = cached.page, hasMore = cached.hasMore)
    }

    suspend fun saveSectionDefault(
        family: Family,
        ids: List<String>,
        page: Int,
        hasMore: Boolean
    ) {
        listCache.saveSectionDefault(family, ids, page, hasMore)
    }
}
