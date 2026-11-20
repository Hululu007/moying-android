package com.moying.app

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.roundToInt

/** 时间与触感阈值属于交互策略；视觉位移、阻尼和形变统一在 Theme。 */
private object NavigationScroll {
    const val IdleMillis = 90L
    const val FirstEventMillis = 16L
    const val MillisPerSecond = 1_000f
    const val BoundaryFraction = .88f
}

/** 输入：页面和开关；输出：保持可见、只在导航绘制阶段读取的牵引状态。 */
@Composable
fun rememberScrollNavigationState(enabled: Boolean, page: String): ScrollNavigationState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val state = remember(scope) { ScrollNavigationState(scope) }
    SideEffect { state.configure(enabled, page, density) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

@Stable
class ScrollNavigationState internal constructor(private val scope: CoroutineScope) {
    /** 输出：受限的像素位移，负数跟随向上滚动，正数跟随向下滚动。 */
    private var rawOffsetY by mutableFloatStateOf(0f)
    // Accumulate subpixel input, but update the glass only when it moves a visible pixel.
    val offsetY by derivedStateOf(structuralEqualityPolicy()) { rawOffsetY.roundToInt().toFloat() }
    val pullFraction: Float get() {
        val offset = offsetY
        val limit = (if (offset < 0f) Theme.NavGlass.TetherUp.value else Theme.NavGlass.TetherDown.value) * density
        return (offset / limit.coerceAtLeast(1f)).coerceIn(-1f, 1f)
    }
    // 保持旧调用兼容；导航现在不因滚动隐藏。
    val hidden: Boolean get() = false
    internal var boundaryEvent by mutableIntStateOf(0)
        private set
    private var enabled = false
    private var page = ""
    private var density = 1f
    private var lastScrollAt = 0L
    internal val isMoving: Boolean get() = lastScrollAt != 0L &&
        SystemClock.uptimeMillis() - lastScrollAt <= NavigationScroll.IdleMillis
    private var returning = false
    private var boundarySent = false
    private var returnJob: Job? = null

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // 保留方向，包括边界剩余距离；不消费页面滚动或打断惯性。
            val distance = consumed.y + available.y
            if (enabled && distance != 0f) onScroll(distance, source == NestedScrollSource.UserInput)
            return Offset.Zero
        }
    }

    internal fun configure(value: Boolean, currentPage: String, currentDensity: Float) {
        density = currentDensity.coerceAtLeast(1f)
        if (enabled != value || page != currentPage) {
            returnJob?.cancel()
            returnJob = null
            returning = false
            rawOffsetY = 0f
            lastScrollAt = 0L
            boundarySent = false
        }
        enabled = value
        page = currentPage
    }

    /** 输入：带方向的滚动距离；输出：速度驱动、始终拴在原位附近的橡胶阻尼。 */
    private fun onScroll(distance: Float, userInput: Boolean) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastScrollAt == 0L) NavigationScroll.FirstEventMillis else (now - lastScrollAt).coerceAtLeast(1L)
        if (gap > NavigationScroll.IdleMillis || lastScrollAt == 0L) boundarySent = false
        lastScrollAt = now
        if (returning) {
            returnJob?.cancel()
            returnJob = null
            returning = false
        }
        val speed = distance / density / gap * NavigationScroll.MillisPerSecond
        val limit = (if (speed < 0f) Theme.NavGlass.TetherUp.value else Theme.NavGlass.TetherDown.value) * density
        val fraction = 1f - exp(-abs(speed) / Theme.NavGlass.TetherSpeed)
        val desired = speed.sign * limit * fraction
        val nextOffset = rawOffsetY + (desired - rawOffsetY) * Theme.NavGlass.TetherFollow
        rawOffsetY = nextOffset.coerceIn(-Theme.NavGlass.TetherUp.value * density, Theme.NavGlass.TetherDown.value * density)
        if (userInput && fraction >= NavigationScroll.BoundaryFraction && !boundarySent) {
            boundarySent = true
            boundaryEvent++
        }
        if (returnJob?.isActive == true) return
        returnJob = scope.launch {
            try {
                while (enabled) {
                    val remaining = NavigationScroll.IdleMillis - (SystemClock.uptimeMillis() - lastScrollAt)
                    if (remaining <= 0L) break
                    delay(remaining)
                }
                returning = true
                Animatable(rawOffsetY).animateTo(
                    targetValue = 0f,
                    animationSpec = spring(Theme.NavGlass.TetherDamping, Theme.NavGlass.TetherStiffness),
                ) { rawOffsetY = value }
                rawOffsetY = 0f
            } finally {
                returning = false
            }
        }
    }

    internal fun dispose() {
        enabled = false
        returnJob?.cancel()
        returnJob = null
    }
}
