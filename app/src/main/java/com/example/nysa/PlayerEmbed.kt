package com.example.nysa

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.nysa.data.AdBlock
import com.example.nysa.player.PlayerUiState
import com.example.nysa.player.PlayerViewModel
import com.example.nysa.ui.theme.NySaGreen
import com.example.nysa.ui.theme.NySaTextMuted
import com.example.nysa.ui.theme.NySaViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Load an embed page into the WebView.
 *
 * No referer (every family except Donghua): the embed loads exactly
 * as it always has — top-level via loadUrl.
 *
 * With a referer (Donghua ONLY — PlayerViewModel fills it with the
 * episode's watch-page URL): 1.3.1 loaded the embed top-level with a
 * Referer header, which passed streamfree's SERVER-side gate but not
 * its CLIENT-side one — the embed's JS checks the frame context and,
 * finding itself top-level (window.self === window.top), renders
 * "video này chỉ có thể phát trong iframe (-2)". Reproduced both
 * ways in a real browser 2026-11-03 (dossier donghua-embed-iframe).
 *
 * 1.3.2: the embed now rides a wrapper page carrying a REAL <iframe>
 * whose markup copies player.php's own (referrerpolicy="unsafe-url",
 * allow="autoplay; fullscreen", allowfullscreen), loaded via
 * loadDataWithBaseURL with the watch page as baseURL — so the iframe
 * navigation carries an hhkungfu.ee Referer (server gate) AND the
 * embed really sits inside a frame (JS gate). Never enable
 * setWebContentsDebuggingEnabled on this WebView: the same host
 * answers DevTools-attached contexts with a "-3" refusal.
 */
private fun loadEmbed(view: WebView, url: String, referer: String?) {
    if (referer.isNullOrBlank()) {
        view.loadUrl(url)
        return
    }
    val src = url.replace("&", "&amp;").replace("\"", "&quot;")
    val wrapper = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%}iframe{border:0;width:100%;height:100%}</style>
        </head><body>
        <iframe src="$src" referrerpolicy="unsafe-url" allow="autoplay; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    view.loadDataWithBaseURL(referer, wrapper, "text/html", "UTF-8", null)
}

