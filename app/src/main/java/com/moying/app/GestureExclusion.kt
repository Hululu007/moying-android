package com.moying.app

import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** 系统手势保护参数，单位为 dp；只保护真实窗口边缘，不保护视频自身边缘。 */
private object GestureExclusionTuning {
    const val MinimumTopDp = 40f
    const val MinimumSideDp = 8f
    const val MinimumBottomDp = 12f
}

/**
 * 输入：视频触摸层的窗口边界、局部起手点；输出：本次触摸是否必须完整让给系统。
 * 每次 DOWN 只读现有 root insets，不发起 Insets 请求，也不注册系统手势排除区域。
 */
internal fun isSystemGestureStart(view: View, layerBoundsInWindow: Rect, localPoint: Offset): Boolean {
    if (layerBoundsInWindow.width <= 0f || layerBoundsInWindow.height <= 0f ||
        !layerBoundsInWindow.left.isFinite() || !layerBoundsInWindow.top.isFinite() ||
        !localPoint.x.isFinite() || !localPoint.y.isFinite()
    ) return true
    val root = view.rootView
    if (root.width <= 0 || root.height <= 0) return true
    val rootLocation = IntArray(2)
    root.getLocationInWindow(rootLocation)
    val density = view.resources.displayMetrics.density
    val insets = ViewCompat.getRootWindowInsets(view)
    val gestures = insets?.getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemGestures() or WindowInsetsCompat.Type.mandatorySystemGestures(),
    )
    val statusBars = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
    val top = max(GestureExclusionTuning.MinimumTopDp * density, max(gestures?.top ?: 0, statusBars?.top ?: 0).toFloat())
    val left = max(GestureExclusionTuning.MinimumSideDp * density, (gestures?.left ?: 0).toFloat())
    val right = max(GestureExclusionTuning.MinimumSideDp * density, (gestures?.right ?: 0).toFloat())
    val bottom = max(GestureExclusionTuning.MinimumBottomDp * density, (gestures?.bottom ?: 0).toFloat())
    // boundsInWindow 和 PointerInput 的局部位置在这里合成同一窗口坐标。
    val windowPoint = layerBoundsInWindow.topLeft + localPoint
    val x = windowPoint.x - rootLocation[0]
    val y = windowPoint.y - rootLocation[1]
    return y <= top || y >= root.height - bottom || x <= left || x >= root.width - right
}

/** 窗口被通知栏接管时直接取消 PointerInput 协程，保证静止长按也立即走 finally 还速。 */
@Stable
internal class GestureWindowFocus(initialFocus: Boolean) {
    var hasFocus by mutableStateOf(initialFocus)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    private var cancelActiveInput: (() -> Unit)? = null

    fun update(value: Boolean) {
        if (hasFocus != value) {
            hasFocus = value
            // 即使失焦和回焦合并在同一重组帧，也必须重启此前已取消的输入协程。
            revision++
        }
        if (!value) cancelActiveInput?.invoke()
    }

    fun bindCancellation(cancel: () -> Unit) {
        cancelActiveInput = cancel
        if (!hasFocus) cancel()
    }

    fun clearCancellation(cancel: () -> Unit) {
        if (cancelActiveInput === cancel) cancelActiveInput = null
    }

    fun dispose() {
        update(false)
        cancelActiveInput = null
    }
}
