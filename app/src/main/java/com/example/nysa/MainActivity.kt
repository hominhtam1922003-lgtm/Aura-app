package com.example.nysa

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nysa.data.AppSettings
import com.example.nysa.data.AvailableUpdate
import com.example.nysa.data.UpdateChecker
import com.example.nysa.data.UpdateDownloader
import com.example.nysa.data.local.UserStateEntity
import com.example.nysa.data.model.EpisodeItem
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.ServerGroup
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.toEntity
import com.example.nysa.data.toItem
import com.example.nysa.data.versionPriorityOrder
import com.example.nysa.ui.components.LiquidGlassBox
import com.example.nysa.ui.components.underlayBlur
import com.example.nysa.ui.detail.DetailScreen
import com.example.nysa.ui.glass.BackdropState
import com.example.nysa.ui.glass.GlassTier
import com.example.nysa.ui.glass.backdropRecorder
import com.example.nysa.ui.glass.currentGlassTier
import com.example.nysa.ui.glass.rememberBackdropState
import com.example.nysa.ui.home.HomeScreen
import com.example.nysa.ui.home.HomeViewModel
import com.example.nysa.ui.library.LibraryScreen
import com.example.nysa.ui.me.MeScreen
import com.example.nysa.ui.search.SearchScreen
import com.example.nysa.ui.section.SectionScreen
import com.example.nysa.ui.section.SectionViewModel
import com.example.nysa.ui.theme.LocalGlassEnabled
import com.example.nysa.ui.theme.NySaTheme
import com.example.nysa.work.NewEpisodeWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class TabSpec(val label: String, val iconRes: Int)

/** 5 tabs (approved design): Trang chủ · Phim · Anime · Kho của tôi · Tôi. */
private val TABS = listOf(
    TabSpec("Trang chủ", R.drawable.ic_home),
    TabSpec("Phim", R.drawable.ic_play_circle),
    TabSpec("Anime", R.drawable.ic_flame),
    TabSpec("Kho của tôi", R.drawable.ic_bookmark),
    TabSpec("Tôi", R.drawable.ic_smiley)
)

/**
 * The default-server rule shared by the quick-play entry points (same
 * as Detail's): the preferred version's first group when one exists,
 * else the first group in pool order; driven by the user's
 * "Ưu tiên bản dịch" setting.
 */
