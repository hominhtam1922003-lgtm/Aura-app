package com.example.nysa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nysa_settings")

/** All user settings. Defaults match the approved design. */
data class AppSettings(
    val qualityDefault: String = "CAO_NHAT",      // CAO_NHAT | P1080 | P720 | P480 | AUTO
    val versionPriority: String = "TM",           // TM | VS | LT — fallback order TM → VS → LT
    val autoFastestServer: Boolean = true,
    val autoNextEpisode: Boolean = true,
    val skipIntroOutro: Boolean = true,
    val pipEnabled: Boolean = true,
    val notifyNewEpisode: Boolean = true,
    val lockScreenNotif: Boolean = true,
    val checkInterval: String = "CONTINUOUS",     // CONTINUOUS | HOURLY | SIX_HOURS | ON_OPEN
    val autoFallbackSource: Boolean = true,
    val adBlock: Boolean = true,
    val playerResize: String = "FIT",             // FIT | FULL | FULLSUB | STRETCH
    /** The approved Liquid Glass look; OFF = the plain black theme everywhere. */
    val liquidGlass: Boolean = true
)

/**
 * A newer release the background worker found on GitHub (1.0.6):
 * drives both the system notification (worker) and the in-app update
 * banner (shell). Empty version = nothing pending.
 */
