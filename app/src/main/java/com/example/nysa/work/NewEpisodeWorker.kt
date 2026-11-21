package com.example.nysa.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nysa.MainActivity
import com.example.nysa.AuraApp
import com.example.nysa.R
import com.example.nysa.data.UpdateCheck
import com.example.nysa.data.UpdateChecker
import com.example.nysa.data.UpdateDownloader
import com.example.nysa.data.toItem
import kotlinx.coroutines.flow.first

/**
 * Background new-episode checker (design: "Kiểm tra tập mới — chạy nền").
 * For every followed title, re-reads its source detail; when the source has
 * more units than last seen, updates latestCount and posts ONE summary
 * notification. Since 2026-11-01 the counts are bookkeeping only — no UI
 * surface displays them (the user's "+N" removal order); this worker's
 * notification is the one place new episodes are announced, and its text
 * names titles without any "+N" count.
 *
 * C2 (K9): the old hard take(30) meant every tracked title past #30 was
 * NEVER checked. A run still checks at most [BATCH_SIZE] titles, but the
 * batch is chosen by fair rotation — the least-recently-checked titles go
 * first (stamps in DataStore), so over successive runs every title is
 * covered. Baseline/notification logic is unchanged.
 */
class NewEpisodeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1.0.18: never die silently — any unexpected failure still
        // leaves a diagnostic line in Settings before the run ends.
        return try {
            doWorkInner()
        } catch (e: Exception) {
            runCatching {
                (applicationContext as AuraApp).settings.setLastCheck(
                    System.currentTimeMillis(),
                    "Lỗi khi kiểm tra tập mới: ${e.javaClass.simpleName}"
                )
            }
            Result.success()
        }
    }

    private suspend fun doWorkInner(): Result {
        val app = applicationContext as AuraApp
        val settings = app.settings.flow.first()
        // App-update check (1.0.6) rides EVERY worker run — including
        // the on-open one — and is independent of the episode-notify
        // toggle below: it stores any newer release for the in-app
        // banner and posts one system notification per version.
        runCatching { checkForAppUpdate(app, settings.lockScreenNotif) }
        if (!settings.notifyNewEpisode) {
            // 1.0.18: even a skipped run leaves a diagnostic trace.
            runCatching {
                app.settings.setLastCheck(
                    System.currentTimeMillis(),
                    "Đã chạy — báo tập mới đang tắt trong Cài đặt"
                )
            }
            return Result.success()
        }

        val dao = app.database.userStateDao()
        val all = dao.trackedOnce()
        val stamps = app.settings.workerStamps.first()
        // Never-checked titles (stamp 0) first, then stalest-checked.
        val states = all
            .sortedBy { stamps[it.titleId] ?: 0L }
            .take(BATCH_SIZE)
        android.util.Log.d(
            TAG,
            "Đang canh tổng ${all.size} bộ; lượt này kiểm tra ${states.size} bộ"
        )
        val updatedNames = mutableListOf<String>()
        val updatedPairs = mutableListOf<Pair<String, String>>()
        var fetchFailures = 0
        for (st in states) {
            val entity = app.database.titleDao().observe(st.titleId).first() ?: continue
            val item = entity.toItem()
            val fetched = runCatching {
                // NySa has one source for both families.
                val detail = app.cobe.detailFor(item)
                maxOf(detail.totalCount, detail.item.episodeCount)
            }
            if (fetched.isFailure) fetchFailures++
            val total = fetched.getOrDefault(0)
            if (total <= 0) continue
            when {
                // First check after tracking started: everything already out
                // is the BASELINE — it must never be reported as new.
                st.seenCount == 0 && st.latestCount == 0 ->
                    dao.upsert(st.copy(latestCount = total, seenCount = total))
                // Legacy row that never got a baseline — heal silently.
                st.seenCount == 0 -> {
                    val base = maxOf(st.latestCount, total)
                    dao.upsert(st.copy(latestCount = base, seenCount = base))
                }
                // Genuinely new units beyond the baseline.
                total > st.latestCount -> {
                    dao.upsert(st.copy(latestCount = total))
                    if (total > st.seenCount) {
                        updatedNames += item.name
                        updatedPairs += item.id to item.name
                    }
                }
            }
        }
        if (updatedNames.isNotEmpty()) {
            notifyNewEpisodes(app, updatedNames, settings.lockScreenNotif)
            // Feed the in-app liquid banner (shell shows it once).
            if (updatedPairs.isNotEmpty()) {
                app.newEpisodeEvents.value = updatedPairs
            }
        }
        // C2: stamp every attempted title so the next run rotates to the
        // ones left behind; prune stamps of titles no longer tracked.
        val nowSec = System.currentTimeMillis() / 1000
        val trackedIds = all.map { it.titleId }.toSet()
        val newStamps = stamps.toMutableMap()
        newStamps.keys.retainAll(trackedIds)
        states.forEach { newStamps[it.titleId] = nowSec }
        runCatching { app.settings.setWorkerStamps(newStamps) }
        // 1.0.18 diagnostics: leave a visible trace of this run in
        // Settings — when it ran, how much it checked, what it found.
        // The pre-1.0.18 worker could die silently for a month.
        val summary = when {
            states.isEmpty() ->
                "Đã kiểm tra — chưa có bộ nào đang theo dõi"
            fetchFailures >= states.size ->
                "Kiểm ${states.size} bộ — không lấy được dữ liệu nguồn (mạng hoặc nguồn lỗi)"
            updatedNames.isNotEmpty() ->
                "Kiểm ${states.size} bộ — ${updatedNames.size} bộ có tập mới: " +
                    updatedNames.take(3).joinToString(", ") +
                    if (updatedNames.size > 3) "…" else ""
            else ->
                "Kiểm ${states.size} bộ — chưa có tập mới" +
                    if (fetchFailures > 0) " ($fetchFailures bộ lỗi mạng)" else ""
        }
        runCatching {
            app.settings.setLastCheck(System.currentTimeMillis(), summary)
        }
        return Result.success()
    }

    private fun notifyNewEpisodes(context: Context, names: List<String>, lockScreenVisible: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (names.size == 1) {
            "${names.first()} vừa có tập/chap mới"
        } else {
            // Grouped form lists the titles themselves — no counts of any
            // kind ("+N" or title tallies) per the user's 2026-11-01 order.
            "Có tập/chap mới: ${names.take(3).joinToString(", ")}" +
                if (names.size > 3) "…" else ""
        }
        val notification = NotificationCompat.Builder(context, AuraApp.CHANNEL_NEW_EPISODES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Có tập mới")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(
                if (lockScreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_SECRET
            )
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * One update check against GitHub Releases (same UpdateChecker
     * the Tôi tab's manual check uses). A newer release is always
     * stored for the in-app banner; the system notification fires
     * only when this version is newer than the last one already
     * announced, so a version is never announced twice.
     */
    private suspend fun checkForAppUpdate(app: AuraApp, lockScreenVisible: Boolean) {
        val current = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: ""
        if (current.isBlank()) return
        val result = UpdateChecker.check(current)
        if (result !is UpdateCheck.Available) return
        app.settings.setAvailableUpdate(result.version, result.apkUrl)
        val notified = app.settings.updateNotifiedVersion.first()
        if (UpdateChecker.compareVersions(result.version, notified) > 0) {
            notifyAppUpdate(app, result.version, result.apkUrl, lockScreenVisible)
            app.settings.setUpdateNotifiedVersion(result.version)
        }
    }

    private fun notifyAppUpdate(
        context: Context,
        version: String,
        apkUrl: String,
        lockScreenVisible: Boolean
    ) {
        // Tapping the notification opens the app itself (1.0.7): the
        // extras tell MainActivity to start the IN-APP download —
        // the update no longer jumps out to the browser.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(UpdateDownloader.EXTRA_AUTO_UPDATE, "1")
            putExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_VERSION, version)
            putExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_URL, apkUrl)
        }
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "Aura $version đã sẵn sàng — chạm để tải bản mới"
        val notification = NotificationCompat.Builder(context, AuraApp.CHANNEL_APP_UPDATES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Có bản cập nhật mới")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(
                if (lockScreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_SECRET
            )
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val UPDATE_NOTIFICATION_ID = 2002
        private const val TAG = "NewEpisodeWorker"

        /** Max titles checked per run — rotation (not a hard cap) covers the rest. */
        private const val BATCH_SIZE = 30
    }
}
