package com.example.nysa.data.source

import com.example.nysa.data.SettingsStore
import com.example.nysa.data.model.ActorRef
import com.example.nysa.data.model.EpisodeItem
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.ServerGroup
import com.example.nysa.data.model.TitleDetail
import com.example.nysa.data.model.TitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One episode resolved to something the player can actually open. */
sealed class CobePlay {
    data class Hls(val url: String, val fallbacks: List<String> = emptyList()) : CobePlay()
    data class Embed(val url: String, val fallbacks: List<String> = emptyList()) : CobePlay()
}

data class Shelf(val name: String, val items: List<TitleItem>)

data class FilterOption(val id: String, val name: String)

/**
 * The ONE source of NySa: the COBE site (user order 2026-11-04).
 * Its web domain rotates (cobephim.fun 301'd to cobephim.mom,
 * measured 2026-11-05) — see SITE_SEEDS / siteBase below.
 *
 * Measured live 2026-11-04 (see ~/workspace/nysa/research/COBE-API.md):
 * the web is a Next.js shell; the data lives on a public JSON API at
 * [DEFAULT_API_BASE]. Lists/details/episodes come from the API; the
 * play link of an episode is NOT in the API (its per-episode endpoint
 * demands a token) — it is scraped from the RSC payload of the watch
 * page, exactly like the site's own player receives it.
 *
 * Phim vs Anime separation (user law):
 *  - Phim tab = type series/movie WITHOUT genre 24 (Hoạt hình).
 *  - Anime tab = genre 24 behind a default-deny country gate applied
 *    at query level: series → Nhật Bản only; movies → Asian countries
 *    only (Nhật/Hàn/Trung/Đài). The by-slug record carries no country
 *    field, so the gate must live in the query, not in local data.
 */
class CobeSource(private val settings: SettingsStore? = null) : MovieSource {

