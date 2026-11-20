package com.moying.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 首页只呈现 Main 持有的状态；页面切换不重建片单、分页或 LazyGridState。 */
@Composable
internal fun HomeScreen(
    repository: MovieRepository,
    state: HomeState,
    onOpenMovie: (Movie, WatchHistory?) -> Unit,
    panels: CapsulePanelState,
    posterGlass: State<GlassTuning>,
    panelGlass: State<GlassTuning>,
    bottomPadding: Dp = Theme.Space.Section,
) {
    val tvBox = repository as? TvBoxMovieRepository
    val resume = repository.history.firstOrNull()
    val resumeMovie = resume?.let { repository.getMovie(it.movieId) }
    val deferNewPosters by remember(state.gridState) { derivedStateOf { state.gridState.isScrollInProgress } }

    val windowLayout = LocalWindowLayout.current
    val deviceTouch = LocalViewConfiguration.current
    val minimumDrag = with(LocalDensity.current) { 4.dp.toPx() }
    val gridTouch = remember(deviceTouch, minimumDrag) {
        object : ViewConfiguration by deviceTouch {
            override val touchSlop = minOf(deviceTouch.touchSlop, maxOf(minimumDrag, deviceTouch.touchSlop * .60f))
        }
    }
    // Start a slow, short vertical drag sooner without changing the system's
    // tap timeouts or the gesture configuration of panels and playback.
    CompositionLocalProvider(LocalViewConfiguration provides gridTouch) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // 输入：窗口可用宽度；输出：平板/横屏增加列数，手机竖屏保持原来的 3 列。
    val columns = (maxWidth.value / Theme.Layout.PosterColumnStep.value).toInt()
        .coerceIn(Theme.Layout.PosterColumnsMin, Theme.Layout.PosterColumnsMax)
    val horizontalPadding = if (windowLayout.isWide) Theme.Space.XXLarge else Theme.Space.Page

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state.gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.Medium),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.Page),
    ) {
        item(key = "home-header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = Theme.Space.Page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                        BrandIcon(Modifier.size(Theme.Size.Badge))
                        AppText("墨映", style = Theme.Type.Brand)
                    }
                    CompositionLocalProvider(LocalGlassTuning provides panelGlass) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.XSmall)) {
                        AmbientGlassSurface(Modifier.capsuleAnchor(panels, HomePanel.SOURCES), shape = RoundedCornerShape(Theme.Radius.Pill),
                            style = CatalogGlassStyle.OSHIN_CONTROL, pressDeformation = true) {
                            Box(Modifier.height(Theme.Size.Touch).hapticClickable { panels.open(HomePanel.SOURCES) }.padding(horizontal = Theme.Space.Medium), contentAlignment = Alignment.Center) {
                                AppText("片源", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                            }
                        }
                        AmbientGlassSurface(Modifier.capsuleAnchor(panels, HomePanel.SEARCH), shape = RoundedCornerShape(Theme.Radius.Pill),
                            style = CatalogGlassStyle.OSHIN_CONTROL, pressDeformation = true) {
                            IconAction("搜索影片", Theme.IconPaths.Search, { panels.open(HomePanel.SEARCH) })
                        }
                        AmbientGlassSurface(Modifier.capsuleAnchor(panels, HomePanel.APPEARANCE), shape = RoundedCornerShape(Theme.Radius.Pill),
                            style = CatalogGlassStyle.OSHIN_CONTROL, pressDeformation = true) {
                            IconAction("主题与背景", Theme.IconPaths.Appearance, { panels.open(HomePanel.APPEARANCE) })
                        }
                    }
                    }
                }
                tvBox?.sources?.find { it.id == state.sourceId }?.let { source ->
                    AppText("当前片源 · ${source.name}", style = Theme.Type.Micro, color = Theme.Colors.Muted, modifier = Modifier.padding(top = Theme.Space.Small))
                }
                if (state.importBusy || state.importMessage != null) {
                    AppText(if (state.importBusy) "正在导入片源…" else state.importMessage.orEmpty(), style = Theme.Type.Caption, color = Theme.Colors.Secondary, modifier = Modifier.padding(top = Theme.Space.Small))
                }
                if (state.query.isNotBlank()) {
                    Row(Modifier.fillMaxWidth().padding(top = Theme.Space.Large), verticalAlignment = Alignment.CenterVertically) {
                        AppText("正在搜索 · ${state.query}", style = Theme.Type.Caption, color = Theme.Colors.Secondary, modifier = Modifier.weight(Theme.Size.Weight).hapticClickable { panels.open(HomePanel.SEARCH) }, maxLines = Theme.Size.SingleLine)
                        Box(Modifier.heightIn(min = Theme.Size.Touch).hapticClickable { state.setQuery("") }.padding(horizontal = Theme.Space.Medium), contentAlignment = Alignment.Center) {
                            AppText("清空", style = Theme.Type.Label, color = Theme.Colors.Accent)
                        }
                    }
                }
                CompositionLocalProvider(LocalGlassTuning provides panelGlass) {
                    CatalogBrowseHeader(state, Modifier.padding(top = Theme.Space.Large))
                }
                val showRecommendations = state.category == CatalogRecommend && state.filters.isEmpty() && state.query.isBlank()
                if (resume != null && resumeMovie != null && showRecommendations && panels.active != HomePanel.SEARCH) {
                    Spacer(Modifier.height(Theme.Space.Page))
                    PlaybackHistoryGlassContainer(
                        movie = resumeMovie,
                        record = resume,
                        loadingDeferred = deferNewPosters,
                        label = "继续观看",
                        onOpen = { onOpenMovie(resumeMovie, resume) },
                    )
                }
                Spacer(Modifier.height(Theme.Space.Section))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                    SectionTitle(
                        when {
                            state.query.isNotBlank() -> "搜索结果"
                            showRecommendations -> "为你推荐"
                            state.category == CatalogRecommend -> "筛选结果"
                            else -> state.catalog.channels.firstOrNull { it.id == state.category }?.label ?: "片库"
                        },
                        if (state.query.isNotBlank()) "${state.movies.size} 部" else null,
                        Modifier.weight(Theme.Size.Weight),
                    )
                    CompositionLocalProvider(LocalGlassTuning provides panelGlass) {
                    if (tvBox != null) AmbientGlassSurface(
                        Modifier.capsuleAnchor(panels, HomePanel.FAVORITES),
                        shape = RoundedCornerShape(Theme.Radius.Pill),
                        style = CatalogGlassStyle.OSHIN_CONTROL,
                        pressDeformation = true,
                    ) {
                        IconAction("打开收藏", Theme.IconPaths.Bookmark, { panels.open(HomePanel.FAVORITES) })
                    }
                    AmbientGlassSurface(Modifier.capsuleAnchor(panels, HomePanel.CACHE), shape = RoundedCornerShape(Theme.Radius.Pill),
                        style = CatalogGlassStyle.OSHIN_CONTROL, pressDeformation = true) {
                        IconAction("打开离线缓存", Theme.IconPaths.Download, { panels.open(HomePanel.CACHE) })
                    }
                    }
                }
            }
        }
        items(state.movies, key = { it.id }, contentType = { "movie" }) { movie ->
            val enterOnce = remember(state.catalogKey, movie.id) { state.markRecommendationShown(movie.id) && !deferNewPosters }
            CompositionLocalProvider(LocalGlassTuning provides posterGlass) {
              PlaygroundMovieCard(
                movie,
                loadingDeferred = deferNewPosters,
                animateEntrance = enterOnce,
                isFavorite = tvBox?.isFavorite(movie.id) == true,
                onToggleFavorite = tvBox?.let { source ->
                    { source.setFavorite(movie, !source.isFavorite(movie.id)) }
                },
            ) {
                onOpenMovie(movie, repository.history.find { it.movieId == movie.id })
            }
            }
        }
        item(key = "home-footer", contentType = "footer", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = Theme.Space.Section), contentAlignment = Alignment.Center) {
                if (state.loadError != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.Space.Medium)) {
                    AppText(state.loadError.orEmpty(), style = Theme.Type.Caption, color = Theme.Colors.Muted)
                    ActionButton("点击重试", state::retryLoad, primary = false)
                } else if (state.loading || (state.movies.isEmpty() && state.hasMore)) {
                    AppText("加载中…", style = Theme.Type.Caption, color = Theme.Colors.Muted)
                } else if (state.movies.isEmpty()) {
                    CatalogEmptyState(state)
                }
            }
            if (state.hasMore && !state.loading && state.loadError == null) {
                LaunchedEffect(state.catalogKey, state.loadedPage) {
                    delay(Theme.Motion.FadeMillis.toLong())
                    state.nextPage()
                }
            }
        }
    }
    }
    }
}
