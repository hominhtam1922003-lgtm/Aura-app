package com.example.nysa.data.model

/** Sheet "Trạng thái" group: single-select, none = all. */
const val STATUS_ALL = 0
const val STATUS_ONGOING = 1
const val STATUS_COMPLETED = 2

fun TitleItem.matchesStatus(status: Int): Boolean = when (status) {
    STATUS_ONGOING -> !completed
    STATUS_COMPLETED -> completed
    else -> true
}

/** NySa has exactly two families — Phim (live action) and Anime, kept apart everywhere. */
enum class Family(val label: String) {
    PHIM("Phim"),
    ANIME("Anime")
}

/** One row in any list/shelf across the app. */
data class TitleItem(
    val id: String,                 // "<family>:cobe:<slug>"
    val family: Family,
    val sourceId: String,           // always "cobe" in NySa
    val slug: String,
    val name: String,
    val originName: String = "",
    val posterUrl: String = "",     // vertical art (icdn URL from the API)
    val thumbUrl: String = "",      // horizontal art
    val quality: String = "",       // source label, shown only when the source gives one
    val versionBadge: String = "",  // "TM" / "VS" / "" derived from the source language string
    val languages: String = "",     // the source's own language descriptor, verbatim
    val viewCount: Long = 0,        // source view counter when this item came from a view chart; 0 = none
    val latestLabel: String = "",   // "Tập 19" / "Full"
    val episodeCount: Int = 0,      // newest episode number known (counted, never the raw status string)
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    val genreSlugs: List<String> = emptyList(),
    /** Source genre ids parallel to [genres] — powers per-section filter accumulation. */
    val genreIds: List<Int> = emptyList(),
    /** Completion derived from EPISODE COUNTS vs the announced total (Cobe's status string lies). */
    val completed: Boolean = false,
    val updatedEpochSec: Long = 0,  // for "x phút trước" (Cobe updatedAt is true UTC)
    val isHot: Boolean = false,
    val isNew: Boolean = false
)

data class EpisodeItem(
    val label: String,              // "Tập 01"
    val number: Float,              // numeric position for sorting/ranges
    val slug: String,               // play locator "cobe:<movieSlug>:<episodeId>" (see CobeSource)
    val streamUrl: String = "",     // direct m3u8 when already resolved
    val embedUrl: String = ""       // the source watch page — the WebView fallback
)

data class ServerGroup(
    val name: String,               // the source's own server label, verbatim ("Vietsub #1", "Song ngữ"…)
    val langKey: String = "",       // "tm" / "vs" / "lt" — derived for the version-priority order only
    val episodes: List<EpisodeItem> = emptyList()
)

data class ActorRef(
    val name: String,
    val slug: String = "",
    val thumb: String = ""
)

data class TitleDetail(
    val item: TitleItem,
    val description: String = "",
    val status: String = "",        // "Đang tiến hành" / "Hoàn thành" / ""
    val totalCount: Int = 0,        // episode total counted from the real episode list
    val durationLabel: String = "",
    val actors: List<String> = emptyList(),
    val actorRefs: List<ActorRef> = emptyList(),
    val directors: List<String> = emptyList(),
    val servers: List<ServerGroup> = emptyList(),
    val trailerUrl: String = "",
    val genreLinks: List<Pair<String, String>> = emptyList(), // (label, genre id) — powers "Cùng thể loại"
    /** Honest load note from the adapter when the load was degraded; empty = normal. */
    val loadNote: String = ""
)
