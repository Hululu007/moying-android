package com.moying.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.max
import kotlin.math.sqrt

/** 绘制当前窗口的环境背景，供玻璃卡片取样。 */
@Composable
fun AppBackground(
    style: AppBackgroundStyle,
    customImageUri: String,
    modifier: Modifier = Modifier,
    builtInWallpaper: BuiltInWallpaper? = null,
    timeGradient: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val source = if (style == AppBackgroundStyle.CUSTOM) customImageUri else ""
    var target by remember { mutableStateOf(IntSize.Zero) }
    val wallpaper = builtInWallpaper.takeIf { style == AppBackgroundStyle.BUILT_IN }
    var image by remember(source, wallpaper) { mutableStateOf<BackgroundImage?>(null) }
    LaunchedEffect(source, wallpaper, target) {
        image = when {
            target.width <= 0 || target.height <= 0 -> null
            wallpaper != null -> loadBackground(target, readOrientation = false) { context.assets.open(wallpaper.asset) }
            source.isNotBlank() -> loadBackground(target) { context.contentResolver.openInputStream(Uri.parse(source)) }
            else -> null
        }
    }
    val base = when (style) {
        AppBackgroundStyle.BLUE -> Theme.Background.BlueBase
        AppBackgroundStyle.SAND -> Theme.Background.SandBase
        else -> Theme.Colors.Background
    }
    Box(modifier.background(base).onSizeChanged { measured ->
        val bucket = Theme.Background.ImageSizeBucket
        // Preserve the viewport aspect for crop sampling; loadBackground still
        // applies the unchanged edge and pixel budgets to the decoded bitmap.
        fun bounded(value: Int) = ((value + bucket - 1) / bucket) * bucket
        target = IntSize(bounded(measured.width), bounded(measured.height))
    }) {
        if (timeGradient && style == AppBackgroundStyle.SOLID) {
            val horizon = Theme.Colors.AccentSurface
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(base, horizon, base)))
            }
        }
        if (style == AppBackgroundStyle.BLUE || style == AppBackgroundStyle.SAND) {
            val light = if (style == AppBackgroundStyle.BLUE) Theme.Background.BlueLight else Theme.Background.SandLight
            val shade = if (style == AppBackgroundStyle.BLUE) Theme.Background.BlueShade else Theme.Background.SandShade
            Canvas(Modifier.fillMaxSize()) {
                // 极轻的固定环境光，不启用逐帧动画，也不建立全屏模糊读回。
                val center = Offset(size.width * Theme.Background.LightXFraction, size.height * Theme.Background.LightYFraction)
                val radius = size.maxDimension * Theme.Background.LightRadiusFraction
                drawCircle(Brush.radialGradient(listOf(light, Theme.Colors.Transparent), center, radius), radius, center)
                val lower = Offset(size.width * Theme.Background.ShadeXFraction, size.height * Theme.Background.ShadeYFraction)
                val lowerRadius = size.maxDimension * Theme.Background.ShadeRadiusFraction
                drawCircle(Brush.radialGradient(listOf(shade, Theme.Colors.Transparent), lower, lowerRadius), lowerRadius, lower)
            }
        }
        image?.let { loaded ->
            Image(loaded.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            BackgroundReadabilityScrim(loaded.brightness.bright, loaded.brightness.dark,
                loaded.brightness.topBright, loaded.brightness.topDark, wallpaper)
        }
    }
}

@Composable
private fun BackgroundReadabilityScrim(
    bright: Float,
    dark: Float,
    topBright: Float,
    topDark: Float,
    wallpaper: BuiltInWallpaper?,
) {
    val darkTheme = Theme.isDark
    val tone = if (darkTheme) bright else dark
    val topTone = if (darkTheme) topBright else topDark
    val scrim = Theme.Background.ImageScrim
    val scrimTone = Theme.Background.ImageScrimTone
    val builtIn = wallpaper != null
    // The train image is already low-key. Use less white over the locomotive and trees.
    val retainShadows = wallpaper == BuiltInWallpaper.VALLEY_TRAIN && !darkTheme
    val bodyTarget = when {
        retainShadows -> .50f
        builtIn -> if (darkTheme) .39f else .70f
        else -> Theme.Background.ImageTargetTone
    }
    val headerTarget = when {
        retainShadows -> .67f
        builtIn -> if (darkTheme) .34f else .78f
        else -> Theme.Background.ImageHeaderTargetTone
    }
    val baseAlpha = backgroundScrimAlpha(tone,
        bodyTarget,
        scrimTone, 0f, if (builtIn) .72f else Theme.Background.ImageMaxScrimAlpha)
    val headerAlpha = backgroundScrimAlpha(topTone,
        headerTarget,
        scrimTone, baseAlpha, if (builtIn) .80f else Theme.Background.ImageHeaderMaxAlpha)
    if (headerAlpha == 0f) return

    // One cached gradient carries the total opacity, including its small floor.
    // This avoids stacking two full-screen fills and keeps the header cap exact.
    Box(Modifier.fillMaxSize().drawWithCache {
        val baseColor = scrim.copy(alpha = baseAlpha)
        val gradient = if (headerAlpha > baseAlpha) {
            val middleAlpha = baseAlpha + (headerAlpha - baseAlpha) * Theme.Background.ImageHeaderMidAlpha
            Brush.verticalGradient(
                listOf(scrim.copy(alpha = headerAlpha), scrim.copy(alpha = middleAlpha), baseColor),
                endY = size.height * Theme.Background.ImageHeaderFraction,
            )
        } else null
        onDrawBehind {
            if (gradient != null) drawRect(gradient) else drawRect(baseColor)
        }
    })
}

