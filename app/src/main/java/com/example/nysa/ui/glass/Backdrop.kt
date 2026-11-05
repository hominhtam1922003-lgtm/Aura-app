package com.example.nysa.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import android.graphics.RenderEffect as FrameworkRenderEffect
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/**
 * Real-glass backdrop machinery (1.0.5-glass), built ONLY on stock
 * Compose 1.7 APIs — no new dependency, and specifically NOT the
 * backdrop-blur library removed in 1.0.2 after it crashed 1.0.1 on
 * the user's Xiaomi.
 *
 * Pattern: the shell's tab content is recorded ONCE per frame into a
 * shared [GraphicsLayer] ([Modifier.backdropRecorder]). A floating
 * glass surface (tab bar, banner) then draws the slice of that layer
 * that lies underneath itself — through a small offscreen layer with
 * a blur (+ AGSL refraction on API 33+) RenderEffect, clipped to the
 * surface shape — and the caller layers the usual tint/rim on top.
 * Because the surface's recorded draw ops reference the LIVE backdrop
 * layer, scrolling the content updates the slice every frame without
 * re-recording the surface itself.
 *
 * Tiers (decided from SDK_INT, wired to the shell's glass toggle by
 * the caller — glass OFF means no recording, no effects, plain theme):
 *  - API 33+  → blur + edge refraction (shader failure ⇒ plain blur)
 *  - API 31–32 → plain RenderEffect blur
 *  - API < 31 → STATIC: callers keep the old tint-only glassSurface.
 *
 * Perf discipline: at most one backdrop record per screen; surfaces
 * render their slice at [BACKDROP_DOWNSCALE] and blur in that space;
 * positions are read in the draw phase, so scrolling never triggers
 * recomposition; glass is never nested inside recorded glass (the
 * surfaces live OUTSIDE the recorded subtree).
 */
enum class GlassTier { STATIC, BLUR, REFRACT }

fun currentGlassTier(): GlassTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassTier.REFRACT
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlassTier.BLUR
    else -> GlassTier.STATIC
}

/** Scale of the offscreen layer a surface's backdrop slice is blurred in. */
private const val BACKDROP_DOWNSCALE = 0.6f

/** Backdrop blur radius, in downscaled effect-layer px (≈43 full-res px — a deep frost). */
private const val BLUR_RADIUS_PX = 26f

/** Shared per-screen backdrop: the recorded tab content + its last recorded size. */
class BackdropState internal constructor(
    internal val layer: GraphicsLayer
) {
    /**
     * Written by the recorder during its draw, read by glass surfaces
     * later in the same frame's draw pass (single UI thread, and the
     * content subtree always draws before its sibling surfaces) —
     * a size mismatch means "no valid backdrop yet", surfaces skip.
     */
    internal var contentSize: IntSize = IntSize.Zero
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

/**
 * Records the wrapped content into [BackdropState.layer] as it draws
 * (one record per draw of the content — display-list replay, cheap),
 * then draws the recorded layer in place of the raw content.
 * Apply ONLY around the tab content, never around a glass surface.
 */
fun Modifier.backdropRecorder(state: BackdropState): Modifier = drawWithContent {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w > 0 && h > 0) {
        // DrawScope's own GraphicsLayer.record extension: it takes
        // density/layoutDirection from this draw scope (the 4-arg
        // member overload cannot be called with a bare `density`
        // here — inside a DrawScope that name is the Float scale).
        state.layer.record(IntSize(w, h)) {
            this@drawWithContent.drawContent()
        }
        state.contentSize = IntSize(w, h)
        drawLayer(state.layer)
    } else {
        drawContent()
    }
}

/** Per-surface cache of the built RenderEffect, rebuilt only when its inputs change. */
private class EffectCache {
    private var key: EffectKey? = null
    private var effect: ComposeRenderEffect? = null

    fun get(
        tier: GlassTier,
        slot: String,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Float
    ): ComposeRenderEffect {
        val k = EffectKey(tier, slot, widthPx, heightPx, radiusPx)
        val hit = effect
        if (hit != null && key == k) return hit
        val built = buildEffect(tier, slot, widthPx, heightPx, radiusPx)
        key = k
        effect = built
        return built
    }

