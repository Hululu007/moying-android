/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Full license: assets/licenses/Kyant-Backdrop-Apache-2.0.txt.
 *
 * Modified 2026-11-03 for Moying. Glass Playground's clear depth lens and
 * InteractiveHighlight's local press light are adapted for a movie grid.
 * Reference: Kyant0/AndroidLiquidGlass @65ab177e90e5c1d8c62e70cf7755841982da65f6.
 */
package com.moying.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.collect

/**
 * One clear Playground lens covers the complete frame, slightly larger than its
 * inset poster. Only wallpaper and poster enter the exported GPU display list;
 * the lens, labels and press light are always separate foreground.
 */
@Composable
internal fun PlaygroundMovieCard(
    movie: Movie,
    loadingDeferred: Boolean,
    animateEntrance: Boolean,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val parentBackdrop = LocalAmbientBackdrop.current
    val posterBackdrop = rememberAmbientBackdrop()
    val density = LocalDensity.current.density
    val posterMaterial = remember(density) {
        createNavigationGlassEffect(density, CatalogGlassStyle.OSHIN_POSTER)
    }
    val marginSample = remember(parentBackdrop) { PosterMarginSample() }
    SideEffect {
        if (!loadingDeferred) marginSample.refresh(parentBackdrop)
    }
    val interaction = rememberGlassTouchInteraction(enabled = !loadingDeferred)
    val clickSource = remember { MutableInteractionSource() }
    LaunchedEffect(clickSource, interaction) {
        // Scrollable clickable defers its press feedback until it can distinguish a drag.
        var activePress: PressInteraction.Press? = null
        clickSource.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    activePress = event
                    interaction.contact(event.pressPosition)
                }
                is PressInteraction.Release -> if (activePress == event.press) {
                    activePress = null
                    interaction.end(ripple = true)
                }
                is PressInteraction.Cancel -> if (activePress == event.press) {
                    activePress = null
                    interaction.reset()
                }
            }
        }
    }
    val feedback = LocalInteractionHaptics.current
    val entrance = remember(movie.id) {
        Animatable(if (animateEntrance) Theme.Size.EmptyFraction else Theme.Size.FullFraction)
    }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // A finite, one-shot appearance. Home owns the ID set so scrolling back to a
    // disposed Lazy cell does not replay the same movie's entrance.
    LaunchedEffect(movie.id) {
        if (entrance.value < Theme.Size.FullFraction) entrance.animateTo(
            Theme.Size.FullFraction,
            tween(Theme.PlaygroundGlass.EnterMillis, easing = FastOutSlowInEasing),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { cardSize = it }
            .clickable(interactionSource = clickSource, indication = null) {
                feedback.tap()
                onClick()
            }
            .graphicsLayer {
                val pressure = interaction.pressProgress.coerceIn(Theme.Size.EmptyFraction, Theme.Size.FullFraction)
                val entry = entrance.value
                val contact = interaction.touch
                val normalizedX = if (contact != null && cardSize.width > 0)
                    ((contact.x / cardSize.width - Theme.Size.CenterFraction) / Theme.Size.CenterFraction)
                        .coerceIn(-Theme.Size.FullFraction, Theme.Size.FullFraction)
                else Theme.Size.EmptyFraction
                val normalizedY = if (contact != null && cardSize.height > 0)
                    ((contact.y / cardSize.height - Theme.Size.CenterFraction) / Theme.Size.CenterFraction)
                        .coerceIn(-Theme.Size.FullFraction, Theme.Size.FullFraction)
                else Theme.Size.EmptyFraction
                val scale = (Theme.PlaygroundGlass.EnterScale +
                    (Theme.Size.FullFraction - Theme.PlaygroundGlass.EnterScale) * entry) *
                    (Theme.Size.FullFraction - Theme.PlaygroundGlass.PressScale * pressure)
                scaleX = scale
                scaleY = scale
                rotationX = -normalizedY * Theme.PlaygroundGlass.TiltDegrees * pressure
                rotationY = normalizedX * Theme.PlaygroundGlass.TiltDegrees * pressure
                translationY = (Theme.Size.FullFraction - entry) * Theme.PlaygroundGlass.EnterLift.toPx()
                cameraDistance = Theme.PlaygroundGlass.CameraDistance * density
                alpha = entry
            },
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(Theme.Size.CoverAspect)
                .clip(RoundedCornerShape(Theme.PlaygroundGlass.PosterRadius)),
        ) {
            // Keep the poster and its glass together during scrolling. The
            // narrow margin reuses its last wallpaper projection until idle.
            AmbientBackdropHost(
                posterBackdrop,
                Modifier.matchParentSize().onGloballyPositioned { coordinates ->
                    marginSample.coordinates = coordinates
                    if (!loadingDeferred || marginSample.projection == null) marginSample.refresh(parentBackdrop)
                },
                trackPosition = false,
                // The lens draws the whole card, so it can be the only on-screen
                // poster draw. If the shader is unavailable, draw the source here.
                captureOnly = posterMaterial != null,
            ) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawRect(Theme.Colors.Background)
                    val source = parentBackdrop
                    val projection = marginSample.projection
                    if (source != null && source.ready && projection != null) {
                        translate(projection.origin.x, projection.origin.y) {
                            scale(projection.scaleX, projection.scaleY, pivot = Offset.Zero) { drawLayer(source.layer) }
                        }
                    }
                    drawContent()
                }) {
                    AssetImage(
                        movie.poster,
                        movie.title,
                        Modifier.fillMaxSize().padding(Theme.PlaygroundGlass.MaskInset)
                            .clip(RoundedCornerShape(Theme.PlaygroundGlass.PanelRadius)),
                        loadingDeferred = loadingDeferred,
                    )
                }
            }
            CompositionLocalProvider(LocalAmbientBackdrop provides posterBackdrop) {
                AmbientGlassSurface(
                    modifier = Modifier.matchParentSize(),
                    shape = RoundedCornerShape(Theme.PlaygroundGlass.PosterRadius),
                    lightResponse = false,
                    style = CatalogGlassStyle.OSHIN_POSTER,
                    posterMaterial = posterMaterial,
                    materialProgressProvider = remember(interaction) { { interaction.pressProgress } },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().drawWithCache {
                            onDrawBehind {
                                val pressure = interaction.pressProgress
                                if (pressure > Theme.Size.EmptyFraction) {
                                    val point = interaction.touch ?: Offset(
                                        size.width * Theme.Size.CenterFraction,
                                        size.height * Theme.Size.CenterFraction,
                                    )
                                    val contact = Offset(
                                        point.x.coerceIn(Theme.Size.EmptyFraction, size.width),
                                        point.y.coerceIn(Theme.Size.EmptyFraction, size.height),
                                    )
                                    drawRect(Theme.Colors.OnMedia.copy(alpha = Theme.PlaygroundGlass.PressWash * pressure), blendMode = BlendMode.Plus)
                                    drawRect(
                                        Brush.radialGradient(
                                            listOf(Theme.Colors.OnMedia.copy(alpha = Theme.PlaygroundGlass.PressSpotlight * pressure), Color.Transparent),
                                            center = contact,
                                            radius = size.minDimension * Theme.PlaygroundGlass.TouchRadius,
                                        ),
                                        blendMode = BlendMode.Plus,
                                    )
                                }
                            }
                        })
                        if (onToggleFavorite != null) {
                            FavoriteButton(
                                isFavorite,
                                onToggleFavorite,
                                modifier = Modifier.align(Alignment.TopEnd),
                                title = movie.title,
                                onPoster = true,
                            )
                        }
                        if (movie.score.isNotBlank()) {
                            val scoreAlignment = if (onToggleFavorite != null) Alignment.TopStart else Alignment.TopEnd
                            AppText(
                                movie.score,
                                modifier = Modifier.align(scoreAlignment).padding(Theme.PlaygroundGlass.PanelInset)
                                    .clip(RoundedCornerShape(Theme.Radius.Pill)).background(Theme.PlaygroundGlass.ScoreTint)
                                    .padding(horizontal = Theme.PlaygroundGlass.ScoreHorizontal, vertical = Theme.PlaygroundGlass.ScoreVertical),
                                style = Theme.Type.Caption,
                                color = Theme.Colors.OnMedia,
                                maxLines = Theme.Size.SingleLine,
                            )
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(Theme.PlaygroundGlass.PanelInset)
                                .fillMaxWidth().clip(RoundedCornerShape(Theme.PlaygroundGlass.PanelRadius))
                                .background(Theme.PlaygroundGlass.MediaTint).padding(Theme.PlaygroundGlass.PanelPadding),
                            verticalArrangement = Arrangement.spacedBy(Theme.PlaygroundGlass.PanelRowGap),
                        ) {
                            AppText(movie.title, style = Theme.Type.Label, color = Theme.Colors.OnMedia, maxLines = Theme.Size.SingleLine)
                            AppText(movie.status, style = Theme.Type.Micro, color = Theme.Colors.MediaSecondary, maxLines = Theme.Size.SingleLine)
                        }
                    }
                }
            }
        }
        AppText("${movie.year} · ${movie.genres}", style = Theme.Type.Caption, color = Theme.Colors.Muted, maxLines = Theme.Size.SingleLine)
    }
}

private data class PosterMarginProjection(val origin: Offset, val scaleX: Float, val scaleY: Float)

private class PosterMarginSample {
    var coordinates: LayoutCoordinates? = null
    var projection by mutableStateOf<PosterMarginProjection?>(null)
        private set

    fun refresh(backdrop: AmbientBackdrop?) {
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val source = backdrop?.coordinates?.takeIf { it.isAttached } ?: return
        if (source.size.width <= 0 || source.size.height <= 0) return
        val origin = target.localPositionOf(source, Offset.Zero)
        val x = target.localPositionOf(source, Offset(source.size.width.toFloat(), 0f))
        val y = target.localPositionOf(source, Offset(0f, source.size.height.toFloat()))
        projection = PosterMarginProjection(origin, (x.x - origin.x) / source.size.width, (y.y - origin.y) / source.size.height)
    }
}
