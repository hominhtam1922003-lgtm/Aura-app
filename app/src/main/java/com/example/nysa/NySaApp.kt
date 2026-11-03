package com.example.nysa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.nysa.data.SettingsStore
import com.example.nysa.data.local.NySaDatabase
import com.example.nysa.data.source.CobeSource
import com.example.nysa.work.NewEpisodeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NySaApp : Application(), ImageLoaderFactory {

    val database: NySaDatabase by lazy {
        Room.databaseBuilder(this, NySaDatabase::class.java, "nysa.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** The one and only content source (user order 2026-11-04). */
    val cobe: CobeSource by lazy { CobeSource(settings) }

    /**
     * The video cache shared by the player (write-through streaming)
     * and the next-episode prefetcher. Sized generously per the user's
     * rule — the app may be heavy as long as playback is best-quality
     * and fast (the 30 GB data budget is the ceiling, LRU eviction
     * keeps this cache at 2 GB).
     */
    val videoCache: androidx.media3.datasource.cache.SimpleCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(cacheDir, "nysa_video_cache"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(
                2L * 1024 * 1024 * 1024
            ),
            androidx.media3.database.StandaloneDatabaseProvider(this)
        )
    }

    /**
     * In-app "có tập mới" events for the liquid banner: names of tracked
     * titles that gained episodes, filled by the worker (background and
     * on-open runs share this process). The shell shows the banner once
     * per fill and clears the list.
     */
    val newEpisodeEvents =
        MutableStateFlow<List<Pair<String, String>>>(emptyList())

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Heal legacy "Có tập mới" baselines: rows tracked before the
        // baseline rule existed can carry seenCount = 0 with a full back
        // catalog in latestCount. First sight of the current count IS
        // the baseline — only units released after tracking may count.
        appScope.launch {
            runCatching {
                val dao = database.userStateDao()
                dao.trackedOnce()
                    .filter { it.seenCount == 0 && it.latestCount > 0 }
                    .forEach { dao.upsert(it.copy(seenCount = it.latestCount)) }
            }
        }
        // Keep the background check schedule in sync with the user's setting.
        appScope.launch {
            settings.flow
                .map { it.checkInterval to it.notifyNewEpisode }
                .distinctUntilChanged()
                .collect { (interval, notify) -> scheduleChecks(interval, notify) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_NEW_EPISODES,
                "Tập mới",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Báo khi bộ đang theo dõi có tập mới" }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun scheduleChecks(interval: String, notify: Boolean) {
        val workManager = WorkManager.getInstance(this)
        if (!notify || interval == "ON_OPEN") {
            workManager.cancelUniqueWork(WORK_EPISODE_CHECKS)
            return
        }
        val request = when (interval) {
            "HOURLY" -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(1, TimeUnit.HOURS)
            "SIX_HOURS" -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(6, TimeUnit.HOURS)
            else -> PeriodicWorkRequestBuilder<NewEpisodeWorker>(15, TimeUnit.MINUTES)
        }.build()
        workManager.enqueueUniquePeriodicWork(
            WORK_EPISODE_CHECKS,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    companion object {
        const val CHANNEL_NEW_EPISODES = "nysa_new_episodes"
        const val WORK_EPISODE_CHECKS = "nysa_episode_checks"
    }

    /**
     * App-wide image loader (performance gate): posters are requested
     * at their display size by the cards themselves; here we bound the
     * caches — memory 20% of the app budget, disk 256 MB — and give
     * every request a whole-call ceiling so a slow CDN can never pin
     * a grid cell forever.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    req.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) NySa/1.0")
                        .build()
                )
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "nysa_image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}
