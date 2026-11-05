package com.example.nysa.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outcome of one manual update check against the app's GitHub Releases. */
sealed class UpdateCheck {
    data class UpToDate(val current: String) : UpdateCheck()
    data class Available(val version: String, val apkUrl: String) : UpdateCheck()
    data class Failed(val reason: String) : UpdateCheck()
}

/**
 * Manual update check (1.0.4): NySa ships outside any store, so tab
 * Tôi asks GitHub for the latest release of the app's own repo and
 * compares version numbers. It only REPORTS the newest release and
 * its APK URL — the actual download + install lives in
 * [UpdateDownloader] (in-app since 1.0.7).
 */
object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/hominhtam1922003-lgtm/NySa-app/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("User-Agent", "NySa")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheck.Failed("GitHub trả HTTP ${resp.code}")
                }
                val body = resp.body?.string()
                    ?: return@withContext UpdateCheck.Failed("GitHub trả rỗng")
                val json = JSONObject(body)
                val latest = json.optString("tag_name").removePrefix("v").trim()
                if (latest.isBlank()) {
                    return@withContext UpdateCheck.Failed("Không đọc được tag bản mới nhất")
                }
                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (compareVersions(latest, currentVersion) > 0) {
                    UpdateCheck.Available(latest, apkUrl)
                } else {
                    UpdateCheck.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            UpdateCheck.Failed(e.message ?: "Lỗi mạng")
        }
    }

    /**
     * Dotted numeric compare ("1.0.10" > "1.0.9"); each segment keeps
     * only its leading digits, so stray suffixes never break the math.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
