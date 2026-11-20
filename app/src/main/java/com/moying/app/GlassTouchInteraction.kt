package com.moying.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 输入：同一玻璃的按下、移动与释放；输出：仅在交互后短时活动的压力与局部余波。 */
@Stable
internal class GlassTouchInteraction(private val scope: CoroutineScope) {
    var touch by mutableStateOf<Offset?>(null)
        private set
    private var pressed = false
    private var accepting = false
    private var pressure by mutableStateOf(Animatable(0f))
    private var release by mutableStateOf(Animatable(0f))
    private var pressureJob: Job? = null
    private var releaseJob: Job? = null
    val pressProgress: Float get() = pressure.value
    val releaseProgress: Float get() = release.value

    fun setActive(value: Boolean) {
        accepting = value
        if (!value) reset()
    }

    fun contact(point: Offset) {
        if (!accepting) return
        touch = point
        if (pressed) return
        pressed = true
        releaseJob?.cancel()
        release = Animatable(0f)
        animatePressure(1f)
    }

    fun moveTouch(point: Offset) {
        if (accepting) touch = point
    }

    fun end(ripple: Boolean) {
        if (!pressed) return
        pressed = false
        animatePressure(0f)
        releaseJob?.cancel()
        val animation = Animatable(0f)
        release = animation
        if (ripple && accepting) releaseJob = scope.launch {
            animation.animateTo(1f, tween(Theme.Motion.GlassReleaseMillis, easing = LinearEasing))
            animation.snapTo(0f)
        }
    }

    private fun animatePressure(target: Float) {
        val animation = pressure
        val velocity = animation.velocity
        pressureJob?.cancel()
        pressureJob = scope.launch {
            animation.animateTo(target, spring(Theme.NavGlass.PressDamping, Theme.NavGlass.PressStiffness), initialVelocity = velocity)
        }
    }

    /** 输入：停止/销毁；输出：立即归零并取消任务，后台不会保留帧动画。 */
    fun reset() {
        pressureJob?.cancel()
        releaseJob?.cancel()
        pressureJob = null
        releaseJob = null
        pressed = false
        touch = null
        pressure = Animatable(0f)
        release = Animatable(0f)
    }
}

/** 输入：响应开关；输出：随页面与既有窗口 Lifecycle 清理的共享触摸状态，无新依赖。 */
@Composable
internal fun rememberGlassTouchInteraction(enabled: Boolean = true): GlassTouchInteraction {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { GlassTouchInteraction(scope) }
    val view = LocalView.current
    DisposableEffect(state, view, enabled) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            state.setActive(enabled && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false)
        }
        lifecycle?.addObserver(observer)
        state.setActive(enabled && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false)
        onDispose {
            lifecycle?.removeObserver(observer)
            state.setActive(false)
        }
    }
    return state
}

/** Observes the primary pointer without consuming taps or scrolls. */
internal fun Modifier.observeGlassTouch(
    state: GlassTouchInteraction,
    cancelOnDrag: Boolean = false,
): Modifier = pointerInput(state, cancelOnDrag) {
    var tracked: PointerId? = null
    var pressOrigin = Offset.Zero
    var dragCancelled = false
    // In Final, the scroll parent has already had a chance to consume movement.
    val pass = if (cancelOnDrag) PointerEventPass.Final else PointerEventPass.Initial
    val touchSlopSquared = viewConfiguration.touchSlop.let { it * it }
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass)
                val id = tracked
                if (id == null) {
                    event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let {
                        tracked = it.id
                        pressOrigin = it.position
                        dragCancelled = false
                        state.contact(it.position)
                    }
                } else {
                    val contact = event.changes.firstOrNull { it.id == id }
                    if (contact == null || !contact.pressed) {
                        state.end(ripple = !dragCancelled && contact?.previousPressed == true)
                        tracked = null
                    } else if (!cancelOnDrag) {
                        state.contact(contact.position)
                    } else if (!dragCancelled) {
                        val consumedMovement = contact.isConsumed && contact.position != contact.previousPosition
                        if (consumedMovement || (contact.position - pressOrigin).getDistanceSquared() > touchSlopSquared) {
                            // Keep tracking until UP, but stop rebinding the press shader during a drag.
                            dragCancelled = true
                            state.reset()
                        }
                    }
                }
            }
        }
    } finally {
        state.end(ripple = false)
    }
}
