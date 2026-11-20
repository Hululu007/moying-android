package com.moying.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SourceImportPanel(repository: MovieRepository, state: HomeState, appearance: AppAppearanceState, onOpenMovie: (Movie, WatchHistory?) -> Unit, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val tvBox = repository as? TvBoxMovieRepository
    val availabilityRepository = repository as? SourceAvailabilityRepository
    val importEnabled = !state.importBusy && !state.sourceCheckRunning
    var managingSources by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current.applicationContext
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && tvBox != null) state.importSources {
            withContext(Dispatchers.IO) {
                val (text, origin) = readImportDocument(context, uri)
                currentCoroutineContext().ensureActive()
                tvBox.importSources(text, origin)
            }
        }
    }
        Column(modifier.fillMaxWidth().padding(Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Medium)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                AppText("片源与导入", style = Theme.Type.Section, modifier = Modifier.weight(Theme.Size.Weight))
                if (tvBox != null) {
                    Box(Modifier.heightIn(min = Theme.Size.Touch).hapticClickable(enabled = importEnabled) {
                        managingSources = !managingSources
                    }.padding(horizontal = Theme.Space.Medium), contentAlignment = Alignment.Center) {
                        AppText(if (managingSources) "完成" else "管理", style = Theme.Type.Label, color = Theme.Colors.Secondary)
                    }
                }
                IconAction("收起片源面板", Theme.IconPaths.Close, onDismiss)
            }
            if (tvBox != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                    items(tvBox.sources, key = { it.id }) { source ->
                        GlassSurface(shape = RoundedCornerShape(Theme.Radius.Control), tint = if (state.sourceId == source.id) Theme.Glass.SelectedFill else Theme.Glass.FieldFill) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.heightIn(min = Theme.Size.Touch).hapticClickable(enabled = !state.importBusy) { tvBox.selectSource(source.id); state.selectSource(source.id) }.padding(horizontal = Theme.Space.Medium, vertical = Theme.Space.Small), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.Space.Tiny)) {
                                    AppText(source.name, style = Theme.Type.Caption, color = if (state.sourceId == source.id) Theme.Colors.Accent else Theme.Colors.Secondary)
                                    val result = state.sourceChecks[source.id]
                                    val pending = state.pendingSourceChecks[source.id]
                                    val status = when {
                                        pending == true -> "检测中…"
                                        pending == false -> "等待检测"
                                        source.id == "local" -> "本地 · 无需联网"
                                        result != null -> availabilityLabel(result.status)
                                        else -> "未检测"
                                    }
                                    AppText(status, style = Theme.Type.Micro,
                                        color = if (result?.status == SourceAvailability.AVAILABLE && pending == null) Theme.Colors.Accent else Theme.Colors.Muted,
                                        maxLines = Theme.Size.SingleLine)
                                }
                                if (managingSources && source.id != "local") {
                                    IconAction("删除片源${source.name}", Theme.IconPaths.Trash, { pendingDeleteId = source.id }, tint = Theme.Colors.Accent, enabled = importEnabled)
                                }
                            }
                        }
                    }
                }
                if (managingSources) {
                    AppText("点击右侧删除按钮移除片源；本地 / 直链入口保留。", style = Theme.Type.Caption, color = Theme.Colors.Muted)
                }
                if (availabilityRepository != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                        ActionButton("检测当前片源", {
                            tvBox.sources.firstOrNull { it.id == state.sourceId }?.let {
                                state.checkSources(availabilityRepository, listOf(it))
                            }
                        }, Modifier.weight(Theme.Size.Weight), primary = false, enabled = importEnabled)
                        ActionButton("检测全部片源", {
                            state.checkSources(availabilityRepository, tvBox.sources.toList())
                        }, Modifier.weight(Theme.Size.Weight), enabled = importEnabled)
                    }
                    if (state.sourceCheckRunning) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                            AppText("检测中 ${state.sourceCheckCompleted} / ${state.sourceCheckTotal}", style = Theme.Type.Caption,
                                color = Theme.Colors.Secondary, modifier = Modifier.weight(Theme.Size.Weight))
                            ActionButton("停止检测", state::cancelSourceChecks, primary = false)
                        }
                    } else if (state.sourceCheckTotal > 0) {
                        AppText("${if (state.sourceCheckStopped) "已停止" else "检测完成"} ${state.sourceCheckCompleted} / ${state.sourceCheckTotal} · 本次接口可用 ${state.sourceCheckAvailable} 个",
                            style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                    }
                    val selectedResult = state.sourceChecks[state.sourceId]
                    if (state.sourceId == "local") {
                        AppText("本地 / 直链无需检测站点接口，具体视频以实际播放为准。", style = Theme.Type.Caption, color = Theme.Colors.Muted)
                    } else if (state.pendingSourceChecks.containsKey(state.sourceId)) {
                        AppText(if (state.pendingSourceChecks[state.sourceId] == true) "正在检测当前片源…" else "当前片源等待检测…",
                            style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                    } else if (selectedResult != null) {
                        AppText("${availabilityLabel(selectedResult.status)} · ${selectedResult.latencyMillis} ms · ${selectedResult.detail}",
                            style = Theme.Type.Caption, color = if (selectedResult.status == SourceAvailability.AVAILABLE) Theme.Colors.Accent else Theme.Colors.Secondary)
                    }
                    AppText("检测接口与目录，不代表每部视频均可播放；导入新片源后可重新检测。", style = Theme.Type.Micro, color = Theme.Colors.Muted)
                }
                AppText("订阅链接 / 片源文本", style = Theme.Type.Label)
                ImportField(state.importText, { state.importText = it }, "粘贴订阅 URL、JSON、M3U 或源文本", enabled = importEnabled, multiline = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                    ActionButton(if (state.importBusy) "正在导入…" else "导入文本", {
                        val text = state.importText.trim()
                        state.importSources { withContext(Dispatchers.IO) { tvBox.importSources(text) } }
                    }, Modifier.weight(Theme.Size.Weight), enabled = importEnabled && state.importText.isNotBlank())
                    ActionButton("选择文件", { pickDocument.launch(arrayOf("*/*")) }, Modifier.weight(Theme.Size.Weight), primary = false, enabled = importEnabled)
                }
                AppText("支持 JSON · M3U · M3U8 · TXT · XML，文件最大 ${SourceImport.MAX_BYTES / (1024 * 1024)} MiB", style = Theme.Type.Caption, color = Theme.Colors.Muted)
                AppText("不兼容条目会跳过，并显示导入结果", style = Theme.Type.Caption, color = Theme.Colors.Muted)
            }
            if (state.importBusy) AppText("正在读取并解析，完成后片源列表会自动更新。", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
            state.importMessage?.let { AppText(it, style = Theme.Type.Caption, color = Theme.Colors.Secondary) }
            AppText("播放与流量", style = Theme.Type.Label)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                ActionButton("仅 Wi-Fi 预缓存", { appearance.setWifiOnlyPrecache(true) }, Modifier.weight(Theme.Size.Weight), primary = appearance.wifiOnlyPrecache)
                ActionButton("允许移动数据预缓存", { appearance.setWifiOnlyPrecache(false) }, Modifier.weight(Theme.Size.Weight), primary = !appearance.wifiOnlyPrecache)
            }
            AppText("默认仅 Wi-Fi 预缓存；移动数据下只按播放进度缓存当前集。", style = Theme.Type.Caption, color = Theme.Colors.Muted)
            AppText("下一集预取（默认关闭）", style = Theme.Type.Caption, color = Theme.Colors.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                ActionButton("开启", { appearance.setNextEpisodePrefetch(true) }, Modifier.weight(Theme.Size.Weight), primary = appearance.nextEpisodePrefetch)
                ActionButton("关闭", { appearance.setNextEpisodePrefetch(false) }, Modifier.weight(Theme.Size.Weight), primary = !appearance.nextEpisodePrefetch)
            }
            AppText("开启后仅在 Wi-Fi 且当前集已有足够余量时预取下一集。", style = Theme.Type.Caption, color = Theme.Colors.Muted)
            Box(Modifier.fillMaxWidth().height(Theme.Space.Hairline).background(Theme.Colors.Border))
            AppText("添加视频直链", style = Theme.Type.Label)
            ImportField(state.sourceTitle, { state.sourceTitle = it }, "片名", enabled = importEnabled)
            ImportField(state.sourceUrl, { state.sourceUrl = it }, "视频地址（HTTP(S) · MP4 / M3U8）", enabled = importEnabled)
            ActionButton("添加并播放", {
                val added = repository.addSource(state.sourceTitle, state.sourceUrl)
                if (added == null) state.importMessage = "请输入有效的 HTTP(S) 视频地址"
                else {
                    onDismiss()
                    state.sourceTitle = ""
                    state.sourceUrl = ""
                    onOpenMovie(added, repository.history.find { it.movieId == added.id })
                }
            }, Modifier.fillMaxWidth(), enabled = importEnabled && state.sourceUrl.isNotBlank())
        }
    tvBox?.sources?.firstOrNull { it.id == pendingDeleteId && it.id != "local" }?.let { source ->
        Dialog(onDismissRequest = { pendingDeleteId = null }) {
            // Dialog 与页面不在同一个布局根，不能采样页面的实时玻璃坐标。
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.Radius.Card)).background(Theme.Colors.Surface)
                .border(Theme.Space.Hairline, Theme.Colors.Border, RoundedCornerShape(Theme.Radius.Card))) {
                Column(Modifier.padding(Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Large)) {
                    AppText("删除片源？", style = Theme.Type.Section)
                    AppText("确定删除「${source.name}」？观看历史和已添加的直链会保留，需要时可重新导入。", style = Theme.Type.Body, color = Theme.Colors.Secondary)
                    if (tvBox.selectedSourceId == source.id) {
                        val fallback = tvBox.sources.first { it.id != source.id }
                        AppText("删除后将切换到「${fallback.name}」。", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                        ActionButton("取消", { pendingDeleteId = null }, Modifier.weight(Theme.Size.Weight), primary = false)
                        ActionButton("确认删除", {
                            if (state.deleteSource(tvBox, source.id)) pendingDeleteId = null
                        }, Modifier.weight(Theme.Size.Weight), enabled = importEnabled)
                    }
                }
            }
        }
    }
}