/** 解码应用壁纸或本地文档，按视口和像素预算缩图。 */
private suspend fun loadBackground(
    target: IntSize,
    readOrientation: Boolean = true,
    openImage: () -> InputStream?,
): BackgroundImage? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openImage() ?: return@withContext null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val targetEdge = max(target.width, target.height).coerceAtMost(Theme.Background.ImageMaxEdge)
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > targetEdge * 2 ||
            bounds.outWidth.toLong() / sample * bounds.outHeight / sample > Theme.Background.ImageMaxPixels) sample *= 2
        ensureActive()
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var decoded = openImage()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
        ensureActive()
        val orientation = if (readOrientation) openImage()?.use { stream ->
            runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL else ExifInterface.ORIENTATION_NORMAL
        val rotation = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (!rotation.isIdentity) {
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, rotation, true)
            if (oriented !== decoded) decoded.recycle()
            decoded = oriented
        }
        val ratio = minOf(1f, targetEdge.toFloat() / max(decoded.width, decoded.height),
            sqrt(Theme.Background.ImageMaxPixels.toDouble() / (decoded.width.toLong() * decoded.height)).toFloat())
        val result = if (ratio < 1f) Bitmap.createScaledBitmap(decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1), true) else decoded
        if (result !== decoded) decoded.recycle()
        ensureActive()
        BackgroundImage(result.asImageBitmap(), sampleBackgroundBrightness(result, target))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private data class BackgroundImage(val bitmap: ImageBitmap, val brightness: BackgroundBrightness)

private data class BackgroundBrightness(val bright: Float, val dark: Float, val topBright: Float, val topDark: Float)

/** Sample only the centre-cropped visible area, once after decode, on the IO dispatcher. */
private fun sampleBackgroundBrightness(bitmap: Bitmap, target: IntSize): BackgroundBrightness {
    val scale = max(target.width.toFloat() / bitmap.width, target.height.toFloat() / bitmap.height)
    val visibleWidth = (target.width / scale).coerceAtMost(bitmap.width.toFloat())
    val visibleHeight = (target.height / scale).coerceAtMost(bitmap.height.toFloat())
    val left = (bitmap.width - visibleWidth) / 2f
    val top = (bitmap.height - visibleHeight) / 2f
    val samples = ArrayList<Float>(Theme.Background.ImageSampleColumns * Theme.Background.ImageSampleRows)
    val headerSamples = ArrayList<Float>()
    repeat(Theme.Background.ImageSampleRows) { row ->
        val yFraction = (row + .5f) / Theme.Background.ImageSampleRows
        val y = (top + yFraction * visibleHeight).toInt().coerceIn(0, bitmap.height - 1)
        repeat(Theme.Background.ImageSampleColumns) { column ->
            val x = (left + (column + .5f) / Theme.Background.ImageSampleColumns * visibleWidth).toInt().coerceIn(0, bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            // Weighted display brightness is sufficient for a bounded visual adjustment;
            // no full bitmap readback, histogram allocation or per-frame analysis is needed.
            val brightness = (((pixel shr 16) and 255) * .2126f + ((pixel shr 8) and 255) * .7152f + (pixel and 255) * .0722f) / 255f
            samples.add(brightness)
            if (yFraction <= Theme.Background.ImageHeaderFraction) headerSamples.add(brightness)
        }
    }
    samples.sort()
    headerSamples.sort()
    fun List<Float>.tone(percentile: Float) = this[((size - 1) * percentile).toInt().coerceIn(indices)]
    return BackgroundBrightness(samples.tone(Theme.Background.ImageBrightPercentile), samples.tone(Theme.Background.ImageDarkPercentile),
        headerSamples.tone(Theme.Background.ImageBrightPercentile), headerSamples.tone(Theme.Background.ImageDarkPercentile))
}

/** Solve an ordinary colour overlay toward the theme's text-friendly brightness range. */
private fun backgroundScrimAlpha(tone: Float, target: Float, scrim: Float, minimum: Float, maximum: Float): Float {
    if ((scrim < target && tone <= target) || (scrim > target && tone >= target)) return minimum
    val difference = tone - scrim
    if (kotlin.math.abs(difference) < .001f) return minimum
    return ((tone - target) / difference).coerceIn(minimum, maximum)
}