private fun defaultServerOrder(
    servers: List<ServerGroup>,
    priority: String = "TM"
): List<ServerGroup> {
    if (servers.size <= 1) return servers
    var idx = -1
    for (key in versionPriorityOrder(priority)) {
        val i = servers.indexOfFirst { it.langKey == key }
        if (i >= 0) {
            idx = i
            break
        }
    }
    if (idx <= 0) return servers
    return listOf(servers[idx]) + servers.filterIndexed { i, _ -> i != idx }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001
            )
        }
        // "Chỉ khi mở app" check mode: run one check per app start.
        lifecycleScope.launch {
            val app = application as NySaApp
            if (app.settings.flow.first().checkInterval == "ON_OPEN") {
                androidx.work.WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                    "nysa_check_on_open",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<NewEpisodeWorker>().build()
                )
            }
        }
        setContent {
            val app = application as NySaApp
            val settings by app.settings.flow.collectAsState(initial = AppSettings())
            NySaTheme(glass = settings.liquidGlass) {
                Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    NySaShell()
                }
            }
        }
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // In-app update installs (1.0.7) may only be LAUNCHED while
        // the app is foreground; a download that finished in the
        // background gets its single install offer here.
        UpdateDownloader.appInForeground = true
        UpdateDownloader.onForeground(this)
    }

    override fun onPause() {
        UpdateDownloader.appInForeground = false
        super.onPause()
    }

    /**
     * Deep link from the app-update system notification (1.0.7): the
     * worker no longer points ACTION_VIEW at the browser — tapping it
     * lands here and the download runs inside the app. Version/URL
     * come from the intent extras the worker attached, healed from
     * the stored AvailableUpdate when an extra is missing.
     */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getStringExtra(UpdateDownloader.EXTRA_AUTO_UPDATE) != "1") return
        val extraVersion =
            intent.getStringExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_VERSION).orEmpty()
        val extraUrl =
            intent.getStringExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_URL).orEmpty()
        // Consume the extras so a recreate never re-triggers.
        intent.removeExtra(UpdateDownloader.EXTRA_AUTO_UPDATE)
        intent.removeExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_VERSION)
        intent.removeExtra(UpdateDownloader.EXTRA_AUTO_UPDATE_URL)
        lifecycleScope.launch {
            val app = application as NySaApp
            val stored = app.settings.availableUpdate.first()
            val version = extraVersion.ifBlank { stored.version }
            val url = extraUrl.ifBlank { stored.apkUrl }
            if (version.isNotBlank()) {
                UpdateDownloader.start(this@MainActivity, version, url)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Fullscreen shell: both system bars hidden, immersive-sticky, content in the cutout area. */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun NySaShell() {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val glass = LocalGlassEnabled.current
    var selected by remember { mutableIntStateOf(0) }
    var detailItem by remember { mutableStateOf<TitleItem?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    val shellScope = rememberCoroutineScope()

    // Real glass (1.0.5-glass): the tab content is recorded into one
    // shared backdrop layer that the floating tab bar + banner sample.
    // Active ONLY while the glass toggle is on AND the device reaches
    // the BLUR tier (API 31+); glass off / older devices keep the
    // exact previous behaviour — no recording, no effects.
    val backdropState = rememberBackdropState()
    val useRealGlass = glass && currentGlassTier() != GlassTier.STATIC

    // Tab ViewModels live in the ACTIVITY's ViewModelStore — they
    // survive tab switches untouched, which (with the
    // SaveableStateHolder below restoring scroll) is the whole
    // "chuyển tab không load lại" fix from verdict 1.3.14.
    val homeVm: HomeViewModel = viewModel(
        key = "home",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(app) as T
            }
        }
    )
    val phimVm: SectionViewModel = viewModel(
        key = "section-phim",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SectionViewModel(app, Family.PHIM) as T
            }
        }
    )
    val animeVm: SectionViewModel = viewModel(
        key = "section-anime",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SectionViewModel(app, Family.ANIME) as T
            }
        }
    )

    val openTitle: (TitleItem) -> Unit = { item ->
        // The player resolves titles from the local store — persist
        // the tapped item before anything opens it.
        shellScope.launch { app.database.titleDao().upsert(item.toEntity()) }
        detailItem = item
    }

    val playVideo: (TitleItem, EpisodeItem, ServerGroup) -> Unit = { item, episode, _ ->
        shellScope.launch { app.database.titleDao().upsert(item.toEntity()) }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE_ID, item.id)
            putExtra(PlayerActivity.EXTRA_EPISODE_SLUG, episode.slug)
            putExtra(PlayerActivity.EXTRA_EPISODE_LABEL, episode.label)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, episode.number)
        }
        context.startActivity(intent)
    }

    val resumeFromState: (TitleItem, UserStateEntity) -> Unit = { item, st ->
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE_ID, item.id)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, st.lastUnitNumber)
            putExtra(PlayerActivity.EXTRA_EPISODE_LABEL, st.lastUnitLabel)
        }
        context.startActivity(intent)
    }

    val resumeByItem: (TitleItem) -> Unit = { item ->
        shellScope.launch {
            val st = app.database.userStateDao().get(item.id)
            if (st != null && st.lastUnitNumber > 0f) resumeFromState(item, st)
            else openTitle(item)
        }
    }

    // Overlays first: detail above search, search above the tabs.
    when {
        detailItem != null -> {
            BackHandler { detailItem = null }
            Box(modifier = Modifier.fillMaxSize()) {
                // The shell stays mounted underneath (state kept) and,
                // in glass mode, is blurred ONCE as a static layer —
                // the perf gate allows blur only here, never per item.
                Box(modifier = Modifier.fillMaxSize().underlayBlur(glass)) {
                    TabContent(
                        selected = selected,
                        homeVm = homeVm, phimVm = phimVm, animeVm = animeVm,
                        onOpenTitle = openTitle,
                        onFamilyChip = { fam ->
                            selected = if (fam == Family.PHIM) 1 else 2
                        },
                        onResume = resumeByItem,
                        onGoSearch = { searchOpen = true }
                    )
                }
                DetailScreen(
                    item = detailItem!!,
                    onBack = { detailItem = null },
                    onPlay = playVideo,
                    onOpenTitle = openTitle
                )
            }
            return
        }
        searchOpen -> {
            BackHandler { searchOpen = false }
            SearchScreen(
                onOpenTitle = openTitle,
                onClose = { searchOpen = false }
            )
            return
        }
    }

    BackHandler(enabled = selected != 0) { selected = 0 }

    Box(modifier = Modifier.fillMaxSize()) {
        // The recorded subtree: tab content ONLY. The glass surfaces
        // below are siblings OUTSIDE it — a surface must never sample
        // a layer that contains itself.
        Box(
            modifier = Modifier.fillMaxSize()
                .then(if (useRealGlass) Modifier.backdropRecorder(backdropState) else Modifier)
        ) {
            TabContent(
                selected = selected,
                homeVm = homeVm, phimVm = phimVm, animeVm = animeVm,
                onOpenTitle = openTitle,
                onFamilyChip = { fam -> selected = if (fam == Family.PHIM) 1 else 2 },
                onResume = resumeByItem,
                onGoSearch = { searchOpen = true }
            )
        }

        // Floating glass tab pill (approved design). In plain mode it
        // is the same pill in flat dark gray — layout never changes.
        // With a backdrop it is REAL glass: the content scrolling
        // underneath blurs through it (API 31+, refraction on 33+).
        LiquidGlassBox(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            shape = RoundedCornerShape(50),
            backdrop = if (useRealGlass) backdropState else null,
            backdropSlot = "tabbar"
        ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { index, tab ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) {
                                if (glass) Color.White.copy(alpha = 0.20f)
                                else Color.White
                            } else Color.Transparent
                        )
                        .clickable { selected = index }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = tab.label,
                        tint = if (isSelected) {
                            if (glass) Color.White else Color.Black
                        } else Color(0xFF9A9AA3),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        tab.label,
                        color = if (isSelected) {
                            if (glass) Color.White else Color.Black
                        } else Color(0xFF9A9AA3),
                        fontSize = 9.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        }

        // Liquid new-episode banner (approved design): drops from the
        // top edge with a springy melt, names the titles — no counts.
        val newEvents by app.newEpisodeEvents.collectAsState()

        // App-update banner (1.0.6): the background worker stores any
        // newer release in SettingsStore. The banner shows once per
        // version per session (auto-hide is session-only, like the
        // episode banner consuming its event); × is "Để sau" and is
        // persisted for THAT version only — a newer release announces
        // again. When both banners are pending the episode banner
        // takes the slot: it is time-sensitive, the update state
        // persists and gets its turn right after.
        val availableUpdate by app.settings.availableUpdate
            .collectAsState(initial = AvailableUpdate())
        val dismissedUpdate by app.settings.updateDismissedVersion
            .collectAsState(initial = "")
        val currentVersion = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: ""
        }
        var updateBannerHidden by remember { mutableStateOf(false) }
        LaunchedEffect(availableUpdate.version) { updateBannerHidden = false }
        val showUpdate = availableUpdate.version.isNotBlank() &&
            availableUpdate.version != dismissedUpdate &&
            !updateBannerHidden &&
            UpdateChecker.compareVersions(availableUpdate.version, currentVersion) > 0

        // In-app download state (1.0.7): the banner's action label
        // narrates the download of the version it announces.
        val dlState by UpdateDownloader.state.collectAsState()
        val dl = dlState
        val dlForBanner = when (dl) {
            is UpdateDownloader.State.Downloading ->
                dl.version == availableUpdate.version
            is UpdateDownloader.State.Ready ->
                dl.version == availableUpdate.version
            is UpdateDownloader.State.AwaitingInstallPermission ->
                dl.version == availableUpdate.version
            is UpdateDownloader.State.Failed ->
                dl.version == availableUpdate.version
            else -> false
        }
        val dlBannerLabel = when {
            dl is UpdateDownloader.State.Downloading && dlForBanner ->
                if (dl.progressPercent >= 0) "Đang tải… ${dl.progressPercent}%"
                else "Đang tải…"
            dl is UpdateDownloader.State.Ready && dlForBanner -> "Cài đặt"
            dl is UpdateDownloader.State.AwaitingInstallPermission && dlForBanner ->
                "Cài đặt"
            dl is UpdateDownloader.State.Failed && dlForBanner -> "Thử lại"
            else -> "Tải"
        }

        if (newEvents.isNotEmpty()) {
            LiquidNewEpisodeBanner(
                names = newEvents.map { it.second },
                onOpen = {
                    val firstId = newEvents.firstOrNull()?.first
                    app.newEpisodeEvents.value = emptyList()
                    if (firstId != null) {
                        shellScope.launch {
                            app.database.titleDao().get(firstId)
                                ?.let { openTitle(it.toItem()) }
                        }
                    }
                },
                onDismiss = { app.newEpisodeEvents.value = emptyList() },
                modifier = Modifier.align(Alignment.TopCenter),
                backdrop = if (useRealGlass) backdropState else null
            )
        } else if (showUpdate) {
            LiquidUpdateBanner(
                version = availableUpdate.version,
                actionLabel = dlBannerLabel,
                onDownload = {
                    // 1.0.7: download + install in-app; the banner
                    // stays up so its label can narrate progress
                    // (start() itself is a no-op mid-download).
                    UpdateDownloader.start(
                        context, availableUpdate.version, availableUpdate.apkUrl
                    )
                },
                onLater = {
                    updateBannerHidden = true
                    shellScope.launch {
                        app.settings.setUpdateDismissedVersion(availableUpdate.version)
                    }
                },
                onHide = { updateBannerHidden = true },
                modifier = Modifier.align(Alignment.TopCenter),
                backdrop = if (useRealGlass) backdropState else null
            )
        }
    }
}

