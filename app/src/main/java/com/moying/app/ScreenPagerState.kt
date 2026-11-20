package com.moying.app

import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底栏与三页只共用这一份连续位置；不把离屏预览当成正在播放的页面。 */
@Stable
internal class ScreenPagerState(val pager: PagerState, private val scope: CoroutineScope) {
    val position: State<Float> = derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction }
    var committedPage by mutableIntStateOf(pager.currentPage)
        private set
    var navDragging by mutableStateOf(false)
        private set
    private var settling by mutableStateOf(false)
    private var generation = 0L
    private var latestDesired = position.value
    private var beginJob: Job? = null
    private var settleJob: Job? = null

    val isTransitioning: Boolean get() = navDragging || settling || pager.isScrollInProgress
    val activePage: Int? get() = pager.currentPage.takeIf {
        !isTransitioning && abs(pager.currentPageOffsetFraction) < Theme.PagerMotion.SettledEpsilon
    }

    /** 输入：开始拖动；输出：取消旧吸附，停止自动滚动后接管实际分页位置。 */
    fun beginDrag() {
        generation++
        navDragging = true
        settling = false
        settleJob?.cancel()
        beginJob?.cancel()
        latestDesired = position.value
        val token = generation
        beginJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pager.stopScroll(MutatePriority.UserInput)
            if (token == generation && navDragging) applyDesiredPosition()
        }
    }

    /** 输入：底栏的连续逻辑页码；输出：同一帧移动页面，不为每个 MOVE 排协程。 */
    fun dragTo(value: Float) {
        if (!navDragging || !value.isFinite()) return
        latestDesired = value.coerceIn(Theme.PagerMotion.HomePage.toFloat(), Theme.PagerMotion.HistoryPage.toFloat())
        if (beginJob?.isActive != true) applyDesiredPosition()
    }

    private fun applyDesiredPosition() {
        val pagePixels = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
        if (pagePixels > 0) pager.dispatchRawDelta((latestDesired - position.value) * pagePixels)
    }

    /** 输入：松手目标与速度（页/秒）；输出：连续位置弹性吸附，快速切换取消旧任务。 */
    fun finishDrag(target: Int, velocity: Float) = settleTo(target, velocity)
    fun cancelDrag() = settleTo(position.value.roundToInt(), Theme.Size.EmptyFraction)
    fun navigateTo(page: Int) = settleTo(page, Theme.Size.EmptyFraction)

    private fun settleTo(page: Int, velocity: Float) {
        val target = page.coerceIn(Theme.PagerMotion.HomePage, Theme.PagerMotion.HistoryPage)
        val token = ++generation
        navDragging = false
        beginJob?.cancel()
        settleJob?.cancel()
        settling = true
        settleJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val speed = abs(velocity).takeIf { it.isFinite() }?.coerceAtMost(Theme.NavGlass.MaxSwipeVelocity) ?: 0f
                pager.animateScrollToPage(target, animationSpec = spring(
                    dampingRatio = Theme.PagerMotion.SettleDamping,
                    stiffness = Theme.PagerMotion.SettleStiffness + speed * Theme.PagerMotion.VelocityStiffness,
                ))
                if (token == generation) committedPage = target
            } finally {
                if (token == generation) settling = false
            }
        }
    }

    /** 输入：恢复后的分页状态；输出：消除旋转/重建中保存的半页，避免停在中缝。 */
    fun restorePosition() {
        if (!isTransitioning && abs(pager.currentPageOffsetFraction) >= Theme.PagerMotion.SettledEpsilon) navigateTo(pager.currentPage)
    }

    fun dispose() {
        generation++
        beginJob?.cancel()
        settleJob?.cancel()
    }
}

@Composable
internal fun rememberScreenPagerState(pager: PagerState): ScreenPagerState {
    val scope = rememberCoroutineScope()
    val state = remember(pager, scope) { ScreenPagerState(pager, scope) }
    LaunchedEffect(state) { state.restorePosition() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
