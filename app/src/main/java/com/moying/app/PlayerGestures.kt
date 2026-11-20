package com.moying.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewTreeObserver
import android.view.ViewConfiguration as AndroidViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** 手势行为参数，与界面视觉参数分开。 */
private object GestureTuning {
    const val SeekWindowMillis = 120_000L
    const val FullRange = 1f
    const val HalfWidth = .5f
    const val MinimumBrightness = .02f
    const val SystemBrightnessMaximum = 255f
    const val DefaultSystemBrightness = 128
    const val Percent = 100
}

private class PendingGestureTap(val position: Offset, val upTime: Long) {
    var dispatch: Runnable? = null
}

/** 输入：播放接口及回调；输出：延迟单击、双击、临时长按倍速，以及原有亮度/音量/定位手势。 */
@Composable
fun PlayerGestures(
    controller: PlayerController,
    onTap: () -> Unit,
    onSeek: (Long) -> Unit,
    onActive: (Boolean) -> Unit,
    onFeedback: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
    onHoldSpeedChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val haptics = LocalInteractionHaptics.current
    val configuration = LocalConfiguration.current
    val windowFocus = remember(hostView) { GestureWindowFocus(hostView.hasWindowFocus()) }
    val window = remember(context) { context.activity()?.window }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val doubleTapSlop = remember(context) { AndroidViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    var gestureSize by remember { mutableStateOf(IntSize.Zero) }
    var layerBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    val latestTap by rememberUpdatedState(onTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestActive by rememberUpdatedState(onActive)
    val latestFeedback by rememberUpdatedState(onFeedback)
    val latestHoldSpeed by rememberUpdatedState(onHoldSpeedChanged)
    DisposableEffect(hostView, windowFocus) {
        val observer = hostView.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener(windowFocus::update)
        observer.addOnWindowFocusChangeListener(listener)
        windowFocus.update(hostView.hasWindowFocus())
        onDispose {
            val currentObserver = if (observer.isAlive) observer else hostView.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnWindowFocusChangeListener(listener)
            windowFocus.dispose()
        }
    }
    DisposableEffect(window) {
        val original = window?.attributes?.screenBrightness
        onDispose {
            if (original != null) window?.let { target -> target.attributes = target.attributes.apply { screenBrightness = original } }
        }
    }
    Box(modifier.semantics {
        contentDescription = "播放画面，单击显示或隐藏控制栏，双击暂停或播放，长按临时3倍速，左侧调亮度，右侧调音量，横滑调进度"
    }.onGloballyPositioned { layerBoundsInWindow = it.boundsInWindow() }
    .onSizeChanged { gestureSize = it }.pointerInput(
        controller, window, audio, doubleTapSlop, gestureSize, hostView, windowFocus.hasFocus, windowFocus.revision,
        configuration.screenWidthDp, configuration.screenHeightDp,
    ) {
        if (!windowFocus.hasFocus || !hostView.hasWindowFocus()) return@pointerInput
        val inputJob = currentCoroutineContext()[Job]
        val cancelForFocusLoss: () -> Unit = { inputJob?.cancel() }
        val handler = Handler(Looper.getMainLooper())
        val pendingTaps = mutableListOf<PendingGestureTap>()
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        val doubleTapMinTime = viewConfiguration.doubleTapMinTimeMillis

        /** 输入：短按记录；输出：在系统双击窗口结束后才发出单击。 */
        fun queueTap(tap: PendingGestureTap) {
            val dispatch = Runnable {
                if (pendingTaps.remove(tap) && windowFocus.hasFocus && hostView.hasWindowFocus()) latestTap()
            }
            tap.dispatch = dispatch
            pendingTaps += tap
            val delay = (tap.upTime + doubleTapTimeout - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed(dispatch, delay)
        }

        /** 输入：新一轮按下；输出：暂挂所有单击，避免第二次按住期间先触发显隐。 */
        fun suspendPendingTaps(): MutableList<PendingGestureTap> {
            val suspended = pendingTaps.toMutableList()
            suspended.forEach { it.dispatch?.let(handler::removeCallbacks) }
            pendingTaps.clear()
            return suspended
        }

        windowFocus.bindCancellation(cancelForFocusLoss)
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val previousTaps = suspendPendingTaps()
                val start = down.position
                // 起手即判断系统区，整次让行；不能先调整亮度/音量，再等待系统取消。
                if (down.isConsumed || !windowFocus.hasFocus || !hostView.hasWindowFocus() ||
                    currentEvent.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                    isSystemGestureStart(hostView, layerBoundsInWindow, start)
                ) return@awaitEachGesture
                val startPosition = controller.getPosition()
                val duration = controller.getDuration()
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                val startBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, GestureTuning.DefaultSystemBrightness) / GestureTuning.SystemBrightnessMaximum)
                var direction: String? = null
                var targetPosition = startPosition
                var released = false
                var cancelled = false
                var upTime = down.uptimeMillis
                var longPressLocked = false
                var speedBeforeHold: Float? = null
                var holdConfirmed = false
                val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                latestActive(true)
                try {
                    while (true) {
                        if (!windowFocus.hasFocus || !hostView.hasWindowFocus()) { cancelled = true; break }
                        val event = if (direction == null && !longPressLocked) {
                            val remaining = longPressDeadline - SystemClock.uptimeMillis()
                            // AwaitPointerEventScope 的超时会在无 MOVE 时到期，静止按住也能触发。
                            if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (!windowFocus.hasFocus || !hostView.hasWindowFocus()) { cancelled = true; break }
                        if (event == null) {
                            longPressLocked = true
                            previousTaps.clear()
                            if (controller.isPlaying) {
                                speedBeforeHold = controller.speed
                                controller.setSpeed(Playback.HoldSpeed)
                                holdConfirmed = controller.speed == Playback.HoldSpeed
                                latestHoldSpeed(holdConfirmed)
                                if (holdConfirmed) haptics.hold()
                                latestFeedback(if (holdConfirmed) "3倍速播放中" else "当前片源暂不支持3倍速")
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val anotherPointer = event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }
                        if (change == null || change.isConsumed || anotherPointer) { cancelled = true; break }
                        if (!change.pressed) {
                            // 某些快速滑动只有 DOWN/UP；终点越过阈值也不能误判为单击或双击。
                            if (!longPressLocked && direction == null && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                cancelled = true
                                previousTaps.clear()
                                break
                            }
                            if (longPressLocked) change.consume()
                            released = true
                            upTime = change.uptimeMillis
                            break
                        }
                        if (longPressLocked) {
                            // 长按已锁定；离开画面取消，画面内移动不会变成音量、亮度或定位。
                            change.consume()
                            if (change.position.x < 0f || change.position.y < 0f || change.position.x > size.width || change.position.y > size.height) {
                                cancelled = true
                                break
                            }
                            continue
                        }
                        val delta: Offset = change.position - start
                        if (direction == null && delta.getDistance() > viewConfiguration.touchSlop) {
                            previousTaps.clear()
                            direction = if (abs(delta.x) >= abs(delta.y)) "seek" else if (start.x < size.width * GestureTuning.HalfWidth) "brightness" else "volume"
                        }
                        when (direction) {
                            "seek" -> {
                                if (duration > Playback.Start && size.width > 0) {
                                    val windowMillis = minOf(duration, GestureTuning.SeekWindowMillis)
                                    targetPosition = (startPosition + (delta.x / size.width * windowMillis).toLong()).coerceIn(Playback.Start, duration)
                                    val offset = targetPosition - startPosition
                                    latestFeedback("${if (offset >= Playback.Start) "+" else "−"}${formatTime(abs(offset))}  ·  ${formatTime(targetPosition)} / ${formatTime(duration)}")
                                } else latestFeedback("直播暂不支持定位")
                            }
                            "brightness" -> {
                                val level = (startBrightness - delta.y / size.height.coerceAtLeast(1)).coerceIn(GestureTuning.MinimumBrightness, GestureTuning.FullRange)
                                window?.let { target -> target.attributes = target.attributes.apply { screenBrightness = level } }
                                latestFeedback("亮度  ${(level * GestureTuning.Percent).roundToInt()}%")
                            }
                            "volume" -> {
                                val level = (startVolume - delta.y / size.height.coerceAtLeast(1)).coerceIn(0f, GestureTuning.FullRange)
                                val volume = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                latestFeedback("音量  ${(volume.toFloat() / maxVolume * GestureTuning.Percent).roundToInt()}%")
                            }
                        }
                        if (direction != null) change.consume()
                    }
                    if (released && !cancelled && !longPressLocked) {
                        if (direction == null) {
                            val doubleTap = latestDoubleTap
                            val matched = if (doubleTap == null) null else previousTaps.lastOrNull {
                                down.uptimeMillis - it.upTime in doubleTapMinTime..doubleTapTimeout &&
                                    (start - it.position).getDistance() <= doubleTapSlop
                            }
                            previousTaps.filterNot { it === matched }.forEach(::queueTap)
                            if (matched != null && doubleTap != null) doubleTap()
                            else queueTap(PendingGestureTap(start, upTime))
                        } else if (direction == "seek" && duration > Playback.Start) latestSeek(targetPosition)
                    }
                } finally {
                    try {
                        // 抬手、取消、多指、旋转和离开页面都走恢复；这里只还原倍速，不调用 play。
                        speedBeforeHold?.let(controller::setSpeed)
                    } finally {
                        // 输入：抬手或取消；输出：清除顶部提示，生效的长按只在结束时轻反馈一次。
                        latestHoldSpeed(false)
                        if (holdConfirmed) haptics.detent()
                        if (longPressLocked || cancelled || !windowFocus.hasFocus || !hostView.hasWindowFocus()) latestFeedback(null)
                        latestActive(false)
                    }
                }
            }
        } finally {
            suspendPendingTaps()
            windowFocus.clearCancellation(cancelForFocusLoss)
        }
    })
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}
