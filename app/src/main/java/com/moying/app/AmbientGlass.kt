/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Full license: assets/licenses/Kyant-Backdrop-Apache-2.0.txt.
 *
 * Modified 2026-11-02 for Moying. Adapted rounded SDF, analytical gradient,
 * circular refraction and GPU backdrop projection from Kyant0/AndroidLiquidGlass,
 * commit 65ab177e90e5c1d8c62e70cf7755841982da65f6.
 * Background-only capture prevents cards from sampling their own foreground.
 */
package com.moying.app

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.ceil
import kotlin.math.tanh

val LocalAmbientBackdrop = staticCompositionLocalOf<AmbientBackdrop?> { null }

@Stable
class AmbientBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var bounds by mutableStateOf(Rect.Zero)
    internal var ready by mutableStateOf(false)
    internal var coordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())
}

/** 输入：无；输出：由 Compose 管理和释放的独立背景显示列表。 */
@Composable
fun rememberAmbientBackdrop(): AmbientBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { AmbientBackdrop(layer) }
}

/** 输入：只包含壁纸/背景的内容；输出：原样背景及供卡片复用的 GPU 层，不能把卡片放进来。 */
@Composable
fun AmbientBackdropHost(
    backdrop: AmbientBackdrop,
    modifier: Modifier = Modifier,
    trackPosition: Boolean = true,
    captureOnly: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val placement = if (trackPosition) Modifier.onGloballyPositioned { coordinates ->
        backdrop.coordinates = coordinates
        val bounds = Rect(coordinates.localToRoot(Offset.Zero), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        if (backdrop.bounds.size != bounds.size) backdrop.ready = false
        backdrop.bounds = bounds
    } else Modifier
    // Keep the shared background display list independent from the scrolling page.
    // The default graphics layer isolates drawing without allocating an offscreen buffer.
    val isolation = if (trackPosition && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Modifier.graphicsLayer()
    } else Modifier
    Box(modifier.then(placement).then(isolation).drawWithContent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            if (!backdrop.ready) backdrop.ready = true
            if (!captureOnly) drawLayer(backdrop.layer)
        } else drawContent()
    }, content = content)
}

