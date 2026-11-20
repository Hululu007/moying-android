/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * Full license: assets/licenses/Kyant-Backdrop-Apache-2.0.txt.
 *
 * Modified 2026-11-03 for Moying. Adapted LiquidSlider and DampedDragAnimation
 * from Kyant0/AndroidLiquidGlass, commit 65ab177e90e5c1d8c62e70cf7755841982da65f6.
 * Uses stable Compose APIs and the app's existing backdrop renderer.
 */
package com.moying.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToLong

/** Catalog 鐨勮兌鍥婃粦鍧楋細鎷栧姩鍙瑙堬紝姝ｅ父鏉炬墜鎴栫偣鍑绘椂鎵嶅悜鎾斁鍣ㄦ彁浜や竴娆°€?*/
@Composable
fun LiquidSlider(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bufferedPosition: Long = Playback.Start,
    trackOffset: Dp = Theme.Space.None,
    contentLabel: String = "播放进度",
    describeValue: ((Long) -> String)? = null,
    onPreviewChange: ((Long?) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val feedback = LocalInteractionHaptics.current
    val enabled = duration > Playback.Start
    val externalFraction = sliderFraction(position, duration)
    val bufferedFraction = sliderFraction(bufferedPosition, duration)
    var previewFraction by remember(duration) { mutableFloatStateOf(externalFraction) }
    var tracking by remember(duration) { mutableStateOf(false) }
    var expanded by remember(duration) { mutableStateOf(false) }
    var pendingSeek by remember(duration) { mutableStateOf<Long?>(null) }
    var dragVelocity by remember(duration) { mutableFloatStateOf(Theme.Size.EmptyFraction) }
    val targetFraction = if (tracking) previewFraction else pendingSeek?.let { sliderFraction(it, duration) } ?: externalFraction
    val latestSeek by rememberUpdatedState(onSeek)
    val latestPreviewChange by rememberUpdatedState(onPreviewChange)
    val latestFraction by rememberUpdatedState(targetFraction)

    // 控制器可能下一次轮询才报告新位置；保留松手预览，避免滑块先弹回旧播放位置。
    LaunchedEffect(position, pendingSeek, tracking) {
        val pending = pendingSeek
        if (!tracking && pending != null &&
            abs(position.toDouble() - pending.toDouble()) <= Theme.SliderGlass.SeekAcknowledgementMillis
        ) pendingSeek = null
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(Theme.SliderGlass.SeekTimeoutMillis)
            pendingSeek = null
        }
    }

    val animatedFraction = animateFloatAsState(
        targetFraction,
        spring(Theme.SliderGlass.ValueDamping, Theme.SliderGlass.ValueStiffness, Theme.SliderGlass.ValueThreshold),
        label = "catalog-slider-position",
    )
    val press = animateFloatAsState(
        if (expanded) Theme.Size.FullFraction else Theme.Size.EmptyFraction,
        spring(Theme.SliderGlass.PressDamping, Theme.SliderGlass.PressStiffness, Theme.SliderGlass.ScaleThreshold),
        label = "catalog-slider-glass",
    )
    val scaleX = animateFloatAsState(
        if (expanded) Theme.SliderGlass.PressedScale else Theme.SliderGlass.RestScale,
        spring(Theme.SliderGlass.ScaleXDamping, Theme.SliderGlass.ScaleStiffness, Theme.SliderGlass.ScaleThreshold),
        label = "catalog-slider-scale-x",
    )
    val scaleY = animateFloatAsState(
        if (expanded) Theme.SliderGlass.PressedScale else Theme.SliderGlass.RestScale,
        spring(Theme.SliderGlass.ScaleYDamping, Theme.SliderGlass.ScaleStiffness, Theme.SliderGlass.ScaleThreshold),
        label = "catalog-slider-scale-y",
    )
    val velocity = animateFloatAsState(
        if (tracking) dragVelocity else Theme.Size.EmptyFraction,
        spring(Theme.SliderGlass.VelocityDamping, Theme.SliderGlass.VelocityStiffness, Theme.SliderGlass.VelocityThreshold),
        label = "catalog-slider-velocity",
    )
    LaunchedEffect(tracking, targetFraction, expanded) {
        if (!tracking && expanded) {
            // 与上游相同，位置接近目标后才释放玻璃的膨胀形变。
            withFrameNanos { }
            snapshotFlow { abs(animatedFraction.value - targetFraction) }
                .first { it <= Theme.SliderGlass.ReleaseSettleFraction }
            expanded = false
        }
    }

    fun submit(fraction: Float, confirm: Boolean) {
        if (!enabled || !fraction.isFinite()) return
        val bounded = fraction.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
        val target = (duration.toDouble() * bounded).roundToLong().coerceIn(Playback.Start, duration)
        previewFraction = bounded
        pendingSeek = target
        expanded = true
        if (confirm) feedback.confirm()
        latestSeek(target)
    }

    BoxWithConstraints(
        contentAlignment = AbsoluteAlignment.TopLeft,
        modifier = modifier.fillMaxWidth().height(Theme.Size.SeekHeight)
            .semantics {
                val value = (duration.coerceAtLeast(Playback.Start).toDouble() * targetFraction).roundToLong()
                contentDescription = contentLabel
                stateDescription = describeValue?.invoke(value) ?: "${formatTime(value)}，总时长 ${formatTime(duration)}"
                progressBarRangeInfo = ProgressBarRangeInfo(targetFraction, Theme.Size.EmptyFraction..Theme.Size.FullFraction)
                if (!enabled) disabled()
                setProgress { requested ->
                    if (enabled && requested.isFinite()) {
                        feedback.tap()
                        submit(requested, confirm = false)
                        true
                    } else false
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) false else {
                    val target = when (event.key) {
                        Key.DirectionLeft -> latestFraction + if (isLtr) -Theme.SliderGlass.KeyboardStep else Theme.SliderGlass.KeyboardStep
                        Key.DirectionRight -> latestFraction + if (isLtr) Theme.SliderGlass.KeyboardStep else -Theme.SliderGlass.KeyboardStep
                        Key.DirectionUp -> latestFraction + Theme.SliderGlass.KeyboardStep
                        Key.DirectionDown -> latestFraction - Theme.SliderGlass.KeyboardStep
                        Key.MoveHome -> Theme.Size.EmptyFraction
                        Key.MoveEnd -> Theme.Size.FullFraction
                        else -> null
                    }
                    if (target != null) {
                        feedback.tap()
                        submit(target, confirm = false)
                        true
                    } else false
                }
            }
            .focusable(enabled)
            .pointerInput(duration, isLtr, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                    // 进度条独占本次按下，避免播放器把按住滑块识别成临时三倍速。
                    down.consume()
                    val compactScale = minOf(Theme.Size.FullFraction,
                        size.height / (Theme.SliderGlass.ThumbHeight * Theme.SliderGlass.PressedScale).toPx())
                    val radius = minOf(Theme.SliderGlass.TrackHeight.toPx() * compactScale,
                        size.width.toFloat()) * Theme.Size.CenterFraction
                    val usableWidth = (size.width - radius / Theme.Size.CenterFraction)
                        .coerceAtLeast(Theme.Size.FullFraction)
                    fun fractionAt(x: Float): Float {
                        val physical = ((x - radius) / usableWidth)
                            .coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                        return if (isLtr) physical else Theme.Size.FullFraction - physical
                    }
                    val startFraction = latestFraction
                    val maximumScaleX = Theme.SliderGlass.PressedScale /
                        (Theme.Size.FullFraction - Theme.SliderGlass.MaxVelocityDistortion)
                    val thumbWidth = minOf(Theme.SliderGlass.ThumbWidth.toPx() * compactScale,
                        size.width / maximumScaleX)
                    val halfThumb = thumbWidth * sliderScaleX(scaleX.value, velocity.value) * Theme.Size.CenterFraction
                    val visibleFraction = if (isLtr) animatedFraction.value else Theme.Size.FullFraction - animatedFraction.value
                    val visibleCenter = (radius + usableWidth * visibleFraction)
                        .coerceIn(halfThumb, (size.width - halfThumb).coerceAtLeast(halfThumb))
                    val grabbedThumb = abs(down.position.x - visibleCenter) <= halfThumb
                    val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                    var dragged = false
                    var completed = false
                    previewFraction = startFraction
                    pendingSeek = null
                    tracking = true
                    expanded = true
                    dragVelocity = Theme.Size.EmptyFraction
                    feedback.tap()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                            val delta = change.position - down.position
                            if (!dragged && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                            if (!change.pressed) {
                                change.consume()
                                if (dragged) {
                                    val direction = if (isLtr) Theme.Size.FullFraction else -Theme.Size.FullFraction
                                    previewFraction = if (grabbedThumb) {
                                        (startFraction + direction * delta.x / usableWidth)
                                            .coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                                    } else fractionAt(change.position.x)
                                    submit(previewFraction, confirm = true)
                                }
                                else submit(fractionAt(change.position.x), confirm = false)
                                completed = true
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!dragged && abs(delta.x) > viewConfiguration.touchSlop) dragged = true
                            if (dragged) {
                                change.consume()
                                val direction = if (isLtr) Theme.Size.FullFraction else -Theme.Size.FullFraction
                                previewFraction = if (grabbedThumb) {
                                    (startFraction + direction * delta.x / usableWidth)
                                        .coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                                } else fractionAt(change.position.x)
                                latestPreviewChange?.invoke((duration.toDouble() * previewFraction).roundToLong())
                                dragVelocity = tracker.calculateVelocity().x / usableWidth * direction
                            }
                        }
                    } finally {
                        // ACTION_CANCEL、第二根手指、父级抢占与协程取消均不提交定位。
                        if (!completed) {
                            pendingSeek = null
                            latestPreviewChange?.invoke(null)
                        }
                        tracking = false
                        dragVelocity = Theme.Size.EmptyFraction
                    }
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (widthPx <= Theme.Size.EmptyFraction || heightPx <= Theme.Size.EmptyFraction) return@BoxWithConstraints
        val heightScale = minOf(Theme.Size.FullFraction,
            heightPx / with(density) { (Theme.SliderGlass.ThumbHeight * Theme.SliderGlass.PressedScale).toPx() })
        val trackHeightPx = minOf(with(density) { Theme.SliderGlass.TrackHeight.toPx() } * heightScale, widthPx)
        val trackStart = trackHeightPx * Theme.Size.CenterFraction
        val trackEnd = (widthPx - trackStart).coerceAtLeast(trackStart)
        val trackWidth = trackEnd - trackStart
        val trackY = (heightPx * Theme.Size.CenterFraction + with(density) { trackOffset.toPx() })
            .coerceIn(trackStart, (heightPx - trackStart).coerceAtLeast(trackStart))
        val thumbHeightPx = with(density) { Theme.SliderGlass.ThumbHeight.toPx() } * heightScale
        val maximumScaleX = Theme.SliderGlass.PressedScale /
            (Theme.Size.FullFraction - Theme.SliderGlass.MaxVelocityDistortion)
        val thumbWidthPx = minOf(with(density) { Theme.SliderGlass.ThumbWidth.toPx() } * heightScale,
            widthPx / maximumScaleX)
        val trackColor = Theme.SliderGlass.TrackColor
        val playedColor = Theme.SliderGlass.ProgressColor
        val bufferColor = Theme.Colors.Buffered

        fun physicalX(fraction: Float): Float = trackStart + trackWidth *
            if (isLtr) fraction else Theme.Size.FullFraction - fraction
        fun visualScaleX(): Float = sliderScaleX(scaleX.value, velocity.value)
        fun visualScaleY(): Float = (scaleY.value *
            (Theme.Size.FullFraction - (velocity.value / Theme.SliderGlass.VelocityDivisor * Theme.SliderGlass.VelocitySquashY)
                .coerceIn(-Theme.SliderGlass.MaxVelocityDistortion, Theme.SliderGlass.MaxVelocityDistortion)))
            .coerceIn(Theme.SliderGlass.MinScaleY, Theme.SliderGlass.PressedScale)
        fun centerX(): Float {
            val half = thumbWidthPx * visualScaleX() * Theme.Size.CenterFraction
            return physicalX(animatedFraction.value).coerceIn(half, (widthPx - half).coerceAtLeast(half))
        }
        fun centerY(): Float {
            val half = thumbHeightPx * visualScaleY() * Theme.Size.CenterFraction
            return trackY.coerceIn(half, (heightPx - half).coerceAtLeast(half))
        }

        Canvas(Modifier.matchParentSize()) {
            val value = animatedFraction.value.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
            val start = physicalX(Theme.Size.EmptyFraction)
            drawLine(trackColor, Offset(trackStart, trackY), Offset(trackEnd, trackY), trackHeightPx, StrokeCap.Round)
            drawLine(bufferColor, Offset(start, trackY), Offset(physicalX(bufferedFraction.coerceAtLeast(value)), trackY), trackHeightPx, StrokeCap.Round)
            drawLine(playedColor, Offset(start, trackY), Offset(physicalX(value), trackY), trackHeightPx, StrokeCap.Round)
        }

        GlassSurface(
            modifier = Modifier.size(with(density) { thumbWidthPx.toDp() }, with(density) { thumbHeightPx.toDp() })
                .graphicsLayer {
                    this.scaleX = visualScaleX()
                    this.scaleY = visualScaleY()
                    translationX = centerX() - thumbWidthPx * Theme.Size.CenterFraction
                    translationY = centerY() - thumbHeightPx * Theme.Size.CenterFraction
                },
            shape = RoundedCornerShape(Theme.Radius.Pill),
            tint = Color.Transparent,
            lightResponse = false,
            style = CatalogGlassStyle.SLIDER_THUMB,
            materialProgress = press.value.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p = press.value.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                val sx = visualScaleX()
                val sy = visualScaleY()
                val cx = centerX()
                val cy = centerY()
                val value = animatedFraction.value.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                fun localPoint(fraction: Float) = Offset(
                    (physicalX(fraction) - cx) / sx + size.width * Theme.Size.CenterFraction,
                    (trackY - cy) / sy + size.height * Theme.Size.CenterFraction,
                )
                // 适配上游 trackBackdrop：真实 GPU 背景上叠加细轨道投影，不复刻视频帧。
                val projectionX = Theme.SliderGlass.TrackProjectionRestScaleX +
                    (Theme.Size.FullFraction - Theme.SliderGlass.TrackProjectionRestScaleX) * p
                scale(projectionX, p) {
                    drawLine(trackColor.copy(alpha = trackColor.alpha * p), localPoint(Theme.Size.EmptyFraction), localPoint(Theme.Size.FullFraction), trackHeightPx / sy, StrokeCap.Round)
                    drawLine(bufferColor.copy(alpha = bufferColor.alpha * p), localPoint(Theme.Size.EmptyFraction), localPoint(bufferedFraction.coerceAtLeast(value)), trackHeightPx / sy, StrokeCap.Round)
                    drawLine(playedColor.copy(alpha = playedColor.alpha * p), localPoint(Theme.Size.EmptyFraction), localPoint(value), trackHeightPx / sy, StrokeCap.Round)
                }
                // Catalog 松手时纯白胶囊，按住后逐渐暴露真实背景、色散与蓝色轨道。
                drawRect(Color.White.copy(alpha = Theme.Size.FullFraction - p))
            }
        }
    }
}

private fun sliderFraction(position: Long, duration: Long): Float = if (duration > Playback.Start) {
    (position.toDouble() / duration).toFloat().coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
} else Theme.Size.EmptyFraction

private fun sliderScaleX(scale: Float, velocity: Float): Float = (scale /
    (Theme.Size.FullFraction - (velocity / Theme.SliderGlass.VelocityDivisor * Theme.SliderGlass.VelocityStretchX)
        .coerceIn(-Theme.SliderGlass.MaxVelocityDistortion, Theme.SliderGlass.MaxVelocityDistortion)))
    .coerceIn(Theme.SliderGlass.MinScaleY,
        Theme.SliderGlass.PressedScale / (Theme.Size.FullFraction - Theme.SliderGlass.MaxVelocityDistortion))

