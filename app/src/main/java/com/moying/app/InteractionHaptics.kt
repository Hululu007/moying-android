package com.moying.app

import android.os.SystemClock
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** 交互节奏参数，不属于视觉主题；统一防止同一操作或高速拖动产生重复震动。 */
private object InteractionHapticTiming {
    const val TapMillis = 45L
    const val DetentMillis = 55L
    const val ConfirmMillis = 70L
    const val BoundaryMillis = 140L
    const val HoldMillis = 80L
}

val LocalInteractionHaptics = staticCompositionLocalOf { InteractionHaptics(null) }

/** 输入：当前窗口；输出：共享节流的系统交互触感，遵守系统设置与设备能力。 */
@Composable
fun rememberInteractionHaptics(): InteractionHaptics {
    val view = LocalView.current
    return remember(view) { InteractionHaptics(view) }
}

/** 输入：按键是否可用及动作；输出：保留原生点击/无障碍，并在有效点击时给出一次触感。 */
@Composable
fun Modifier.hapticClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val feedback = LocalInteractionHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (enabled && pressed) Theme.Motion.ButtonPressedScale else 1f,
        spring(Theme.Motion.ButtonSpringDamping, Theme.Motion.ButtonSpringStiffness),
        label = "button-press-rebound",
    )
    // 只缩放当前控件前景，外层玻璃/背景不变；同一 source 同步点击与回弹，禁用不形变。
    return clickable(interactionSource = interactionSource, indication = null, enabled = enabled) {
        feedback.tap()
        onClick()
    }.graphicsLayer {
        val value = if (enabled) scale.value else 1f
        scaleX = value
        scaleY = value
    }
}

class InteractionHaptics internal constructor(private val view: View?) {
    private var lastFeedbackAt = Long.MIN_VALUE

    /** 输入：一次有效按键；输出：短促点击触感，不强制打开已被用户关闭的系统触感。 */
    fun tap() = pulse(HapticFeedbackConstantsCompat.VIRTUAL_KEY, InteractionHapticTiming.TapMillis)

    /** 输入：跨过一个离散选项；输出：轻刻度，兼容旧系统的 CLOCK_TICK。 */
    fun detent() = pulse(HapticFeedbackConstantsCompat.SEGMENT_TICK, InteractionHapticTiming.DetentMillis)

    /** 输入：松手吸附并确认新选项；输出：确认触感，距最后刻度太近则合并。 */
    fun confirm() = pulse(HapticFeedbackConstantsCompat.CONFIRM, InteractionHapticTiming.ConfirmMillis)

    /** 输入：一次手势首次到达阻尼边界；输出：轻触边界，不使用失败/长按重震。 */
    fun boundary() = pulse(HapticFeedbackConstantsCompat.CLOCK_TICK, InteractionHapticTiming.BoundaryMillis)

    /** 输入：长按三倍速已生效；输出：一次长按确认触感，遵守系统触感设置。 */
    fun hold() = pulse(HapticFeedbackConstantsCompat.LONG_PRESS, InteractionHapticTiming.HoldMillis)

    private fun pulse(feedback: Int, minimumInterval: Long) {
        val target = view ?: return
        if (!target.isAttachedToWindow || !target.hasWindowFocus() || !target.isHapticFeedbackEnabled) return
        val now = SystemClock.uptimeMillis()
        if (lastFeedbackAt != Long.MIN_VALUE && now - lastFeedbackAt < minimumInterval) return
        if (runCatching { ViewCompat.performHapticFeedback(target, feedback) }.getOrDefault(false)) lastFeedbackAt = now
    }
}

