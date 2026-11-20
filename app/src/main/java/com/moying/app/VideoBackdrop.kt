package com.moying.app

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 同一份 Surface 像素及其 Compose 根坐标；version 每次成功采样递增。 */
data class VideoBackdropFrame(val bitmap: Bitmap, val videoBounds: Rect, val version: Long)

/** 采样领域的资源和时序限制；视觉强度仍由玻璃主题管理。 */
private object VideoBackdropTuning {
    const val FrameIntervalMillis = 42L
    const val RetryIntervalMillis = 160L
    const val MaxBitmapEdge = 640
    const val PoolSize = 3
}

/** 输入：玻璃是否可见；输出：随页面销毁的真实视频背景，Android 13 以下不采样。 */
@Composable
fun rememberVideoBackdrop(enabled: Boolean): VideoBackdrop {
    val backdrop = remember { VideoBackdrop() }
    SideEffect { backdrop.setEnabled(enabled) }
    DisposableEffect(backdrop) { onDispose { backdrop.dispose() } }
    return backdrop
}

/**
 * 仅复制 SurfaceView 的视频平面，不把玻璃覆盖层复制回自己。
 * 生命周期、PixelCopy 完成回调和可观察状态均在主线程处理；不生成磁盘截图。
 */
@Stable
class VideoBackdrop internal constructor() {
    private data class Capture(
        val view: SurfaceView,
        val bitmap: Bitmap,
        val bounds: Rect,
        val generation: Long,
        val viewWidth: Int,
        val viewHeight: Int,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
    )

    var frame: VideoBackdropFrame? by mutableStateOf(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var view: SurfaceView? = null
    private var enabled = false
    private var disposed = false
    private var surfaceReady = false
    private var bounds = Rect.Zero
    private var boundsFresh = false
    private var generation = 0L
    private var version = 0L
    private var inFlight: Capture? = null
    private var nextCaptureAt = 0L
    private val pool = ArrayList<Bitmap>(VideoBackdropTuning.PoolSize)
    private var newestBitmap: Bitmap? = null
    private var precedingBitmap: Bitmap? = null
    private val sample = Runnable { capture() }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (view?.holder !== holder) return
            surfaceReady = true
            invalidateGeometry()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (view?.holder !== holder) return
            surfaceReady = holder.surface.isValid
            invalidateGeometry()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (view?.holder !== holder) return
            surfaceReady = false
            invalidateGeometry()
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(attached: View) {
            val current = view ?: return
            if (current !== attached) return
            surfaceReady = current.holder.surface.isValid
            invalidateGeometry()
        }

        override fun onViewDetachedFromWindow(detached: View) {
            if (view === detached) invalidateGeometry()
        }
    }

    private val layoutListener = View.OnLayoutChangeListener { changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === changed && (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) {
            // 原生尺寸先变更时，等 Compose 再报告根坐标，避免新画面套用旧位置。
            boundsFresh = false
            invalidateGeometry()
        }
    }

    /** 输入：开关；输出：重新开启立即采样，隐藏时停调度并撤下旧帧。 */
    internal fun setEnabled(value: Boolean) {
        val next = value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !disposed
        if (enabled == next) return
        enabled = next
        invalidateGeometry()
    }

    /** 输入：VideoSurface 的原生视图；输出：只监听对应 Surface 生命周期，不接管播放器。 */
    internal fun attach(source: SurfaceView) {
        if (disposed || view === source) return
        view?.let(::detach)
        view = source
        bounds = Rect.Zero
        boundsFresh = false
        surfaceReady = source.holder.surface.isValid
        source.holder.addCallback(surfaceCallback)
        source.addOnAttachStateChangeListener(attachListener)
        source.addOnLayoutChangeListener(layoutListener)
        invalidateGeometry()
    }

    /** 输入：Compose boundsInRoot；输出：几何变化立即使旧帧和在途结果失效。 */
    internal fun updateBounds(source: SurfaceView, newBounds: Rect) {
        if (view !== source || disposed) return
        val valid = newBounds.left.isFinite() && newBounds.top.isFinite() &&
            newBounds.right.isFinite() && newBounds.bottom.isFinite() &&
            newBounds.width > 0f && newBounds.height > 0f
        val next = if (valid) newBounds else Rect.Zero
        if (bounds == next && boundsFresh == valid) return
        bounds = next
        boundsFresh = valid
        invalidateGeometry()
    }

    /** 输入：待移除视图；输出：解除监听、停止请求；在途位图保留到 PixelCopy 回调结束。 */
    internal fun detach(source: SurfaceView) {
        if (view !== source) return
        source.holder.removeCallback(surfaceCallback)
        source.removeOnAttachStateChangeListener(attachListener)
        source.removeOnLayoutChangeListener(layoutListener)
        view = null
        surfaceReady = false
        boundsFresh = false
        bounds = Rect.Zero
        invalidateGeometry()
        retirePool()
    }

