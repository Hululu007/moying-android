package com.moying.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class HomePanel(val title: String) {
    SOURCES("片源与导入"), SEARCH("搜索影片"), APPEARANCE("主题与背景"), CACHE("离线缓存"), FAVORITES("我的收藏"),
}

@Stable
internal class CapsulePanelState(initial: HomePanel? = null) {
    var active by mutableStateOf(initial)
        private set
    var presented by mutableStateOf(initial)
        internal set
    internal val progress = Animatable(if (initial == null) 0f else 1f)
    internal val widthProgress = Animatable(if (initial == null) 0f else 1f)
    internal val anchors = mutableStateMapOf<HomePanel, Rect>()

    fun open(panel: HomePanel) { presented = panel; active = panel }
    fun dismiss() { active = null }

    companion object {
        val StateSaver = Saver<CapsulePanelState, String>(
            save = { it.active?.name.orEmpty() },
            restore = { saved -> CapsulePanelState(HomePanel.entries.find { it.name == saved }) },
        )
    }
}

@Composable
internal fun rememberCapsulePanelState() = rememberSaveable(saver = CapsulePanelState.StateSaver) { CapsulePanelState() }

/** Record the real capsule, keeping its layout slot while the travelling glass replaces it. */
internal fun Modifier.capsuleAnchor(state: CapsulePanelState, panel: HomePanel): Modifier =
    onGloballyPositioned { state.anchors[panel] = it.boundsInRoot() }
        .graphicsLayer { alpha = if (state.presented == panel) 0f else 1f }

/**
 * A single glass surface changes bounds from the capsule to the measured panel.
 * Text is measured once at the final width and clipped, never stretched. Everything
 * shares the Activity root so the background stays aligned through the transition.
 */
