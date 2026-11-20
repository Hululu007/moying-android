package com.moying.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

@Composable
fun AppText(text: String, modifier: Modifier = Modifier, style: TextStyle = Theme.Type.Body, color: Color = style.color, maxLines: Int = Int.MAX_VALUE) {
    // 视频上的前景保持恒白，浅色主题也不把控制栏文字变成黑色。
    val mediaColor = if (LocalVideoBackdrop.current != null) when (color) {
        Theme.Colors.Text -> Theme.Colors.OnMedia
        Theme.Colors.Secondary, Theme.Colors.Muted -> Theme.Colors.MediaSecondary
        else -> color
    } else color
    BasicText(text = text, modifier = modifier, style = style.copy(color = mediaColor), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun AppIcon(pathData: String, modifier: Modifier = Modifier, tint: Color = Theme.Colors.Text, filled: Boolean = false) {
    val path = remember(pathData) { PathParser.createPathFromPathData(pathData)!!.asComposePath() }
    val foreground = if (LocalVideoBackdrop.current != null && tint == Theme.Colors.Text) Theme.Colors.OnMedia else tint
    Canvas(modifier.size(Theme.Size.Icon)) {
        withTransform({ scale(size.width / Theme.Size.IconGrid, size.height / Theme.Size.IconGrid, Offset.Zero) }) {
            if (filled) drawPath(path, foreground)
            else drawPath(path, foreground, style = Stroke(width = Theme.Size.Stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun IconAction(label: String, path: String, onClick: () -> Unit, tint: Color = Theme.Colors.Text, enabled: Boolean = true, iconOffsetX: Dp = Theme.Space.None) {
    Box(Modifier.size(Theme.Size.Touch).alpha(if (enabled) Theme.Size.FullFraction else Theme.Opacity.DisabledControl).clip(RoundedCornerShape(Theme.Radius.Pill)).hapticClickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        AppIcon(path, modifier = Modifier.offset(x = iconOffsetX), tint = tint, filled = path == Theme.IconPaths.Play || path == Theme.IconPaths.Next)
    }
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val media = LocalVideoBackdrop.current != null
    Box(modifier.alpha(if (enabled) 1f else .45f).heightIn(min = Theme.Size.Touch).clip(RoundedCornerShape(Theme.Radius.Control)).background(if (primary) Theme.Colors.Accent else if (media) Theme.Glass.RefractedTint else Theme.Colors.Elevated).hapticClickable(enabled = enabled, onClick = onClick).padding(horizontal = Theme.Space.Large, vertical = Theme.Space.Small), contentAlignment = Alignment.Center) {
        AppText(label, style = Theme.Type.Label, color = if (primary) Theme.Colors.OnAccent else Theme.Colors.Text)
    }
}

@Composable
fun AssetImage(asset: String, description: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, loadingDeferred: Boolean = false) {
    val context = LocalContext.current.applicationContext
    var requestedSize by remember(asset) { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(asset) { mutableStateOf(PosterLoader.peek(asset)) }
    val imageAlpha = remember(asset) { Animatable(if (bitmap != null) Theme.Size.FullFraction else Theme.Size.EmptyFraction) }
    var revealComplete by remember(asset) { mutableStateOf(bitmap != null) }
    val deferNewRequest by rememberUpdatedState(loadingDeferred)
    LaunchedEffect(context, asset, requestedSize) {
        if (requestedSize.width > 0 && requestedSize.height > 0) {
            PosterLoader.load(context, asset, requestedSize.width, requestedSize.height, deferNewRequest)?.let { bitmap = it }
        }
    }
    // Cache hits appear immediately. A newly arrived image fades in once;
    // later size upgrades reuse the visible image without replaying the fade.
    LaunchedEffect(asset, bitmap != null) {
        if (bitmap != null && !revealComplete) {
            imageAlpha.animateTo(Theme.Size.FullFraction, tween(Theme.Motion.FadeMillis, easing = LinearOutSlowInEasing))
            revealComplete = true
        }
    }
    // 输入：布局实际像素；输出：向上分桶的解码尺寸，尺寸轻微变化不会重复请求。
    val measuredModifier = modifier.onSizeChanged { size ->
        val bounded = PosterLoader.requestSize(size.width, size.height)
        if (bounded != requestedSize) requestedSize = bounded
    }
    val shown = bitmap
    // 输入缓存/解码结果；输出同一测量节点，图像到达时只替换内部绘制，避免重新归零尺寸。
    Box(measuredModifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (!revealComplete) Box(Modifier.matchParentSize().background(Theme.Colors.PosterFallback), contentAlignment = Alignment.Center) {
            AppIcon(Theme.IconPaths.Play, modifier = Modifier.size(Theme.Size.SmallIcon), tint = Theme.Colors.Muted.copy(alpha = .28f), filled = true)
        }
        if (shown != null) Image(shown, contentDescription = null, modifier = Modifier.matchParentSize().graphicsLayer { alpha = imageAlpha.value }, contentScale = contentScale)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        AppText(title, style = Theme.Type.Section)
        subtitle?.let { AppText(it, style = Theme.Type.Caption, color = Theme.Colors.Muted) }
    }
}

@Composable
fun BottomNavigation(
    selected: String,
    onNavigate: (String) -> Unit,
    scrollState: ScrollNavigationState? = null,
    linkedPosition: State<Float>? = null,
    onPagerDragStart: () -> Unit = {},
    onPagerDragPosition: (Float) -> Unit = {},
    onPagerDragEnd: (Int, Float) -> Unit = { _, _ -> },
    onPagerDragCancel: () -> Unit = {},
    glassEnabled: Boolean = true,
    homeScrollState: ScrollableState? = null,
) {
    val destinations = listOf("首页" to Theme.IconPaths.Home, "播放" to Theme.IconPaths.Play, "历史" to Theme.IconPaths.History)
    // 宽屏下收敛为居中胶囊；手机上仍铺满可用宽度，保持原触控面积。
    val navWidth = if (LocalWindowLayout.current.isWide) Modifier.widthIn(max = Theme.Layout.NavMaxWidth) else Modifier
    val verticalDrag = if (homeScrollState == null) Modifier else {
        val dragState = remember(homeScrollState) {
            object : ScrollableState by homeScrollState {
                // Keep axis arbitration and tab taps during a grid fling.
                override val isScrollInProgress: Boolean get() = false
            }
        }
        val nestedScroll = scrollState?.let { Modifier.nestedScroll(it.connection) } ?: Modifier
        nestedScroll.scrollable(dragState, Orientation.Vertical, reverseDirection = true)
    }
    NavLiquidGlass(destinations.indexOfFirst { it.first == selected }.coerceAtLeast(0), destinations.size,
        navWidth.fillMaxWidth().padding(horizontal = Theme.BottomTabsGlass.SideInset).navigationBarsPadding()
            .padding(bottom = Theme.Space.Small).height(Theme.Size.NavHeight).then(verticalDrag),
        scrollState = scrollState,
        onSelected = { index -> destinations.getOrNull(index)?.let { onNavigate(it.first) } },
        linkedPosition = linkedPosition,
        onPagerDragStart = onPagerDragStart,
        onPagerDragPosition = onPagerDragPosition,
        onPagerDragEnd = onPagerDragEnd,
        onPagerDragCancel = onPagerDragCancel,
        glassEnabled = glassEnabled,
    ) { selectionPosition ->
      Row(Modifier.fillMaxSize().padding(Theme.NavGlass.CapsuleInset)) {
        destinations.forEachIndexed { index, (label, path) ->
            val distance = kotlin.math.abs(selectionPosition.value - index)
            val proximity = (Theme.Size.FullFraction - distance / Theme.NavGlass.IconInfluenceRadius)
                .coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
            val color = lerp(Theme.NavGlass.InactiveText, Theme.Colors.Accent, proximity)
            Column(Modifier.weight(Theme.Size.Weight).fillMaxHeight().clip(RoundedCornerShape(Theme.Radius.Pill))
                .hapticClickable { onNavigate(label) }.semantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selectionPosition.value.roundToInt() == index
                }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Theme.BottomTabsGlass.ContentGap, Alignment.CenterVertically)) {
                // 页码、透镜和图标共同读取 Pager 的连续位置，滑动半程不会提前换色。
                AppIcon(path, modifier = Modifier.size(Theme.BottomTabsGlass.IconSize).graphicsLayer {
                    val distance = kotlin.math.abs(selectionPosition.value - index)
                    val proximity = (Theme.Size.FullFraction - distance / Theme.NavGlass.IconInfluenceRadius)
                        .coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                    val iconScale = Theme.NavGlass.IconScaleRest +
                        (Theme.NavGlass.IconScalePeak - Theme.NavGlass.IconScaleRest) * proximity
                    scaleX = iconScale
                    scaleY = iconScale
                }, tint = color)
                AppText(label, style = Theme.Type.Caption.copy(fontSize = Theme.BottomTabsGlass.LabelSize,
                    lineHeight = Theme.BottomTabsGlass.LabelLineHeight), color = color)
            }
        }
      }
    }
}

/** 输入：播放器进度；输出：Catalog 胶囊滑块，点击或松手时提交定位。 */
@Composable
fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, bufferedPosition: Long = Playback.Start, trackOffset: Dp = Theme.Space.None) {
    LiquidSlider(position, duration, onSeek, modifier, bufferedPosition, trackOffset)
}

@Composable
fun ThinProgress(position: Long, duration: Long, modifier: Modifier = Modifier) {
    val fraction = if (duration > Playback.Start) (position.toFloat() / duration).coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction) else Theme.Size.EmptyFraction
    Box(modifier.fillMaxWidth().height(Theme.Size.ProgressHeight).background(Theme.Colors.Border)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Theme.Colors.Accent))
    }
}

/** 输入：毫秒；输出：MM:SS（长片可超过60分钟）。 */
fun formatTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(Playback.Start) / Playback.MillisPerSecond
    return "%02d:%02d".format(seconds / Playback.SecondsPerMinute, seconds % Playback.SecondsPerMinute)
}

fun episodeLabel(movie: Movie, episode: Int): String = if (movie.episodeCount == Playback.FirstEpisode) "完整版" else "第${episode}集"
