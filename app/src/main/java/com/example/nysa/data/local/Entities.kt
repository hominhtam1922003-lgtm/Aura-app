package com.example.nysa.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached title metadata so library screens render instantly and offline. */
@Entity(tableName = "titles")
data class TitleEntity(
    @PrimaryKey val id: String,
    val family: String,
    val sourceId: String,
    val slug: String,
    val name: String,
    val originName: String = "",
    val posterUrl: String = "",
    val thumbUrl: String = "",
    val quality: String = "",
    val versionBadge: String = "",
    val latestLabel: String = "",
    val episodeCount: Int = 0,
    val year: Int = 0,
    val updatedEpochSec: Long = 0
)

/**
 * The single shared state book for every title, across all four families:
 * library flags + playback/reading progress + new-episode tracking.
 */
@Entity(tableName = "user_states")
data class UserStateEntity(
    @PrimaryKey val titleId: String,
    val favorite: Boolean = false,
    val watchLater: Boolean = false,
    val following: Boolean = false,
    val notifyOn: Boolean = false,
    // progress (video: episode + position; comic: chapter + page)
    val lastUnitLabel: String = "",
    val lastUnitNumber: Float = 0f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    // reader bookmark (Màn 11): a marked chapter + page, independent of progress
    val bookmarkChapter: Float = 0f,
    val bookmarkPage: Int = 0,
    val progressFraction: Float = 0f,
    val finished: Boolean = false,
    // new-episode tracking for followed titles
    val seenCount: Int = 0,
    val latestCount: Int = 0,
    // units the user ACTUALLY opened in player/reader, comma-separated numbers ("1,2,5.5,132")
    val watchedUnits: String = "",
    // last time the user opened/watched/read this title (Activity screen)
    val lastTouchedEpochSec: Long = 0
)

/** Parse the stored "1,2,5.5" watched-units string. */
fun parseWatchedUnits(raw: String): Set<Float> =
    raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.toSet()

fun formatWatchedUnits(units: Set<Float>): String =
    units.sorted().joinToString(",") { n -> if (n % 1f == 0f) n.toInt().toString() else n.toString() }

fun UserStateEntity.watchedSet(): Set<Float> = parseWatchedUnits(watchedUnits)

fun UserStateEntity.withWatched(number: Float): UserStateEntity =
    copy(watchedUnits = formatWatchedUnits(watchedSet() + number))