    companion object {
        const val SOURCE_ID = "cobe"

        /**
         * Seed watch-page origins, newest first. The site's web
         * domain ROTATES (.fun 301'd to .mom on 2026-11-05, and on
         * the user's network .fun is refused outright) while the API
         * host lives elsewhere — so the live origin is learned at
         * runtime (see siteBase) and the seeds are only starting
         * points / fallback candidates for resolvePlay.
         */
        val SITE_SEEDS = listOf("https://cobephim.mom", "https://cobephim.fun")

        /** Built-in default watch origin = the newest known seed. */
        const val SITE = "https://cobephim.mom"
        const val DEFAULT_API_BASE =
            "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1"
        const val GENRE_ANIME_ID = 24
        const val COUNTRY_JAPAN = 8
        /** Asian animated-movie gate: Nhật, Hàn, Trung, Đài Loan. */
        val ANIME_MOVIE_COUNTRIES = listOf(8, 3, 1, 28)
        const val LOCATOR_PREFIX = "cobe:"

        fun isLocator(slug: String): Boolean = slug.startsWith(LOCATOR_PREFIX)

        fun locator(movieSlug: String, episodeId: Long): String =
            "$LOCATOR_PREFIX$movieSlug:$episodeId"

        fun parseLocator(locator: String): Pair<String, Long>? {
            if (!locator.startsWith(LOCATOR_PREFIX)) return null
            val rest = locator.removePrefix(LOCATOR_PREFIX)
            val idx = rest.lastIndexOf(':')
            if (idx <= 0) return null
            val epId = rest.substring(idx + 1).toLongOrNull() ?: return null
            return rest.substring(0, idx) to epId
        }

        fun watchPageUrl(base: String, movieSlug: String, episodeId: Long): String =
            "$base/phim/$movieSlug/tap-$episodeId"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var apiBase: String = DEFAULT_API_BASE

    @Volatile
    private var baseLoaded = false

    /** Live watch-page origin; learned from redirects, seeded by [SITE]. */
    @Volatile
    private var siteBase: String = SITE

    @Volatile
    private var siteLoaded = false

    /** One page of a listing; [hasMore] follows the measured rule: a full page means another exists. */
    data class Page(val items: List<TitleItem>, val hasMore: Boolean)

    // ------------------------------------------------------------------
    // HTTP plumbing (all blocking work on IO — performance gate)
    // ------------------------------------------------------------------

    private suspend fun httpGet(url: String): String = httpGetFinal(url).first

    /**
     * GET returning the body AND the final URL after redirects.
     * The watch-page origin is learned from where a request
     * actually LANDS: a 301 from an old domain teaches the new
     * one (that is how the .fun → .mom rotation self-heals on
     * networks where the old domain still answers).
     */
    private suspend fun httpGetFinal(url: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) NySa/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} từ nguồn")
                }
                val body = resp.body?.string()
                    ?: throw IllegalStateException("Nguồn trả rỗng")
                body to resp.request.url.toString()
            }
        }

    /** Load the persisted API host once; keeps [apiBase] current across host rotations. */
    private suspend fun ensureBase() {
        if (baseLoaded) return
        baseLoaded = true
        val saved = try {
            settings?.cobeApiHost?.first()
        } catch (e: Exception) {
            null
        }
        if (!saved.isNullOrBlank()) {
            apiBase = saved.trimEnd('/')
        }
    }

    /** Load the persisted watch-page origin once (mirror of [ensureBase]). */
    private suspend fun ensureSite() {
        if (siteLoaded) return
        siteLoaded = true
        val saved = try {
            settings?.cobeSiteHost?.first()
        } catch (e: Exception) {
            null
        }
        if (!saved.isNullOrBlank()) {
            siteBase = saved.trimEnd('/')
        }
    }

    override fun currentSiteBase(): String = siteBase

    /**
     * GET against the API with ONE self-heal attempt: if the call fails
     * outright (host rotated => DNS/404 storm), rediscover the host from
     * the site's JS bundles, persist it, and retry once. Returns the raw
     * body — endpoints that answer with a bare array (episodes) or any
     * non-object payload go through here too (1.0.4; loadServers used to
     * call httpGet directly and missed the self-heal entirely).
     */
    private suspend fun apiGetRaw(path: String): String {
        ensureBase()
        try {
            return httpGet(apiBase + path)
        } catch (e: Exception) {
            val fresh = rediscoverApiBase()
            if (fresh != null && fresh != apiBase) {
                apiBase = fresh
                try {
                    settings?.setCobeApiHost(fresh)
                } catch (ignored: Exception) {
                }
                return httpGet(apiBase + path)
            }
            throw e
        }
    }

    private suspend fun apiGet(path: String): JSONObject = JSONObject(apiGetRaw(path))

    private val hostRegex = Regex("""https://[a-z0-9]+\.darkbytes\.xyz""")

    /** Best-effort host rediscovery from the site's own JS (measured: chunks carry the API host). */
    private suspend fun rediscoverApiBase(): String? = try {
        ensureSite()
        val home = httpGet("$siteBase/")
        val chunks = Regex("src=\"(/_next/static/chunks/[^\"]+\\.js)\"")
            .findAll(home).map { it.groupValues[1] }.take(14).toList()
        var found: String? = null
        for (chunk in chunks) {
            if (found != null) break
            val js = runCatching { httpGet(siteBase + chunk) }.getOrNull() ?: continue
            found = hostRegex.find(js)?.value
        }
        found?.let { "$it/api/v1" }
    } catch (e: Exception) {
        null
    }

    /** Source health, measured — used by the Tôi tab's "Sức khoẻ nguồn" card. */
    override suspend fun health(): Pair<Boolean, Long> {
        ensureBase()
        val start = System.currentTimeMillis()
        return try {
            httpGet("$apiBase/movies/hot")
            true to (System.currentTimeMillis() - start)
        } catch (e: Exception) {
            false to -1L
        }
    }

    override fun currentApiBase(): String = apiBase

    // ------------------------------------------------------------------
    // JSON → model mapping
    // ------------------------------------------------------------------

    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val isoUtcShort = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseEpochSec(raw: String): Long {
        if (raw.isBlank()) return 0
        for (fmt in listOf(isoUtc, isoUtcShort)) {
            try {
                return (fmt.parse(raw)?.time ?: 0) / 1000
            } catch (e: Exception) {
                // try the next format
            }
        }
        return 0
    }

    private fun releasedAndTotal(episodeCurrent: String, episodeTotal: String): Pair<Int, Int> {
        val curNums = Regex("""\d+""").findAll(episodeCurrent).map { it.value.toInt() }.toList()
        val total = episodeTotal.toIntOrNull()
            ?: curNums.getOrNull(1)
            ?: 0
        val released = curNums.getOrNull(0) ?: 0
        return released to total
    }

    private fun movieToItem(m: JSONObject, familyHint: Family? = null): TitleItem {
        val cats = m.optJSONArray("categories") ?: JSONArray()
        val genreNames = mutableListOf<String>()
        val genreSlugs = mutableListOf<String>()
        val genreIds = mutableListOf<Int>()
        var isAnime = false
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            genreNames += c.optString("name")
            genreSlugs += c.optString("slug")
            genreIds += c.optInt("id")
            if (c.optInt("id") == GENRE_ANIME_ID) isAnime = true
        }
        val family = familyHint ?: if (isAnime) Family.ANIME else Family.PHIM
        val type = m.optString("type")
        val (released, total) = releasedAndTotal(
            m.optString("episode_current"), m.optString("episode_total")
        )
        val language = m.optString("language")
        val completed = m.optString("status") == "completed" ||
            (total > 0 && released >= total)
        val latestLabel = when {
            type == "movie" -> "Full"
            released > 0 -> "Tập $released"
            else -> ""
        }
        return TitleItem(
            id = "${family.name.lowercase()}:$SOURCE_ID:${m.optString("slug")}",
            family = family,
            sourceId = SOURCE_ID,
            slug = m.optString("slug"),
            name = m.optString("name"),
            originName = m.optString("origin_name"),
            posterUrl = m.optString("poster"),
            thumbUrl = m.optString("thumbnail"),
            quality = m.optString("quality"),
            versionBadge = when {
                language.contains("Lồng tiếng", true) -> "LT"
                language.contains("Thuyết", true) -> "TM"
                language.contains("Vietsub", true) -> "VS"
                else -> ""
            },
            languages = language,
            viewCount = m.optLong("view_day"),
            latestLabel = latestLabel,
            episodeCount = released,
            year = m.optInt("publish_year"),
            genres = genreNames,
            genreSlugs = genreSlugs,
            genreIds = genreIds,
            completed = completed,
            updatedEpochSec = parseEpochSec(m.optString("updatedAt")),
            isHot = false
        )
    }

    private fun resultItems(json: JSONObject, familyHint: Family? = null): List<TitleItem> {
        val arr = json.optJSONArray("result") ?: return emptyList()
        val out = mutableListOf<TitleItem>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("slug").isBlank()) continue
            out += movieToItem(m, familyHint)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Listings
    // ------------------------------------------------------------------

    override suspend fun hot(): List<TitleItem> = resultItems(apiGet("/movies/hot"))

    override suspend fun homeShelves(): List<Shelf> {
        val json = apiGet("/lists/homepageLists?page=1&limit=15")
        val collections = json.optJSONObject("result")?.optJSONArray("collections")
            ?: return emptyList()
        val shelves = mutableListOf<Shelf>()
        for (i in 0 until collections.length()) {
            val c = collections.optJSONObject(i) ?: continue
            val movies = c.optJSONArray("movies") ?: continue
            val items = mutableListOf<TitleItem>()
            for (j in 0 until movies.length()) {
                val m = movies.optJSONObject(j) ?: continue
                if (m.optString("slug").isBlank()) continue
                items += movieToItem(m)
            }
            if (items.isNotEmpty()) {
                shelves += Shelf(c.optString("name"), items)
            }
        }
        return shelves
    }

    /**
     * The one listing endpoint. [type] null = all types (tvshows are
     * dropped locally for the Phim tab; the Anime tab always passes
     * genres=24 + the country gate).
     */
    override suspend fun searchPage(
        keyword: String,
        type: String?,
        genreIds: List<Int>,
        countryIds: List<Int>,
        year: Int,
        sort: String,
        page: Int,
        limit: Int,
        familyHint: Family?
    ): Page {
        val q = StringBuilder("/movies/search?page=$page&limit=$limit&sort=$sort")
        if (keyword.isNotBlank()) {
            q.append("&keyword=").append(java.net.URLEncoder.encode(keyword, "UTF-8"))
        }
        if (type != null) q.append("&type=").append(type)
        if (genreIds.isNotEmpty()) q.append("&genres=").append(genreIds.joinToString(","))
        if (countryIds.isNotEmpty()) q.append("&countries=").append(countryIds.joinToString(","))
        if (year > 0) q.append("&years=").append(year)
        val items = resultItems(apiGet(q.toString()), familyHint)
        return Page(items, hasMore = items.size >= limit)
    }

    /**
     * One page of the Phim tab. type=null merges series+movie server-side;
     * animation (genre 24) and tvshows are excluded locally — the tab must
     * never leak anime into Phim (user law: phim ra phim, anime ra anime).
     */
    override suspend fun phimPage(
        type: String?,
        genreId: Int,
        countryId: Int,
        year: Int,
        page: Int,
        limit: Int
    ): Page {
        val raw = searchPage(
            type = type,
            genreIds = listOfNotNull(genreId.takeIf { it > 0 }),
            countryIds = listOfNotNull(countryId.takeIf { it > 0 }),
            year = year,
            page = page,
            limit = limit,
            familyHint = Family.PHIM
        )
        val filtered = raw.items.filter {
            it.family == Family.PHIM && !it.genreSlugs.contains("hoat-hinh")
        }
        // hasMore follows the RAW page (the measured full-page rule), not
        // the locally filtered count.
        return Page(filtered, raw.hasMore)
    }

    /**
     * One page of the Anime tab, country gate applied IN the query:
     * series → Nhật only; movie → Asian countries only; "all" runs the
     * series query (the movie gate is applied when the user picks Phim lẻ).
     */
    override suspend fun animePage(
        type: String?,
        genreId: Int,
        year: Int,
        page: Int,
        limit: Int
    ): Page {
        val genres = mutableListOf(GENRE_ANIME_ID)
        if (genreId > 0 && genreId != GENRE_ANIME_ID) genres += genreId
        val countries = when (type) {
            "movie" -> ANIME_MOVIE_COUNTRIES
            else -> listOf(COUNTRY_JAPAN)
        }
        return searchPage(
            type = type,
            genreIds = genres,
            countryIds = countries,
            year = year,
            page = page,
            limit = limit,
            familyHint = Family.ANIME
        )
    }

    override suspend fun searchKeyword(keyword: String, page: Int, limit: Int): Page =
        searchPage(keyword = keyword, page = page, limit = limit)

    override suspend fun categories(): List<FilterOption> {
        val json = apiGet("/categories?limit=100")
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<FilterOption>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out += FilterOption(c.optInt("id").toString(), c.optString("name"))
        }
        return out
    }

    override suspend fun regions(): List<FilterOption> {
        // Paginated (1.0.6 fix): the table holds 140 regions but the
        // API caps limit at 100/page (limit=200 returns empty), so the
        // old single page silently dropped the last 40 — follow
        // meta.totalPages (measured 2 on 2026-11-05) and merge the
        // pages in order.
        val out = mutableListOf<FilterOption>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages) {
            val json = apiGet("/regions?limit=100&page=$page")
            val arr = json.optJSONArray("data") ?: break
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                out += FilterOption(r.optInt("id").toString(), r.optString("name"))
            }
            totalPages = json.optJSONObject("meta")?.optInt("totalPages", 1) ?: 1
            if (arr.length() == 0) break
            page++
        }
        return out
    }

    // ------------------------------------------------------------------
    // Detail + episodes
    // ------------------------------------------------------------------

    /** The worker / quick-play routing entry: detail by the item itself. */
    override suspend fun detailFor(item: TitleItem): TitleDetail = detail(item.slug)

    override suspend fun detail(slug: String): TitleDetail {
        val json = apiGet("/movies/by-slug/$slug")
        val m = json.optJSONObject("movie")
            ?: throw IllegalStateException("Nguồn không trả chi tiết cho phim này")
        val item = movieToItem(m)
        val movieId = m.optLong("id")
        val servers = loadServers(slug, movieId)
        val totalCount = servers.maxOfOrNull { it.episodes.size } ?: 0
        val genres = (0 until (m.optJSONArray("categories")?.length() ?: 0)).mapNotNull { i ->
            val c = m.optJSONArray("categories")?.optJSONObject(i) ?: return@mapNotNull null
            c.optString("name") to c.optInt("id").toString()
        }
        val actors = mutableListOf<ActorRef>()
        val actorArr = m.optJSONArray("actors") ?: JSONArray()
        for (i in 0 until actorArr.length()) {
            val a = actorArr.optJSONObject(i)
            if (a != null) {
                actors += ActorRef(
                    name = a.optString("name"),
                    slug = a.optString("slug"),
                    thumb = a.optString("thumbnail")
                )
            } else {
                actorArr.optString(i).takeIf { it.isNotBlank() }?.let { actors += ActorRef(it) }
            }
        }
        val directors = mutableListOf<String>()
        val dirArr = m.optJSONArray("directors") ?: JSONArray()
        for (i in 0 until dirArr.length()) {
            val d = dirArr.optJSONObject(i)
            if (d != null) directors += d.optString("name")
            else dirArr.optString(i).takeIf { it.isNotBlank() }?.let { directors += it }
        }
        val description = m.optString("description")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return TitleDetail(
            item = item.copy(episodeCount = maxOf(item.episodeCount, totalCount)),
            description = description,
            status = when {
                item.completed -> "Hoàn thành"
                m.optString("status").isNotBlank() -> "Đang tiến hành"
                else -> ""
            },
            totalCount = totalCount,
            durationLabel = m.optString("episode_time"),
            actors = actors.map { it.name }.filter { it.isNotBlank() },
            actorRefs = actors,
            directors = directors.filter { it.isNotBlank() },
            servers = servers,
            trailerUrl = m.optString("trailer_url"),
            genreLinks = genres
        )
    }

    /**
     * ONLY the genre list of one title (1.0.4): reads the by-slug
     * record's categories WITHOUT loading episodes/servers — the cheap
     * call the Anime genre probe fans out over its sample (the old
     * probe paid a full detail() load per title).
     */
    override suspend fun genresOf(slug: String): List<FilterOption> {
        val json = apiGet("/movies/by-slug/$slug")
        val m = json.optJSONObject("movie") ?: return emptyList()
        val cats = m.optJSONArray("categories") ?: return emptyList()
        val out = mutableListOf<FilterOption>()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val name = c.optString("name")
            if (name.isBlank()) continue
            out += FilterOption(c.optInt("id").toString(), name)
        }
        return out
    }

    /**
     * Episodes come as ONE flat array (episode × server). Group by the
     * source's server label; normalize only the langKey (for the
     * version-priority order) — the displayed name stays verbatim.
     * Ranking: "#1" variants first (measured: the un-numbered
     * "Thuyết Minh" is the stale feed), then by episode count.
     */
    private suspend fun loadServers(movieSlug: String, movieId: Long): List<ServerGroup> {
        ensureSite()
        val body = apiGetRaw("/episodes/by-idMovie/$movieId")
        val arr = JSONArray(body)
        val byServer = linkedMapOf<String, MutableList<EpisodeItem>>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val server = e.optString("server").ifBlank { "Mặc định" }
            val name = e.optString("name")
            val number = name.toFloatOrNull()
                ?: Regex("""\d+(\.\d+)?""").find(name)?.value?.toFloatOrNull()
                ?: 0f
            val epId = e.optLong("id")
            if (epId == 0L) continue
            val ep = EpisodeItem(
                label = if (number > 0f && number % 1f == 0f) "Tập ${number.toInt()}"
                else "Tập $name",
                number = number,
                slug = locator(movieSlug, epId),
                embedUrl = watchPageUrl(siteBase, movieSlug, epId)
            )
            byServer.getOrPut(server) { mutableListOf() } += ep
        }
        val groups = byServer.map { (server, eps) ->
            ServerGroup(
                name = server,
                langKey = langKeyOf(server),
                episodes = eps.sortedBy { it.number }
            )
        }
        return groups.sortedWith(
            compareByDescending<ServerGroup> { if (it.name.contains("#1")) 1 else 0 }
                .thenByDescending { it.episodes.size }
        )
    }

    private fun langKeyOf(serverLabel: String): String {
        val s = serverLabel.lowercase()
        return when {
            s.contains("lồng tiếng") -> "lt"
            s.contains("thuyết minh") -> "tm"
            s.contains("vietsub") || s.contains("phụ đề") || s.contains("song ngữ") -> "vs"
            else -> ""
        }
    }

    // ------------------------------------------------------------------
    // Play-link resolution (RSC scrape of the watch page)
    // ------------------------------------------------------------------

    private val episodeJsonRegex =
        Regex("""\\?"episode\\?"\s*:\s*\{[^{}]*\\?"type\\?"\s*:\s*\\?"(m3u8|embed)\\?"[^{}]*\\?"link\\?"\s*:\s*\\?"([^"\\]+)\\?"\s*""")
    private val episodeJsonRegexAlt =
        Regex("""\\?"link\\?"\s*:\s*\\?"([^"\\]+)\\?"[^{}]*\\?"type\\?"\s*:\s*\\?"(m3u8|embed)\\?"\s*""")

    /**
     * Resolve one episode locator to a playable target:
     *  - type=m3u8            -> direct HLS for ExoPlayer.
     *  - embed streamvsmov    -> derived master.m3u8 (measured live,
     *    1080p) for ExoPlayer; the embed page stays as fallback.
     *  - any other embed      -> WebView embed surface.
     * Fallbacks always end with the source watch page itself.
     */
    override suspend fun resolvePlay(locatorSlug: String): CobePlay {
        val (movieSlug, episodeId) = parseLocator(locatorSlug)
            ?: throw IllegalStateException("Định danh tập không hợp lệ")
        ensureSite()
        // The play link lives ONLY on the watch page, and the web
        // domain rotates (.fun → .mom): try the learned origin
        // first, then every seed. The first page that loads wins;
        // its FINAL url (after redirects) both becomes the fallback
        // watch url below and teaches the live origin — scheme +
        // host are adopted as the new siteBase and persisted, so
        // the next rotation self-heals wherever the old domain
        // still answers with a redirect.
        val candidates = (listOf(siteBase) + SITE_SEEDS).distinct()
        var watchUrl = watchPageUrl(siteBase, movieSlug, episodeId)
        var html: String? = null
        var lastError: Exception? = null
        for (base in candidates) {
            try {
                val (body, finalUrl) =
                    httpGetFinal(watchPageUrl(base, movieSlug, episodeId))
                html = body
                watchUrl = finalUrl
                val origin = Regex("""^https?://[^/]+""").find(finalUrl)?.value
                if (origin != null && origin != siteBase) {
                    siteBase = origin
                    try {
                        settings?.setCobeSiteHost(origin)
                    } catch (ignored: Exception) {
                    }
                }
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        val page = html
            ?: throw (lastError
                ?: IllegalStateException("Không tải được trang xem từ nguồn"))
        var type: String? = null
        var link: String? = null
        val m1 = episodeJsonRegex.find(page)
        if (m1 != null) {
            type = m1.groupValues[1]
            link = m1.groupValues[2]
        } else {
            val m2 = episodeJsonRegexAlt.find(page)
            if (m2 != null) {
                link = m2.groupValues[1]
                type = m2.groupValues[2]
            }
        }
        val realLink = link?.replace("\\/", "/")?.replace("\\u0026", "&")
            ?: throw IllegalStateException("Không bóc được link phát từ trang nguồn")
        return when {
            type == "m3u8" || realLink.contains(".m3u8") ->
                CobePlay.Hls(realLink, fallbacks = listOf(watchUrl))
            realLink.contains("streamvsmov.com/video/") -> {
                val host = Regex("""https://([^/]+)""").find(realLink)?.groupValues?.get(1)
                val uuid = realLink.substringAfter("/video/").substringBefore("?").trim('/')
                if (host != null && uuid.isNotBlank()) {
                    CobePlay.Hls(
                        "https://$host/stream/$uuid/master.m3u8",
                        fallbacks = listOf(realLink, watchUrl)
                    )
                } else {
                    CobePlay.Embed(realLink, fallbacks = listOf(watchUrl))
                }
            }
            else -> CobePlay.Embed(realLink, fallbacks = listOf(watchUrl))
        }
    }
}
