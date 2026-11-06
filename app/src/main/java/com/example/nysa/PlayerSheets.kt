package com.example.nysa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.data.AppSettings
import com.example.nysa.data.SettingsStore
import com.example.nysa.data.model.EpisodeItem
import com.example.nysa.data.model.ServerGroup
import com.example.nysa.ui.components.FilterChips
import com.example.nysa.ui.components.GlassSwitch
import com.example.nysa.ui.components.playerGlassSurface
import com.example.nysa.ui.theme.LocalGlassEnabled
import com.example.nysa.ui.detail.fmtNum
import com.example.nysa.data.ServerStatus
import com.example.nysa.ui.theme.NySaAmber
import com.example.nysa.ui.theme.NySaGreen
import com.example.nysa.ui.theme.NySaRed
import com.example.nysa.ui.theme.NySaSurface
import com.example.nysa.ui.theme.NySaTextMuted
import com.example.nysa.ui.theme.NySaViolet
import com.example.nysa.ui.theme.NySaVioletSoft
import kotlinx.coroutines.launch

private fun qLabel(height: Int): String = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "FHD"
    height >= 700 -> "HD"
    height > 0 -> "${height}p"
    else -> "FHD"
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Player option sheets (approved design): in the landscape player the
 * options open as a LIQUID GLASS PANEL anchored to the RIGHT edge —
 * quality / subtitles / speed / display mode / source / episodes all
 * live here. The backdrop is a dark scrim, NOT a blur: the video is a
 * SurfaceView, whose pixels a Compose backdrop blur cannot touch
 * (the detail screen, pure Compose, gets the real underlay blur).
 * Static glass — no touch-follow effects (user removed them).
 */