@Composable
internal fun CapsulePanelHost(
    state: CapsulePanelState,
    onDismiss: () -> Unit,
    content: @Composable (HomePanel, Boolean) -> Unit,
) {
    LaunchedEffect(state.active) {
        val target = if (state.active == null) 0f else 1f
        // A quick horizontal release leads the slower vertical spring. Reversal starts at the current bounds.
        coroutineScope {
            launch { state.widthProgress.animateTo(target, spring(.82f, 510f, Theme.PanelMotion.SettleThreshold)) }
            state.progress.animateTo(target, spring(.78f, 390f, Theme.PanelMotion.SettleThreshold))
        }
        if (state.active == null) state.presented = null
    }
    val panel = state.presented ?: return
    val isActive = state.active == panel
    val lastGeometry = remember(panel) { PanelGeometry() }
    val movingShape = remember(state, lastGeometry) { CapsuleMorphShape(state, lastGeometry) }
    val ready by remember(state, panel) { derivedStateOf { state.active == panel && state.progress.value >= Theme.PanelMotion.ContentReady } }
    val bodyVisible by remember(state, panel) { derivedStateOf { state.active == panel || state.progress.value > Theme.PanelMotion.ContentStart } }
    val widePanel = LocalWindowLayout.current.isWide
    val dismissInteraction = remember { MutableInteractionSource() }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(enabled = isActive, onBack = onDismiss)
    val panelSemantics = if (isActive) Modifier.semantics { paneTitle = panel.title } else Modifier
    val scrimAction = if (isActive) Modifier.clickable(
        interactionSource = dismissInteraction, indication = null, onClick = onDismiss,
    ) else Modifier
    Box(Modifier.fillMaxSize().then(panelSemantics)) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = state.progress.value.coerceIn(0f, 1f) }
            .background(Theme.Appearance.PanelScrim).then(scrimAction))
        Layout(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = Theme.Space.Medium, vertical = Theme.Space.Small)
                .onGloballyPositioned { containerOrigin = it.localToRoot(Offset.Zero) },
            content = {
                AmbientGlassSurface(
                    shape = movingShape,
                    lightResponse = false,
                    style = CatalogGlassStyle.OSHIN_PANEL,
                    panelExpansionProvider = { state.progress.value.coerceIn(0f, 1f) },
                ) {}
                if (bodyVisible) key(panel) {
                    val scroll = when (panel) {
                        HomePanel.APPEARANCE, HomePanel.FAVORITES -> Modifier
                        else -> Modifier.verticalScroll(rememberScrollState())
                    }
                    val closingInput = if (isActive) Modifier else Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                    Box(closingInput.then(scroll).then(if (ready) Modifier else Modifier.clearAndSetSemantics { })) {
                        content(panel, ready)
                    }
                }
                Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    when (panel) {
                        HomePanel.SOURCES -> AppText("片源", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                        HomePanel.SEARCH -> AppIcon(Theme.IconPaths.Search)
                        HomePanel.APPEARANCE -> AppIcon(Theme.IconPaths.Appearance)
                        HomePanel.CACHE -> AppIcon(Theme.IconPaths.Download)
                        HomePanel.FAVORITES -> AppIcon(Theme.IconPaths.Bookmark)
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val targetWidth = width.coerceAtMost(Theme.PanelMotion.MaxWidth.roundToPx()).coerceAtLeast(1)
            // 宽屏下放宽高度上限：内容仍在面板内滚动，但不像手机上那样过早截断。
            val targetMaxHeight = height.coerceAtMost((if (widePanel) Theme.Layout.PanelMaxHeightWide
                else Theme.Appearance.PanelMaxHeight).roundToPx()).coerceAtLeast(1)
            val body = if (bodyVisible) {
                val closingHeight = lastGeometry.bodyHeight.coerceIn(1, targetMaxHeight)
                val bodyConstraints = if (isActive) {
                    Constraints(minWidth = targetWidth, maxWidth = targetWidth, maxHeight = targetMaxHeight)
                } else Constraints.fixed(targetWidth, closingHeight)
                measurables[1].measure(bodyConstraints).also { if (isActive) lastGeometry.bodyHeight = it.height }
            } else null
            val bodyHeight = lastGeometry.bodyHeight.coerceIn(1, targetMaxHeight)
            val targetX = (width - targetWidth) / 2f
            // 宽屏下不再贴顶或贴底，改为垂直居中，避免面板看起来像一条悬边横条。
            val targetY = when {
                widePanel || panel == HomePanel.FAVORITES -> (height - bodyHeight) / 2f
                panel == HomePanel.APPEARANCE -> (height - bodyHeight).toFloat()
                else -> 0f
            }
            if (isActive) {
                state.anchors[panel]?.translate(-containerOrigin)
                    ?.takeIf { !it.isEmpty && it.bottom > 0f && it.top < height && it.right > 0f && it.left < width }
                    ?.let { lastGeometry.anchor = it }
            }
            val anchor = lastGeometry.anchor
                ?: Rect(width - Theme.Size.Touch.toPx(), 0f, width.toFloat(), Theme.Size.Touch.toPx())
            val horizontal = state.widthProgress.value.coerceIn(0f, 1.02f)
            val vertical = state.progress.value.coerceIn(0f, 1.02f)
            val visibility = vertical.coerceAtMost(1f)
            val x = interpolate(anchor.left, targetX, horizontal).roundToInt()
            val y = interpolate(anchor.top, targetY, vertical).roundToInt()
            val frameWidth = interpolate(anchor.width, targetWidth.toFloat(), horizontal).roundToInt().coerceAtLeast(1)
            val frameHeight = interpolate(anchor.height, bodyHeight.toFloat(), vertical).roundToInt().coerceAtLeast(1)
            val radius = interpolate(anchor.height / 2f, Theme.Appearance.PanelRadius.toPx(), visibility)
            val glass = measurables[0].measure(Constraints.fixed(frameWidth, frameHeight))
            val glyph = measurables.last().measure(Constraints.fixed(anchor.width.roundToInt().coerceAtLeast(1), anchor.height.roundToInt().coerceAtLeast(1)))
            layout(width, height) {
                glass.place(x, y)
                body?.placeWithLayer(x, y) {
                    alpha = ((visibility - Theme.PanelMotion.ContentStart) / (1f - Theme.PanelMotion.ContentStart)).coerceIn(0f, 1f)
                    shape = PanelRevealShape(frameWidth.toFloat(), frameHeight.toFloat(), radius)
                    clip = true
                }
                glyph.placeWithLayer(x + (frameWidth - glyph.width) / 2, y + (frameHeight - glyph.height) / 2) {
                    alpha = (1f - visibility / Theme.PanelMotion.GlyphEnd).coerceIn(0f, 1f)
                }
            }
        }
    }
}

// Written during active measurement and reused by the reverse morph after the body leaves composition.
private class PanelGeometry {
    var bodyHeight = 1
    var anchor: Rect? = null
}

private fun interpolate(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private class CapsuleMorphShape(private val state: CapsulePanelState, private val geometry: PanelGeometry) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) {
            val start = (geometry.anchor?.height ?: Theme.Size.Touch.toPx()) / 2f
            interpolate(start, Theme.Appearance.PanelRadius.toPx(), state.progress.value.coerceIn(0f, 1f))
        }
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
}

private class PanelRevealShape(private val width: Float, private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(0f, 0f, width, height, CornerRadius(radius)))
}