/** 输入：仓库确认的检测状态；输出：不会将接口响应误称为影片可播放的简短标签。 */
private fun availabilityLabel(status: SourceAvailability): String = when (status) {
    SourceAvailability.AVAILABLE -> "接口可用"
    SourceAvailability.EMPTY -> "无有效样本"
    SourceAvailability.UNSUPPORTED -> "不兼容"
    SourceAvailability.FAILED -> "检测失败"
    SourceAvailability.LOCAL -> "本地 · 无需联网"
}

@Composable
private fun ImportField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, multiline: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.XSmall)) {
        AppText(label, style = Theme.Type.Caption, color = Theme.Colors.Secondary)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Theme.Radius.Control)).background(Theme.Glass.FieldFill).padding(Theme.Space.Medium)) {
            BasicTextField(value, onChange, enabled = enabled, textStyle = Theme.Type.Body, cursorBrush = SolidColor(Theme.Colors.Accent), singleLine = !multiline, maxLines = if (multiline) Theme.Size.ImportTextLines else Theme.Size.SingleLine, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}

/** 有界读取，取消时在下一次读取前退出，不无限分配未知文件大小的内存。 */
private suspend fun readImportDocument(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
    }.orEmpty()
    val text = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= SourceImport.MAX_BYTES) { "文件过大，请选择不超过 ${SourceImport.MAX_BYTES / (1024 * 1024)} MiB 的片源文件" }
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name()).trimStart('\uFEFF')
    } ?: error("无法读取所选文件，请重新选择")
    require(text.isNotBlank()) { "${name.ifBlank { "所选文件" }}内容为空" }
    return text to uri.toString()
}