/** 折射独立背景。需要折射内部内容的控件可以将内容录进同一个采样层。 */
@Composable
internal fun AmbientGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Theme.Radius.Card),
    tint: Color = Theme.Glass.Fill,
    lightResponse: Boolean = true,
    style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT,
    materialProgress: Float? = null,
    materialProgressProvider: (() -> Float)? = null,
    posterMaterial: NavigationGlassEffect? = null,
    refractContent: Boolean = false,
    pressDeformation: Boolean = false,
    panelExpansionProvider: (() -> Float)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalAmbientBackdrop.current
    val density = LocalDensity.current.density
    val tunable = style.isTunable
    val tuningState = LocalGlassTuning.current
    val light = LocalGlassLight.current
    // The poster and its lens share local coordinates and move as a unit.
    val posterStyle = style == CatalogGlassStyle.OSHIN_POSTER
    val alignedBackdrop = style == CatalogGlassStyle.PLAYGROUND || (posterStyle && posterMaterial != null)
    val material = if (alignedBackdrop) posterMaterial else remember(density, Theme.isDark, style) {
        createNavigationGlassEffect(density, style)
    }
    val glassLayer = rememberGraphicsLayer()
    val contentLayer = if (refractContent) rememberGraphicsLayer() else null
    val surfaceTint = when {
        tint != Color.Transparent && tint != Theme.Glass.Fill && tint != Theme.NavGlass.FallbackFill -> tint
        tunable -> Theme.Colors.Elevated
        else -> null
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val touchInteraction = rememberGlassTouchInteraction(lightResponse)

    val touchModifier = if (lightResponse) {
        Modifier.observeGlassTouch(touchInteraction, cancelOnDrag = pressDeformation || style == CatalogGlassStyle.SCROLL)
    } else Modifier
    val projection = if (!alignedBackdrop) Modifier.onGloballyPositioned { placed ->
        coordinates = placed
        bounds = Rect(placed.localToRoot(Offset.Zero), Size(placed.size.width.toFloat(), placed.size.height.toFloat()))
    } else Modifier
    val glassDrawing = Modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radii = if (outline is Outline.Rounded) floatArrayOf(
                outline.roundRect.topLeftCornerRadius.x, outline.roundRect.topRightCornerRadius.x,
                outline.roundRect.bottomRightCornerRadius.x, outline.roundRect.bottomLeftCornerRadius.x,
            ) else floatArrayOf(0f, 0f, 0f, 0f)
            val padding = ceil(Theme.NavGlass.SamplePadding.toPx())
            val dimensions = IntSize(ceil(size.width + padding * 2f).toInt(), ceil(size.height + padding * 2f).toInt())
            val rim = Brush.verticalGradient(listOf(Theme.NavGlass.TopRim, Theme.NavGlass.QuietRim, Theme.NavGlass.BottomRim))
            val outerStroke = Theme.NavGlass.OuterStroke.toPx()
            val rimOutline = shape.createOutline(Size((size.width - outerStroke).coerceAtLeast(0f), (size.height - outerStroke).coerceAtLeast(0f)), layoutDirection, this)
            var alignedRecordingReady = false
            onDrawWithContent {
                val savedTuning = if (tunable) tuningState.value else GlassTuning.Default
                // Content inside this lens keeps its sharp edges while its coordinates refract.
                val tuning = if (refractContent) savedTuning.copy(blur = 0f) else savedTuning
                if (!tunable) drawOutline(outline, Theme.NavGlass.FallbackFill)
                // 输入按压动画；输出仅draw失效，海报采样源不会因逐帧压力而反复重组。
                val progress = (materialProgressProvider?.invoke() ?: materialProgress ?: touchInteraction.pressProgress).coerceIn(0f, Theme.NavGlass.MaxPress)
                val contact = touchInteraction.touch ?: Offset(size.width / 2f, size.height / 2f)
                val canSample = backdrop?.ready == true && (alignedBackdrop || !bounds.isEmpty)
                if (material != null && backdrop != null && canSample) {
                    // 直接使用导航同一材质，仅保留卡片自身圆角；卡片不绘制导航选中胶囊。
                    material.update(size, padding, Rect.Zero, contact, progress, cornerRadii = radii,
                        selectionLens = false, surfaceTint = surfaceTint, releaseProgress = touchInteraction.releaseProgress,
                        tuning = tuning,
                        lightDirection = if (tunable && tuning.highlight > 0f) light.value
                            else Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY),
                        panelExpansion = panelExpansionProvider?.invoke() ?: 1f)
                    glassLayer.renderEffect = material.effect
                    if (alignedBackdrop && contentLayer == null) {
                        // The poster's layer is stable and shares the lens's local coordinates.
                        // Its own drawing can update without rerecording this reference.
                        if (!alignedRecordingReady) {
                            glassLayer.record(size = dimensions) {
                                translate(padding, padding) { drawLayer(backdrop.layer) }
                            }
                            alignedRecordingReady = true
                        }
                    } else {
                        val sourceCoordinates = backdrop.coordinates?.takeIf { it.isAttached }
                        val targetCoordinates = coordinates?.takeIf { it.isAttached }
                        val origin = if (sourceCoordinates != null && targetCoordinates != null)
                            targetCoordinates.localPositionOf(sourceCoordinates, Offset.Zero)
                        else Offset(backdrop.bounds.left - bounds.left, backdrop.bounds.top - bounds.top)
                        val endX = if (sourceCoordinates != null && targetCoordinates != null)
                            targetCoordinates.localPositionOf(sourceCoordinates, Offset(sourceCoordinates.size.width.toFloat(), 0f))
                        else origin + Offset(backdrop.bounds.width, 0f)
                        val endY = if (sourceCoordinates != null && targetCoordinates != null)
                            targetCoordinates.localPositionOf(sourceCoordinates, Offset(0f, sourceCoordinates.size.height.toFloat()))
                        else origin + Offset(0f, backdrop.bounds.height)
                        val scaleX = (endX.x - origin.x) / (sourceCoordinates?.size?.width?.toFloat() ?: backdrop.bounds.width).coerceAtLeast(1f)
                        val scaleY = (endY.y - origin.y) / (sourceCoordinates?.size?.height?.toFloat() ?: backdrop.bounds.height).coerceAtLeast(1f)
                        contentLayer?.record { this@onDrawWithContent.drawContent() }
                        glassLayer.record(size = dimensions) {
                            translate(padding + origin.x, padding + origin.y) {
                                scale(scaleX, scaleY, pivot = Offset.Zero) { drawLayer(backdrop.layer) }
                            }
                            if (contentLayer != null) translate(padding, padding) { drawLayer(contentLayer) }
                        }
                    }
                    translate(-padding, -padding) { drawLayer(glassLayer) }
                } else if (tunable && !posterStyle) {
                    val fallbackTint = surfaceTint ?: Theme.Colors.Elevated
                    drawOutline(outline, fallbackTint.copy(alpha = tuning.translucentOpacity))
                }
                // 输入 GPU 能力；输出仅永久降级时描边，背景首帧未就绪不闪白边。
                if (material == null && !posterStyle) {
                    translate(outerStroke / 2f, outerStroke / 2f) {
                        drawOutline(rimOutline, rim, alpha = if (tunable) (tuning.highlight * 2f).coerceAtMost(1f) else 1f,
                            style = Stroke(outerStroke))
                    }
                }
                if (contentLayer != null && (material == null || !canSample)) drawContent()
            }
    }
    val deformation = if (pressDeformation) Modifier.graphicsLayer {
        val pressure = touchInteraction.pressProgress.coerceIn(-.2f, 1.1f)
        scaleX = 1f + .055f * pressure
        scaleY = 1f - .075f * pressure
        val contact = touchInteraction.touch ?: Offset(size.width / 2f, size.height / 2f)
        translationX = 3.dp.toPx() * tanh((contact.x - size.width / 2f) / size.width.coerceAtLeast(1f)) * pressure
    } else Modifier
    val surface = modifier.then(touchModifier).then(deformation).then(projection).clip(shape)
    if (refractContent) {
        Box(surface.then(glassDrawing), content = content)
    } else {
        Box(surface) {
            Box(Modifier.matchParentSize().then(glassDrawing))
            Box(Modifier.zIndex(Theme.NavGlass.ForegroundZ), content = content)
        }
    }
}