@Composable
internal fun EmbedPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // Embeds are a watch screen too: keep the screen on while it is
    // open (same user order as the stream player); cleared on exit.
    DisposableEffect(Unit) {
        val win = (context as? PlayerActivity)?.window
        win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 1.3.2: the last embedUrl actually loaded into the WebView. The
    // reload guard compares against THIS, never view.url — with the
    // Donghua wrapper page, view.url is the watch-page baseURL, which
    // never equals the embed URL.
    var loadedEmbedUrl by remember { mutableStateOf<String?>(null) }
    // 1.1.1 (N5): a dead embed used to be a dead end — the page's own
    // "Setup error" (or a blank frame) just sat there. Main-frame load
    // failures now raise the same recovery overlay as the stream branch:
    // retry, switch server, report. Sub-resource errors (ad images, XHR
    // inside the page) must NOT trigger it — isForMainFrame guards that.
    var embedError by remember(state.embedUrl) { mutableStateOf<String?>(null) }
    // V2 (0.1): the ad-block setting, REALLY wired — sub-requests to
    // known ad/gambling domains are answered with an empty response
    // before they ever leave the WebView. Live value (a settings
    // change takes effect on the next embed open).
    val embedApp = context.applicationContext as AuraApp
    var adBlockOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        embedApp.settings.flow.collect { adBlockOn = it.adBlock }
    }
    // 1.3.7: display size for embeds — the same persisted setting as
    // the stream surface, applied as object-fit CSS on the page's
    // video element. Best effort: players rendering into a plain
    // <video> tag honour it; exotic renderers ignore it and keep
    // their own fit. The style element persists in the page, so a
    // video element created after injection is covered too.
    var embedResize by remember { mutableStateOf("FIT") }
    LaunchedEffect(Unit) {
        embedApp.settings.flow.collect { embedResize = it.playerResize }
    }
    LaunchedEffect(embedResize, loadedEmbedUrl) {
        val fit = when (embedResize) {
            "FULL" -> "cover"
            "STRETCH" -> "fill"
            // 1.3.13: bottom-anchored cover — same "Lấp đầy
            // (giữ phụ đề)" idea as the stream surface: the
            // crop lands on the top, the bottom (subtitles)
            // stays.
            "FULLSUB" -> "cover"
            else -> "contain"
        }
        val pos = if (embedResize == "FULLSUB") "center bottom" else "center center"
        webView?.evaluateJavascript(
            "(function(){var s=document.getElementById('nysa-fit');" +
                "if(!s){s=document.createElement('style');s.id='nysa-fit';" +
                "document.head.appendChild(s);}" +
                "s.textContent='video{object-fit:$fit !important;object-position:$pos !important}';})()",
            null
        )
    }
    var showServers by remember { mutableStateOf(false) }
    var reportSaved by remember(state.embedUrl) { mutableStateOf(false) }
    val embedScope = androidx.compose.runtime.rememberCoroutineScope()
    val servers = state.detail?.servers ?: emptyList()
    // 1.3.4: a main-frame embed failure first advances through the
    // untried candidates (other groups + same-group variants, real
    // streams first — see PlayerViewModel.failoverEmbed); only when
    // none are left does the error card appear, carrying the REAL
    // error. The candidate list is built in the background at apply
    // time, so a fast failure retries the call once after a short
    // wait before giving up.
    fun onEmbedFailed(realError: String) {
        // 1.3.6: this episode's own alternates first (see
        // failoverPlay), then the cross-group candidates.
        if (viewModel.failoverPlay()) return
        if (viewModel.failoverEmbed()) return
        embedScope.launch {
            kotlinx.coroutines.delay(1500)
            if (!viewModel.failoverPlay() && !viewModel.failoverEmbed()) {
                embedError = realError
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (state.embedUrl.isNotBlank()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                // V2 (0.1): known ad/gambling hosts get
                                // an empty body; everything else falls
                                // through to the real network untouched.
                                if (adBlockOn) {
                                    AdBlock.intercept(request)?.let { return it }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val desc = error?.description?.toString()
                                        ?.takeIf { it.isNotBlank() }
                                    onEmbedFailed(
                                        "Không tải được trang phát của server này" +
                                            (desc?.let { " ($it)" } ?: "") +
                                            ". Hãy thử lại hoặc đổi server."
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    val code = errorResponse?.statusCode ?: 0
                                    onEmbedFailed(
                                        "Server embed trả lỗi HTTP $code. Hãy thử lại hoặc đổi server."
                                    )
                                }
                            }
                        }
                        webView = this
                        loadedEmbedUrl = state.embedUrl
                        loadEmbed(this, state.embedUrl, state.embedReferer)
                    }
                },
                update = { view ->
                    if (loadedEmbedUrl != state.embedUrl) {
                        loadedEmbedUrl = state.embedUrl
                        loadEmbed(view, state.embedUrl, state.embedReferer)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (state.loading) {
            CircularProgressIndicator(
                color = NySaViolet,
                modifier = Modifier.align(Alignment.Center).size(46.dp)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(0x80000000), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.item?.name ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    state.episode?.label ?: "",
                    color = NySaTextMuted,
                    fontSize = 12.sp
                )
            }
            // Server escape hatch right in embed mode (N5): pages that
            // load "successfully" but show their own error text inside
            // raise no WebView error — the user still needs a way out.
            if (servers.size > 1) {
                Text(
                    "Đổi server",
                    color = NySaViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showServers = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        if (state.error.isNotBlank() && embedError == null) {
            Text(
                state.error,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }
        // Main-frame embed failure — same recovery shape as the stream
        // branch's error card (Thử lại · Đổi server · Báo lỗi này).
        if (embedError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(embedError!!, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Thử lại",
                        color = NySaViolet,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            embedError = null
                            webView?.reload()
                        }
                    )
                    if (servers.size > 1) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Đổi server",
                            color = NySaViolet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showServers = true }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (reportSaved) "Đã ghi báo lỗi ✓" else "Báo lỗi này",
                    color = if (reportSaved) NySaGreen else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = !reportSaved) {
                        val errorText = embedError ?: ""
                        embedScope.launch {
                            withContext(Dispatchers.IO) {
                                com.example.nysa.data.ReportStore.add(
                                    context,
                                    com.example.nysa.data.ErrorReport(
                                        timeEpochSec = System.currentTimeMillis() / 1000,
                                        family = state.item?.family?.name ?: "",
                                        titleId = state.item?.id ?: "",
                                        titleName = state.item?.name ?: "",
                                        unitLabel = state.episode?.label ?: "",
                                        server = servers.getOrNull(state.serverIndex)?.name ?: "",
                                        message = errorText
                                    )
                                )
                            }
                            reportSaved = true
                        }
                    }
                )
            }
        }
    }
    if (showServers) {
        ServerSheet(
            servers = servers,
            selectedIndex = state.serverIndex,
            onSelect = { idx ->
                showServers = false
                embedError = null
                viewModel.switchServer(idx, 0)
            },
            onDismiss = { showServers = false },
            serverStatuses = state.serverStatuses
        )
    }
    DisposableEffect(Unit) {
        onDispose { /* WebView is destroyed with the view tree */ }
    }
}
