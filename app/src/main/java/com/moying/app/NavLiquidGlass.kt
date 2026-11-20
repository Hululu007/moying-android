/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See app/src/main/assets/licenses/Kyant-Backdrop-Apache-2.0.txt for full terms.
 *
 * Modified 2026-11-02 for Moying. Adapted GPU backdrop projection and
 * velocity/press-driven lens animation from Kyant0/AndroidLiquidGlass,
 * commit 65ab177e90e5c1d8c62e70cf7755841982da65f6, LayerBackdrop.kt,
 * DrawBackdropModifier.kt and catalog/components/LiquidBottomTabs.kt.
 * Added bounded scroll tethering and continuous swipe selection; foreground
 * remains separate and the backdrop projection compensates layer transforms.
 */
package com.moying.app

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.tanh
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object NavigationGestureTiming {
    const val FirstEventMillis = 16L
    const val MillisPerSecond = 1_000f
}

private const val PressExpansionYRatio = .25f
private const val LensTouchFollow = .08f

/** The pointer updates the glass directly. Only the return to rest needs an animation. */
private class NavigationGlassPull {
    var offset by mutableStateOf(Offset.Zero)
    private var returnJob: Job? = null

    fun stopReturning() {
        returnJob?.cancel()
        returnJob = null
    }

    fun settle(scope: CoroutineScope) {
        stopReturning()
        returnJob = scope.launch {
            Animatable(offset, Offset.VectorConverter).animateTo(
                Offset.Zero, spring(Theme.NavGlass.TetherDamping, Theme.NavGlass.TetherStiffness),
            ) { offset = value }
        }
    }
}