    private data class EffectKey(
        val tier: GlassTier,
        val slot: String,
        val widthPx: Int,
        val heightPx: Int,
        val radiusPx: Float
    )
}

private fun buildEffect(
    tier: GlassTier,
    slot: String,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Float
): ComposeRenderEffect {
    if (tier == GlassTier.REFRACT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RefractionShaders.buildChain(slot, widthPx, heightPx, radiusPx, BLUR_RADIUS_PX)
            ?.let { return it }
        // Shader unavailable/failed: degrade to plain blur, never crash.
    }
    // API 31+ guaranteed by the caller gate (tier != STATIC).
    return FrameworkRenderEffect.createBlurEffect(
        BLUR_RADIUS_PX,
        BLUR_RADIUS_PX,
        android.graphics.Shader.TileMode.CLAMP
    ).asComposeRenderEffect()
}

private fun Outline.maxCornerRadiusPx(): Float = when (this) {
    is Outline.Rounded -> maxOf(
        roundRect.topLeftCornerRadius.x,
        roundRect.topRightCornerRadius.x,
        roundRect.bottomLeftCornerRadius.x,
        roundRect.bottomRightCornerRadius.x
    )
    is Outline.Rectangle -> 0f
    is Outline.Generic -> Float.NaN
}

/**
 * Draws the real-glass backdrop slice for one floating surface: the
 * part of [state]'s recorded content directly underneath this
 * element, blurred (+ refracted on the REFRACT tier), clipped to
 * [shape]. Must be placed BEFORE the surface's tint chain in the
 * modifier order (its slice goes down first, tint draws over it via
 * the caller's following modifiers), and the surface must NOT be
 * inside the recorded subtree.
 *
 * [slot] identifies the surface for the refraction shader cache
 * ("tabbar", "banner") — one compiled shader per slot.
 */
fun Modifier.realGlassBackdrop(
    state: BackdropState,
    shape: Shape,
    slot: String
): Modifier = composed {
    val tier = remember { currentGlassTier() }
    val effectLayer = rememberGraphicsLayer()
    val cache = remember { EffectCache() }
    // Position in root coordinates, written at layout, read at draw:
    // draw-phase state, so a moving surface never recomposes.
    val positionState = remember { mutableStateOf<Offset?>(null) }

    this
        .onGloballyPositioned { positionState.value = it.positionInRoot() }
        .drawWithContent {
            val position = positionState.value
            val backdropSize = state.contentSize
            val w = size.width.roundToInt()
            val h = size.height.roundToInt()
            if (tier != GlassTier.STATIC && position != null &&
                backdropSize != IntSize.Zero && state.layer.size == backdropSize &&
                w > 0 && h > 0
            ) {
                val s = BACKDROP_DOWNSCALE
                val ew = (w * s).roundToInt().coerceAtLeast(1)
                val eh = (h * s).roundToInt().coerceAtLeast(1)
                val outline = shape.createOutline(size, layoutDirection, this)
                val radiusPx = outline.maxCornerRadiusPx().let {
                    if (it.isNaN()) minOf(ew, eh) / 2f else it * s
                }
                effectLayer.record(IntSize(ew, eh)) {
                    withTransform({
                        translate(-position.x * s, -position.y * s)
                        scale(s, s, Offset.Zero)
                    }) {
                        drawLayer(state.layer)
                    }
                }
                effectLayer.renderEffect = cache.get(tier, slot, ew, eh, radiusPx)
                val drawSlice: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                    withTransform({ scale(1f / s, 1f / s, Offset.Zero) }) {
                        drawLayer(effectLayer)
                    }
                }
                when (outline) {
                    is Outline.Rectangle -> clipRect { drawSlice() }
                    is Outline.Rounded -> clipPath(
                        Path().apply { addRoundRect(outline.roundRect) }
                    ) { drawSlice() }
                    is Outline.Generic -> clipPath(outline.path) { drawSlice() }
                }
            }
            drawContent()
        }
}
