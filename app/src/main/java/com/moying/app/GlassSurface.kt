package com.moying.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity

/**
 * 视频区在 API 33+ 使用实时视频背景折射；文字和按钮仍以原样绘制。
 * 无视频帧、旧版系统或驱动不支持时降级为透明底与边缘高光。
 * 视频与普通面板共用导航玻璃光学；视频节点不包含任何前景控件。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Theme.Radius.Card),
    tint: Color = Theme.Glass.Fill,
    lightResponse: Boolean = true,
    style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT,
    materialProgress: Float? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalVideoBackdrop.current
    if (backdrop == null) {
        AmbientGlassSurface(modifier = modifier, shape = shape, tint = tint, lightResponse = lightResponse,
            style = style, materialProgress = materialProgress, content = content)
        return
    }
    val density = LocalDensity.current.density
    val glassTuning = LocalGlassTuning.current
    val glassLight = LocalGlassLight.current
    val refraction = remember(backdrop, density, Theme.isDark, style) { createVideoRefractionRenderer(density, style) }
    DisposableEffect(refraction) { onDispose { refraction?.dispose() } }
    // 输入面板底色；输出默认深色视频玻璃，浅色主题下仍托住恒白的播放文字。
    val videoTint = if (tint == Theme.Glass.Fill || tint == Theme.Colors.PlayerControls) Theme.Glass.RefractedTint else tint
    var surfaceBounds by remember { mutableStateOf(Rect.Zero) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val touchInteraction = rememberGlassTouchInteraction(lightResponse)

    val response = if (lightResponse) Modifier.observeGlassTouch(touchInteraction) else Modifier
    Box(modifier.then(response).onGloballyPositioned {
        coordinates = it
        surfaceBounds = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val cornerRadii = if (outline is Outline.Rounded) floatArrayOf(
            outline.roundRect.topLeftCornerRadius.x,
            outline.roundRect.topRightCornerRadius.x,
            outline.roundRect.bottomRightCornerRadius.x,
            outline.roundRect.bottomLeftCornerRadius.x,
        ) else floatArrayOf(0f, 0f, 0f, 0f)
        val edge = Brush.linearGradient(
            colors = listOf(Theme.Glass.RimLight, Theme.Glass.RimQuiet, Theme.Glass.RimBottom),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val innerEdge = Brush.linearGradient(
            colors = listOf(Theme.Glass.InnerLight, Color.Transparent, Theme.Glass.InnerShade),
            end = Offset(0f, size.height),
        )
        onDrawWithContent {
            val tuning = if (style.isTunable) glassTuning.value else GlassTuning.Default
            val lightDirection = if (style.isTunable && tuning.highlight > 0f) glassLight.value
                else Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY)
            val fill = if (style.isTunable) tint.copy(alpha = tint.alpha * tuning.translucentOpacity) else tint
            drawOutline(outline, fill)
            // 在 draw 阶段观察帧状态，只重绘玻璃，不随视频帧重组前景控件。
            val frame = backdrop?.frame
            val touchProgress = (materialProgress ?: touchInteraction.pressProgress).coerceIn(0f, Theme.NavGlass.MaxPress)
            // 输入当前图层变换；输出移动/膨胀滑块下实时投影，而非拖着旧背景一起走。
            val liveBounds = coordinates?.takeIf { it.isAttached }?.let { placed ->
                val origin = placed.localToRoot(Offset.Zero)
                val endX = placed.localToRoot(Offset(placed.size.width.toFloat(), 0f))
                val endY = placed.localToRoot(Offset(0f, placed.size.height.toFloat()))
                Rect(origin, Size((endX.x - origin.x).coerceAtLeast(1f), (endY.y - origin.y).coerceAtLeast(1f)))
            } ?: surfaceBounds
            if (refraction != null && frame != null) drawIntoCanvas { canvas ->
                refraction.draw(canvas.nativeCanvas, frame, liveBounds, size, cornerRadii, tint = videoTint,
                    touch = touchInteraction.touch ?: Offset(size.width / 2f, size.height / 2f),
                    press = touchProgress, releaseProgress = touchInteraction.releaseProgress, tuning = tuning,
                    lightDirection = lightDirection)
            }
            // 输入 GPU 能力；输出仅永久降级时描边，唤出控制栏等待视频帧时不补白圈。
            if (refraction == null) drawOutline(outline, innerEdge, style = Stroke(Theme.Glass.InnerEdge.toPx()))
            touchInteraction.touch?.takeIf { touchProgress > 0f }?.let { point ->
                drawCircle(
                    brush = Brush.radialGradient(listOf(Theme.NavGlass.TouchLight.copy(alpha = Theme.NavGlass.TouchLight.alpha * touchProgress.coerceAtMost(1f)), Color.Transparent), center = point, radius = size.height.coerceAtLeast(1f) * Theme.NavGlass.TouchRadius),
                    radius = size.height.coerceAtLeast(1f) * Theme.NavGlass.TouchRadius,
                    center = point,
                )
            }
            if (refraction == null) drawOutline(outline, edge, style = Stroke(Theme.Glass.Edge.toPx()))
            // 所有材质和光线先画；最后才绘制按钮、进度与文字，防止被玻璃盖住。
            drawContent()
        }
    }, content = content)
}