/** 输入：选中项、滚动牵引和提交回调；输出：跟手滑动、弹性吸附及保持世界坐标的玻璃导航。 */
@Composable
fun NavLiquidGlass(
    selectedIndex: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    scrollState: ScrollNavigationState? = null,
    onSelected: (Int) -> Unit = {},
    linkedPosition: State<Float>? = null,
    onPagerDragStart: () -> Unit = {},
    onPagerDragPosition: (Float) -> Unit = {},
    onPagerDragEnd: (Int, Float) -> Unit = { _, _ -> },
    onPagerDragCancel: () -> Unit = {},
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.(State<Float>) -> Unit,
) {
    val tuningState = LocalGlassTuning.current
    val light = LocalGlassLight.current
    val backdrop = if (glassEnabled) LocalPageBackdrop.current else null
    val ambient = if (glassEnabled) LocalAmbientBackdrop.current else null
    val feedback = LocalInteractionHaptics.current
    val density = LocalDensity.current
    // Keep the compiled material and its last uniform snapshot across switch changes.
    val material by remember(density.density) {
        lazy(LazyThreadSafetyMode.NONE) { createNavigationGlassEffect(density.density, CatalogGlassStyle.BOTTOM_TABS) }
    }
    val glassLayer = rememberGraphicsLayer()
    val animationScope = rememberCoroutineScope()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val touchInteraction = rememberGlassTouchInteraction()
    var dragging by remember { mutableStateOf(false) }
    var navigationWidth by remember { mutableFloatStateOf(1f) }
    var dragVelocity by remember { mutableFloatStateOf(0f) }
    var linkedVelocity by remember { mutableFloatStateOf(0f) }
    var releaseVelocity by remember { mutableStateOf<Float?>(null) }
    val selection = remember { Animatable(selectedIndex.toFloat()) }
    val pull = remember { NavigationGlassPull() }
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelected by rememberUpdatedState(onSelected)
    val latestLinkedPosition by rememberUpdatedState(linkedPosition)
    val latestOnDragStart by rememberUpdatedState(onPagerDragStart)
    val latestOnDragPosition by rememberUpdatedState(onPagerDragPosition)
    val latestOnDragEnd by rememberUpdatedState(onPagerDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onPagerDragCancel)
    val selectionPosition = remember {
        derivedStateOf { latestLinkedPosition?.value ?: selection.value }
    }
    val lensPosition = remember {
        derivedStateOf { selectionPosition.value + if (latestLinkedPosition != null) pull.offset.y else 0f }
    }
    LaunchedEffect(selectedIndex, dragging, linkedPosition) {
        if (linkedPosition == null && !dragging) {
            val velocity = releaseVelocity ?: selection.velocity
            releaseVelocity = null
            // 独立使用时才负责吸附；接入 Pager 时位置只有一个来源。
            selection.animateTo(selectedIndex.toFloat(),
                spring(Theme.NavGlass.SelectionDamping, Theme.NavGlass.SelectionStiffness),
                initialVelocity = velocity)
        }
    }
    LaunchedEffect(linkedPosition) {
        val position = linkedPosition ?: return@LaunchedEffect
        var previous = position.value
        var previousAt = SystemClock.uptimeMillis()
        snapshotFlow { position.value }.drop(1).collectLatest { value ->
            val now = SystemClock.uptimeMillis()
            val sample = (value - previous) / (now - previousAt).coerceAtLeast(1L) * NavigationGestureTiming.MillisPerSecond
            val boundedVelocity = sample.coerceIn(-Theme.NavGlass.MaxSwipeVelocity, Theme.NavGlass.MaxSwipeVelocity)
            linkedVelocity += (boundedVelocity - linkedVelocity) * Theme.NavGlass.TetherFollow
            previous = value
            previousAt = now
            delay(Theme.BottomTabsGlass.VelocityResetMillis)
            Animatable(linkedVelocity).animateTo(
                0f, spring(Theme.NavGlass.TetherDamping, Theme.NavGlass.TetherStiffness),
            ) { linkedVelocity = value }
        }
    }
    LaunchedEffect(scrollState, feedback) {
        if (scrollState != null) snapshotFlow { scrollState.boundaryEvent }.drop(1).collect { feedback.boundary() }
    }

    Box(modifier.graphicsLayer {
        val vertical = abs(scrollState?.pullFraction ?: 0f)
        val horizontalLimit = Theme.NavGlass.HorizontalTether.toPx()
        val horizontal = (pull.offset.x / horizontalLimit.coerceAtLeast(1f)).coerceIn(-1f, 1f)
        val pressure = touchInteraction.pressProgress.coerceIn(0f, Theme.NavGlass.MaxPress)
        val expansion = Theme.BottomTabsGlass.PressExpansion.toPx() * pressure
        val pressScaleX = 1f + expansion / size.width.coerceAtLeast(1f)
        val pressScaleY = 1f + expansion * PressExpansionYRatio / size.height.coerceAtLeast(1f)
        scaleX = pressScaleX * (1f - vertical * Theme.NavGlass.TetherScaleX + abs(horizontal) * Theme.NavGlass.TetherScaleX)
        scaleY = pressScaleY * (1f + vertical * Theme.NavGlass.TetherScaleY - abs(horizontal) * Theme.NavGlass.TetherScaleY)
        // 外边距内保留最大牵引，永远不会被拉到屏幕外或盖住系统手势区。
        val availableX = (Theme.BottomTabsGlass.SideInset.toPx() - size.width * Theme.NavGlass.TetherScaleX / 2f - Theme.Space.Hairline.toPx()).coerceAtLeast(0f)
        translationX = pull.offset.x.coerceIn(-availableX, availableX)
        translationY = scrollState?.offsetY ?: 0f
    }.observeGlassTouch(touchInteraction).pointerInput(itemCount, density.density) {
        var startPosition = 0f
        var startIndex = 0
        var distance = 0f
        var lastBounded = 0f
        var lastMoveAt = 0L
        var lastDetent = 0
        var boundarySent = false
        val velocityTracker = VelocityTracker()
        val horizontalLimit = with(density) { Theme.NavGlass.HorizontalTether.toPx() }
        val inset = with(density) { Theme.NavGlass.CapsuleInset.toPx() }
        fun tabWidth() = ((navigationWidth - inset * 2f) / itemCount.coerceAtLeast(1)).coerceAtLeast(1f)
        detectHorizontalDragGestures(
            onDragStart = { point ->
                latestOnDragStart()
                startPosition = selectionPosition.value
                startIndex = startPosition.roundToInt().coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                distance = 0f
                lastBounded = startPosition
                lastMoveAt = SystemClock.uptimeMillis()
                velocityTracker.resetTracking()
                velocityTracker.addPosition(lastMoveAt, Offset.Zero)
                lastDetent = startIndex
                boundarySent = false
                dragVelocity = 0f
                dragging = true
                pull.stopReturning()
                touchInteraction.moveTouch(point)
                feedback.tap()
                if (latestLinkedPosition == null) {
                    animationScope.launch(start = CoroutineStart.UNDISPATCHED) { selection.stop() }
                }
            },
            onDragEnd = {
                val lastIndex = (itemCount - 1).coerceAtLeast(0)
                velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(distance, 0f))
                val velocity = (velocityTracker.calculateVelocity().x / tabWidth())
                    .coerceIn(-Theme.NavGlass.MaxSwipeVelocity, Theme.NavGlass.MaxSwipeVelocity)
                val target = (lastBounded + velocity * Theme.BottomTabsGlass.FlingProjectionSeconds)
                    .roundToInt().coerceIn(0, lastIndex)
                releaseVelocity = velocity
                dragging = false
                pull.settle(animationScope)
                if (target != startIndex) feedback.confirm()
                latestOnDragEnd(target, velocity)
                if (latestLinkedPosition == null && target != latestSelected) latestOnSelected(target)
            },
            onDragCancel = {
                releaseVelocity = 0f
                dragging = false
                pull.settle(animationScope)
                latestOnDragCancel()
            },
        ) { change, amount ->
            change.consume() // 超过系统横向 slop 后取消子项点击；单击和无障碍仍交给 clickable。
            touchInteraction.moveTouch(change.position)
            distance += amount
            val now = SystemClock.uptimeMillis()
            val elapsed = if (lastMoveAt == 0L) NavigationGestureTiming.FirstEventMillis else (now - lastMoveAt).coerceAtLeast(1L)
            lastMoveAt = now
            val cell = tabWidth()
            val raw = startPosition + distance / cell
            val lastIndex = (itemCount - 1).coerceAtLeast(0).toFloat()
            val bounded = raw.coerceIn(0f, lastIndex)
            lastBounded = bounded
            val overshoot = raw - bounded
            val resisted = bounded + Theme.NavGlass.SwipeRubber * tanh(overshoot)
            val velocity = (amount / cell / elapsed * NavigationGestureTiming.MillisPerSecond)
                .coerceIn(-Theme.NavGlass.MaxSwipeVelocity, Theme.NavGlass.MaxSwipeVelocity)
            dragVelocity += (velocity - dragVelocity) * Theme.NavGlass.TetherFollow
            velocityTracker.addPosition(change.uptimeMillis, Offset(distance, 0f))
            // A bounded rubber pull inspired by OShin's LiquidButton.
            pull.offset = Offset(horizontalLimit * tanh(distance / cell), resisted - bounded)
            // Pager 接收每次 move；橡皮筋仅施加给边界外的透镜，不制造第二套页码动画。
            latestOnDragPosition(bounded)
            if (latestLinkedPosition == null) {
                animationScope.launch(start = CoroutineStart.UNDISPATCHED) { selection.snapTo(resisted) }
            }
            val detent = bounded.roundToInt()
            if (detent != lastDetent) { feedback.detent(); lastDetent = detent }
            if (raw !in 0f..lastIndex && !boundarySent) { feedback.boundary(); boundarySent = true }
        }
    }.onGloballyPositioned {
        coordinates = it
        navigationWidth = it.size.width.toFloat()
    }) {
      Box(Modifier.matchParentSize().drawWithCache {
        val padding = ceil(Theme.NavGlass.SamplePadding.toPx())
        val layerSize = IntSize(ceil(size.width + padding * 2f).toInt(), ceil(size.height + padding * 2f).toInt())
        val capsuleInset = Theme.NavGlass.CapsuleInset.toPx()
        val capsuleGap = Theme.NavGlass.CapsuleGap.toPx()
        val stroke = Theme.NavGlass.CapsuleStroke.toPx()
        val outerStroke = Theme.NavGlass.OuterStroke.toPx()
        val radius = size.height / 2f
        val outerRim = Brush.verticalGradient(listOf(Theme.NavGlass.TopRim, Theme.NavGlass.QuietRim, Theme.NavGlass.BottomRim))
        var recordedLayer: GraphicsLayer? = null
        var recordedOffset = Offset.Unspecified
        var recordedScale = Size.Unspecified
        onDrawBehind {
            val pressure = touchInteraction.pressProgress.coerceIn(0f, Theme.NavGlass.MaxPress)
            val progress = pressure.coerceAtMost(1f)
            val cell = ((size.width - capsuleInset * 2f) / itemCount.coerceAtLeast(1)).coerceAtLeast(1f)
            val verticalPull = abs(scrollState?.pullFraction ?: 0f)
            val horizontal = abs(pull.offset.x) / Theme.NavGlass.HorizontalTether.toPx().coerceAtLeast(1f)
            val velocity = abs(if (latestLinkedPosition != null) linkedVelocity else if (dragging) dragVelocity else selection.velocity) /
                ((itemCount - 1).coerceAtLeast(1) * Theme.BottomTabsGlass.VelocityDivisor)
            val lensScale = Theme.BottomTabsGlass.LensRestScale +
                (Theme.BottomTabsGlass.LensPressedScale - Theme.BottomTabsGlass.LensRestScale) * pressure
            val velocityX = (velocity * Theme.BottomTabsGlass.VelocityStretchX)
                .coerceAtMost(Theme.BottomTabsGlass.MaxVelocityDistortion)
            val velocityY = (velocity * Theme.BottomTabsGlass.VelocitySquashY)
                .coerceAtMost(Theme.BottomTabsGlass.MaxVelocityDistortion)
            val tether = (verticalPull + horizontal) * Theme.NavGlass.TetherScaleX
            val lensWidth = (cell - capsuleGap * 2f) * lensScale / (1f - velocityX) * (1f + tether)
            val lensHeight = (size.height - capsuleInset * 2f) * lensScale * (1f - velocityY)
            val center = capsuleInset + (lensPosition.value + .5f) * cell
            val contact = touchInteraction.touch ?: Offset(center, size.height / 2f)
            val follow = tanh((contact.x - center) / cell) * cell * LensTouchFollow * progress
            val capsule = Rect(center + follow - lensWidth / 2f, (size.height - lensHeight) / 2f,
                center + follow + lensWidth / 2f, (size.height + lensHeight) / 2f)
            val placed = coordinates?.takeIf { it.isAttached }
            // Keep the same backdrop through a drag so the glass does not change tint.
            val usePage = backdrop?.enabled == true && backdrop.ready
            // The persistent pageLayer is a live display-list dependency. Its
            // children update without a per-frame observable invalidation loop.
            val inputLayer = if (usePage) backdrop?.pageLayer else ambient?.takeIf { it.ready }?.layer
            val inputBounds = if (usePage) backdrop?.hostBounds else ambient?.bounds
            val effect = if (inputLayer != null && inputBounds != null && placed != null) material else null
            val tuning = tuningState.value
            if (effect == null) {
                val fill = if (glassEnabled) Theme.NavGlass.FallbackFill.copy(alpha = tuning.translucentOpacity)
                    else Theme.NavGlass.FallbackFill
                drawRoundRect(fill, cornerRadius = CornerRadius(radius))
            }
            if (effect != null && inputLayer != null && inputBounds != null && placed != null) {
                val origin = placed.localToRoot(Offset.Zero)
                val endX = placed.localToRoot(Offset(size.width, 0f))
                val endY = placed.localToRoot(Offset(0f, size.height))
                val sx = ((endX.x - origin.x) / size.width.coerceAtLeast(1f)).coerceAtLeast(.01f)
                val sy = ((endY.y - origin.y) / size.height.coerceAtLeast(1f)).coerceAtLeast(.01f)
                // The moving lens is part of the shell's coordinate mapping, so
                // both read the page through a single blur and shader pass.
                effect.update(size, padding, capsule, contact, progress,
                    surfaceTint = Theme.NavGlass.Tint, releaseProgress = touchInteraction.releaseProgress,
                    tuning = tuning,
                    lightDirection = if (tuning.highlight > 0f) light.value else Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY))
                val renderEffect = effect.effect
                if (glassLayer.renderEffect !== renderEffect) glassLayer.renderEffect = renderEffect
                val sampleOffset = Offset(padding + (inputBounds.left - origin.x) / sx,
                    padding + (inputBounds.top - origin.y) / sy)
                val sampleScale = Size(1f / sx, 1f / sy)
                if (recordedLayer !== inputLayer || recordedOffset != sampleOffset || recordedScale != sampleScale) {
                    glassLayer.record(size = layerSize) {
                        // Cull page draws outside the navigation viewport and its sampling margin.
                        clipRect(0f, 0f, layerSize.width.toFloat(), layerSize.height.toFloat()) {
                            translate(sampleOffset.x, sampleOffset.y) {
                                scale(sampleScale.width, sampleScale.height, pivot = Offset.Zero) { drawLayer(inputLayer) }
                            }
                        }
                    }
                    recordedLayer = inputLayer
                    recordedOffset = sampleOffset
                    recordedScale = sampleScale
                }
                translate(-padding, -padding) { drawLayer(glassLayer) }
            }
            drawRoundRect(Theme.Colors.Text.copy(alpha = Theme.NavGlass.CapsuleTintAlpha * (1f - progress)),
                capsule.topLeft, capsule.size, CornerRadius(lensHeight / 2f))
            // 不因切页首帧临时补亮边，继续保留 1.7.3 的闪白修复。
            if (effect == null && (!glassEnabled || inputLayer != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)) {
                drawRoundRect(Theme.NavGlass.CapsuleRim, capsule.topLeft, capsule.size,
                    CornerRadius(lensHeight / 2f), style = Stroke(stroke))
                drawRoundRect(outerRim, Offset(outerStroke / 2f, outerStroke / 2f),
                    Size(size.width - outerStroke, size.height - outerStroke), CornerRadius(radius),
                    alpha = if (glassEnabled) (tuning.highlight * 2f).coerceAtMost(1f) else 1f,
                    style = Stroke(outerStroke))
            }
        }
      })
      Box(Modifier.fillMaxSize().zIndex(Theme.NavGlass.ForegroundZ)) {
          content(selectionPosition)
      }
    }
}
