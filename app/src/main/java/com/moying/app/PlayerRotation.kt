package com.moying.app

import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs

/** 旋转行为参数集中于此；不改变系统自动旋转设置，也不触碰播放器。 */
private object PlayerRotationTuning {
    const val StableMillis = 350L
    const val AxisToleranceDegrees = 25
    const val FullTurnDegrees = 360
    const val HalfTurnDegrees = 180
    const val QuarterTurnDegrees = 90
    const val AutoRotationOff = 0
}

private enum class PlayerRotationAxis(val request: Int) {
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
}

/** 输入：宿主、是否在播放页、窗口是否为大屏；输出：随前台生命周期释放的方向控制，不重建播放状态。 */
@Composable
internal fun rememberPlayerRotation(activity: ComponentActivity, inPlayer: Boolean, allowWideRotation: Boolean): PlayerRotationController {
    val rotation = remember(activity) { PlayerRotationController(activity) }
    DisposableEffect(rotation, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> rotation.setForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> rotation.setForeground(false)
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        rotation.setForeground(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            activity.lifecycle.removeObserver(observer)
            rotation.dispose()
        }
    }
    SideEffect {
        rotation.setPlayerActive(inPlayer)
        rotation.setWideRotation(allowWideRotation)
    }
    return rotation
}

