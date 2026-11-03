package com.example.nysa.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.data.TimeText
import com.example.nysa.data.model.TitleItem
import com.example.nysa.ui.theme.NySaTextMuted
import com.example.nysa.ui.theme.NySaVioletSoft

/**
 * The standard 3-column poster card (user-approved grid). Under the
 * title: the newest episode + relative time — the user's standing
 * format ("Tập 107 · 24 phút trước"). No "+N" counts anywhere (his
 * abolition order); the new-episode signal lives in notifications.
 */
@Composable
fun TitlePosterCard(
    item: TitleItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 108.dp
) {
    val posterHeight = cardWidth * 1.5f
    Column(
        modifier = modifier.width(cardWidth).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(posterHeight)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PosterImage(
                url = item.posterUrl,
                genreLabel = item.genres.firstOrNull() ?: "",
                titleName = item.name,
                widthDp = cardWidth,
                heightDp = posterHeight,
                modifier = Modifier.fillMaxSize()
            )
            // Episode badge (bottom-left, glass pill)
            if (item.latestLabel.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(item.latestLabel, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (item.versionBadge.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(item.versionBadge, color = Color.White, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        val meta = buildString {
            if (item.latestLabel.isNotBlank()) append(item.latestLabel)
            val rel = relativeOf(item)
            if (rel.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(rel)
            }
            if (isEmpty()) {
                if (item.year > 0) append(item.year)
                item.genres.firstOrNull()?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
        }
        if (meta.isNotBlank()) {
            Text(meta, color = NySaTextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private fun relativeOf(item: TitleItem): String =
    if (item.updatedEpochSec > 0) TimeText.relative(item.updatedEpochSec) else ""

/** Wide 16:9 card for "Đang xem dở" — carries the real progress fraction. */
@Composable
fun ContinueCard(
    item: TitleItem,
    progressFraction: Float,
    progressLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 240.dp
) {
    val thumbHeight = cardWidth * 9f / 16f
    Column(modifier = modifier.width(cardWidth).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(thumbHeight)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PosterImage(
                url = item.thumbUrl.ifBlank { item.posterUrl },
                genreLabel = item.genres.firstOrNull() ?: "",
                titleName = item.name,
                widthDp = cardWidth,
                heightDp = thumbHeight,
                modifier = Modifier.fillMaxSize()
            )
            // Progress bar (the user's violet in the old design is the
            // white accent here — near-white on a dark track).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .height(3.5.dp)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, color = Color.White, fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (progressLabel.isNotBlank()) {
            Text(progressLabel, color = NySaTextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Shelf header with an optional "see all" affordance. */
@Composable
fun ShelfHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Small muted note line used for honest empty/error states. */
@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = NySaTextMuted,
        fontSize = 13.sp,
        modifier = modifier
            .background(NySaVioletSoft, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}
