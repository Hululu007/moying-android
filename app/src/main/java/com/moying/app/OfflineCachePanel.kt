package com.moying.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog

/** 输入：主动缓存队列；输出：按影片分组的已缓存/下载中列表，支持播放、暂停、重试和删除。 */
@Composable
internal fun OfflineCachePanel(
    cache: OfflineCacheManager,
    modifier: Modifier = Modifier,
    onOpenEpisode: (movieId: String, routeId: String, episode: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = cache.state
    var confirmClear by remember { mutableStateOf(false) }
    var confirmMovie by remember { mutableStateOf<OfflineRecordGroup?>(null) }
    val groups = remember(state.records) { groupedRecords(state.records) }

    Column(modifier.fillMaxWidth().padding(Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            AppText("离线缓存", style = Theme.Type.Section, modifier = Modifier.weight(Theme.Size.Weight))
            IconAction("收起离线缓存面板", Theme.IconPaths.Close, onDismiss)
        }
        AppText(
            "已用 ${formatSize(state.totalBytes)} / ${formatSize(state.capacityBytes)} · ${state.completedCount} 集可离线播放",
            style = Theme.Type.Caption, color = Theme.Colors.Secondary,
        )
        AppText("主动缓存优先保留，不会被播放缓存逐出；总容量与播放缓存共用上限。", style = Theme.Type.Micro, color = Theme.Colors.Muted)

        if (groups.isEmpty()) {
            AppText("还没有主动缓存的剧集。可在播放页选集区点击下载图标，或使用「整剧缓存」。", style = Theme.Type.Body, color = Theme.Colors.Muted)
        }

        groups.forEach { group ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(Theme.Size.Weight), verticalArrangement = Arrangement.spacedBy(Theme.Space.Tiny)) {
                    AppText(group.movie.title, style = Theme.Type.Label, maxLines = Theme.Size.SingleLine)
                    AppText("${group.records.size} 集 · ${formatSize(group.bytes)}", style = Theme.Type.Micro, color = Theme.Colors.Muted)
                }
                Box(
                    Modifier.heightIn(min = Theme.Size.Touch).hapticClickable { confirmMovie = group }.padding(horizontal = Theme.Space.Medium),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText("删除整剧", style = Theme.Type.Label, color = Theme.Colors.Accent)
                }
            }
            group.records.forEach { record ->
                OfflineRecordRow(record, cache, onOpenEpisode)
            }
            Spacer(Modifier.height(Theme.Space.XSmall))
        }

        if (state.records.isNotEmpty()) {
            ActionButton("清空全部缓存", { confirmClear = true }, Modifier.fillMaxWidth(), primary = false)
        }
    }

    if (confirmClear) {
        Dialog(onDismissRequest = { confirmClear = false }) {
            ConfirmBox(
                title = "清空离线缓存？",
                body = "将删除全部主动缓存和下载中的任务，释放 ${formatSize(state.totalBytes)} 空间。观看历史不受影响。",
                confirm = "确认清空",
                onDismiss = { confirmClear = false },
                onConfirm = {
                    cache.clearAll()
                    confirmClear = false
                },
            )
        }
    }
    confirmMovie?.let { group ->
        Dialog(onDismissRequest = { confirmMovie = null }) {
            ConfirmBox(
                title = "删除整剧缓存？",
                body = "将删除「${group.movie.title}」的全部已缓存集和下载任务，释放 ${formatSize(group.bytes)} 空间。",
                confirm = "确认删除",
                onDismiss = { confirmMovie = null },
                onConfirm = {
                    cache.deleteMovie(group.movie.id)
                    confirmMovie = null
                },
            )
        }
    }
}

