package com.example.nysa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.data.AppSettings
import com.example.nysa.player.PlayerUiState
import com.example.nysa.ui.components.playerGlassSurface
import com.example.nysa.ui.theme.LocalGlassEnabled
import com.example.nysa.ui.theme.NySaGreen
import com.example.nysa.ui.theme.NySaTextMuted
import com.example.nysa.ui.theme.NySaViolet

private fun fmtT(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * C5 (K11): custom seek bar with the design's 3 layers — remaining (dark
 * grey) at the back, buffered (light grey) in the middle, played (violet)
 * on top, plus the thumb. A tap or drag scrubs: onScrub fires while the
 * finger moves, onScrubFinished once on release (same contract the old
 * Material Slider gave the activity). Without buffered data the middle
 * layer is simply empty and the bar behaves exactly as before.
 */
@Composable
private fun BufferedSeekBar(
    fraction: Float,
    bufferedFraction: Float,
    durationMs: Long,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassEnabled.current
    // Fraction under the finger while scrubbing; null at rest. Drives
    // the time bubble below.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        scrubFraction = f
                        onScrub(f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubFraction = f
                        onScrub(f)
                    },
                    onDragEnd = { scrubFraction = null; onScrubFinished() },
                    onDragCancel = { scrubFraction = null; onScrubFinished() }
                )
            }
    ) {
        val trackShape = RoundedCornerShape(50)
        // Remaining.
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp)
                .align(Alignment.Center)
                .clip(trackShape)
                .background(Color(0xFF4A4A58))
        )
        // Buffered (never behind the played head visually — it starts at 0
        // and the played layer paints over its own span).
        if (bufferedFraction > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(bufferedFraction).height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(trackShape)
                    .background(Color(0xFF9A9AA8))
            )
        }
        // Played.
        if (fraction > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction).height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(trackShape)
                    .background(NySaViolet)
            )
        }
        // Thumb.
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - 14.dp) * fraction)
                .size(14.dp)
                .align(Alignment.CenterStart)
                .background(NySaViolet, CircleShape)
        )
        // Scrub time bubble (user order 2026-11-06): while dragging,
        // float the target time above the thumb so he can see exactly
        // where he is scrubbing to.
        scrubFraction?.let { f ->
            if (durationMs > 0) {
                val bubbleW = 64.dp
                val thumbCenter = (maxWidth - 14.dp) * f + 7.dp
                val bubbleX = (thumbCenter - bubbleW / 2).coerceIn(0.dp, maxWidth - bubbleW)
                Box(
                    modifier = Modifier
                        .offset(x = bubbleX, y = (-18).dp)
                        .width(bubbleW)
                        .playerGlassSurface(RoundedCornerShape(50), glass)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        fmtT((f * durationMs).toLong()),
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    isPlaying: Boolean,
    ended: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0,
    speed: Float,
    qualityLabel: String,
    statusText: String,
    statusDot: Color,
    settings: AppSettings,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekFwd: () -> Unit,
    onLock: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    nextLabel: String,
    introEndMs: Long,
    onSkipIntro: () -> Unit,
    onShowServers: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowQuality: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowQuickSettings: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit
) {
    val glass = LocalGlassEnabled.current
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // 1.3.14: the intro boundary is per title (learned), not a
    // universal 85 s — see PlayerActivity's introEndMs.
    val showSkipIntro = settings.skipIntroOutro && durationMs > 360_000 && positionMs < introEndMs
    val showSkipOutro = settings.skipIntroOutro && durationMs > 0 &&
        durationMs - positionMs in 1..90_000 && positionMs >= introEndMs

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlCircle(R.drawable.ic_back, "Quay lại", onBack)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.item?.name ?: "",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val versionText = when (state.item?.versionBadge) {
                    "TM" -> "Thuyết minh"
                    "VS" -> "Vietsub"
                    else -> ""
                }
                Text(
                    buildAnnotatedString {
                        append("${state.episode?.label ?: ""}")
                        if (versionText.isNotBlank()) append(" · $versionText")
                        append(" · ")
                        withStyle(SpanStyle(color = statusDot)) { append("●") }
                        append(" $statusText")
                        // 1.3.3b: measured by HlsAdFilter on THIS
                        // stream's playlist — shown only when ad
                        // segments were really removed.
                        if (state.adCutSeconds > 0) {
                            append(" · ")
                            withStyle(SpanStyle(color = NySaGreen)) {
                                append("Đã cắt quảng cáo: ~${state.adCutSeconds}s")
                            }
                        }
                    },
                    color = NySaTextMuted,
                    fontSize = 12.sp
                )
            }
            Row(
                modifier = Modifier
                    .playerGlassSurface(RoundedCornerShape(50), glass)
                    .clickable(onClick = onShowServers)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Đổi server", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            ControlCircle(R.drawable.ic_gear, "Cài đặt phát", onShowQuickSettings)
        }

        // Center cluster
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(42.dp)
                    .playerGlassSurface(CircleShape, glass)
                    .clickable(onClick = onLock),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = "Khoá điều khiển",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(34.dp)
            ) {
                SeekButton(R.drawable.ic_seek_back, "Tua lùi", onSeekBack)
                Box(
                    modifier = Modifier.size(76.dp)
                        .playerGlassSurface(CircleShape, glass)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying && !ended) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(width = 7.dp, height = 24.dp).background(Color.White, RoundedCornerShape(2.dp)))
                            Box(Modifier.size(width = 7.dp, height = 24.dp).background(Color.White, RoundedCornerShape(2.dp)))
                        }
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = if (ended) "Xem lại" else "Phát",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                SeekButton(R.drawable.ic_seek_fwd, "Tua tới", onSeekFwd)
            }
        }

        // Skip intro/outro pill
        Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            if (showSkipIntro || showSkipOutro) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .playerGlassSurface(RoundedCornerShape(50), glass)
                        .clickable(onClick = onSkipIntro)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (showSkipIntro) "Bỏ qua mở đầu ⏩" else "Bỏ qua kết thúc ⏩",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Seek bar — C5 (K11): three layers per the design (Màn 10):
        // played violet · buffered light grey · remaining dark grey.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(fmtT(positionMs), color = Color.White, fontSize = 12.sp)
            BufferedSeekBar(
                fraction = fraction,
                durationMs = durationMs,
                bufferedFraction = if (durationMs > 0) {
                    (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Text(fmtT(durationMs), color = NySaTextMuted, fontSize = 12.sp)
        }

        // Bottom bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBtn(
                iconRes = R.drawable.ic_prev,
                text = "Tập trước",
                enabled = hasPrev,
                onClick = onPrev
            )
            Spacer(Modifier.width(6.dp))
            BottomBtn(
                iconRes = R.drawable.ic_next,
                text = "Tập sau",
                enabled = hasNext,
                onClick = onNext
            )
            if (hasNext) {
                Spacer(Modifier.width(8.dp))
                Text("Tiếp theo: $nextLabel", color = NySaTextMuted, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${speed}x".replace(".0x", ".0x"),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .playerGlassSurface(RoundedCornerShape(50), glass)
                    .clickable(onClick = onShowSpeed)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .playerGlassSurface(RoundedCornerShape(50), glass)
                    .clickable(onClick = onShowQuality)
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(qualityLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier
                    .playerGlassSurface(RoundedCornerShape(50), glass)
                    .clickable(onClick = onShowEpisodes)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Tập", color = Color.White, fontSize = 13.sp)
            }
            if (settings.pipEnabled) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "PiP",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .playerGlassSurface(RoundedCornerShape(50), glass)
                        .clickable(onClick = onPip)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_full),
                contentDescription = "Toàn màn hình",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onFullscreen)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun ControlCircle(iconRes: Int, desc: String, onClick: () -> Unit) {
    val glass = LocalGlassEnabled.current
    Box(
        modifier = Modifier.size(38.dp)
            .playerGlassSurface(CircleShape, glass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = desc,
            tint = Color.White,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun SeekButton(iconRes: Int, desc: String, onClick: () -> Unit) {
    val glass = LocalGlassEnabled.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(54.dp)
                .playerGlassSurface(CircleShape, glass)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = desc,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Text("10 giây", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BottomBtn(iconRes: Int, text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) Color.White else NySaTextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            color = if (enabled) Color.White else NySaTextMuted,
            fontSize = 13.sp
        )
    }
}

// ---------- Sheets ----------
