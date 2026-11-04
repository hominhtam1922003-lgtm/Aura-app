package com.example.nysa.ui.components

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * The Liquid Glass system (rebuilt for 1.0.1 after the on-device
 * verdict "mất liquid glass, nút trắng đục"; Haze backdrop blur was
 * tried in 1.0.1 and REMOVED in 1.0.2 — 1.0.1 crashed on launch on
 * the user's Xiaomi, so the glass here is layered tint + specular
 * rim + top sheen only, no backdrop-blur library).
 *
 * The layers are noticeably brighter than the 1.0.0 pass, whose
 * 3–10% white over pure black read as flat black on the device.
 * The player uses [playerGlassSurface]: the same rim + sheen over
 * a translucent BLACK tint, which reads as dark glass over bright
 * video and kills the old solid-white fills. Glass OFF collapses
 * every surface to the plain theme's flat panels, as approved.
 */
fun Modifier.glassSurface(shape: Shape, glass: Boolean): Modifier = this
    .clip(shape)
    .then(
        if (glass) {
            Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f),
                            Color.White.copy(alpha = 0.09f)
                        )
                    )
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = 110f
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.28f)
                        )
                    ),
                    shape = shape
                )
        } else {
            Modifier.background(NySaSurfacePlain)
        }
    )

/** Dark glass for surfaces floating over video (no backdrop blur possible there). */
fun Modifier.playerGlassSurface(shape: Shape, glass: Boolean): Modifier = this
    .clip(shape)
    .then(
        if (glass) {
            Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.44f),
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.40f)
                        )
                    )
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = 70f
                    )
                )
                .border(
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
            Modifier.background(Color.Black.copy(alpha = 0.55f))
        }
    )

/**
 * A glass surface (tab bar, banner, settings cards): the tint / rim
 * / sheen layers clipped to [shape], then the content on top.
 */
@Composable
fun LiquidGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit
) {
    val glass = LocalGlassEnabled.current
    if (!glass) {
        Box(modifier = modifier.glassSurface(shape, false)) { content() }
        return
    }
    Box(modifier = modifier.clip(shape)) {
        Box(modifier = Modifier.matchParentSize().glassSurface(shape, true))
        content()
    }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit
) {
    LiquidGlassBox(modifier = modifier, shape = shape, content = content)
}

/**
 * The ambient backdrop the settings glass blurs: near-black with
 * three very soft colour glows, so the cards have something to
 * refract and never read as flat black rectangles.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFF06060A), Color(0xFF0A0A13), Color(0xFF06060A))
            )
        )
    ) {
        Box(
            modifier = Modifier.size(340.dp).offset(x = (-90).dp, y = (-120).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF6366F1).copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier.size(300.dp).align(Alignment.TopEnd)
                .offset(x = 110.dp, y = 30.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF0EA5E9).copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier.size(360.dp).align(Alignment.BottomStart)
                .offset(x = (-120).dp, y = 120.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFD97706).copy(alpha = 0.10f), Color.Transparent)
                    )
                )
        )
    }
}

/** iOS-style switch drawn in the glass language (the stock Material switch read as a white blob). */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalGlassEnabled.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        label = "glassSwitchThumb"
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(shape)
            .then(
                when {
                    checked -> Modifier.background(
                        Brush.horizontalGradient(
                            listOf(Color.White, Color(0xFFDFDFEA))
                        )
                    )
                    glass -> Modifier.background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.32f), shape)
                    else -> Modifier.background(Color.White.copy(alpha = 0.22f))
                }
            )
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .size(28.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF111114) else Color.White)
        )
    }
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
                    if (selected) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f)
                ).background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = 42f
                    )
                ).border(
                    1.dp,
                    if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.34f),
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
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // A chip's label never wraps inside the chip (1.0.3 filter
            // fix) — parents must give it its full intrinsic width.
            maxLines = 1,
            softWrap = false
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