@Composable
private fun TabContent(
    selected: Int,
    homeVm: HomeViewModel,
    phimVm: SectionViewModel,
    animeVm: SectionViewModel,
    onOpenTitle: (TitleItem) -> Unit,
    onFamilyChip: (Family) -> Unit,
    onResume: (TitleItem) -> Unit,
    onGoSearch: () -> Unit
) {
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(key = "tab-$selected") {
        when (selected) {
            0 -> HomeScreen(
                viewModel = homeVm,
                onOpenTitle = onOpenTitle,
                onFamilyChip = onFamilyChip,
                onResume = onResume,
                onGoSearch = onGoSearch
            )
            1 -> SectionScreen(viewModel = phimVm, onOpenTitle = onOpenTitle)
            2 -> SectionScreen(viewModel = animeVm, onOpenTitle = onOpenTitle)
            3 -> LibraryScreen(onOpenTitle = onOpenTitle, onResume = onResume)
            else -> MeScreen()
        }
    }
}

/**
 * The "chảy kính lỏng" banner, as far as Compose springs honestly
 * go: the pill drops from the top edge on a low-damping spring (it
 * overshoots and settles like a droplet), its lower corners stretch
 * while it travels and round out as it lands. Static glass — no
 * touch-follow effects (the user removed those from the design).
 */
