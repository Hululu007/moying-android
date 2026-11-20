package com.moying.app

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only real GPU scene; no timers, networking or app preferences. */
class DebugGlassActivity : ComponentActivity() {
    val windowDraws = AtomicInteger()
    val sceneReady = CountDownLatch(1)
    @Volatile var navigationBounds = Rect.Zero
        private set
    var green by mutableStateOf(false)
    var moved by mutableStateOf(false)
    var page by mutableStateOf("first")
    var glassEnabled by mutableStateOf(true)
    internal var scrollCard by mutableStateOf(false)
    internal var previewStyle by mutableStateOf(CatalogGlassStyle.SCROLL)
    internal var refractPreviewContent by mutableStateOf(false)
    internal val scrollTuning = mutableStateOf(GlassTuning(blur = 0f))
    internal val scrollReady = CountDownLatch(1)
    @Volatile internal var scrollBounds = Rect.Zero
        private set
    private var originalDark = true
    private val drawListener = ViewTreeObserver.OnDrawListener { windowDraws.incrementAndGet() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalDark = Theme.isDark
        Theme.applyAppearance(true)
        window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
        setContent {
            if (scrollCard) {
                ScrollCardScene()
                return@setContent
            }
            val backdrop = rememberPageBackdrop(enabled = glassEnabled, page = page)
            CompositionLocalProvider(LocalPageBackdrop provides backdrop, LocalAmbientBackdrop provides null) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PageBackdropHost(backdrop, Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize()) {
                            val pageColor = if (green) Color(0xFF22D044) else Color(0xFFDA2222)
                            drawRect(pageColor)
                            drawRect(Color(0xFF285EE8), Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                        }
                    }
                    NavLiquidGlass(
                        selectedIndex = 0, itemCount = 3,
                        glassEnabled = glassEnabled,
                        modifier = Modifier.align(if (moved) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 24.dp).width(120.dp).height(64.dp)
                            .onGloballyPositioned { coordinates ->
                                navigationBounds = Rect(coordinates.localToWindow(Offset.Zero),
                                    Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                sceneReady.countDown()
                            },
                    ) { }
                }
            }
        }
    }

    @Composable
    private fun ScrollCardScene() {
        val ambient = rememberAmbientBackdrop()
        CompositionLocalProvider(
            LocalAmbientBackdrop provides ambient,
            LocalGlassTuning provides scrollTuning,
        ) {
            Box(Modifier.fillMaxSize()) {
                AmbientBackdropHost(ambient, Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF242F54))
                        val top = size.height / 2f - 21.dp.toPx()
                        drawRect(
                            Color(0xFF22D044),
                            topLeft = Offset(0f, top),
                            size = Size(100.dp.toPx(), size.height - top),
                        )
                    }
                }
                AmbientGlassSurface(
                    Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp)
                        .width(120.dp).height(64.dp).onGloballyPositioned { coordinates ->
                            scrollBounds = Rect(
                                coordinates.localToWindow(Offset.Zero),
                                Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                            )
                            scrollReady.countDown()
                        },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(Theme.ScrollGlass.Radius),
                    style = previewStyle,
                    refractContent = refractPreviewContent,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).background(Color.White))
                        if (refractPreviewContent) {
                            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(8.dp).background(Color.Red))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
        Theme.applyAppearance(originalDark)
        super.onDestroy()
    }
}
