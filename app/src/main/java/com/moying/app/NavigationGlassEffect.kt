/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified 2026-11-02 for Moying:
 * Adapted rounded-rect SDF, analytical gradient, circular lens profile and
 * directional highlight from Kyant0/AndroidLiquidGlass,
 * commit 65ab177e90e5c1d8c62e70cf7755841982da65f6,
 * backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt.
 * Added a moving inner lens, touch optics, restrained Fresnel/environment
 * lighting, three-tap dispersion and padded Android RenderEffect binding.
 * Revised 2026-11-03: use the refraction and highlight algorithms verified
 * byte-for-byte against the supplied Backdrop Catalog APK, with its original
 * seven-tap dispersion. Keep Moying geometry, touch/release animation and
 * shared GPU rendering lifecycle unchanged.
 */
package com.moying.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

internal interface NavigationGlassEffect {
    val effect: androidx.compose.ui.graphics.RenderEffect

    /** 输入：像素尺寸、胶囊和触点；输出：更新 GPU 材质，不创建位图或重编译 Shader。 */
    fun update(size: Size, padding: Float, capsule: Rect, touch: Offset, press: Float,
        cornerRadii: FloatArray? = null, selectionLens: Boolean = true, surfaceTint: Color? = null,
        releaseProgress: Float = 0f,
        tuning: GlassTuning = GlassTuning.Default,
        lightDirection: Offset = Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY),
        panelExpansion: Float = 1f)
}

internal fun createNavigationGlassEffect(density: Float, style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT): NavigationGlassEffect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { Api33NavigationGlassEffect(density, style) }.getOrNull()
    } else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object SharedNavigationGlassProgram {
    // 导航、卡片和视频共用程序；绑定全部 uniforms 后立即快照，效果互不串扰。
    val shader = RuntimeShader(NAVIGATION_GLASS_SHADER)
}

