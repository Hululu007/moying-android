/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from Kyant0/AndroidLiquidGlass, commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6:
 * backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdrop.kt
 * backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdropModifier.kt
 * Modified 2026-11-02: Compose 1.7.6 Android-only lifecycle, page isolation,
 * hidden-navigation suspension, and root-coordinate bounds for Moying.
 */

package com.moying.app

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned

val LocalPageBackdrop = staticCompositionLocalOf<PageBackdrop?> { null }

/** 输入：导航是否仍在屏幕中及页面标识；输出：由 Compose 释放的共享 GPU 页面图层。 */
@Suppress("UNUSED_PARAMETER") // revealing 保持主界面接口兼容；GPU 路径不再区分采样帧率。
@Composable
fun rememberPageBackdrop(enabled: Boolean, revealing: Boolean = false, page: String = "", shareRenderedPage: Boolean = false): PageBackdrop {
    val pageLayer = rememberGraphicsLayer()
    val backdrop = remember(pageLayer) { PageBackdrop(pageLayer) }
    val active = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    SideEffect {
        // The page and the navigation lens draw this same node. Cache the
        // rendered home page so those draws can reuse pixels, not replay it.
        val strategy = if (active && shareRenderedPage) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        if (pageLayer.compositingStrategy != strategy) pageLayer.compositingStrategy = strategy
        backdrop.configurePage(page)
        backdrop.setEnabled(active)
    }
    DisposableEffect(backdrop) { onDispose { backdrop.dispose() } }
    return backdrop
}

/**
 * 输入：仅包含页面的内容树；输出：正常页面和供兄弟导航复用的同一份 GPU 显示列表。
 * 不包含导航自身，不读回 Bitmap；RenderNode 的子图层更新由渲染图同步传播。
 */
@Composable
fun PageBackdropHost(
    backdrop: PageBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.graphicsLayer().onGloballyPositioned { coordinates ->
        // 完整坐标不裁剪出屏部分，导航的平移/浮现不会改变页面纹理原点。
        backdrop.setHostBounds(Rect(
            coordinates.localToRoot(Offset.Zero),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
        ))
    }.drawWithContent {
        if (backdrop.enabled) {
            backdrop.pageLayer.record { this@drawWithContent.drawContent() }
            backdrop.recorded()
            drawLayer(backdrop.pageLayer)
        } else {
            // 导航完全离场后恢复直接绘制；保留 GPU 显示列表用于下一次浮现。
            drawContent()
        }
    }, content = content)
}

@Stable
class PageBackdrop internal constructor(internal val pageLayer: GraphicsLayer) {
    internal var hostBounds by mutableStateOf(Rect.Zero)
        private set
    internal var enabled by mutableStateOf(false)
        private set
    internal var ready by mutableStateOf(false)
        private set
    private var pageKey = ""
    private var disposed = false

    internal fun setEnabled(value: Boolean) {
        if (disposed || enabled == value) return
        if (!value) ready = false
        enabled = value
    }

    internal fun setHostBounds(bounds: Rect) {
        if (hostBounds.size != bounds.size) ready = false
        hostBounds = bounds
    }

    internal fun configurePage(key: String) {
        // Keep the same display-list object across pages. Child RenderNode changes
        // propagate to every drawLayer consumer, including the navigation's lens.
        // Do not publish a Snapshot counter from draw: reading and incrementing it
        // would invalidate this host again even while the whole screen is idle.
        if (pageKey != key) pageKey = key
    }

    internal fun recorded() {
        if (!ready) ready = true
    }

    /** 输入：导航即将浮现；输出：等页面绘制机会，随后直接从 GPU 读取当前页面。 */
    internal suspend fun prepareReveal() {
        if (enabled && !disposed) withFrameNanos { }
    }

    internal fun dispose() {
        disposed = true
        enabled = false
        ready = false
    }
}
