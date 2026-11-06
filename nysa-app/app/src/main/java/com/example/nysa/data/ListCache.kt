package com.example.nysa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.nysa.data.model.Family
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.listCacheStore: DataStore<Preferences> by preferencesDataStore(
    name = "nysa_list_cache"
)

/** A cached default section view: ordered title ids + paging state. */
data class SectionListCache(
    val ids: List<String>,
    val page: Int,
    val hasMore: Boolean
)

/**
 * Structural cache for list screens (1.0.15, resilience pass).
 *
 * Stores only WHICH titles appeared WHERE — shelf names, ordered title
 * ids, paging state — never the title payloads themselves: those rows
 * already live in Room (every list load upserts them via TitleDao), so
 * screens repaint instantly from disk on a cold start and the network
 * refresh then replaces them (stale-while-revalidate). Living in its
 * own DataStore file means NO Room migration, so favorites, progress
 * and tracking rows are untouched by this feature. Every read/write is
 * failure-tolerant: a corrupt or missing cache degrades to today's
 * network-first behaviour, never to a crash.
 */
class ListCache(context: Context) {

    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> get() = appContext.listCacheStore

    suspend fun saveHomeShelves(shelves: List<Pair<String, List<String>>>) {
        runCatching {
            val arr = JSONArray()
            shelves.forEach { (name, ids) ->
                val idsArr = JSONArray()
                ids.forEach { idsArr.put(it) }
                arr.put(JSONObject().put("name", name).put("ids", idsArr))
            }
            val json = JSONObject().put("shelves", arr).toString()
            store.edit { it[HOME_KEY] = json }
        }
    }

    /** Shelf name -> ordered title ids, or null when nothing usable is cached. */
    suspend fun homeShelves(): List<Pair<String, List<String>>>? = runCatching {
        val raw = store.data.first()[HOME_KEY] ?: return null
        val arr = JSONObject(raw).optJSONArray("shelves") ?: return null
        val out = mutableListOf<Pair<String, List<String>>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val idsArr = obj.optJSONArray("ids") ?: continue
            val ids = (0 until idsArr.length()).map { idsArr.optString(it) }
                .filter { it.isNotBlank() }
            if (name.isNotBlank() && ids.isNotEmpty()) out += name to ids
        }
        out.ifEmpty { null }
    }.getOrNull()

    suspend fun saveSectionDefault(
        family: Family,
        ids: List<String>,
        page: Int,
        hasMore: Boolean
    ) {
        runCatching {
            val idsArr = JSONArray()
            ids.forEach { idsArr.put(it) }
            val json = JSONObject()
                .put("ids", idsArr)
                .put("page", page)
                .put("hasMore", hasMore)
                .toString()
            store.edit { it[sectionKey(family)] = json }
        }
    }

    suspend fun sectionDefault(family: Family): SectionListCache? = runCatching {
        val raw = store.data.first()[sectionKey(family)] ?: return null
        val obj = JSONObject(raw)
        val idsArr = obj.optJSONArray("ids") ?: return null
        val ids = (0 until idsArr.length()).map { idsArr.optString(it) }
            .filter { it.isNotBlank() }
        if (ids.isEmpty()) return null
        SectionListCache(
            ids = ids,
            page = obj.optInt("page", 1).coerceAtLeast(1),
            hasMore = obj.optBoolean("hasMore", true)
        )
    }.getOrNull()

    private fun sectionKey(family: Family) =
        stringPreferencesKey("section_default_${family.name}_v1")

    private companion object {
        val HOME_KEY = stringPreferencesKey("home_shelves_v1")
    }
}