data class AvailableUpdate(
    val version: String = "",
    val apkUrl: String = ""
)

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val flow: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            qualityDefault = p[QUALITY] ?: "CAO_NHAT",
            versionPriority = p[VERSION_PRIORITY] ?: "TM",
            autoFastestServer = p[AUTO_SERVER] ?: true,
            autoNextEpisode = p[AUTO_NEXT] ?: true,
            skipIntroOutro = p[SKIP_INTRO] ?: true,
            pipEnabled = p[PIP] ?: true,
            notifyNewEpisode = p[NOTIFY_NEW] ?: true,
            lockScreenNotif = p[LOCK_NOTIF] ?: true,
            checkInterval = p[CHECK_INTERVAL] ?: "CONTINUOUS",
            autoFallbackSource = p[AUTO_FALLBACK] ?: true,
            adBlock = p[AD_BLOCK] ?: true,
            playerResize = p[PLAYER_RESIZE] ?: "FIT",
            liquidGlass = p[LIQUID_GLASS] ?: true
        )
    }

    suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    /** Search history, newest first, max 12 entries. */
    val searchHistory: Flow<List<String>> = store.data.map { p ->
        (p[SEARCH_HISTORY] ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun addSearchQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        store.edit { p ->
            val current = (p[SEARCH_HISTORY] ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(q, ignoreCase = true) }
            p[SEARCH_HISTORY] = (listOf(q) + current).take(12).joinToString("\n")
        }
    }

    suspend fun removeSearchQuery(query: String) {
        store.edit { p ->
            val current = (p[SEARCH_HISTORY] ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() && it != query }
            p[SEARCH_HISTORY] = current.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        store.edit { it[SEARCH_HISTORY] = "" }
    }

    /**
     * Last background-check stamp per tracked title, stored as
     * "titleId=epochSec;titleId=epochSec" (ids contain ':' but never ';'
     * or '='). The worker rotates fairly through titles older than the
     * per-run batch instead of a hard take(30) that starved the tail.
     */
    val workerStamps: Flow<Map<String, Long>> = store.data.map { p ->
        (p[WORKER_STAMPS] ?: "").split(";")
            .mapNotNull { entry ->
                val idx = entry.lastIndexOf('=')
                if (idx <= 0) null
                else entry.substring(0, idx) to entry.substring(idx + 1).toLongOrNull()
            }
            .mapNotNull { (id, sec) -> sec?.let { id to it } }
            .toMap()
    }

    suspend fun setWorkerStamps(stamps: Map<String, Long>) {
        store.edit { p ->
            p[WORKER_STAMPS] = stamps.entries.joinToString(";") { (id, sec) -> "$id=$sec" }
        }
    }

    /**
     * Learned skip-intro end per title (ms), same blob format as the
     * worker stamps. The source carries no intro metadata, so the
     * player learns the end point per series from the user's own
     * correction seek (see PlayerActivity).
     */
    val introEnds: Flow<Map<String, Long>> = store.data.map { p ->
        (p[INTRO_ENDS] ?: "").split(";")
            .mapNotNull { entry ->
                val idx = entry.lastIndexOf('=')
                if (idx <= 0) null
                else entry.substring(0, idx) to entry.substring(idx + 1).toLongOrNull()
            }
            .mapNotNull { (id, ms) -> ms?.let { id to it } }
            .toMap()
    }

    suspend fun setIntroEnd(titleId: String, ms: Long) {
        store.edit { p ->
            val current = (p[INTRO_ENDS] ?: "").split(";")
                .mapNotNull { entry ->
                    val idx = entry.lastIndexOf('=')
                    if (idx <= 0) null
                    else entry.substring(0, idx) to entry.substring(idx + 1).toLongOrNull()
                }
                .mapNotNull { (id, v) -> v?.let { id to it } }
                .toMap()
            val merged = current + (titleId to ms)
            val kept = if (merged.size > 500)
                merged.entries.drop(merged.size - 500) else merged.entries
            p[INTRO_ENDS] = kept.joinToString(";") { (id, v) -> "$id=$v" }
        }
    }

    /**
     * Last COBE API host that actually answered, cached so a host
     * rotation (the site already changed its web domain once) self-heals —
     * CobeSource rediscovers from the site's JS bundles and stores the
     * new host here. Empty = never discovered; the source uses its
     * built-in default (measured live 2026-11-04).
     */
    val cobeApiHost: Flow<String> = store.data.map { p -> p[COBE_API_HOST] ?: "" }

    suspend fun setCobeApiHost(host: String) {
        store.edit { it[COBE_API_HOST] = host }
    }

    /**
     * Anime genre facets probed from real by-slug records (1.0.4),
     * stored as "id\tname" lines joined by "\n" — the Anime filter
     * restores them at startup instead of re-probing on every cold
     * start. Same line-blob style as the worker stamps above.
     */
    val animeGenreCache: Flow<List<Pair<String, String>>> = store.data.map { p ->
        (p[ANIME_GENRE_CACHE] ?: "").split("\n")
            .mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx <= 0) null
                else line.substring(0, idx) to line.substring(idx + 1)
            }
            .filter { it.second.isNotBlank() }
    }

    suspend fun setAnimeGenreCache(list: List<Pair<String, String>>) {
        store.edit { p ->
            p[ANIME_GENRE_CACHE] = list.joinToString("\n") { (id, name) -> "$id\t$name" }
        }
    }

    /**
     * App-update state (1.0.6): the newest release the background
     * worker has seen, the version the user already got a system
     * notification for (anti-spam — a version is only announced
     * again when a NEWER one appears), and the version the user
     * dismissed with "Để sau" on the in-app banner (that exact
     * version stays quiet; a newer one announces again).
     */
    val availableUpdate: Flow<AvailableUpdate> = store.data.map { p ->
        AvailableUpdate(
            version = p[UPDATE_AVAILABLE_VERSION] ?: "",
            apkUrl = p[UPDATE_AVAILABLE_URL] ?: ""
        )
    }

    suspend fun setAvailableUpdate(version: String, apkUrl: String) {
        store.edit { p ->
            p[UPDATE_AVAILABLE_VERSION] = version
            p[UPDATE_AVAILABLE_URL] = apkUrl
        }
    }

    val updateNotifiedVersion: Flow<String> = store.data.map { p ->
        p[UPDATE_NOTIFIED_VERSION] ?: ""
    }

    suspend fun setUpdateNotifiedVersion(version: String) {
        store.edit { it[UPDATE_NOTIFIED_VERSION] = version }
    }

    val updateDismissedVersion: Flow<String> = store.data.map { p ->
        p[UPDATE_DISMISSED_VERSION] ?: ""
    }

    suspend fun setUpdateDismissedVersion(version: String) {
        store.edit { it[UPDATE_DISMISSED_VERSION] = version }
    }

    companion object {
        val QUALITY = stringPreferencesKey("quality_default")
        val VERSION_PRIORITY = stringPreferencesKey("version_priority")
        val AUTO_SERVER = booleanPreferencesKey("auto_fastest_server")
        val AUTO_NEXT = booleanPreferencesKey("auto_next_episode")
        val SKIP_INTRO = booleanPreferencesKey("skip_intro_outro")
        val PIP = booleanPreferencesKey("pip_enabled")
        val NOTIFY_NEW = booleanPreferencesKey("notify_new_episode")
        val LOCK_NOTIF = booleanPreferencesKey("lock_screen_notif")
        val CHECK_INTERVAL = stringPreferencesKey("check_interval")
        val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback_source")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val PLAYER_RESIZE = stringPreferencesKey("player_resize")
        val LIQUID_GLASS = booleanPreferencesKey("liquid_glass")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val WORKER_STAMPS = stringPreferencesKey("worker_check_stamps")
        val INTRO_ENDS = stringPreferencesKey("intro_ends")
        val COBE_API_HOST = stringPreferencesKey("cobe_api_host")
        val ANIME_GENRE_CACHE = stringPreferencesKey("anime_genre_cache")
        val UPDATE_AVAILABLE_VERSION = stringPreferencesKey("update_available_version")
        val UPDATE_AVAILABLE_URL = stringPreferencesKey("update_available_url")
        val UPDATE_NOTIFIED_VERSION = stringPreferencesKey("update_notified_version")
        val UPDATE_DISMISSED_VERSION = stringPreferencesKey("update_dismissed_version")
    }
}
