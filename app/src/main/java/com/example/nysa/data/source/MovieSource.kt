package com.example.nysa.data.source

import com.example.nysa.data.model.Family
import com.example.nysa.data.model.TitleDetail
import com.example.nysa.data.model.TitleItem

/**
 * The content-source boundary (1.0.16, architecture hardening).
 *
 * Every screen talks to the source through this interface, never to
 * a concrete implementation: [CobeSource] is the one implementation
 * today (user order 2026-11-04 — COBE is the only source), and the
 * boundary is what keeps a source change, or a fix inside one
 * source, from leaking into unrelated features. Signatures mirror
 * CobeSource's public API exactly, defaults included — the defaults
 * live HERE; overrides must not repeat them.
 *
 * Implementation-only helpers (self-healing domain discovery, RSC
 * scraping, locator encoding in the companion) deliberately stay off
 * this interface: they are plumbing, not a contract screens may
 * depend on.
 */
interface MovieSource {

    suspend fun hot(): List<TitleItem>

    suspend fun homeShelves(): List<Shelf>

    suspend fun searchPage(
        keyword: String = "",
        type: String? = null,
        genreIds: List<Int> = emptyList(),
        countryIds: List<Int> = emptyList(),
        year: Int = 0,
        sort: String = "updatedAt",
        page: Int = 1,
        limit: Int = 24,
        familyHint: Family? = null
    ): CobeSource.Page

    suspend fun phimPage(
        type: String? = null,
        genreId: Int = 0,
        countryId: Int = 0,
        year: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): CobeSource.Page

    suspend fun animePage(
        type: String? = "series",
        genreId: Int = 0,
        year: Int = 0,
        page: Int = 1,
        limit: Int = 24
    ): CobeSource.Page

    suspend fun searchKeyword(
        keyword: String,
        page: Int = 1,
        limit: Int = 24
    ): CobeSource.Page

    suspend fun categories(): List<FilterOption>

    suspend fun regions(): List<FilterOption>

    suspend fun detailFor(item: TitleItem): TitleDetail

    suspend fun detail(slug: String): TitleDetail

    suspend fun genresOf(slug: String): List<FilterOption>

    suspend fun resolvePlay(locatorSlug: String): CobePlay

    /** Source health, measured: reachable flag + round-trip ms (-1 on failure). */
    suspend fun health(): Pair<Boolean, Long>

    fun currentSiteBase(): String

    fun currentApiBase(): String
}