@Composable
internal fun PlayerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val glass = LocalGlassEnabled.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
        )
        Box(
            modifier = Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(330.dp)
                // Dark player glass, NOT the shell's white-tint
                // glassSurface: the sheet floats over video, so it
                // follows the player surface family (1.0.5-glass —
                // keeps later shell-glass changes from leaking in).
                .playerGlassSurface(
                    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    glass
                )
                .padding(top = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun ServerSheet(
    servers: List<ServerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    serverStatuses: Map<Int, ServerStatus> = emptyMap()
) {
    PlayerSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Chọn server", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            servers.forEachIndexed { index, server ->
                val srvStatus = serverStatuses[index]
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (index == selectedIndex) NySaVioletSoft else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            when (srvStatus?.state) {
                                ServerStatus.State.OK -> NySaGreen
                                ServerStatus.State.SLOW -> NySaAmber
                                ServerStatus.State.ERROR -> NySaRed
                                else -> NySaTextMuted
                            },
                            CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${server.episodes.size} tập · " +
                                (srvStatus?.describe() ?: "Chưa kiểm tra"),
                            color = NySaTextMuted, fontSize = 12.sp
                        )
                    }
                    if (index == selectedIndex) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Đang chọn",
                            tint = NySaViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    PlayerSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Tốc độ phát", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (s == current) NySaVioletSoft else Color.Transparent)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${s}x", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (s == current) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Đang chọn",
                            tint = NySaViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualitySheet(
    heights: List<Int>,
    currentCap: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    PlayerSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Chất lượng", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            QualityRow(
                label = "Cao nhất",
                sub = "Luôn lấy bản nét nhất nguồn có",
                selected = currentCap <= 0,
                onClick = { onSelect(-1) }
            )
            heights.sortedDescending().forEach { h ->
                QualityRow(
                    label = qLabel(h),
                    sub = "${h}p",
                    selected = currentCap == h,
                    onClick = { onSelect(h) }
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QualityRow(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) NySaVioletSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = NySaTextMuted, fontSize = 12.sp)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "Đang chọn",
                tint = NySaViolet,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodesSheet(
    episodes: List<EpisodeItem>,
    currentSlug: String,
    isWatched: (Float) -> Boolean,
    onSelect: (EpisodeItem) -> Unit,
    onDismiss: () -> Unit
) {
    var rangeIndex by remember { mutableIntStateOf(0) }
    val sorted = remember(episodes) { episodes.sortedBy { it.number } }
    val ranges = remember(sorted) {
        if (sorted.size <= 100) listOf("" to sorted)
        else sorted.chunked(100).map {
            "${fmtNum(it.first().number)}–${fmtNum(it.last().number)}" to it
        }
    }
    val visible = ranges.getOrNull(rangeIndex)?.second ?: sorted
    PlayerSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Text(
                "Danh sách tập · ${episodes.size} tập",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (ranges.size > 1) {
                Spacer(Modifier.height(10.dp))
                FilterChips(
                    labels = ranges.map { it.first },
                    selectedIndex = rangeIndex,
                    onSelect = { rangeIndex = it }
                )
            }
            val rows = (visible.size + 4) / 5
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.padding(top = 12.dp)
                    .height((rows * 50 + (rows - 1) * 8).dp.coerceAtMost(420.dp)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.slug }) { ep ->
                    val isCurrent = ep.slug == currentSlug
                    Box(
                        modifier = Modifier.height(50.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) NySaViolet else NySaSurface)
                            .clickable { onSelect(ep) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmtNum(ep.number),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        if (isWatched(ep.number)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_green),
                                contentDescription = "Đã xem",
                                tint = Color.Unspecified,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(11.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickSettingsSheet(
    settings: AppSettings,
    onShowQuality: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowResize: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AuraApp
    val scope = rememberCoroutineScope()
    // 1.3.9: open FULLY expanded and let the content scroll. In
    // 1.3.8 the sheet opened partially in landscape and its plain
    // column overflowed the sheet's max height, so the last row
    // ("Kích thước hiển thị") sat half-clipped at the bottom edge —
    // unreachable and untappable (user verdict 1.3.8: "Vướng không
    // kéo lên được... ấn cái nào màn vẫn y nguyên").
    PlayerSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Cài đặt phát", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            QuickRow("Chất lượng", "Theo nguồn đang phát") { onShowQuality() }
            QuickRow("Tốc độ", "Đổi tốc độ phát") { onShowSpeed() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bỏ qua mở đầu / kết thúc", color = Color.White, fontSize = 14.sp)
                    Text("Tự hiện nút bỏ qua ở đoạn intro, outro", color = NySaTextMuted, fontSize = 12.sp)
                }
                GlassSwitch(
                    checked = settings.skipIntroOutro,
                    onCheckedChange = { on ->
                        scope.launch { app.settings.set(SettingsStore.SKIP_INTRO, on) }
                    }
                )
            }
            // 1.3.8: display size is a chevron row like Quality/Speed
            // and opens its OWN sheet — in 1.3.7 the three options
            // were inline at the bottom of this sheet, whose column
            // does not scroll, so they sat below the fold and could
            // not be tapped (user verdict 1.3.7: "có chứ không dùng
            // được").
            QuickRow(
                "Kích thước hiển thị",
                when (settings.playerResize) {
                    "FULL" -> "Lấp đầy"
                    "FULLSUB" -> "Lấp đầy (giữ phụ đề)"
                    "STRETCH" -> "Kéo giãn"
                    else -> "Vừa màn hình"
                }
            ) { onShowResize() }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 1.3.8: the display-size picker as its own sheet (Netflix-style),
 * opened from the QuickSettings chevron row. Picking a size writes
 * the persisted setting — the player applies it live — and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResizeSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 1.3.10: the pick is reported to the HOST (PlayerActivity),
    // which writes the setting on the player screen's scope. The
    // 1.3.8/1.3.9 version launched the DataStore write on THIS
    // sheet's own rememberCoroutineScope and dismissed immediately —
    // disposing the sheet cancelled the scope mid-write, so picks
    // kept losing the race and the stored mode stayed whatever it
    // already was (user verdict 1.3.9: the picker "keeps defaulting
    // to Kéo giãn" no matter what he chose).
    fun pick(mode: String) {
        onPick(mode)
        onDismiss()
    }
    // 1.3.9: same reachability guard as QuickSettingsSheet — fully
    // expanded + scrollable content.
    PlayerSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Kích thước hiển thị", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            QualityRow("Vừa màn hình", "Giữ nguyên tỉ lệ gốc, có thể có viền đen", current == "FIT") {
                pick("FIT")
            }
            QualityRow("Lấp đầy", "Phóng to cho đầy màn hình, cắt bớt hai cạnh", current == "FULL") {
                pick("FULL")
            }
            QualityRow("Lấp đầy (giữ phụ đề)", "Phóng đều đầy màn hình, giữ nguyên đáy hình có phụ đề, cắt bớt phía trên", current == "FULLSUB") {
                pick("FULLSUB")
            }
            QualityRow("Kéo giãn", "Giãn hình cho đầy màn hình, hình bị méo", current == "STRETCH") {
                pick("STRETCH")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QuickRow(label: String, sub: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(sub, color = NySaTextMuted, fontSize = 12.sp)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chev_r),
            contentDescription = null,
            tint = NySaTextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}