@Composable
private fun LiquidNewEpisodeBanner(
    names: List<String>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null
) {
    val glass = LocalGlassEnabled.current
    val drop = remember { Animatable(-1.15f) }
    LaunchedEffect(names) {
        drop.animateTo(
            0f,
            spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMediumLow)
        )
    }
    LaunchedEffect(names) {
        delay(5_600)
        onDismiss()
    }
    val progress = drop.value // -1.15 (hidden above) .. 0 (landed), overshoots > 0 while settling
    val stretch = (1f - progress.coerceIn(-1f, 0.3f)).coerceIn(0.7f, 2f)
    val bottomRadius = (26f * stretch).dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .offset { IntOffset(0, (progress * 120.dp.toPx()).toInt()) }
            .graphicsLayer {
                scaleY = 1f + (stretch - 1f) * 0.10f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .clickable(onClick = onOpen)
    ) {
      LiquidGlassBox(
          shape = RoundedCornerShape(26.dp, 26.dp, bottomRadius, bottomRadius),
          backdrop = backdrop,
          backdropSlot = "banner"
      ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bell),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Có tập mới",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    names.take(2).joinToString(" · ") +
                        if (names.size > 2) " và ${names.size - 2} bộ khác" else "",
                    color = Color(0xFFD8D8DE),
                    fontSize = 12.5.sp,
                    maxLines = 1
                )
            }
            Text(
                "Xem",
                color = if (glass) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (glass) 0.18f else 0.9f))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Đóng",
                tint = Color(0xFF9A9AA3),
                modifier = Modifier.size(17.dp).clickable(onClick = onDismiss)
            )
        }
      }
    }
}

/**
 * The app-update banner (1.0.6): the same liquid drop as the
 * new-episode banner above — same spring, same melt, same 5.6 s
 * auto-hide — announcing a newer release the background worker found.
 * The action button downloads + installs IN-APP (1.0.7) and its
 * label follows the download state via [actionLabel]; × is "Để sau"
 * (persisted per version by the caller); the auto-hide is
 * session-only via [onHide].
 */
@Composable
private fun LiquidUpdateBanner(
    version: String,
    actionLabel: String = "Tải",
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null
) {
    val glass = LocalGlassEnabled.current
    val drop = remember { Animatable(-1.15f) }
    LaunchedEffect(version) {
        drop.animateTo(
            0f,
            spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMediumLow)
        )
    }
    LaunchedEffect(version) {
        delay(5_600)
        onHide()
    }
    val progress = drop.value // -1.15 (hidden above) .. 0 (landed), overshoots > 0 while settling
    val stretch = (1f - progress.coerceIn(-1f, 0.3f)).coerceIn(0.7f, 2f)
    val bottomRadius = (26f * stretch).dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .offset { IntOffset(0, (progress * 120.dp.toPx()).toInt()) }
            .graphicsLayer {
                scaleY = 1f + (stretch - 1f) * 0.10f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .clickable(onClick = onDownload)
    ) {
      LiquidGlassBox(
          shape = RoundedCornerShape(26.dp, 26.dp, bottomRadius, bottomRadius),
          backdrop = backdrop,
          backdropSlot = "banner"
      ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_bell),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Có bản mới $version",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Bản cập nhật Aura đã sẵn sàng",
                    color = Color(0xFFD8D8DE),
                    fontSize = 12.5.sp,
                    maxLines = 1
                )
            }
            Text(
                actionLabel,
                color = if (glass) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (glass) 0.18f else 0.9f))
                    .clickable(onClick = onDownload)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Để sau",
                tint = Color(0xFF9A9AA3),
                modifier = Modifier.size(17.dp).clickable(onClick = onLater)
            )
        }
      }
    }
}