    private fun invalidateGeometry() {
        generation++
        frame = null
        nextCaptureAt = 0L
        handler.removeCallbacks(sample)
        schedule()
    }

    private fun canCapture(source: SurfaceView): Boolean =
        enabled && !disposed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            surfaceReady && boundsFresh && source.isAttachedToWindow && source.isShown &&
            source.windowVisibility == View.VISIBLE && source.width > 0 && source.height > 0 &&
            source.holder.surface.isValid && source.holder.surfaceFrame.width() > 0 &&
            source.holder.surfaceFrame.height() > 0

    private fun schedule(delayMillis: Long = 0L) {
        val source = view ?: return
        if (inFlight != null || !canCapture(source)) return
        handler.removeCallbacks(sample)
        handler.postDelayed(sample, delayMillis.coerceAtLeast(0L))
    }

    /** 输入：当前有效 Surface 和根坐标；输出：最多一个异步拷贝，帧率不超过约 24 fps。 */
    private fun capture() {
        val source = view ?: return
        if (inFlight != null || !canCapture(source)) return
        val scale = min(1f, VideoBackdropTuning.MaxBitmapEdge.toFloat() / max(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val destination = obtainBitmap(width, height)
        if (destination == null) {
            schedule(VideoBackdropTuning.RetryIntervalMillis)
            return
        }
        val surfaceFrame = source.holder.surfaceFrame
        val request = Capture(source, destination, bounds, generation, source.width, source.height, surfaceFrame.width(), surfaceFrame.height())
        inFlight = request
        nextCaptureAt = SystemClock.uptimeMillis() + VideoBackdropTuning.FrameIntervalMillis
        try {
            PixelCopy.request(source, destination, { result -> finishCapture(request, result) }, handler)
        } catch (_: IllegalArgumentException) {
            // Surface 刚好销毁或目标非法时，request 也可能同步抛异常。
            if (inFlight === request) inFlight = null
            schedule(VideoBackdropTuning.RetryIntervalMillis)
        } catch (_: IllegalStateException) {
            if (inFlight === request) inFlight = null
            schedule(VideoBackdropTuning.RetryIntervalMillis)
        }
    }

    /** 输入：完成的请求与状态；输出：仅发布仍匹配当前几何的成功帧，其余静默重试。 */
    private fun finishCapture(request: Capture, result: Int) {
        if (inFlight !== request) return
        inFlight = null
        val source = view
        val currentSurface = source?.holder?.surfaceFrame
        val matches = source === request.view && generation == request.generation &&
            bounds == request.bounds && source != null && canCapture(source) &&
            source.width == request.viewWidth && source.height == request.viewHeight &&
            currentSurface != null && currentSurface.width() == request.surfaceWidth &&
            currentSurface.height() == request.surfaceHeight
        if (result == PixelCopy.SUCCESS && matches) {
            // 最新两帧都不作为写入目标，给 Compose / RenderThread 留出交接余量。
            precedingBitmap = newestBitmap
            newestBitmap = request.bitmap
            version++
            frame = VideoBackdropFrame(request.bitmap, request.bounds, version)
        } else if (matches) {
            // 首帧未到或受保护 Surface 无法复制时，交给玻璃回退，避免显示旧片段。
            frame = null
        }
        val delay = if (result == PixelCopy.SUCCESS) (nextCaptureAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            else VideoBackdropTuning.RetryIntervalMillis
        schedule(delay)
    }

    /** 输入：目标大小；输出：三缓冲中未发布的可写位图，不覆盖当前帧或上一帧。 */
    private fun obtainBitmap(width: Int, height: Int): Bitmap? {
        if (pool.any { it.width != width || it.height != height }) retirePool()
        pool.firstOrNull { it !== newestBitmap && it !== precedingBitmap && it !== inFlight?.bitmap }?.let { return it }
        if (pool.size >= VideoBackdropTuning.PoolSize) return null
        return runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull()?.also { pool += it }
    }

    private fun retirePool() {
        // 不调用 recycle：旧 Bitmap 可能仍由 shader、RenderThread 或在途拷贝持有。
        // 清除本类引用后交给系统在所有消费者释放引用后回收。
        pool.clear()
        newestBitmap = null
        precedingBitmap = null
    }

    /** 输入：页面离开；输出：清理调度和监听，迟到回调只结束请求而不再发布/调度。 */
    internal fun dispose() {
        if (disposed) return
        disposed = true
        enabled = false
        view?.let(::detach)
        generation++
        frame = null
        handler.removeCallbacks(sample)
        retirePool()
    }
}
