package com.moying.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Only unconsumed edge drag moves the glass. Ordinary scrolling adds no animation work. */
@Stable
internal class ScrollGlassMotion(
    private val scope: CoroutineScope,
    private val maxPull: Float,
) {
    var edgePull by mutableFloatStateOf(0f)
        private set
    private var returnJob: Job? = null

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && abs(available.y) > Theme.ScrollGlass.MinimumPull) {
                returnJob?.cancel()
                returnJob = null
                // Saturating resistance keeps the tether bounded even during a long edge drag.
                val resistance = (1f - abs(edgePull) / maxPull).coerceIn(0f, 1f)
                edgePull = (edgePull + available.y * Theme.ScrollGlass.EdgePullGain * resistance)
                    .coerceIn(-maxPull, maxPull)
            } else if (abs(consumed.y) > Theme.ScrollGlass.MinimumPull) {
                release()
            }
            // Never consume movement needed by the lazy list or its fling.
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            release()
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            release()
            return Velocity.Zero
        }
    }

    private fun release() {
        if (returnJob?.isActive == true) return
        if (abs(edgePull) <= Theme.ScrollGlass.MinimumPull) {
            edgePull = 0f
            return
        }
        returnJob = scope.launch {
            Animatable(edgePull).animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Theme.ScrollGlass.EdgeReturnDamping,
                    stiffness = Theme.ScrollGlass.EdgeReturnStiffness,
                ),
            ) { edgePull = value }
            edgePull = 0f
        }
    }

    fun dispose() {
        returnJob?.cancel()
        returnJob = null
    }
}

@Composable
internal fun rememberScrollGlassMotion(): ScrollGlassMotion {
    val scope = rememberCoroutineScope()
    val maxPull = with(LocalDensity.current) { Theme.ScrollGlass.MaxEdgePull.toPx() }
    val motion = remember(scope, maxPull) { ScrollGlassMotion(scope, maxPull) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}

/**
 * Catalog Scroll Container: 32dp rounded lens, vibrancy, 16dp / 32dp refraction,
 * and no blur. AmbientGlassSurface samples only the independent wallpaper layer.
 * Layer-only edge motion leaves lazy item composition and image loading untouched.
 */
@Composable
internal fun ScrollGlassContainer(
    modifier: Modifier = Modifier,
    motion: ScrollGlassMotion? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    AmbientGlassSurface(
        modifier = modifier.graphicsLayer { translationY = motion?.edgePull ?: 0f },
        shape = RoundedCornerShape(Theme.ScrollGlass.Radius),
        style = CatalogGlassStyle.SCROLL,
        content = content,
    )
}

/** Shared playback information keeps history actions and the home resume entry in sync. */
@Composable
internal fun PlaybackHistoryGlassContainer(
    movie: Movie,
    record: WatchHistory,
    modifier: Modifier = Modifier,
    motion: ScrollGlassMotion? = null,
    loadingDeferred: Boolean = false,
    label: String? = null,
    editing: Boolean = false,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    ScrollGlassContainer(modifier.fillMaxWidth(), motion) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = Theme.ScrollGlass.MinHeight)
                .hapticClickable(enabled = !editing, onClick = onOpen)
                .padding(Theme.ScrollGlass.ContentPadding),
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssetImage(
                movie.poster,
                movie.title,
                Modifier.width(Theme.ScrollGlass.PosterWidth)
                    .aspectRatio(Theme.Size.CoverAspect)
                    .clip(RoundedCornerShape(Theme.Radius.Poster)),
                loadingDeferred = loadingDeferred,
            )
            Column(
                Modifier.weight(Theme.Size.Weight),
                verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
            ) {
                if (label != null) AppText(label, style = Theme.Type.Micro, color = Theme.Colors.Muted)
                AppText(movie.title, style = Theme.Type.Body, maxLines = Playback.FirstEpisode)
                AppText(
                    "${episodeLabel(movie, record.episode)} · 已看 ${formatTime(record.positionMillis)}",
                    style = Theme.Type.Caption,
                    color = Theme.Colors.Secondary,
                )
                ThinProgress(record.positionMillis, record.durationMillis)
                AppText(
                    "${movie.year} · ${movie.genres}",
                    style = Theme.Type.Caption,
                    color = Theme.Colors.Muted,
                    maxLines = Playback.FirstEpisode,
                )
            }
            if (editing && onDelete != null) {
                IconAction("删除${movie.title}记录", Theme.IconPaths.Trash, onDelete, tint = Theme.Colors.Accent)
            } else if (!editing) {
                IconAction("继续观看${movie.title}", Theme.IconPaths.Play, onOpen)
            }
        }
    }
}
