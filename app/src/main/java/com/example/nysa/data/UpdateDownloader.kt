package com.example.nysa.data

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.nysa.MainActivity
import com.example.nysa.NySaApp
import com.example.nysa.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app update download + install (1.0.7): replaces the old flow
 * where every update touchpoint fired an ACTION_VIEW intent and the
 * user landed in the browser. The APK now downloads through the
 * system DownloadManager into the app's own external-files folder
 * (no storage permission needed) and the system installer is opened
 * from inside the app. Any failure along the way falls back to the
 * old browser behaviour instead of dead-ending.
 *
 * Completion is tracked two ways in parallel: a poll loop over
 * DownloadManager.Query (~700 ms) drives the progress state, and an
 * ACTION_DOWNLOAD_COMPLETE receiver is the belt-and-braces finish
 * line. When the download finishes while the app is in the
 * background, the installer is NOT launched (Android would drop or
 * misplace it); a notification on the app-updates channel invites
 * the user back, and MainActivity offers the install once, on
 * resume, via [onForeground].
 */
object UpdateDownloader {

    sealed class State {
        object Idle : State()
        data class Downloading(val version: String, val progressPercent: Int) : State()
        data class Ready(val version: String) : State()
        data class AwaitingInstallPermission(val version: String) : State()
        data class Failed(val version: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by MainActivity onResume/onPause; gates auto-install. */
    @Volatile
    var appInForeground: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiverRegistered = AtomicBoolean(false)

    /**
     * Guards the Downloading → Ready transition in
     * [onDownloadComplete]: the poll loop (Dispatchers.Default) and
     * the completion receiver (main thread) can both observe the
     * finished download, and the state check alone is not atomic —
     * both could pass it and open the installer twice. Only the CAS
     * winner performs the follow-up; the loser returns quietly.
     * Reset by [start] whenever a new download begins.
     */
    private val completing = AtomicBoolean(false)

    @Volatile
    private var downloadId: Long = -1L

    @Volatile
    private var pendingVersion: String = ""

    @Volatile
    private var pendingUrl: String = ""

    /** Version whose Ready state was already offered for install. */
    @Volatile
    private var handledVersion: String = ""

    private var pollJob: Job? = null

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == downloadId) {
                onDownloadComplete(context.applicationContext)
            }
        }
    }

    /**
     * The single entry point for every update touchpoint (Tôi tab,
     * in-app banner, system-notification deep link). Re-tapping while
     * the same version downloads is a no-op; tapping once the file is
     * ready (or waiting on the install permission) goes straight to
     * [install].
     */
    fun start(context: Context, version: String, url: String) {
        val appCtx = context.applicationContext
        when (val s = _state.value) {
            is State.Downloading -> if (s.version == version) return
            is State.Ready -> if (s.version == version) {
                install(appCtx)
                return
            }
            is State.AwaitingInstallPermission -> if (s.version == version) {
                install(appCtx)
                return
            }
            else -> Unit
        }
        if (url.isBlank()) {
            fallbackOpenBrowser(appCtx, url)
            _state.value = State.Failed(version)
            return
        }
        pendingVersion = version
        pendingUrl = url
        ensureReceiver(appCtx)
        try {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // Always drop the previous attempt, whatever state it
            // ended in (downloading, failed or finished): a leftover
            // record + destination file makes DownloadManager fail a
            // same-version retry with STATUS_FAILED (file already
            // exists), trapping the user in a retry loop. Removing a
            // completed/failed download is safe and idempotent.
            if (downloadId != -1L) {
                runCatching { dm.remove(downloadId) }
            }
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(APK_MIME)
                .setTitle("NySa $version")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    appCtx, Environment.DIRECTORY_DOWNLOADS, "NySa-$version.apk"
                )
            downloadId = dm.enqueue(request)
            handledVersion = ""
            completing.set(false)
            _state.value = State.Downloading(version, -1)
            launchPoll(appCtx, downloadId, version)
        } catch (e: Exception) {
            fallbackOpenBrowser(appCtx, url)
            _state.value = State.Failed(version)
        }
    }

    /**
     * Called from MainActivity.onResume: if a finished download is
     * still waiting for its one install offer, make it now.
     */
    fun onForeground(context: Context) {
        val s = _state.value
        if (s is State.Ready && handledVersion != s.version) {
            handledVersion = s.version
            install(context.applicationContext)
        }
    }

    /**
     * Opens the system installer for the downloaded APK. On API 26+
     * the per-app "install unknown apps" grant is checked first —
     * without it the user is routed to that exact settings screen and
     * the state parks at AwaitingInstallPermission so the next tap
     * retries the install itself.
     */
    fun install(context: Context) {
        val appCtx = context.applicationContext
        val version = when (val s = _state.value) {
            is State.Ready -> s.version
            is State.AwaitingInstallPermission -> s.version
            else -> pendingVersion
        }
        try {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = if (downloadId != -1L) dm.getUriForDownloadedFile(downloadId) else null
            if (uri == null) {
                _state.value = State.Failed(version)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appCtx.packageManager.canRequestPackageInstalls()
            ) {
                _state.value = State.AwaitingInstallPermission(version)
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appCtx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(settingsIntent)
                return
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        } catch (e: Exception) {
            _state.value = State.Failed(version)
            fallbackOpenBrowser(appCtx, pendingUrl)
        }
    }

    private fun launchPoll(appCtx: Context, id: Long, version: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isActive) {
                if (id != downloadId) return@launch // superseded by a newer start()
                var status = -1
                var progress = -1
                runCatching {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            status = c.getInt(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                            )
                            val soFar = c.getLong(
                                c.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                )
                            )
                            val total = c.getLong(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            )
                            if (total > 0) {
                                progress = ((soFar * 100) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onDownloadComplete(appCtx)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        if (downloadId == id) _state.value = State.Failed(version)
                        return@launch
                    }
                    else -> {
                        val s = _state.value
                        if (s is State.Downloading && s.version == version) {
                            _state.value = State.Downloading(version, progress)
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    /** Idempotent: the poll loop and the broadcast may both report. */
    private fun onDownloadComplete(appCtx: Context) {
        val s = _state.value
        if (s !is State.Downloading) return
        val version = s.version
        // Atomic claim of the Downloading → Ready transition: exactly
        // one caller (poll or receiver) proceeds to install/notify.
        if (!completing.compareAndSet(false, true)) return
        _state.value = State.Ready(version)
        if (appInForeground) {
            handledVersion = version
            install(appCtx)
        } else {
            notifyReadyToInstall(appCtx, version)
        }
    }

    private fun notifyReadyToInstall(context: Context, version: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INSTALL_READY, version)
        }
        val pending = PendingIntent.getActivity(
            context, READY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NySaApp.CHANNEL_APP_UPDATES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Đã tải xong NySa $version")
            .setContentText("Chạm để cài đặt")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(READY_NOTIFICATION_ID, notification)
    }

    private fun ensureReceiver(appCtx: Context) {
        if (receiverRegistered.compareAndSet(false, true)) {
            // System broadcasts still reach a RECEIVER_NOT_EXPORTED
            // receiver; the flag only shuts out other apps.
            ContextCompat.registerReceiver(
                appCtx,
                completionReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    /** The pre-1.0.7 behaviour, kept as the universal fallback. */
    private fun fallbackOpenBrowser(context: Context, url: String) {
        val target = url.ifBlank { RELEASES_LATEST_URL }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Extras understood by MainActivity's update intent handling. */
    const val EXTRA_AUTO_UPDATE = "nysa_auto_update"
    const val EXTRA_AUTO_UPDATE_VERSION = "nysa_auto_update_version"
    const val EXTRA_AUTO_UPDATE_URL = "nysa_auto_update_url"
    const val EXTRA_INSTALL_READY = "nysa_install_ready"

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val RELEASES_LATEST_URL =
        "https://github.com/hominhtam1922003-lgtm/NySa-app/releases/latest"
    private const val POLL_MS = 700L
    private const val READY_NOTIFICATION_ID = 2003
    private const val READY_REQUEST_CODE = 3
}
