package com.example.nysa.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as FrameworkRenderEffect
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/**
 * AGSL edge refraction for the real-glass surfaces (1.0.5-glass).
 *
 * ISOLATION RULE (the 1.0.1 lesson — a high-API class reached from
 * an ungated path is a launch crash): every reference to
 * [RuntimeShader] in the whole app lives in THIS file, the object is
 * annotated [RequiresApi] 33, and the only caller reaches it behind
 * an `SDK_INT >= 33` check. API 26–32 devices never load this class.
 *
 * The shader bends the (already blurred) backdrop near the surface's
 * rounded-rect edge — sample points are pulled inward along the SDF
 * gradient, strongest at the rim, zero deeper than the band — which
 * is the "light bending at the edge of the glass" cue the flat tint
 * version lacked, plus a faint specular lift right at the boundary.
 *
 * Compile discipline: the AGSL source is compiled at most once per
 * surface slot (the surfaces are few: tab bar, banner) and cached
 * for the process; a single shared instance would be wrong because
 * uniforms are read at render time and the two surfaces differ in
 * size. Compilation AND chain construction are wrapped in try/catch
 * — ANY failure returns null and the caller falls back to plain
 * blur. This code path can degrade, it cannot crash.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object RefractionShaders {

    private const val SHADER_SOURCE = """
uniform shader content;
uniform float2 uSize;
uniform float uRadius;
uniform float uBand;
uniform float uDisp;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float sdf(float2 coord) {
    float2 halfSize = uSize * 0.5;
    return sdRoundBox(coord - halfSize, halfSize, uRadius);
}

half4 main(float2 coord) {
    float d = sdf(coord);
    float e = 1.25;
    float gx = sdf(coord + float2(e, 0.0)) - sdf(coord - float2(e, 0.0));
    float gy = sdf(coord + float2(0.0, e)) - sdf(coord - float2(0.0, e));
    float2 grad = float2(gx, gy);
    float gl = length(grad);
    grad = gl > 0.0001 ? grad / gl : float2(0.0, 0.0);
    float t = clamp(-d / uBand, 0.0, 1.0);
    float bend = (1.0 - t) * (1.0 - t);
    float2 sampleCoord = coord - grad * (bend * uDisp);
    sampleCoord = clamp(sampleCoord, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
    half4 col = content.eval(sampleCoord);
    float rim = 1.0 - smoothstep(0.0, 2.0, abs(d + 1.0));
    col.rgb += half3(rim * 0.07);
    return col;
}
"""

    /** Refraction band width / max sample displacement, in EFFECT-layer px (the ~0.6x downscaled space). */
    const val BAND_PX = 34f
    const val DISP_PX = 13f

    private val cache = HashMap<String, RuntimeShader?>()

    private fun shaderFor(slot: String): RuntimeShader? {
        if (cache.containsKey(slot)) return cache[slot]
        val created = try {
            RuntimeShader(SHADER_SOURCE)
        } catch (t: Throwable) {
            null
        }
        cache[slot] = created
        return created
    }

    /**
     * The refraction-over-blur chain for one surface slice, or null
     * when the shader is unavailable/fails (caller uses plain blur).
     * Chain order: createChainEffect(outer, inner) applies inner
     * first — the backdrop is blurred, THEN refracted, matching how
     * light passes through real glass.
     */
    fun buildChain(
        slot: String,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Float,
        blurRadiusPx: Float
    ): ComposeRenderEffect? {
        val shader = shaderFor(slot) ?: return null
        return try {
            shader.setFloatUniform("uSize", widthPx.toFloat(), heightPx.toFloat())
            shader.setFloatUniform("uRadius", radiusPx)
            shader.setFloatUniform("uBand", BAND_PX)
            shader.setFloatUniform("uDisp", DISP_PX)
            val refraction = FrameworkRenderEffect.createRuntimeShaderEffect(shader, "content")
            val blur = FrameworkRenderEffect.createBlurEffect(
                blurRadiusPx,
                blurRadiusPx,
                android.graphics.Shader.TileMode.CLAMP
            )
            FrameworkRenderEffect.createChainEffect(refraction, blur).asComposeRenderEffect()
        } catch (t: Throwable) {
            null
        }
    }
}
