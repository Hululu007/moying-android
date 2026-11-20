/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified 2026-11-02 for Moying: adapted Kyant0/AndroidLiquidGlass,
 * commit 65ab177e90e5c1d8c62e70cf7755841982da65f6.
 * Revised 2026-11-03: use the shared glass shader, uniform binder and GPU blur
 * with the existing video-only PixelCopy texture as node content.
 */
package com.moying.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil

/** 仅在视频平面提供像素，文字和按钮永远不进入折射输入。 */
val LocalVideoBackdrop = staticCompositionLocalOf<VideoBackdrop?> { null }

internal interface VideoRefractionRenderer {
    /** 输入：既有视频帧、局部像素几何、触点和底色；输出：是否成功绘制导航同款材质。 */
    fun draw(
        canvas: Canvas,
        frame: VideoBackdropFrame,
        glassBounds: Rect,
        size: Size,
        cornerRadii: FloatArray,
        tint: Color = Theme.Glass.RefractedTint,
        touch: Offset = Offset.Zero,
        press: Float = 0f,
        releaseProgress: Float = 0f,
        tuning: GlassTuning = GlassTuning.Default,
        lightDirection: Offset = Offset(Theme.NavGlass.LightX, Theme.NavGlass.LightY),
    ): Boolean

    /** 输入：无；输出：释放显示列表及旧帧引用，不回收 PixelCopy 仍可能使用的位图。 */
    fun dispose()
}

internal fun createVideoRefractionRenderer(density: Float, style: CatalogGlassStyle = CatalogGlassStyle.DEFAULT): VideoRefractionRenderer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { Api33VideoRefractionRenderer(density, style) }.getOrNull()
    } else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Api33VideoRefractionRenderer(private val density: Float, private val style: CatalogGlassStyle) : VideoRefractionRenderer {
    private val shader = SharedNavigationGlassProgram.shader
    private var blur: RenderEffect? = null
    private var previousBlurRadius = Float.NaN
    private val node = RenderNode("MoyingVideoGlass").apply { setClipToBounds(true) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val padding = ceil(Theme.NavGlass.SamplePadding.value * density)
    private var boundBitmap: Bitmap? = null
    private var boundVersion = Long.MIN_VALUE
    private var recordedSize = Size.Unspecified
    private var recordedGlassBounds = Rect.Zero
    private var recordedVideoBounds = Rect.Zero
    private var previousSize = Size.Unspecified
    private var previousTouch = Offset.Unspecified
    private var previousPress = Float.NaN
    private var previousRelease = Float.NaN
    private var previousRadii: FloatArray? = null
    private var previousTint: Color? = null
    private var previousTuning = GlassTuning.Default
    private var previousLight = Offset.Unspecified
    private var disposed = false

    override fun draw(
        canvas: Canvas,
        frame: VideoBackdropFrame,
        glassBounds: Rect,
        size: Size,
        cornerRadii: FloatArray,
        tint: Color,
        touch: Offset,
        press: Float,
        releaseProgress: Float,
        tuning: GlassTuning,
        lightDirection: Offset,
    ): Boolean {
        val bitmap = frame.bitmap
        val videoBounds = frame.videoBounds
        if (disposed || !canvas.isHardwareAccelerated || bitmap.isRecycled || size.width <= 0f || size.height <= 0f ||
            glassBounds.width <= 0f || glassBounds.height <= 0f || videoBounds.width <= 0f || videoBounds.height <= 0f ||
            !glassBounds.overlaps(videoBounds)
        ) return false
        return try {
            if (boundBitmap !== bitmap || boundVersion != frame.version || recordedSize != size ||
                recordedGlassBounds != glassBounds || recordedVideoBounds != videoBounds
            ) {
                val width = ceil(size.width + padding * 2f).toInt()
                val height = ceil(size.height + padding * 2f).toInt()
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(width, height)
                try {
                    recording.drawColor(AndroidColor.BLACK)
                    // 输入视频 root 坐标；输出带采样边距的局部背景，纹理和帧池仍由 VideoBackdrop 管理。
                    val scaleX = (glassBounds.width / size.width).coerceAtLeast(.01f)
                    val scaleY = (glassBounds.height / size.height).coerceAtLeast(.01f)
                    destination.set(
                        padding + (videoBounds.left - glassBounds.left) / scaleX,
                        padding + (videoBounds.top - glassBounds.top) / scaleY,
                        padding + (videoBounds.right - glassBounds.left) / scaleX,
                        padding + (videoBounds.bottom - glassBounds.top) / scaleY,
                    )
                    recording.drawBitmap(bitmap, null, destination, bitmapPaint)
                } finally { node.endRecording() }
                boundBitmap = bitmap
                boundVersion = frame.version
                recordedSize = size
                recordedGlassBounds = glassBounds
                recordedVideoBounds = videoBounds
            }
            if (previousSize != size || previousTouch != touch || previousPress != press || previousRelease != releaseProgress || previousTint != tint ||
                previousRadii?.contentEquals(cornerRadii) != true || previousTuning != tuning || previousLight != lightDirection
            ) {
                // 同一 binder、Shader 和模糊链；播放器没有导航选择胶囊，其余光学参数完全复用。
                bindNavigationGlassUniforms(shader, density, size, padding, Rect.Zero, touch, press,
                    cornerRadii, selectionLens = false, surfaceTint = tint, releaseProgress = releaseProgress, style = style,
                    tuning = tuning, lightDirection = lightDirection)
                val blurRadius = catalogBlurRadius(style, press, tuning)
                if (blurRadius != previousBlurRadius) {
                    blur = createNavigationGlassBlur(density, style, press, tuning)
                    previousBlurRadius = blurRadius
                }
                node.setRenderEffect(snapshotNavigationGlassFilter(shader, blur))
                previousSize = size
                previousTouch = touch
                previousPress = press
                previousRelease = releaseProgress
                previousTint = tint
                previousTuning = tuning
                previousLight = lightDirection
                previousRadii = cornerRadii.copyOf()
            }
            val checkpoint = canvas.save()
            try {
                canvas.translate(-padding, -padding)
                canvas.drawRenderNode(node)
            } finally { canvas.restoreToCount(checkpoint) }
            true
        } catch (_: IllegalArgumentException) {
            dispose()
            false
        } catch (_: IllegalStateException) {
            dispose()
            false
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        node.setRenderEffect(null)
        node.discardDisplayList()
        boundBitmap = null
        blur = null
        previousRadii = null
    }
}
