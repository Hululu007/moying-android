package com.moying.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp

/** 输入：历史仓库和续播回调；输出：持久化的观看记录、删除和清空操作。 */
@Composable
fun HistoryScreen(repository: MovieRepository, onOpenMovie: (Movie, WatchHistory?) -> Unit, bottomPadding: Dp = Theme.Space.Page) {
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val history = repository.history
    val listState = rememberLazyGridState()
    val scrollMotion = rememberScrollGlassMotion()
    val deferNewPosters by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Theme.Layout.HistoryCardMinWidth),
        Modifier.fillMaxSize().nestedScroll(scrollMotion.connection),
        state = listState,
        contentPadding = PaddingValues(start = Theme.ScrollGlass.PageInset, end = Theme.ScrollGlass.PageInset, top = Theme.ScrollGlass.PageInset, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(Theme.ScrollGlass.ItemSpacing),
        verticalArrangement = Arrangement.spacedBy(Theme.ScrollGlass.ItemSpacing),
    ) {
        item(key = "history-heading", contentType = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText("观看历史", style = Theme.Type.PageTitle, modifier = Modifier.weight(Theme.Size.Weight))
                    if (history.isNotEmpty()) AppText("${history.size} 部", style = Theme.Type.Caption, color = Theme.Colors.Muted, modifier = Modifier.padding(end = Theme.Space.Medium))
                    AmbientGlassSurface(shape = RoundedCornerShape(Theme.Radius.Pill)) {
                        Box(Modifier.height(Theme.Size.Touch).hapticClickable { editing = !editing; confirmClear = false }.padding(horizontal = Theme.Space.Large), contentAlignment = Alignment.Center) { AppText(if (editing) "完成" else "管理", style = Theme.Type.Label, color = Theme.Colors.Secondary) }
                    }
                }
            }
        }
        if (editing && history.isNotEmpty()) {
            item(key = "history-management", contentType = "management", span = { GridItemSpan(maxLineSpan) }) {
                ScrollGlassContainer(Modifier.fillMaxWidth(), motion = scrollMotion) {
                    Row(Modifier.fillMaxWidth().padding(Theme.ScrollGlass.ContentPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText(if (confirmClear) "确定清空全部观看记录？" else "管理本地观看记录", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                        Box(Modifier.height(Theme.Size.Touch).hapticClickable { if (confirmClear) { repository.clearHistory(); confirmClear = false; editing = false } else confirmClear = true }, contentAlignment = Alignment.Center) { AppText(if (confirmClear) "确定清空" else "清空全部", style = Theme.Type.Label, color = Theme.Colors.Accent) }
                    }
                }
            }
        }
        items(history, key = { it.movieId }, contentType = { "watch-history" }) { record ->
            repository.getMovie(record.movieId)?.let { movie ->
                PlaybackHistoryGlassContainer(
                    movie = movie,
                    record = record,
                    motion = scrollMotion,
                    loadingDeferred = deferNewPosters,
                    editing = editing,
                    onOpen = { onOpenMovie(movie, record) },
                    onDelete = { repository.deleteHistory(movie.id) },
                )
            }
        }
        if (history.isEmpty()) {
            item(key = "history-empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ScrollGlassContainer(Modifier.fillMaxWidth(), motion = scrollMotion) {
                    Column(Modifier.fillMaxWidth().heightIn(min = Theme.ScrollGlass.MinHeight).padding(horizontal = Theme.ScrollGlass.ContentPadding, vertical = Theme.Space.XXLarge), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.Space.Large, Alignment.CenterVertically)) {
                        AppIcon(Theme.IconPaths.History, tint = Theme.Colors.Muted)
                        AppText("暂无观看记录", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                    }
                }
            }
        }
    }
}
