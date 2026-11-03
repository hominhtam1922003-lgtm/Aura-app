package com.example.nysa.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.nysa.ui.theme.LocalGlassEnabled
import com.example.nysa.ui.theme.NySaSurfacePlain
import com.example.nysa.ui.theme.NySaTextMuted

/**
 * The approved Liquid Glass surface.
 *
 * PERFORMANCE GATE (user order): the glass here is a static gradient
 * fill + a specular edge border — it costs one gradient draw, works on
 * every list cell, and never blurs per item. Backdrop blur is used in
 * exactly one place ([underlayBlur]): the single static layer behind
 * the detail / player sheets, and only on API 31+ where RenderEffect
 * exists. With the glass setting OFF this collapses to the plain
 * theme's flat dark-gray panel (#1C1C1F), no gradient, no border.
 */
fun Modifier.glassSurface(shape: Shape, glass: Boolean): Modifier = this
    .clip(shape)
    .then(
        if (glass) {
            Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.03f),
                        Color.White.copy(alpha = 0.06f)
                    )
                )
            ).border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.22f)
                    )
                ),
                shape = shape
            )
        } else {
            Modifier.background(NySaSurfacePlain)
        }
    )

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit
) {
    val glass = LocalGlassEnabled.current
    Box(modifier = modifier.glassSurface(shape, glass)) { content() }
}

/**
 * Blur for the CONTENT UNDER an open detail/player sheet — one static
 * underlay layer, blurred once (the sheet content itself stays sharp).
 * API < 31 has no RenderEffect: the caller still draws its dark scrim,
 * so the fallback is a plain dim, never a fake label.
 */
fun Modifier.underlayBlur(enabled: Boolean): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.graphicsLayer {
        renderEffect = android.graphics.RenderEffect
            .createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }
}

/** A small glass chip; selected state is shown by in-glass brightness + rim, never a solid fill. */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassEnabled.current
    val shape = RoundedCornerShape(50)
    val base = modifier
        .clip(shape)
        .then(
            if (glass) {
                Modifier.background(
                    if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f)
                ).border(
                    1.dp,
                    if (selected) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.28f),
                    shape
                )
            } else {
                Modifier.background(
                    if (selected) Color.White else NySaSurfacePlain
                )
            }
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp)
    Box(modifier = base) {
        Text(
            label,
            color = when {
                glass -> Color.White
                selected -> Color.Black
                else -> NySaTextMuted
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Poster image with the performance-gate rules: Coil is asked for the
 * DISPLAY size in pixels (never the CDN original decoded into a small
 * cell), and any failure (dead source art, blank URL) falls back to a
 * drawn genre poster — a cell is never empty (verdict 1.3.14: posters
 * vanished wholesale on the device).
 */
@Composable
fun PosterImage(
    url: String,
    genreLabel: String,
    titleName: String,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val wPx = with(density) { widthDp.roundToPx() }
    val hPx = with(density) { heightDp.roundToPx() }
    if (url.isBlank()) {
        LetterPoster(genreLabel, titleName, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .size(wPx, hPx)
            .build(),
        contentDescription = titleName,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { LetterPoster(genreLabel, titleName, Modifier.fillMaxSize()) },
        error = { LetterPoster(genreLabel, titleName, Modifier.fillMaxSize()) }
    )
}

/** Drawn fallback cover: genre-tinted gradient + the genre and title set as type. */
@Composable
fun LetterPoster(genreLabel: String, titleName: String, modifier: Modifier = Modifier) {
    val hueSeed = (genreLabel.hashCode() and 0xFF) / 255f
    val top = Color.hsv(230f + hueSeed * 90f, 0.45f, 0.32f)
    val bottom = Color.hsv(260f + hueSeed * 60f, 0.55f, 0.14f)
    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp)
        ) {
            if (genreLabel.isNotBlank()) {
                Text(
                    genreLabel.uppercase(),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                titleName,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 4
            )
        }
    }
}
