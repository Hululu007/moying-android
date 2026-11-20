package com.moying.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.geometry.Rect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Exercises the rendered pixels of the navigation and scroll glass backdrops. */
object GlassIdleRegression {
    fun run(instrumentation: Instrumentation, args: Bundle): Bundle {
        var activity: DebugGlassActivity? = null
        var assertions = 0
        var idleDraws = 0
        fun assertThat(condition: Boolean, description: String) {
            assertions++
            check(condition) { description }
        }
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun sample(
            host: DebugGlassActivity,
            horizontal: Float = .5f,
            vertical: Float = .5f,
            bounds: Rect = host.navigationBounds,
        ): IntArray {
            val dimensions = main { host.window.decorView.width to host.window.decorView.height }
            val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1)
                var status = PixelCopy.ERROR_UNKNOWN
                main { PixelCopy.request(host.window, bitmap, { value -> status = value; done.countDown() }, Handler(Looper.getMainLooper())) }
                check(done.await(4, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "Window PixelCopy failed: $status" }
                check(!bounds.isEmpty) { "Glass surface is not placed" }
                val x = (bounds.left + bounds.width * horizontal).roundToInt()
                val y = (bounds.top + bounds.height * vertical).roundToInt()
                val totals = IntArray(3)
                for (dy in -2..2) for (dx in -2..2) {
                    val pixel = bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))
                    totals[0] += Color.red(pixel); totals[1] += Color.green(pixel); totals[2] += Color.blue(pixel)
                }
                return IntArray(3) { totals[it] / 25 }
            } finally { bitmap.recycle() }
        }
        fun assertIdle(host: DebugGlassActivity) {
            // Initial spring/first-frame setup is allowed to settle, then observe
            // real Android window draws without forcing frames or calling idleSync.
            Thread.sleep(650)
            val before = host.windowDraws.get()
            Thread.sleep(650)
            val delta = host.windowDraws.get() - before
            idleDraws += delta
            assertThat(delta <= 2, "Static glass kept redrawing: $delta window draws in 650ms")
        }
        try {
            val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DebugGlassActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DebugGlassActivity
            activity = host
            check(host.sceneReady.await(5, TimeUnit.SECONDS)) { "Glass scene did not become ready" }
            assertIdle(host)
            val red = sample(host)
            assertThat(red[0] > red[1] + 25 && red[0] > red[2] + 25, "Initial glass did not sample red page: ${red.toList()}")

            val wallpaperDraws = host.wallpaperDraws.get()
            // This value is read only by Canvas.draw, so the colour change must
            // propagate through its live RenderNode dependency without forcing
            // recomposition of the navigation or changing the page key.
            main { host.green = true }
            Thread.sleep(250)
            val green = sample(host)
            assertThat(green[1] > green[0] + 25 && green[1] > green[2] + 25,
                "Content/page update left old glass pixels: ${green.toList()}")
            assertThat(wallpaperDraws > 0 && host.wallpaperDraws.get() == wallpaperDraws,
                "Updating the page rerecorded the wallpaper: $wallpaperDraws -> ${host.wallpaperDraws.get()}")
            assertIdle(host)

            main { host.page = "second"; host.green = false }
            Thread.sleep(250)
            val switched = sample(host)
            assertThat(switched[0] > switched[1] + 25 && switched[0] > switched[2] + 25,
                "Switching pages retained the previous page's glass: ${switched.toList()}")

            main { host.moved = true }
            Thread.sleep(250)
            val blue = sample(host)
            assertThat(blue[2] > blue[0] + 25 && blue[2] > blue[1] + 25,
                "Moving glass did not update its backdrop coordinates: ${blue.toList()}")
            assertIdle(host)

            main {
                host.moved = false
            }
            main { host.glassEnabled = false; host.green = true }
            Thread.sleep(250)
            val glassOff = sample(host)
            assertThat(glassOff[1] > glassOff[0] && glassOff[1] > glassOff[2],
                "Turning glass off obscured the underlying page: ${glassOff.toList()}")
            assertIdle(host)

            main { host.glassEnabled = true; host.green = false }
            Thread.sleep(250)
            val glassRestored = sample(host)
            assertThat(glassRestored[0] > glassRestored[2] + 25,
                "Turning glass back on did not sample the current page: ${glassRestored.toList()}")
            assertIdle(host)
            main { host.showWallpaper = true }
            Thread.sleep(250)
            val oldWallpaper = sample(host)
            assertThat(oldWallpaper[0] > oldWallpaper[1] + 25,
                "Navigation did not sample the visible wallpaper: ${oldWallpaper.toList()}")
            main { host.wallpaperGreen = true }
            Thread.sleep(250)
            val newWallpaper = sample(host)
            assertThat(newWallpaper[1] > newWallpaper[0] + 25 && newWallpaper[1] > newWallpaper[2] + 25,
                "Updating the wallpaper left old glass pixels: ${newWallpaper.toList()}")
            assertIdle(host)
            main {
                host.scrollTuning.value = host.scrollTuning.value.copy(refraction = 0f)
                host.scrollCard = true
            }
            check(host.scrollReady.await(5, TimeUnit.SECONDS)) { "Scroll glass scene did not become ready" }
            Thread.sleep(250)
            val flatCard = sample(host, vertical = .045f, bounds = host.scrollBounds)
            assertThat(flatCard[2] > flatCard[1] + 8,
                "Zero refraction displaced the scroll card's backdrop edge: ${flatCard.toList()}")

            main { host.scrollTuning.value = host.scrollTuning.value.copy(refraction = 1f) }
            Thread.sleep(250)
            val refractedCard = sample(host, vertical = .045f, bounds = host.scrollBounds)
            assertThat(refractedCard[1] > refractedCard[2] + 25,
                "Scroll glass did not refract its background: ${refractedCard.toList()}")
            val cardBackground = sample(host, horizontal = .82f, bounds = host.scrollBounds)
            assertThat(cardBackground[2] > cardBackground[1] + 8,
                "Scroll glass obscured its background: ${cardBackground.toList()}")
            val cardForeground = sample(host, bounds = host.scrollBounds)
            assertThat(cardForeground.all { it > 235 },
                "Scroll glass affected the card foreground: ${cardForeground.toList()}")
            assertIdle(host)
            for (style in listOf(CatalogGlassStyle.OSHIN_CONTROL, CatalogGlassStyle.OSHIN_PANEL, CatalogGlassStyle.OSHIN_POSTER)) {
                main {
                    host.previewStyle = style
                    host.scrollTuning.value = GlassTuning(transparency = 1f, blur = 0f, refraction = 0f, highlight = 0f)
                }
                Thread.sleep(250)
                val clear = sample(host, horizontal = .82f, bounds = host.scrollBounds)
                main { host.scrollTuning.value = host.scrollTuning.value.copy(transparency = 0f) }
                Thread.sleep(250)
                val tinted = sample(host, horizontal = .82f, bounds = host.scrollBounds)
                assertThat(clear.indices.any { kotlin.math.abs(clear[it] - tinted[it]) > 4 },
                    "$style did not respond to transparency: ${clear.toList()} -> ${tinted.toList()}")
            }
            main {
                host.previewStyle = CatalogGlassStyle.OSHIN_CONTROL
                host.refractPreviewContent = true
                host.scrollTuning.value = GlassTuning(transparency = 1f, blur = 0f, refraction = 0f, highlight = 0f)
            }
            Thread.sleep(250)
            val flatLabel = sample(host, horizontal = .10f, bounds = host.scrollBounds)
            assertThat(flatLabel[0] > flatLabel[1] + 80, "Flat content did not draw inside the glass: ${flatLabel.toList()}")
            main { host.scrollTuning.value = host.scrollTuning.value.copy(refraction = 1f) }
            Thread.sleep(250)
            val refractedLabel = sample(host, horizontal = .05f, bounds = host.scrollBounds)
            assertThat(refractedLabel[0] > refractedLabel[1] + 80, "Glass did not move content toward the edge: ${refractedLabel.toList()}")
            return Bundle().apply {
                putInt("assertions", assertions)
                putInt("idleWindowDraws", idleDraws)
                putString("redSample", red.toList().toString())
                putString("greenSample", green.toList().toString())
                putString("switchedPageSample", switched.toList().toString())
                putString("blueSample", blue.toList().toString())
                putString("glassOffSample", glassOff.toList().toString())
                putString("glassRestoredSample", glassRestored.toList().toString())
                putString("updatedWallpaperSample", newWallpaper.toList().toString())
                putString("flatScrollSample", flatCard.toList().toString())
                putString("refractedScrollSample", refractedCard.toList().toString())
                putString("scrollBackgroundSample", cardBackground.toList().toString())
                putString("scrollForegroundSample", cardForeground.toList().toString())
                putString("stream", "Glass idle and live-backdrop regression passed: $assertions assertions\n")
            }
        } finally { main { activity?.finish() } }
    }
}