@Composable
private fun OfflineRecordRow(record: OfflineRecord, cache: OfflineCacheManager, onOpenEpisode: (String, String, Int) -> Unit) {
    val playable = record.status == OfflineStatus.COMPLETED
    GlassSurface(shape = RoundedCornerShape(Theme.Radius.Control), tint = Theme.Glass.FieldFill) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Theme.Space.Medium, vertical = Theme.Space.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
        ) {
            Column(
                Modifier.weight(Theme.Size.Weight).heightIn(min = Theme.Size.Touch)
                    .hapticClickable(enabled = playable) { onOpenEpisode(record.movie.id, record.routeId, record.episode) },
                verticalArrangement = Arrangement.spacedBy(Theme.Space.Tiny, Alignment.CenterVertically),
            ) {
                AppText(
                    "${record.episodeName.ifBlank { "第${record.episode}集" }} · ${record.routeName}",
                    style = Theme.Type.Label, maxLines = Theme.Size.SingleLine,
                )
                AppText(
                    offlineStatusLine(record),
                    style = Theme.Type.Micro,
                    color = when {
                        playable -> Theme.Colors.Accent
                        record.status == OfflineStatus.FAILED -> Theme.Colors.BatteryLow
                        else -> Theme.Colors.Muted
                    },
                    maxLines = Theme.Size.SingleLine,
                )
                val progress = record.progress
                if (record.status == OfflineStatus.DOWNLOADING && progress != null) {
                    Box(Modifier.fillMaxWidth().height(Theme.Space.Hairline).clip(RoundedCornerShape(Theme.Radius.Pill)).background(Theme.Colors.Border)) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(0f, 0.99f))
                                .height(Theme.Space.Hairline)
                                .background(Theme.Colors.Accent),
                        )
                    }
                }
            }
            Box(
                Modifier.heightIn(min = Theme.Size.Touch).hapticClickable {
                    when (record.status) {
                        OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING -> cache.pause(record.cacheKey)
                        OfflineStatus.PAUSED, OfflineStatus.FAILED -> cache.resume(record.cacheKey)
                        OfflineStatus.COMPLETED -> onOpenEpisode(record.movie.id, record.routeId, record.episode)
                    }
                }.padding(horizontal = Theme.Space.Small),
                contentAlignment = Alignment.Center,
            ) {
                AppText(recordActionLabel(record), style = Theme.Type.Label, color = Theme.Colors.Accent)
            }
            IconAction("删除${record.movie.title}第${record.episode}集缓存", Theme.IconPaths.Trash, { cache.cancel(record.cacheKey) }, tint = Theme.Colors.Accent)
        }
    }
}

@Composable
private fun ConfirmBox(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.Radius.Card)).background(Theme.Colors.Surface)
            .border(Theme.Space.Hairline, Theme.Colors.Border, RoundedCornerShape(Theme.Radius.Card)),
    ) {
        Column(Modifier.padding(Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Large)) {
            AppText(title, style = Theme.Type.Section)
            AppText(body, style = Theme.Type.Body, color = Theme.Colors.Secondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                ActionButton("取消", onDismiss, Modifier.weight(Theme.Size.Weight), primary = false)
                ActionButton(confirm, onConfirm, Modifier.weight(Theme.Size.Weight))
            }
        }
    }
}

private data class OfflineRecordGroup(val movie: Movie, val records: List<OfflineRecord>) {
    val bytes: Long get() = records.sumOf { it.bytes }
}

/** 输入：全部缓存记录；输出：每部影片一组，组内按线路和集数排序。 */
private fun groupedRecords(records: List<OfflineRecord>): List<OfflineRecordGroup> =
    records.groupBy { it.movie.id }
        .values
        .map { group ->
            OfflineRecordGroup(group.first().movie, group.sortedWith(compareBy({ it.routeName }, { it.episode })))
        }
        .sortedByDescending { group -> group.records.maxOf { it.updatedAt } }

private fun recordActionLabel(record: OfflineRecord): String = when (record.status) {
    OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING -> "暂停"
    OfflineStatus.PAUSED, OfflineStatus.FAILED -> "继续"
    OfflineStatus.COMPLETED -> "播放"
}

private fun offlineStatusLine(record: OfflineRecord): String = when (record.status) {
    OfflineStatus.QUEUED -> "排队中"
    OfflineStatus.WAITING -> record.message ?: "等待网络"
    OfflineStatus.DOWNLOADING -> {
        val percent = record.downloadPercent?.let { " $it%" }.orEmpty()
        "下载中$percent · ${formatSize(record.bytes)}"
    }
    OfflineStatus.PAUSED -> "已暂停 · ${formatSize(record.bytes)}"
    OfflineStatus.COMPLETED -> "已缓存 · ${formatSize(record.bytes)}"
    OfflineStatus.FAILED -> "失败 · ${record.message ?: "点击继续重试"}"
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes.toDouble() / 1024.0)
    else -> "${bytes} B"
}