/** 输入：屏幕密度；输出：导航、卡片和视频共用的 Catalog GPU 模糊，不做 CPU 采样。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun createNavigationGlassBlur(density: Float, style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT,
    progress: Float = 0f, tuning: GlassTuning = GlassTuning.Default, panelExpansion: Float = 1f): RenderEffect? {
    val radius = catalogBlurRadius(style, progress, tuning, panelExpansion) * density
    return if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
}

/** 输入：已完整绑定的程序与模糊；输出：独立 uniform 快照、先模糊后折射的滤镜链。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun snapshotNavigationGlassFilter(shader: RuntimeShader, blur: RenderEffect?): RenderEffect {
    val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
    return if (blur == null) lens else RenderEffect.createChainEffect(lens, blur)
}

/** 输入：局部像素几何、触点、可选胶囊和底色；输出：绑定同一套导航玻璃光学参数。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun bindNavigationGlassUniforms(
    shader: RuntimeShader,
    density: Float,
    size: Size,
    padding: Float,
    capsule: Rect,
    touch: Offset,
    press: Float,
    cornerRadii: FloatArray?,
    selectionLens: Boolean,
    surfaceTint: Color?,
    releaseProgress: Float = 0f,
    style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT,
    tuning: GlassTuning = GlassTuning.Default,
    lightDirection: Offset = Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY),
    panelExpansion: Float = 1f,
) {
    val isBottomTabs = style == CatalogGlassStyle.BOTTOM_TABS
    val tunable = style.isTunable
    val lensScale = if (tunable) tuning.refraction / GlassTuning.Default.refraction else 1f
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("padding", padding)
    if (cornerRadii == null) {
        val radius = size.height / 2f
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
    } else shader.setFloatUniform("cornerRadii", cornerRadii)
    shader.setFloatUniform("capsule", capsule.left, capsule.top, capsule.right, capsule.bottom)
    shader.setFloatUniform("touch", touch.x, touch.y)
    shader.setFloatUniform("press", press.coerceIn(0f, Theme.NavGlass.MaxPress))
    shader.setFloatUniform("releaseProgress", releaseProgress.coerceIn(0f, 1f))
    shader.setFloatUniform("releaseAmplitude", Theme.NavGlass.ReleaseAmplitude.value * density * lensScale)
    shader.setFloatUniform("releaseRadius", Theme.NavGlass.ReleaseRadius.value * density)
    val pressure = press.coerceIn(0f, 1f)
    val minDimension = minOf(size.width, size.height)
    val sliderScale = (size.height / (Theme.CatalogGlass.SliderNominalHeight.value * density)).coerceAtMost(1f)
    val lensHeight = when (style) {
        CatalogGlassStyle.SCROLL -> Theme.CatalogGlass.ScrollLensHeight.value * density
        CatalogGlassStyle.PLAYGROUND -> minDimension * Theme.CatalogGlass.PlaygroundLensFraction
        CatalogGlassStyle.OSHIN_CONTROL -> 12f * density
        CatalogGlassStyle.OSHIN_PANEL -> 12f * (1f + panelExpansion) * density
        CatalogGlassStyle.OSHIN_POSTER -> minOf(12f * density, minDimension * .12f)
        CatalogGlassStyle.SLIDER_THUMB -> Theme.CatalogGlass.SliderLensHeight.value * density * sliderScale * pressure
        CatalogGlassStyle.SLIDER_TRACK -> 0f
        else -> Theme.NavGlass.LensEdge.value * density
    }
    val amount = when (style) {
        CatalogGlassStyle.SCROLL, CatalogGlassStyle.BOTTOM_TABS -> Theme.GlassTuning.MaxRefraction.value * density * tuning.refraction
        CatalogGlassStyle.PLAYGROUND -> minDimension * Theme.CatalogGlass.PlaygroundRefractionFraction
        CatalogGlassStyle.OSHIN_CONTROL -> 24f * density * lensScale
        CatalogGlassStyle.OSHIN_PANEL -> 24f * (1f + panelExpansion) * density * lensScale
        CatalogGlassStyle.OSHIN_POSTER -> minOf(24f * density, minDimension * .16f) * lensScale
        CatalogGlassStyle.SLIDER_THUMB -> Theme.CatalogGlass.SliderRefraction.value * density * sliderScale * pressure
        CatalogGlassStyle.SLIDER_TRACK -> 0f
        else -> Theme.NavGlass.RefractionPixels.value * density
    }
    shader.setFloatUniform("refractionHeight", lensHeight)
    shader.setFloatUniform("refractionAmount", amount)
    shader.setFloatUniform("depthEffect", when (style) {
        CatalogGlassStyle.PLAYGROUND, CatalogGlassStyle.OSHIN_POSTER -> 1f
        CatalogGlassStyle.OSHIN_PANEL -> panelExpansion
        else -> 0f
    })
    shader.setFloatUniform("capsuleRefraction", if (selectionLens) Theme.NavGlass.CapsuleRefraction.value * density * lensScale else 0f)
    shader.setFloatUniform("capsuleLensHeight", Theme.NavGlass.CapsuleLensEdge.value * density)
    shader.setFloatUniform("capsuleStrength", if (isBottomTabs) maxOf(Theme.BottomTabsGlass.RestLensStrength, pressure) else pressure)
    shader.setFloatUniform("dispersion", if (style == CatalogGlassStyle.SLIDER_THUMB) Theme.NavGlass.ChromaticSplit else 0f)
    val highlightWidth = if (tunable) Theme.GlassTuning.HighlightWidth else Theme.NavGlass.SpecularWidth
    shader.setFloatUniform("highlightWidth", highlightWidth.value * density)
    shader.setFloatUniform("highlight", when (style) {
        CatalogGlassStyle.PLAYGROUND -> Theme.CatalogGlass.PlainHighlight
        CatalogGlassStyle.SLIDER_THUMB -> Theme.CatalogGlass.AmbientHighlight * pressure
        CatalogGlassStyle.BOTTOM_TABS, CatalogGlassStyle.SCROLL -> Theme.GlassTuning.Highlight * (tuning.highlight * 2f)
        CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_PANEL -> Theme.CatalogGlass.PlainHighlight * (tuning.highlight * 2f)
        CatalogGlassStyle.OSHIN_POSTER -> .10f * (tuning.highlight * 2f)
        CatalogGlassStyle.SLIDER_TRACK -> 0f
        else -> Theme.NavGlass.Highlight
    })
    shader.setFloatUniform("plainHighlightWeight", when (style) {
        CatalogGlassStyle.PLAYGROUND -> 1f
        CatalogGlassStyle.OSHIN_PANEL -> panelExpansion
        else -> 0f
    })
    shader.setFloatUniform("ambientHighlightWeight", when (style) {
        CatalogGlassStyle.SLIDER_THUMB, CatalogGlassStyle.BOTTOM_TABS, CatalogGlassStyle.SCROLL,
        CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_POSTER -> 1f
        CatalogGlassStyle.OSHIN_PANEL -> 1f - panelExpansion
        else -> 0f
    })
    shader.setFloatUniform("highlightFalloff", Theme.NavGlass.HighlightFalloff)
    val baseSaturation = when (style) {
        CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_PANEL -> 1.5f
        else -> Theme.NavGlass.Saturation
    }
    shader.setFloatUniform("saturation", if (tunable) baseSaturation * (tuning.saturation * 2f) else baseSaturation)
    shader.setFloatUniform("brightness", if (style == CatalogGlassStyle.OSHIN_PANEL && !Theme.isDark) .2f * panelExpansion else 0f)
    shader.setFloatUniform("touchRadius", size.height * Theme.NavGlass.TouchRadius)
    shader.setFloatUniform("touchBulge", Theme.NavGlass.TouchBulge * lensScale)
    shader.setFloatUniform("lightDirection", lightDirection.x, lightDirection.y)
    shader.setFloatUniform("tintAlpha", when (style) {
        CatalogGlassStyle.DEFAULT -> Theme.NavGlass.TintAlpha
        CatalogGlassStyle.BOTTOM_TABS, CatalogGlassStyle.SCROLL,
        CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_PANEL -> tuning.tintStrength
        CatalogGlassStyle.OSHIN_POSTER -> tuning.tintStrength * .28f
        CatalogGlassStyle.PLAYGROUND -> surfaceTint?.alpha ?: 0f
        else -> 0f
    })
    shader.setColorUniform("tint", (surfaceTint ?: Theme.NavGlass.Tint).toArgb())
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Api33NavigationGlassEffect(private val density: Float, private val style: CatalogGlassStyle) : NavigationGlassEffect {
    private val shader = SharedNavigationGlassProgram.shader
    private var blur: RenderEffect? = null
    private var previousBlurRadius = Float.NaN
    private var boundEffect: androidx.compose.ui.graphics.RenderEffect? = null
    override val effect get() = checkNotNull(boundEffect)
    private var previousSize = Size.Unspecified
    private var previousPadding = Float.NaN
    private var previousCapsule = Rect.Zero
    private var previousTouch = Offset.Unspecified
    private var previousPress = Float.NaN
    private var previousRelease = Float.NaN
    private var previousRadii: FloatArray? = null
    private var previousSelectionLens = true
    private var previousTint: Color? = null
    private var previousTuning = GlassTuning.Default
    private var previousLight = Offset.Unspecified
    private var previousPanelExpansion = Float.NaN

    override fun update(size: Size, padding: Float, capsule: Rect, touch: Offset, press: Float,
        cornerRadii: FloatArray?, selectionLens: Boolean, surfaceTint: Color?, releaseProgress: Float,
        tuning: GlassTuning, lightDirection: Offset, panelExpansion: Float) {
        val radiiUnchanged = if (cornerRadii == null) previousRadii == null else previousRadii?.contentEquals(cornerRadii) == true
        if (size == previousSize && padding == previousPadding && capsule == previousCapsule &&
            touch == previousTouch && press == previousPress && releaseProgress == previousRelease &&
            radiiUnchanged && selectionLens == previousSelectionLens && surfaceTint == previousTint &&
            tuning == previousTuning && lightDirection == previousLight && panelExpansion == previousPanelExpansion) return
        bindNavigationGlassUniforms(shader, density, size, padding, capsule, touch, press, cornerRadii,
            selectionLens, surfaceTint, releaseProgress, style, tuning, lightDirection, panelExpansion)
        // Skia 在创建 ImageFilter 时快照 uniforms；只更新 shader 不会改变已有 effect。
        // 缓存编译后的 shader 和 blur，仅参数改变时重新绑定轻量效果；不创建像素位图。
        val blurRadius = catalogBlurRadius(style, press, tuning, panelExpansion)
        if (blurRadius != previousBlurRadius) {
            blur = createNavigationGlassBlur(density, style, press, tuning, panelExpansion)
            previousBlurRadius = blurRadius
        }
        boundEffect = snapshotNavigationGlassFilter(shader, blur).asComposeRenderEffect()
        previousSize = size
        previousPadding = padding
        previousCapsule = capsule
        previousTouch = touch
        previousPress = press
        previousRelease = releaseProgress
        previousRadii = cornerRadii?.copyOf()
        previousSelectionLens = selectionLens
        previousTint = surfaceTint
        previousTuning = tuning
        previousLight = lightDirection
        previousPanelExpansion = panelExpansion
    }
}

// 圆弧透镜、七色色散及方向高光来自所附 Backdrop Catalog APK；保留应用原有互动时间线。
internal const val NAVIGATION_GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float padding;
uniform float4 cornerRadii;
uniform float4 capsule;
uniform float2 touch;
uniform float press;
uniform float releaseProgress;
uniform float releaseAmplitude;
uniform float releaseRadius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float capsuleRefraction;
uniform float capsuleLensHeight;
uniform float capsuleStrength;
uniform float dispersion;
uniform float highlightWidth;
uniform float highlight;
uniform float plainHighlightWeight;
uniform float ambientHighlightWeight;
uniform float highlightFalloff;
uniform float saturation;
uniform float brightness;
uniform float touchRadius;
uniform float touchBulge;
uniform float2 lightDirection;
uniform float tintAlpha;
layout(color) uniform half4 tint;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 outside = max(cornerCoord, 0.0);
        return sign(coord) * outside / max(length(outside), 0.0001);
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}


// 输入局部坐标和透镜几何；输出原 APK 的圆弧折射位移，中央不发生位移。
float2 catalogLens(float2 point, float2 halfSize, float radius, float height, float amount) {
    if (height <= 0.0 || amount <= 0.0) return float2(0.0);
    float sd = sdRoundedRect(point, halfSize, radius);
    if (-sd >= height) return float2(0.0);
    sd = min(sd, 0.0);
    float d = circleMap(clamp(1.0 - -sd / height, 0.0, 1.0)) * -amount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(point, halfSize, gradRadius)
        + depthEffect * point / max(length(point), 0.0001);
    grad /= max(length(grad), 0.0001);
    return d * grad;
}

// 输入已合成透镜坐标和色散；输出与原 APK 相同的七色加权采样。
half4 catalogSample(float2 refractedCoord, float2 dispersedCoord) {
    if (dot(dispersedCoord, dispersedCoord) < 0.0001) return content.eval(refractedCoord);
    half4 color = half4(0.0);
    
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    
    return color;
}

// 输入原 APK 解析法线；输出同一方向高光（45 度、线性 falloff）。
float3 catalogHighlight(float2 point, float2 halfSize, float radius, float sd, float2 light) {
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(point, halfSize, gradRadius);
    float d = dot(grad, light);
    float width = max(highlightWidth, 0.5);
    float edge = 1.0 - smoothstep(width * 0.5, width * 1.5, max(-sd, 0.0));
    return float3(step(0.0, d), edge * pow(abs(d), highlightFalloff), edge);
}

half4 main(float2 coord) {
    float2 point = coord - padding;
    float2 halfSize = size * 0.5;
    float2 centered = point - halfSize;
    float radius = min(radiusAt(centered, cornerRadii), min(halfSize.x, halfSize.y));
    float sd = sdRoundedRect(centered, halfSize, radius);
    float mask = 1.0 - smoothstep(-0.75, 0.75, sd);
    if (mask <= 0.0) return half4(0.0);
    float2 mappedPoint = point;
    float2 dispersedCoord = float2(0.0);
    float3 capsuleLight = float3(0.0);
    float2 light = normalize(lightDirection + (touch / max(size, float2(1.0)) - 0.5) * press);
    if (capsuleRefraction > 0.0) {
        float2 capsuleHalf = (capsule.zw - capsule.xy) * 0.5;
        float2 capsulePoint = point - (capsule.xy + capsule.zw) * 0.5;
        float csd = sdRoundedRect(capsulePoint, capsuleHalf, capsuleHalf.y);
        float capsuleMask = 1.0 - smoothstep(-0.75, 0.75, csd);
        if (capsuleMask > 0.0) {
            float2 displacement = catalogLens(capsulePoint, capsuleHalf, capsuleHalf.y,
                capsuleLensHeight, capsuleRefraction * capsuleStrength);
            mappedPoint += displacement;
            float dispersionIntensity = dispersion * ((capsulePoint.x * capsulePoint.y) / max(capsuleHalf.x * capsuleHalf.y, 1.0));
            dispersedCoord = displacement * dispersionIntensity;
            capsuleLight = catalogHighlight(capsulePoint, capsuleHalf, capsuleHalf.y, csd, light);
            capsuleLight.y *= capsuleMask * capsuleStrength;
            capsuleLight.z *= capsuleMask * capsuleStrength;
        }
    }
    // 两层仅合成坐标，沿用现有一次 GPU 材质绘制，前景图标与文字不参与采样。
    float2 mappedCentered = mappedPoint - halfSize;
    float mappedRadius = min(radiusAt(mappedCentered, cornerRadii), min(halfSize.x, halfSize.y));
    float2 shellDisplacement = catalogLens(mappedCentered, halfSize, mappedRadius, refractionHeight, refractionAmount);
    mappedPoint += shellDisplacement;
    dispersedCoord += shellDisplacement * dispersion * ((mappedCentered.x * mappedCentered.y) / max(halfSize.x * halfSize.y, 1.0));
    float2 displacement = mappedPoint - point;
    float2 fromTouch = point - touch;
    float touchFalloff = exp(-dot(fromTouch, fromTouch) / max(touchRadius * touchRadius, 1.0));
    displacement -= fromTouch * touchBulge * press * touchFalloff;
    // 释放后仅扰动触点附近的既有采样坐标；边界与时间端点连续归零，不新增纹理采样。
    if (releaseProgress > 0.0 && releaseProgress < 1.0) {
        float distance = length(fromTouch);
        float radial = distance / max(releaseRadius, 1.0);
        float localEnvelope = max(1.0 - radial, 0.0);
        float temporal = sin(3.14159265 * releaseProgress) * (1.0 - releaseProgress);
        float wave = sin(6.28318531 * (radial - releaseProgress));
        displacement += fromTouch / max(distance, 1.0) * releaseAmplitude * wave * localEnvelope * localEnvelope * temporal;
    }
    float2 sampleCoord = coord + displacement;
    half4 source = catalogSample(sampleCoord, dispersedCoord);
    half3 color = source.rgb;
    half luma = dot(color, half3(0.2126, 0.7152, 0.0722));
    color = mix(half3(luma), color, half(saturation));
    color += half3(half(brightness));
    color = mix(color, tint.rgb, half(tintAlpha));
    float3 shellLight = catalogHighlight(centered, halfSize, radius, sd, light);
    if (ambientHighlightWeight > 0.0) {
        half3 ambient = mix(color, half3(half(shellLight.x)), half(shellLight.y * highlight));
        ambient = mix(ambient, half3(half(capsuleLight.x)), half(capsuleLight.y * highlight));
        color = mix(color, ambient, half(ambientHighlightWeight));
    }
    float directionalAdd = 1.0 - ambientHighlightWeight - plainHighlightWeight;
    float additive = (shellLight.z + capsuleLight.z) * plainHighlightWeight
        + (shellLight.y + capsuleLight.y) * directionalAdd;
    color += half3(half(additive * highlight));
    color = clamp(color, half3(0.0), half3(1.0));
    half alpha = half(mask);
    return half4(color * alpha, alpha);
}
"""
