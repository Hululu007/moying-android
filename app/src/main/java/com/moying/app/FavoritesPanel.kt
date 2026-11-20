package com.moying.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    onPoster: Boolean = false,
) {
    val shape = RoundedCornerShape(Theme.Radius.Pill)
    val action = if (isFavorite) "取消收藏" else "收藏"
    Box(
        modifier.size(Theme.Size.Touch).clip(shape)
            .hapticClickable(onClick = onClick)
            .semantics {
                contentDescription = action + title
                role = Role.Button
                selected = isFavorite
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = when {
            isFavorite -> Theme.Colors.Accent
            onPoster -> Theme.Colors.OnMedia
            else -> Theme.Colors.Text
        }
        if (onPoster) {
            Box(
                Modifier.size(30.dp).clip(shape).background(Color(0xA6101824)),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Theme.IconPaths.Bookmark, Modifier.size(18.dp), tint, filled = isFavorite)
            }
        } else {
            AppIcon(Theme.IconPaths.Bookmark, tint = tint, filled = isFavorite)
        }
    }
}

@Composable
internal fun FavoritesPanel(
    movies: List<Movie>,
    onOpenMovie: (Movie) -> Unit,
    onRemoveMovie: (Movie) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Theme.Space.Page),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = Theme.Space.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("我的收藏", style = Theme.Type.PageTitle, modifier = Modifier.weight(Theme.Size.Weight))
            IconAction("收起收藏面板", Theme.IconPaths.Close, onDismiss)
        }
        if (movies.isEmpty()) {
            ScrollGlassContainer(Modifier.fillMaxWidth().padding(bottom = Theme.Space.Page)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = Theme.ScrollGlass.MinHeight)
                        .padding(Theme.ScrollGlass.ContentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Theme.Space.Small, Alignment.CenterVertically),
                ) {
                    AppIcon(Theme.IconPaths.Bookmark, tint = Theme.Colors.Muted)
                    AppText("暂无收藏", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(Theme.Size.Weight, fill = false),
                contentPadding = PaddingValues(bottom = Theme.Space.Page),
                verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
            ) {
                items(movies, key = { it.id }, contentType = { "favorite" }) { movie ->
                    ScrollGlassContainer(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(Theme.Space.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(Theme.Size.Weight).clip(RoundedCornerShape(Theme.Radius.Control))
                                    .hapticClickable { onOpenMovie(movie) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
                            ) {
                                AssetImage(
                                    movie.poster,
                                    "",
                                    Modifier.width(54.dp).aspectRatio(Theme.Size.CoverAspect)
                                        .clip(RoundedCornerShape(Theme.Radius.Poster)),
                                )
                                Column(
                                    Modifier.weight(Theme.Size.Weight),
                                    verticalArrangement = Arrangement.spacedBy(Theme.Space.Tiny),
                                ) {
                                    AppText(movie.title, style = Theme.Type.Label, maxLines = 2)
                                    val details = listOf(movie.year, movie.region, movie.genres)
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    if (details.isNotBlank()) {
                                        AppText(details, style = Theme.Type.Caption, color = Theme.Colors.Muted, maxLines = 1)
                                    }
                                }
                            }
                            FavoriteButton(isFavorite = true, onClick = { onRemoveMovie(movie) }, title = movie.title)
                        }
                    }
                }
            }
        }
    }
}