/** USER 负责自动旋转；传感器只判断何时安全撤销手动覆盖，避免方向请求与实际配置互相追逐。 */
internal class PlayerRotationController(private val activity: ComponentActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = activity.contentResolver
    private var playerActive = false
    private var foreground = false
    private var wideRotation = false
    private var disposed = false
    private var autoRotation = false
    private var observingSettings = false
    private var manualAxis: PlayerRotationAxis? = null
    private var physicalAxis: PlayerRotationAxis? = null
    private var candidateAxis: PlayerRotationAxis? = null
    private var pendingRelease: Runnable? = null
    private var orientationListener: OrientationEventListener? = null
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (disposed) return
            autoRotation = readAutoRotation()
            updateSensor()
        }
    }

    /** 输入：页面是否为播放页；输出：首页/历史固定竖屏，离开播放页时清除手动方向。 */
    fun setPlayerActive(value: Boolean) {
        if (disposed) return
        if (playerActive != value) {
            playerActive = value
            cancelRelease()
            if (!value) manualAxis = null
            updateObservation()
        }
        applyPolicy()
    }

    /** 输入：宿主是否处于 RESUMED；输出：仅前台观察设置和物理方向。 */
    fun setForeground(value: Boolean) {
        if (disposed || foreground == value) return
        foreground = value
        updateObservation()
        if (value) applyPolicy()
    }

    /** 输入：窗口是否达到平板尺寸；输出：大窗口不再强制竖屏，小窗口保持原有竖屏策略。 */
    fun setWideRotation(value: Boolean) {
        if (disposed || wideRotation == value) return
        wideRotation = value
        applyPolicy()
    }

    /** 输入：实际横屏布局；输出：优先读取尚在过渡中的手动意图，连续按键也不会误反转。 */
    fun isFullscreenRequested(actualFullscreen: Boolean): Boolean = playerActive &&
        (manualAxis?.let { it == PlayerRotationAxis.LANDSCAPE } ?: actualFullscreen)

    /** 输入：当前实际布局和视频宽高比；输出：横屏视频全屏转横屏，竖屏视频全屏保持竖屏最大化。 */
    fun toggleFullscreen(actualFullscreen: Boolean, videoAspect: Float = 16f / 9f) {
        if (!playerActive || disposed) return
        if (isFullscreenRequested(actualFullscreen)) {
            releaseManualAxis()
        } else {
            val targetAxis = if (videoAspect >= 1f) PlayerRotationAxis.LANDSCAPE else PlayerRotationAxis.PORTRAIT
            requestManual(targetAxis)
        }
    }

    /** 输入：全屏返回操作；输出：小窗口请求竖屏，大窗口只撤销手动轴以保持当前横竖方向。 */
    fun exitFullscreen() {
        if (playerActive && !disposed) releaseManualAxis()
    }

    /** 输入：退出全屏；输出：大窗口回到跟随系统方向，小窗口沿用原来的强制竖屏。 */
    private fun releaseManualAxis() {
        if (wideRotation) {
            manualAxis = null
            applyPolicy()
            updateSensor()
        } else requestManual(PlayerRotationAxis.PORTRAIT)
    }

    private fun requestManual(axis: PlayerRotationAxis) {
        cancelRelease()
        manualAxis = axis
        applyPolicy()
        updateSensor()
        considerRelease()
    }

    private fun applyPolicy() {
        if (disposed || activity.isFinishing || activity.isDestroyed) return
        val requested = when {
            !playerActive -> if (wideRotation) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> manualAxis?.request ?: ActivityInfo.SCREEN_ORIENTATION_USER
        }
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }

    private fun readAutoRotation(): Boolean = runCatching {
        Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, PlayerRotationTuning.AutoRotationOff) != PlayerRotationTuning.AutoRotationOff
    }.getOrDefault(false)

    private fun updateObservation() {
        val shouldObserve = !disposed && playerActive && foreground
        if (shouldObserve && !observingSettings) {
            autoRotation = readAutoRotation()
            observingSettings = runCatching {
                resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsObserver)
                true
            }.getOrDefault(false)
        } else if (!shouldObserve && observingSettings) {
            resolver.unregisterContentObserver(settingsObserver)
            observingSettings = false
        }
        updateSensor()
    }

    private fun shouldListen(): Boolean = !disposed && playerActive && foreground && autoRotation && manualAxis != null

    private fun updateSensor() {
        if (shouldListen() && orientationListener == null) {
            physicalAxis = null
            // 每次新建监听，确保静止姿态也有首个有效回调，不沿用上次 enable 前的角度缓存。
            orientationListener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    if (!shouldListen()) return
                    physicalAxis = axisForAngle(orientation)
                    considerRelease()
                }
            }.also { if (it.canDetectOrientation()) it.enable() }
        } else if (!shouldListen()) {
            orientationListener?.disable()
            orientationListener = null
            physicalAxis = null
            cancelRelease()
        }
    }

    /** 输入：最新物理方向；输出：目标轴连续稳定后恢复 USER，平放和死区会取消候选。 */
    private fun considerRelease() {
        val target = manualAxis
        if (!shouldListen() || target == null || physicalAxis != target) {
            cancelRelease()
            return
        }
        if (candidateAxis == target && pendingRelease != null) return
        cancelRelease()
        candidateAxis = target
        pendingRelease = Runnable {
            val release = shouldListen() && manualAxis == target && physicalAxis == target && candidateAxis == target
            cancelRelease()
            if (release) {
                manualAxis = null
                applyPolicy()
                updateSensor()
            }
        }.also { handler.postDelayed(it, PlayerRotationTuning.StableMillis) }
    }

    private fun axisForAngle(angle: Int): PlayerRotationAxis? {
        if (angle == OrientationEventListener.ORIENTATION_UNKNOWN) return null
        val normalized = (angle % PlayerRotationTuning.FullTurnDegrees + PlayerRotationTuning.FullTurnDegrees) % PlayerRotationTuning.FullTurnDegrees
        val tolerance = PlayerRotationTuning.AxisToleranceDegrees
        if (minOf(normalized, PlayerRotationTuning.FullTurnDegrees - normalized) <= tolerance ||
            abs(normalized - PlayerRotationTuning.HalfTurnDegrees) <= tolerance) return PlayerRotationAxis.PORTRAIT
        if (abs(normalized - PlayerRotationTuning.QuarterTurnDegrees) <= tolerance ||
            abs(normalized - PlayerRotationTuning.HalfTurnDegrees - PlayerRotationTuning.QuarterTurnDegrees) <= tolerance) return PlayerRotationAxis.LANDSCAPE
        return null
    }

    private fun cancelRelease() {
        pendingRelease?.let(handler::removeCallbacks)
        pendingRelease = null
        candidateAxis = null
    }

    /** 输入：宿主销毁；输出：取消任务、传感器与设置监听，不触发播放器或额外旋转。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        cancelRelease()
        orientationListener?.disable()
        orientationListener = null
        if (observingSettings) {
            resolver.unregisterContentObserver(settingsObserver)
            observingSettings = false
        }
    }
}
