package com.example.nysa.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nysa.NySaApp
import com.example.nysa.R
import com.example.nysa.data.ServerStatus
import com.example.nysa.data.model.EpisodeItem
import com.example.nysa.data.model.ServerGroup
import com.example.nysa.data.model.TitleItem
import com.example.nysa.ui.components.GlassChip
import com.example.nysa.ui.components.NoteText
import com.example.nysa.ui.components.PosterImage
import com.example.nysa.ui.components.ShelfHeader
import com.example.nysa.ui.components.TitlePosterCard
import com.example.nysa.ui.theme.LocalGlassEnabled
import com.example.nysa.ui.theme.NySaGreen
import com.example.nysa.ui.theme.NySaRed
import com.example.nysa.ui.theme.NySaAmber
import com.example.nysa.ui.theme.NySaTextMuted

/**
 * Trang chi tiết: backdrop + info, source picker with MEASURED status
 * dots, episode grid, related titles. The shell behind this overlay is
 * blurred by MainActivity (one static underlay layer) and this screen
 * adds its own dark scrim, so text never fights the content behind
 * (user order 2026-11-04) — while the glass elements stay clear.
 */
@Composable
fun DetailScreen(
    item: TitleItem,
    onBack: () -> Unit,
    onPlay: (TitleItem, EpisodeItem, ServerGroup) -> Unit,
    onOpenTitle: (TitleItem) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val vm: DetailViewModel = viewModel(
        key = "detail-${item.id}",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DetailViewModel(app, item) as T
            }
        }
    )
    val state by vm.state.collectAsState()
    BackHandler(onBack = onBack)
    val glass = LocalGlassEnabled.current

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xE607070B))
    ) {
        when {
            state.loading && state.detail == null -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.detail == null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    NoteText(state.error.ifBlank { "Không tải được." })
                    Spacer(Modifier.height(12.dp))
                    GlassChip(label = "Thử lại", selected = true, onClick = { vm.load() })
                }
            }
            else -> {
                val detail = state.detail!!
                val dItem = detail.item
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    // Backdrop
                    Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
                        PosterImage(
                            url = dItem.thumbUrl.ifBlank { dItem.posterUrl },
                            genreLabel = dItem.genres.firstOrNull() ?: "",
                            titleName = dItem.name,
                            widthDp = 400.dp,
                            heightDp = 230.dp,
                            modifier = Modifier.fillMaxSize(),
                            fallbackUrl = dItem.posterUrl
                        )
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF07070B).copy(alpha = 0.65f),
                                        Color(0xFF07070B)
                                    )
                                )
                            )
                        )
                        Box(
                            modifier = Modifier
                                .padding(14.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
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
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row {
                            Box(
                                modifier = Modifier.width(104.dp).height(156.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                PosterImage(
                                    url = dItem.posterUrl,
                                    genreLabel = dItem.genres.firstOrNull() ?: "",
                                    titleName = dItem.name,
                                    widthDp = 104.dp,
                                    heightDp = 156.dp,
                                    modifier = Modifier.fillMaxSize(),
                                    fallbackUrl = dItem.thumbUrl
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    dItem.name,
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Black
                                )
                                if (dItem.originName.isNotBlank()) {
                                    Text(
                                        dItem.originName,
                                        color = NySaTextMuted,
                                        fontSize = 12.5.sp,
                                        maxLines = 1
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                val meta = listOfNotNull(
                                    dItem.year.takeIf { it > 0 }?.toString(),
                                    detail.status.ifBlank { null },
                                    detail.durationLabel.ifBlank { null },
                                    dItem.quality.ifBlank { null }
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, color = NySaTextMuted, fontSize = 12.5.sp)
                                }
                                if (dItem.genres.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        dItem.genres.joinToString(" · "),
                                        color = NySaTextMuted,
                                        fontSize = 12.5.sp
                                    )
                                }
                                if (detail.totalCount > 0) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Tổng ${detail.totalCount} tập",
                                        color = NySaTextMuted,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        // Play + actions (NO download — removed by user order)
                        val firstServer = detail.servers.firstOrNull()
                        val firstEp = firstServer?.episodes?.firstOrNull()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White)
                                    .clickable(enabled = firstEp != null && firstServer != null) {
                                        if (firstEp != null && firstServer != null) {
                                            onPlay(dItem, firstEp, firstServer)
                                        }
                                    }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (firstEp != null) "▶  Phát · ${firstEp.label}" else "Chưa có tập",
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val st = state.userState
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassChip(
                                label = if (st?.favorite == true) "♥ Đã thích" else "♡ Yêu thích",
                                selected = st?.favorite == true,
                                onClick = { vm.toggleFavorite() }
                            )
                            GlassChip(
                                label = if (st?.notifyOn == true) "🔔 Đang báo tập mới" else "Báo tập mới",
                                selected = st?.notifyOn == true,
                                onClick = { vm.toggleNotify() }
                            )
                            GlassChip(
                                label = if (st?.watchLater == true) "✓ Đã lưu" else "Xem sau",
                                selected = st?.watchLater == true,
                                onClick = { vm.toggleWatchLater() }
                            )
                        }

                        if (detail.description.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(detail.description, color = Color(0xFFD8D8DE), fontSize = 13.5.sp)
                        }
                        if (detail.actors.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Diễn viên: ${detail.actors.take(8).joinToString(", ")}",
                                color = NySaTextMuted, fontSize = 12.5.sp
                            )
                        }
                        if (detail.loadNote.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            NoteText(detail.loadNote)
                        }

                        // Source picker — labels verbatim, dots MEASURED
                        if (detail.servers.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Chọn nguồn phát",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                detail.servers.forEachIndexed { idx, group ->
                                    ServerRow(
                                        group = group,
                                        status = state.serverStatuses[idx],
                                        selected = state.selectedServer == idx,
                                        onClick = { vm.selectServer(idx) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Danh sách tập",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(10.dp))
                            val server = vm.server()
                            if (server != null) {
                                EpisodeGrid(
                                    episodes = server.episodes,
                                    rangeKey = "${dItem.id}|${server.name}",
                                    initialEpisodeNumber = state.userState?.lastUnitNumber ?: 0f,
                                    onPlay = { ep -> onPlay(dItem, ep, server) }
                                )
                            }
                        }

                        if (state.related.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            ShelfHeader("Có thể bạn cũng thích", Modifier.padding(start = 0.dp))
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(state.related, key = { it.id }) { rel ->
                                    TitlePosterCard(
                                        item = rel,
                                        onClick = { onOpenTitle(rel) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(120.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    group: ServerGroup,
    status: ServerStatus?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (dotColor, statusText) = when (status?.state) {
        ServerStatus.State.OK, ServerStatus.State.SLOW -> NySaGreen to status.describe()
        ServerStatus.State.ERROR, ServerStatus.State.UNRESPONSIVE -> NySaRed to status.describe()
        ServerStatus.State.EMBED -> NySaAmber to "phát qua web"
        else -> NySaTextMuted to "chưa kiểm tra"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) Color.White.copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${group.episodes.size} tập · $statusText",
                color = NySaTextMuted, fontSize = 12.sp
            )
        }
        if (selected) {
            Text("Đang chọn", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<com.example.nysa.data.model.EpisodeItem>,
    rangeKey: String,
    initialEpisodeNumber: Float,
    onPlay: (com.example.nysa.data.model.EpisodeItem) -> Unit
) {
    // 1.0.4: past 60 episodes the grid gets range chips (100 episodes
    // per range, in list order) and only the selected range is
    // composed — a 1.000+ episode series used to build every button
    // at once inside the scrolling Column and jank the whole page.
    // At 60 or fewer the behaviour is exactly the old full grid.
    val ranged = episodes.size > 60
    val ranges = remember(episodes) {
        if (ranged) episodes.chunked(100) else listOf(episodes)
    }
    var selectedRange by rememberSaveable(rangeKey) {
        mutableIntStateOf(
            if (ranged && initialEpisodeNumber > 0f) {
                val idx = episodes.indexOfFirst { it.number >= initialEpisodeNumber }
                if (idx >= 0) (idx / 100).coerceIn(0, ranges.size - 1) else 0
            } else {
                0
            }
        )
    }
    val shown = ranges.getOrElse(selectedRange) { ranges.first() }
    val rows = shown.chunked(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ranged) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ranges.indices.toList()) { r ->
                    val first = ranges[r].first()
                    val last = ranges[r].last()
                    val label = if (first.number > 0f || last.number > 0f) {
                        "Tập ${fmtNum(first.number)}–${fmtNum(last.number)}"
                    } else {
                        "Tập ${r * 100 + 1}–${r * 100 + ranges[r].size}"
                    }
                    GlassChip(
                        label = label,
                        selected = r == selectedRange,
                        onClick = { selectedRange = r }
                    )
                }
            }
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ep ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .clickable { onPlay(ep) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmtNum(ep.number),
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
