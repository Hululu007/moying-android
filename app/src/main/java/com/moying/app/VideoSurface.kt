package com.moying.app

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 把真实的 SurfaceView 塞进 Compose。
 * 画布准备好后回调 controller.attachSurface()，播放器才知道往哪儿画。
 */
@Composable
fun VideoSurface(controller: PlayerController, modifier: Modifier = Modifier, backdrop: VideoBackdrop? = null) {
    val binding = remember { VideoSurfaceBinding() }
    DisposableEffect(binding) { onDispose { binding.release() } }
    AndroidView(
        modifier = modifier.onGloballyPositioned { binding.updateBounds(it.boundsInRoot()) },
        factory = { context ->
            SurfaceView(context).also { binding.bind(it, controller, backdrop) }
        },
        update = { binding.bind(it, controller, backdrop) },
    )
}

/** 为当前 AndroidView 保存唯一 Holder 监听；参数变化时正确解绑旧 controller / backdrop。 */
private class VideoSurfaceBinding {
    private var view: SurfaceView? = null
    private var controller: PlayerController? = null
    private var backdrop: VideoBackdrop? = null
    private var surfaceReady = false
    private var bounds = Rect.Zero
    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (view?.holder !== holder) return
            surfaceReady = true
            controller?.attachSurface(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (view?.holder !== holder) return
            surfaceReady = false
            controller?.detachSurface(holder)
        }
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(attached: View) {
            val source = view ?: return
            if (source.holder.surface.isValid) {
                surfaceReady = true
                controller?.attachSurface(source.holder)
            }
        }
        override fun onViewDetachedFromWindow(detached: View) {
            view?.holder?.let { controller?.detachSurface(it) }
            surfaceReady = false
        }
    }

    /** 输入：原生视图和最新参数；输出：绑定实际播放器，并把可选背景采样接到同一视图。 */
    fun bind(source: SurfaceView, nextController: PlayerController, nextBackdrop: VideoBackdrop?) {
        if (view !== source) {
            release()
            view = source
            surfaceReady = source.holder.surface.isValid
            source.holder.addCallback(callback)
            source.addOnAttachStateChangeListener(attachment)
        }
        if (controller !== nextController) {
            if (surfaceReady) controller?.detachSurface(source.holder)
            controller = nextController
            if (surfaceReady) nextController.attachSurface(source.holder)
        }
        if (backdrop !== nextBackdrop) {
            backdrop?.detach(source)
            backdrop = nextBackdrop
            nextBackdrop?.attach(source)
            if (bounds != Rect.Zero) nextBackdrop?.updateBounds(source, bounds)
        }
    }

    /** 输入：Compose 根坐标；输出：向采样器同步视频画面位置和尺寸。 */
    fun updateBounds(value: Rect) {
        bounds = value
        view?.let { backdrop?.updateBounds(it, value) }
    }

    fun release() {
        view?.let { old ->
            old.holder.removeCallback(callback)
            old.removeOnAttachStateChangeListener(attachment)
            backdrop?.detach(old)
            controller?.detachSurface(old.holder)
        }
        view = null
        controller = null
        backdrop = null
        surfaceReady = false
        bounds = Rect.Zero
    }
}
