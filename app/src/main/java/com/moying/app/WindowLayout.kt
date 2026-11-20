package com.moying.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass

/**
 * 输入：当前窗口可用宽高（dp）；
 * 输出：由窗口尺寸类派生的布局判断。断点沿用 androidx.window（600/840dp 宽、480/900dp 高），
 * 折叠屏、分屏、平板和手机横竖屏都用同一套窗口尺寸判断，不看设备型号。
 */
internal data class WindowLayout(val widthDp: Int, val heightDp: Int) {
    private val sizeClass = WindowSizeClass.compute(widthDp.toFloat(), heightDp.toFloat())

    val isLandscape: Boolean get() = widthDp > heightDp
    val isWide: Boolean get() = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isExpandedWidth: Boolean get() = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val hasCompactHeight: Boolean get() = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    // 平板/大窗口：宽高都不是紧凑类，手机横屏（高度紧凑）不在此列。
    val isLargeScreen: Boolean get() = isWide && !hasCompactHeight
}

/** 首页、历史、播放页和面板共用同一个窗口尺寸实例。 */
internal val LocalWindowLayout = staticCompositionLocalOf { WindowLayout(411, 891) }
