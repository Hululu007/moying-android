package com.moying.app

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PlaybackToggleIconOffset = Theme.Size.PlayerIconInset - Theme.Size.PlayerRowShift
private const val IntroSettingsPanel = "片头设置"
private const val MinimumIntroEndMillis = 1_000L

/** 输入：影片、续播记录、数据/播放接口；输出：支持手势、预载、换源和选集的播放页。 */
@Composable
fun PlayerScreen(
    movie: Movie, initialHistory: WatchHistory?, repository: MovieRepository,
    controller: PlayerController, fullscreen: Boolean, onFullscreen: () -> Unit,
    playbackSession: PlaybackSession, playbackOwner: Any,
    onSwitchMovie: ((Movie, WatchHistory) -> Unit)? = null,
    bottomPadding: Dp = Theme.Space.None,
    active: Boolean = true,
    surfaceVisible: Boolean = true,
    routesOverride: List<PlaybackRoute>? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val routes = remember(movie.id, routesOverride) {
        routesOverride?.takeIf { it.isNotEmpty() } ?: repository.getPlaybackRoutes(movie.id).ifEmpty {
            listOf(PlaybackRoute("default", "默认线路", repository.getEpisodes(movie.id)))
        }
    }
    // 离线入口是用户对某一集的明确选择，不再沿用播放器里其它集的续播选择。
    val retained = remember(movie.id, routesOverride) {
        if (routesOverride != null) null else playbackSession.selection?.takeIf { it.movieId == movie.id }
    }
    var routeId by rememberSaveable(movie.id) { mutableStateOf(retained?.routeId?.takeIf { id -> routes.any { it.id == id } } ?: routes.first().id) }
    val route = routes.firstOrNull { it.id == routeId } ?: routes.first()
    val episodes = route.episodes
    var episode by rememberSaveable(movie.id) {
        mutableIntStateOf((retained?.episode ?: initialHistory?.episode ?: Playback.FirstEpisode).coerceIn(Playback.FirstEpisode, episodes.size.coerceAtLeast(Playback.FirstEpisode)))
    }
    var position by rememberSaveable(movie.id) { mutableLongStateOf(if (retained != null) controller.getPosition() else initialHistory?.positionMillis ?: Playback.Start) }
    var knownDuration by rememberSaveable(movie.id) {
        mutableLongStateOf((if (retained != null) controller.getDuration().takeIf { it > Playback.Start } else null)
            ?: initialHistory?.durationMillis?.takeIf { it > Playback.Start } ?: movie.durationMillis.coerceAtLeast(Playback.Start))
    }
    var loadedPlaybackKey by remember(movie.id) { mutableStateOf<String?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTouched by remember { mutableStateOf(false) }
    var gestureActive by remember { mutableStateOf(false) }
    var holdSpeedActive by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    val panelDismissInteractionSource = remember { MutableInteractionSource() }
    var findingSources by remember { mutableStateOf(false) }
    var searchedSources by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var alternatives by remember(movie.id) { mutableStateOf(emptyList<Movie>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val offlineCache = remember(context) { OfflineCacheManager.get(context.applicationContext) }
    val offlineState = offlineCache.state
    var cacheNotice by remember { mutableStateOf<String?>(null) }
    val currentIndex = episode - Playback.FirstEpisode
    val currentEpisode = episodes.getOrNull(currentIndex)
    val currentUrl = currentEpisode?.url.orEmpty()
    val introReference = episodes.getOrNull(currentIndex + 1)?.takeIf { it.url.isNotBlank() && it.url != currentUrl }
        ?: episodes.getOrNull(currentIndex - 1)?.takeIf { it.url.isNotBlank() && it.url != currentUrl }
    val playbackKey = ContentCacheKey.root(movie.id, routeId, episode)
    val needsResolution = currentEpisode?.resolver != null
    val canResolve = !needsResolution || playbackSession.isForeground(playbackOwner)
    var resolutionAttempt by remember(playbackKey, currentEpisode) { mutableIntStateOf(0) }
    var resolutionError by remember(playbackKey, currentEpisode, resolutionAttempt) { mutableStateOf<String?>(null) }
    val resolving = needsResolution && loadedPlaybackKey != playbackKey && resolutionError == null
    val introPreferences = remember(context, movie.id, route.id) {
        IntroSkipPreferences(context.applicationContext, movie.id, route.id)
    }
    var introRule by remember(introPreferences) { mutableStateOf(introPreferences.read()) }
    var introChecked by rememberSaveable(playbackKey) { mutableStateOf(false) }
    var detectedIntro by remember(introPreferences, currentEpisode) {
        mutableStateOf(currentEpisode?.let(introPreferences::readDetection))
    }
    var detectedIntroSkipped by rememberSaveable(playbackKey, currentUrl) { mutableStateOf(false) }
    var detectionRequest by remember(playbackKey, currentUrl) { mutableStateOf<Any?>(null) }
    val request = detectionRequest
    var detectionMessage by remember(playbackKey, currentUrl) { mutableStateOf<String?>(null) }
    val introPanelOpen = panel == IntroSettingsPanel
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentMediaReady = loadedPlaybackKey == playbackKey && playbackSession.selection?.key == playbackKey &&
        controller.errorMessage == null && !controller.isBuffering
    val canSkipIntro = if (introRule.endMillis > 0L) introRule.canSkip(position, knownDuration)
        else detectedIntro?.canSkip(position, knownDuration) == true
    val showIntroSkip = active && currentMediaReady && canSkipIntro
    val latestPlaybackKey by rememberUpdatedState(playbackKey)
    val playbackControlsVisible = active && (controlsVisible || panel != null)
    // 控制栏在相邻页也会先组合一次，回调必须读取最新的 active，不能依赖闭包里的首帧值。
    val activeNow = rememberUpdatedState(active)
    val videoAspect = controller.videoAspectRatio?.takeIf { playbackSession.selection?.movieId == movie.id } ?: Theme.Size.VideoAspect
    val nextEpisode = episodes.getOrNull(episode)
    val nextBufferLabel = when {
        nextEpisode == null -> null
        nextEpisode.resolver != null -> "下一集将在播放时解析"
        controller.nextBufferedDuration > Playback.Start -> "下一集已缓冲 ${formatTime(controller.nextBufferedDuration)}" +
            if (controller.nextPreloadDuration > Playback.Start) " / ${formatTime(controller.nextPreloadDuration)}" else ""
        controller.nextBufferedBytes > Playback.Start -> "下一集已缓存 ${formatCacheSize(controller.nextBufferedBytes)}"
        else -> controller.nextPreloadMessage ?: "下一集等待预缓冲"
    }
    val backdrop = rememberVideoBackdrop(surfaceVisible && (controlsVisible || panel != null || feedback != null || showSpeed))

    BackHandler(active && (panel != null || showSpeed)) {
        panel = null; showSpeed = false; controlsVisible = true
    }

    /** 输入：当前实例的采样；输出：缓冲/错误的临时 0 和未知时长不覆盖页面已确认进度。 */
    fun refreshProgress() {
        if (loadedPlaybackKey == null || loadedPlaybackKey != latestPlaybackKey || loadedPlaybackKey != playbackSession.selection?.key) return
        val sampled = controller.getPosition().coerceAtLeast(Playback.Start)
        if (sampled > Playback.Start || position == Playback.Start || (!controller.isBuffering && controller.errorMessage == null)) position = sampled
        controller.getDuration().takeIf { it > Playback.Start }?.let { knownDuration = it }
    }
    /** 输入：当前已加载集的确认进度；输出：保存历史，切集间隙不把旧集位置写到新集。 */
    fun saveProgress() {
        refreshProgress()
        loadedPlaybackKey?.let { playbackSession.saveProgress(repository, it) }
    }
    /** 输入：显式播放/重试操作；输出：接收 controller 的续播点，只有显式重播才允许归零。 */
    fun playOrRetry() {
        if (!activeNow.value || resolving) return
        if (resolutionError != null || (needsResolution && controller.errorMessage != null)) {
            resolutionAttempt++
        } else {
            controller.play()
            position = controller.getPosition()
        }
    }
    /** 输入：面板名；输出：在控制栏上方展开或收起面板，保留控制栏显示和操作。 */
    fun togglePanel(name: String) {
        if (!activeNow.value) return
        panel = if (panel == name) null else name
        showSpeed = false
        controlsVisible = true
    }
    fun seekTo(target: Long) {
        if (!activeNow.value || loadedPlaybackKey != latestPlaybackKey ||
            loadedPlaybackKey != playbackSession.selection?.key) return
        introChecked = true
        controller.seek(target)
        position = target
        saveProgress()
    }
    fun updateIntroRule(rule: IntroSkipRule) {
        introPreferences.write(rule)
        introRule = rule
    }
    fun skipIntro() {
        if (loadedPlaybackKey != latestPlaybackKey || loadedPlaybackKey != playbackSession.selection?.key ||
            controller.errorMessage != null || controller.isBuffering) return
        refreshProgress()
        val duration = controller.getDuration()
        if (introRule.endMillis > 0L) {
            if (introRule.canSkip(position, duration)) seekTo(introRule.endMillis)
        } else detectedIntro?.takeIf { it.canSkip(position, duration) }?.let {
            detectedIntroSkipped = true
            seekTo(it.endMillis)
        }
    }
    fun selectEpisode(number: Int) {
        if (!activeNow.value) return
        if (number != episode && number in Playback.FirstEpisode..episodes.size) {
            saveProgress()
            loadedPlaybackKey = null
            position = Playback.Start
            knownDuration = movie.durationMillis.coerceAtLeast(Playback.Start)
            episode = number
        }
        panel = null; controlsVisible = true
    }
    fun selectRoute(target: PlaybackRoute) {
        if (!activeNow.value) return
        if (target.id != routeId) {
            saveProgress(); position = controller.getPosition()
            loadedPlaybackKey = null
            episode = episode.coerceAtMost(target.episodes.size.coerceAtLeast(Playback.FirstEpisode))
            routeId = target.id
        }
        panel = null; controlsVisible = true
    }
    /** 输入：要缓存的集；输出：统一入队并给出明确提示，移动网络下不会静默失败。 */
    fun cacheEpisodes(tracks: List<Episode>) {
        val requests = tracks.filter { it.url.isNotBlank() }.map {
            OfflineRequest(movie, routeId, route.name, episodes.size, it, it.url)
        }
        cacheNotice = offlineCache.enqueue(requests)
    }
    fun recordFor(track: Episode): OfflineRecord? =
        offlineState.recordFor(ContentCacheKey.root(movie.id, routeId, track.number))

    // 输入：播放页激活状态；输出：每次进入播放页默认收起控制栏，离页时关闭浮层。
    LaunchedEffect(active) {
        controlsVisible = false
        if (!active) { panel = null; showSpeed = false }
    }
    // 输入：横竖屏切换；输出：进入横屏时先收起控制栏和浮层，转屏后不立即遮挡画面。
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            controlsVisible = false
            panel = null
            showSpeed = false
        }
    }
    // 输入：控制栏与触摸/面板状态；输出：横竖屏均在最后一次操作结束两秒后收起。
    LaunchedEffect(active, showSpeed, controlsTouched, controlsVisible, gestureActive, panel) {
        if (active && controlsVisible && !showSpeed && !controlsTouched && !gestureActive && panel == null) {
            delay(Theme.Motion.ControlsHideMillis); controlsVisible = false
        }
    }
    LaunchedEffect(feedback, gestureActive) {
        if (!gestureActive && feedback != null) { delay(Theme.Motion.GestureFeedbackMillis); feedback = null }
    }
    LaunchedEffect(cacheNotice) {
        if (cacheNotice != null) { delay(4_000L); cacheNotice = null }
    }
    LaunchedEffect(request, active, introPanelOpen, windowFocused, playbackKey, currentUrl, introReference) {
        if (request == null) return@LaunchedEffect
        try {
            val first = currentEpisode ?: return@LaunchedEffect
            val second = introReference ?: return@LaunchedEffect
            if (!active || !introPanelOpen || !windowFocused) return@LaunchedEffect
            val match = withContext(Dispatchers.IO) {
                val currentMedia = resolvePlaybackEpisode(first)
                val referenceMedia = resolvePlaybackEpisode(second)
                val scanContext = currentCoroutineContext()
                val budget = IntroAudioScanBudget { !scanContext.isActive }
                val extractor = IntroAudioExtractor(context.applicationContext)
                val current = IntroAudioFingerprint.Collector()
                extractor.scan(currentMedia.url, budget, currentMedia.headers, current::appendPcm16)
                val reference = IntroAudioFingerprint.Collector()
                extractor.scan(referenceMedia.url, budget, referenceMedia.headers, reference::appendPcm16)
                IntroAudioFingerprint.match(current.finish(), reference.finish(), budget::check)
            }
            currentCoroutineContext().ensureActive()
            if (detectionRequest !== request || panel != IntroSettingsPanel ||
                !activeNow.value || latestPlaybackKey != playbackKey) return@LaunchedEffect
            if (match == null) {
                detectionMessage = "前 3 分钟内没有找到可靠的重复片头"
            } else {
                val range = DetectedIntro(match.startMillis, match.endMillis)
                val referenceRange = DetectedIntro(match.referenceStartMillis, match.referenceEndMillis)
                introPreferences.saveDetections(first, range, second, referenceRange)
                detectedIntro = range
                detectedIntroSkipped = false
                detectionMessage = "已保存两集各自的片头位置"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlaybackResolutionException) {
            detectionMessage = error.message ?: "无法解析这条线路"
        } catch (error: IntroAudioScanException) {
            detectionMessage = error.message ?: "暂时无法识别此线路的音频"
        } catch (_: Exception) {
            detectionMessage = "暂时无法识别此线路的音频"
        } finally {
            if (detectionRequest === request) detectionRequest = null
        }
    }
    LaunchedEffect(playbackKey, currentEpisode, active, canResolve, resolutionAttempt) {
        if (!active || !canResolve || resolutionError != null) return@LaunchedEffect
        loadedPlaybackKey = null
        val requested = RetainedPlayback(movie.id, routeId, episode, currentUrl,
            currentEpisode?.headers.orEmpty(), originalEpisode = currentEpisode)
        val resolved = if (currentEpisode != null && needsResolution) {
            val current = playbackSession.selection
            if (current?.key == playbackKey && current.originalEpisode == currentEpisode &&
                current.url.isNotBlank() && controller.errorMessage == null) {
                current
            } else {
                playbackSession.ensurePlayback(requested.copy(url = ""), movie.durationMillis, position)
                try {
                    val media = resolvePlaybackEpisode(currentEpisode)
                    requested.copy(url = media.url, headers = media.headers)
                } catch (error: PlaybackResolutionException) {
                    resolutionError = error.message ?: "无法解析这条线路"
                    return@LaunchedEffect
                }
            }
        } else requested
        playbackSession.ensurePlayback(resolved, movie.durationMillis, position)
        if (resolved.url.isBlank()) return@LaunchedEffect
        loadedPlaybackKey = playbackKey
        val next = nextEpisode?.takeIf { it.resolver == null }
        var lastSave = Playback.Start
        while (true) {
            refreshProgress()
            if (controller.isPlaying && !controller.isBuffering &&
                loadedPlaybackKey == latestPlaybackKey && loadedPlaybackKey == playbackSession.selection?.key) {
                if (!introChecked) {
                    introChecked = true
                    if (introRule.endMillis > 0L && introRule.automatic) skipIntro()
                }
                if (introRule.endMillis == 0L && introRule.automatic && !detectedIntroSkipped &&
                    detectedIntro?.canSkip(position, knownDuration) == true) {
                    skipIntro()
                }
            }
            // 控制器结合当前集余量、倍速与缓冲状态决定是否启动/暂停下一集 native 预准备。
            playbackSession.preload(RetainedPlayback(movie.id, routeId, episode + Playback.FirstEpisode, next?.url.orEmpty(), next?.headers.orEmpty()))
            if (System.currentTimeMillis() - lastSave >= Theme.Motion.HistorySaveMillis) {
                saveProgress(); lastSave = System.currentTimeMillis()
            }
            delay(Theme.Motion.PlayerPollMillis)
        }
    }
    DisposableEffect(movie.id, playbackOwner, active) {
        if (active) playbackSession.setVisible(playbackOwner, movie.id, true)
        onDispose {
            if (active) {
                saveProgress()
                playbackSession.setVisible(playbackOwner, movie.id, false)
            }
        }
    }

    // 输入：系统状态栏高度和全屏状态；输出：竖屏用同款玻璃填满状态栏一条，视频紧接其下沿，横屏仍铺满屏幕。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val topInset = maxOf(statusBarTop, cutoutTop)
    // 输入：窗口尺寸类与横竖比；输出：横屏大窗口并排，手机竖屏和平板竖屏保持原竖排结构。
    val windowLayout = LocalWindowLayout.current
    val splitLayout = !fullscreen && windowLayout.isLargeScreen && windowLayout.isLandscape
    val widePlayer = windowLayout.isWide
    Column(Modifier.fillMaxSize().background(if (fullscreen) Theme.Colors.Black else Theme.Colors.Transparent)
        .pointerInput(active) {
        if (active) try {
            awaitPointerEventScope {
                while (true) controlsTouched = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
            }
        } finally { controlsTouched = false }
    }) {
        if (!fullscreen) {
            // 竖屏顶部状态栏和下方满宽控制栏共用轻薄玻璃。
            AmbientGlassSurface(
                Modifier.fillMaxWidth().height(topInset),
                shape = RectangleShape,
                style = CatalogGlassStyle.OSHIN_CONTROL,
            ) { }
        }
        // 视频舞台：竖排、并排和全屏共用同一套手势与浮层，只有外层容器尺寸不同。
        val videoStage: @Composable (Modifier) -> Unit = { stageModifier ->
        BoxWithConstraints(stageModifier.background(Theme.Colors.Black)) {
            CompositionLocalProvider(LocalVideoBackdrop provides backdrop) {
                // 输入：窗口是否达到宽屏；输出：宽屏面板加宽并多容纳一列剧集。
                val panelWidth = if (widePlayer) Theme.Layout.PlayerPanelWidthWide else Theme.Size.PlayerPanelWidth
                val panelMaxHeight = if (widePlayer) Theme.Layout.PlayerPanelMaxHeightWide else Theme.Size.PlayerPanelMaxHeight
                val panelColumns = if (widePlayer) Theme.Layout.PlayerEpisodeColumnsWide else Theme.Size.PlayerEpisodeColumns
            val videoWidth = minOf(maxWidth, maxHeight * videoAspect)
            if (surfaceVisible && (active || playbackSession.selection?.movieId == movie.id)) {
                VideoSurface(controller, Modifier.size(videoWidth, videoWidth / videoAspect).align(Alignment.Center), backdrop)
            } else if (surfaceVisible) {
                AssetImage(movie.poster, movie.title, Modifier.matchParentSize())
            }
            if (active) PlayerGestures(controller,
                onTap = { if (panel == null) { controlsVisible = !controlsVisible; if (!controlsVisible) showSpeed = false } },
                onSeek = ::seekTo,
                onActive = { gestureActive = it },
                onFeedback = { feedback = it },
                onHoldSpeedChanged = { holdSpeedActive = it },
                modifier = Modifier.matchParentSize(),
                onDoubleTap = {
                    if (controller.playbackRequested) { controller.pause(); saveProgress() } else playOrRetry()
                    controlsVisible = true
                    feedback = null
                },
            )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                val error = resolutionError ?: controller.errorMessage.takeUnless { resolving }
                val message = when {
                    resolving -> "正在解析播放地址…"
                    error != null -> error
                    currentUrl.isBlank() -> "暂无可播放地址"
                    controller.isBuffering -> "正在缓冲…"
                    else -> null
                }
                if (message != null) AppText(message, style = Theme.Type.Caption, modifier = Modifier.background(Theme.Colors.Overlay).padding(Theme.Space.Medium))
                if (active && error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                        ActionButton("重试", ::playOrRetry)
                        ActionButton("换源", { togglePanel("换源") }, primary = false)
                    }
                }
            }
            if (fullscreen && playbackControlsVisible) {
                val landscape = windowLayout.isLandscape
                val titleInsets = if (landscape) WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                    else WindowInsets.displayCutout
                val titleStart = if (landscape) fullscreenBackStartPadding(cutoutTop) else Theme.Space.XSmall
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    PlayerStatusBar(Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                        .height(Theme.Size.PlayerStatusHeight).padding(horizontal = Theme.Space.Page))
                    Row(
                        Modifier.windowInsetsPadding(titleInsets)
                            .padding(start = titleStart)
                            .fillMaxWidth(Theme.Size.PlayerTitleFraction)
                            .height(Theme.Size.PlayerStatusHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction("退出全屏", Theme.IconPaths.Back, onFullscreen,
                            iconOffsetX = if (landscape) PlaybackToggleIconOffset else Theme.Space.None)
                        AppText("${movie.title} · ${episodes.getOrNull(episode - Playback.FirstEpisode)?.name.orEmpty()}", style = Theme.Type.Label, maxLines = Playback.FirstEpisode, modifier = Modifier.weight(Theme.Size.Weight))
                    }
                }
            }
            if (fullscreen && playbackControlsVisible) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Theme.Space.Small)) {
                    PlaybackControls(
                        fullscreen = true,
                        playbackKey = playbackKey,
                        position = position,
                        knownDuration = knownDuration,
                        bufferedPosition = controller.bufferedPosition,
                        playing = controller.playbackRequested,
                        canNext = episode < episodes.size,
                        speed = controller.speed,
                        onSeek = ::seekTo,
                        onPlayPause = { if (controller.playbackRequested) { controller.pause(); saveProgress() } else playOrRetry() },
                        onNext = { selectEpisode(episode + Playback.FirstEpisode) },
                        onSpeed = { panel = null; controlsVisible = true; showSpeed = !showSpeed },
                        onPanel = { togglePanel(it) },
                        onToggleFullscreen = onFullscreen,
                    )
                }
            }
            if (active && panel == null && !showSpeed && feedback == null &&
                (showIntroSkip || (fullscreen && playbackControlsVisible))) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(
                        end = Theme.Space.Small,
                        bottom = if (fullscreen && playbackControlsVisible)
                            Theme.Size.PlayerControlsHeight + Theme.Space.Large else Theme.Space.Small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                ) {
                    if (showIntroSkip) IntroSkipButton(onClick = ::skipIntro)
                    if (fullscreen && playbackControlsVisible) GlassSurface(
                        shape = RoundedCornerShape(Theme.Radius.Pill),
                        tint = Theme.Colors.PlayerControls,
                        style = CatalogGlassStyle.OSHIN_CONTROL,
                    ) { PlayerChip(IntroSettingsPanel, { togglePanel(IntroSettingsPanel) }) }
                }
            }
            if (fullscreen && playbackControlsVisible && panel == null && !showSpeed && nextBufferLabel != null) {
                AppText(nextBufferLabel, style = Theme.Type.Micro, color = Theme.Colors.Text,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = Theme.Space.Page, bottom = Theme.Size.PlayerControlsHeight + Theme.Space.Large)
                        .clip(RoundedCornerShape(Theme.Radius.Control)).background(Theme.Colors.Overlay)
                        .padding(horizontal = Theme.Space.Small, vertical = Theme.Space.Tiny))
            }
            if (playbackControlsVisible && showSpeed) {
                GlassSurface(Modifier.align(Alignment.BottomEnd).padding(end = Theme.Space.Small,
                    bottom = if (fullscreen) Theme.Size.PlayerControlsHeight + Theme.Space.Large else Theme.Space.Small),
                    tint = Theme.Colors.PlayerControls, style = CatalogGlassStyle.OSHIN_CONTROL) {
                    Row(Modifier.height(Theme.Size.PlayerControlsRow).padding(horizontal = Theme.Space.Small), horizontalArrangement = Arrangement.spacedBy(Theme.Space.XSmall)) {
                        Playback.Speeds.forEach { speed -> PlayerChip("${speed}×", { controller.setSpeed(speed); showSpeed = false }, controller.speed == speed) }
                    }
                }
            }
            feedback?.takeIf { active }?.let { text ->
                // 三倍速反馈靠近视频正上方，并避开横屏时间栏；其他手势仍保持居中提示。
                val feedbackPlacement = if (holdSpeedActive) Modifier
                    .padding(top = if (fullscreen) cutoutTop + Theme.Size.PlayerStatusHeight + Theme.Space.Small else Theme.Space.Small)
                    else Modifier.padding(Theme.Space.Page)
                GlassSurface(Modifier.align(if (holdSpeedActive) Alignment.TopCenter else Alignment.Center).then(feedbackPlacement),
                    tint = Theme.Colors.PlayerControls, lightResponse = false, style = CatalogGlassStyle.OSHIN_CONTROL) {
                    AppText(text, style = Theme.Type.Label, modifier = Modifier.padding(if (holdSpeedActive) Theme.Space.Small else Theme.Space.Large))
                }
            }
            if (active && panel != null) {
                // 面板和外侧关闭区都避开完整控制栏；横屏控制栏仍浮在视频上，竖屏已移到视频下方。
                val controlsClearance = if (fullscreen)
                    Theme.Size.PlayerControlsHeight + Theme.Space.Small + Theme.Space.Small + Theme.Space.Small
                else Theme.Space.Small
                val topClearance = if (fullscreen) cutoutTop + Theme.Size.PlayerStatusHeight + Theme.Space.Small
                    else Theme.Size.Touch + Theme.Space.Small
                val panelHeight = minOf(panelMaxHeight, (maxHeight - controlsClearance - topClearance).coerceAtLeast(Theme.Space.None))
                val compactPanel = panelHeight < Theme.Size.PlayerPanelHeaderHeight + Theme.Size.Touch + Theme.Space.Small + Theme.Space.Small
                // 只拦截关闭点击；默认 indication 会在整个透明命中区绘制按压波纹，使视频看起来闪暗。
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height((maxHeight - controlsClearance).coerceAtLeast(Theme.Space.None)).clickable(
                    interactionSource = panelDismissInteractionSource,
                    indication = null,
                ) { panel = null; controlsVisible = true })
                GlassSurface(Modifier.align(Alignment.BottomEnd).padding(end = Theme.Space.Small, bottom = controlsClearance)
                    .width(minOf(maxWidth * Theme.Size.PlayerPanelFraction, panelWidth)).height(panelHeight),
                    tint = Theme.Colors.PlayerControls, style = CatalogGlassStyle.OSHIN_PANEL) {
                    Column(Modifier.fillMaxSize().padding(if (compactPanel) Theme.Space.Tiny else Theme.Space.Small)) {
                        if (!compactPanel) Row(Modifier.fillMaxWidth().height(Theme.Size.PlayerPanelHeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                            AppText(panel.orEmpty(), style = Theme.Type.Section, modifier = Modifier.weight(Theme.Size.Weight))
                            IconAction("关闭面板", Theme.IconPaths.Close, { panel = null; controlsVisible = true })
                        }
                        if (panel == IntroSettingsPanel) {
                            IntroSkipSettings(
                                manualEndMillis = introRule.endMillis.takeIf { it > 0L },
                                detected = detectedIntro,
                                autoSkip = introRule.automatic,
                                currentMillis = position,
                                canSetEnd = currentMediaReady && position in MinimumIntroEndMillis until knownDuration,
                                canDetect = currentUrl.isNotBlank() && introReference != null,
                                detecting = request != null,
                                playing = controller.isPlaying,
                                detectionMessage = detectionMessage,
                                onDetect = {
                                    if (detectionRequest != null) {
                                        detectionRequest = null
                                        detectionMessage = "已停止识别"
                                    } else if (currentUrl.isNotBlank() && introReference != null) {
                                        if (controller.isPlaying || controller.isBuffering) controller.pause()
                                        detectionMessage = null
                                        detectionRequest = Any()
                                    }
                                },
                                onSetEnd = {
                                    refreshProgress()
                                    if (loadedPlaybackKey == latestPlaybackKey &&
                                        loadedPlaybackKey == playbackSession.selection?.key &&
                                        !controller.isBuffering && controller.errorMessage == null &&
                                        position in MinimumIntroEndMillis until controller.getDuration()) {
                                        updateIntroRule(introRule.copy(endMillis = position))
                                    }
                                },
                                onAutoSkipChange = { updateIntroRule(introRule.copy(automatic = it)) },
                                onClear = {
                                    if (introRule.endMillis > 0L) {
                                        updateIntroRule(IntroSkipRule(automatic = detectedIntro != null && introRule.automatic))
                                    } else {
                                        currentEpisode?.let(introPreferences::clearDetection)
                                        detectedIntro = null
                                        detectionMessage = null
                                    }
                                },
                            )
                        } else if (panel == "选集") {
                            val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentIndex)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(panelColumns),
                                modifier = Modifier.weight(Theme.Size.Weight),
                                state = gridState,
                                horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                                verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                            ) {
                                items(episodes, key = { it.number }) { track ->
                                    EpisodeCacheTile(
                                        label = track.name,
                                        selected = track.number == episode,
                                        record = recordFor(track),
                                        onPlay = { selectEpisode(track.number) },
                                        onAction = {
                                            when (recordFor(track)?.status) {
                                                null -> cacheEpisodes(listOf(track))
                                                OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING ->
                                                    offlineCache.pause(ContentCacheKey.root(movie.id, routeId, track.number))
                                                OfflineStatus.PAUSED, OfflineStatus.FAILED ->
                                                    offlineCache.resume(ContentCacheKey.root(movie.id, routeId, track.number))
                                                OfflineStatus.COMPLETED -> Unit
                                            }
                                        },
                                        modifier = if (compactPanel) Modifier.height(Theme.Size.PlayerPanelHeaderHeight)
                                        else Modifier.heightIn(min = Theme.Size.Touch),
                                    )
                                }
                            }
                        } else {
                            val routeIndex = routes.indexOf(route)
                            val listState = rememberLazyListState(
                                initialFirstVisibleItemIndex = if (routeIndex > 0) routeIndex + 1 else 0,
                            )
                            LazyColumn(
                                Modifier.weight(Theme.Size.Weight),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(Theme.Space.Small),
                            ) {
                                item { AppText("播放线路", style = Theme.Type.Caption, color = Theme.Colors.Secondary) }
                                items(routes, key = { it.id }) { item -> PlayerChip(item.name, { selectRoute(item) }, item.id == route.id) }
                                if (onSwitchMovie != null) {
                                    item {
                                        ActionButton(if (findingSources) "正在查找…" else "查找同片其他源", {
                                            if (!findingSources) {
                                                findingSources = true; sourceError = null
                                                scope.launch {
                                                    try { alternatives = withContext(Dispatchers.IO) { repository.findAlternatives(movie.id) }; searchedSources = true }
                                                    catch (cancelled: CancellationException) { throw cancelled }
                                                    catch (error: Exception) { sourceError = "查找失败，请稍后重试" }
                                                    finally { findingSources = false }
                                                }
                                            }
                                        }, primary = false)
                                    }
                                    items(alternatives, key = { it.id }) { target ->
                                        PlayerChip(target.sourceCredit.substringAfter("片源：").substringBefore('·').ifBlank { target.title }, {
                                            saveProgress()
                                            onSwitchMovie(target, WatchHistory(target.id, episode.coerceAtMost(target.episodeCount.coerceAtLeast(Playback.FirstEpisode)), position, knownDuration, System.currentTimeMillis()))
                                        })
                                    }
                                    item {
                                        val message = sourceError ?: if (searchedSources && alternatives.isEmpty() && !findingSources) "暂未找到同名可播放片源" else null
                                        message?.let { AppText(it, style = Theme.Type.Micro, color = Theme.Colors.Secondary) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }
        val controlsBar: @Composable () -> Unit = {
            // 竖屏控制栏常驻在视频下方，紧贴视频下沿。
            AmbientGlassSurface(
                Modifier.fillMaxWidth(),
                shape = RectangleShape,
                style = CatalogGlassStyle.OSHIN_CONTROL,
            ) {
                PlaybackControls(
                    fullscreen = false,
                    playbackKey = playbackKey,
                    position = position,
                    knownDuration = knownDuration,
                    bufferedPosition = controller.bufferedPosition,
                    playing = controller.playbackRequested,
                    canNext = episode < episodes.size,
                    speed = controller.speed,
                    onSeek = ::seekTo,
                    onPlayPause = { if (controller.playbackRequested) { controller.pause(); saveProgress() } else playOrRetry() },
                    onNext = { selectEpisode(episode + Playback.FirstEpisode) },
                    onSpeed = { controlsVisible = true; showSpeed = !showSpeed },
                    onPanel = { togglePanel(it) },
                    onToggleFullscreen = onFullscreen,
                        onIntroSettings = { togglePanel(IntroSettingsPanel) },
                )
            }
        }
        val details: @Composable (Modifier) -> Unit = { detailsModifier ->
            BoxWithConstraints(detailsModifier) {
                // 输入：详情区实际宽度；输出：宽屏多排剧集，手机竖屏仍是原来的 5 列。
                val episodeColumns = (maxWidth.value / Theme.Layout.EpisodeChipWidth.value).toInt()
                    .coerceIn(Theme.Size.EpisodeColumns, Theme.Layout.EpisodeColumnsMax)
                val sideInset = if (splitLayout) Theme.Space.Medium else Theme.Space.Page
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = sideInset, top = Theme.Space.Page, end = sideInset,
                    bottom = if (splitLayout) Theme.Space.Page else bottomPadding + Theme.Space.Page), verticalArrangement = Arrangement.spacedBy(Theme.Space.Page)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AppText(movie.title, style = Theme.Type.PageTitle, modifier = Modifier.weight(Theme.Size.Weight))
                            if (onToggleFavorite != null) FavoriteButton(isFavorite, onToggleFavorite)
                            PlayerChip("换源", { togglePanel("换源") })
                        }
                        AppText("${movie.year} / ${movie.region} / ${movie.genres}", style = Theme.Type.Caption, color = Theme.Colors.Secondary)
                        AppText(listOfNotNull(route.name, nextBufferLabel).joinToString(" · "), style = Theme.Type.Micro, color = Theme.Colors.Muted, modifier = Modifier.padding(vertical = Theme.Space.Small))
                        AppText(movie.synopsis, style = Theme.Type.Body, color = Theme.Colors.Secondary)
                    }
                    item {
                        val cacheable = episodes.filter { it.url.isNotBlank() }
                        val allCached = cacheable.isNotEmpty() && cacheable.all { recordFor(it)?.status == OfflineStatus.COMPLETED }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                            SectionTitle("选集", "${episodes.size} 集", Modifier.weight(Theme.Size.Weight))
                            PlayerChip(if (allCached) "已缓存" else "整剧缓存", { if (!allCached) cacheEpisodes(cacheable) }, allCached)
                        }
                        cacheNotice?.let { AppText(it, style = Theme.Type.Micro, color = Theme.Colors.Accent, modifier = Modifier.padding(top = Theme.Space.XSmall)) }
                    }
                    items(episodes.chunked(episodeColumns)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.Small)) {
                            row.forEach { track ->
                                EpisodeCacheTile(
                                    label = track.name,
                                    selected = track.number == episode,
                                    record = recordFor(track),
                                    onPlay = { selectEpisode(track.number) },
                                    onAction = {
                                        when (recordFor(track)?.status) {
                                            null -> cacheEpisodes(listOf(track))
                                            OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING ->
                                                offlineCache.pause(ContentCacheKey.root(movie.id, routeId, track.number))
                                            OfflineStatus.PAUSED, OfflineStatus.FAILED ->
                                                offlineCache.resume(ContentCacheKey.root(movie.id, routeId, track.number))
                                            OfflineStatus.COMPLETED -> Unit
                                        }
                                    },
                                    modifier = Modifier.weight(Theme.Size.Weight).heightIn(min = Theme.Size.Touch),
                                )
                            }
                            repeat(episodeColumns - row.size) { Spacer(Modifier.weight(Theme.Size.Weight)) }
                        }
                    }
                    if (movie.sourceCredit.isNotBlank()) item { AppText(movie.sourceCredit, style = Theme.Type.Caption, color = Theme.Colors.Muted) }
                }
            }
        }
        if (splitLayout) {
            // 平板/大窗口横屏：视频与控制栏留在左侧，详情与选集在右侧独立滚动，互不遮挡。
            Row(Modifier.fillMaxWidth().weight(Theme.Size.Weight).padding(bottom = bottomPadding)) {
                BoxWithConstraints(Modifier.weight(Theme.Layout.PlayerSplitMainWeight).fillMaxHeight()) {
                    val stageAspect = videoAspect.coerceAtLeast(Theme.Size.MinPortraitVideoAspect)
                    val stageHeight = (maxHeight - Theme.Size.PlayerControlsHeight - Theme.Space.Small).coerceAtLeast(Theme.Space.None)
                    val stageWidth = minOf(maxWidth, stageHeight * stageAspect)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        videoStage(Modifier.width(stageWidth).aspectRatio(stageAspect))
                        controlsBar()
                    }
                }
                details(Modifier.weight(Theme.Layout.PlayerSplitSideWeight).fillMaxHeight())
            }
        } else {
            videoStage(if (fullscreen) Modifier.fillMaxWidth().weight(Theme.Size.FullscreenWeight)
                else Modifier.fillMaxWidth().aspectRatio(videoAspect.coerceAtLeast(Theme.Size.MinPortraitVideoAspect)))
            if (!fullscreen) controlsBar()
            if (!fullscreen) details(Modifier.fillMaxWidth().weight(Theme.Size.Weight))
        }
    }
}

/** 输入：真实缓存字节数；输出：时长未知时使用的可读大小，不把字节百分比伪装成秒。 */
private fun formatCacheSize(bytes: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))

@Composable
private fun fullscreenBackStartPadding(top: Dp): Dp {
    val controlInset = Theme.Space.Small + Theme.Space.Medium
    val direction = LocalLayoutDirection.current
    val safeStart = WindowInsets.displayCutout.asPaddingValues().calculateStartPadding(direction)
    if (safeStart <= controlInset || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return maxOf(controlInset, safeStart)
    }
    val cutout = LocalView.current.rootWindowInsets?.displayCutout ?: return safeStart
    val left = direction == LayoutDirection.Ltr
    val sideCutout = if (left) cutout.boundingRectLeft else cutout.boundingRectRight
    return with(LocalDensity.current) {
        // A centered landscape cutout doesn't overlap the top row of controls.
        if (!sideCutout.isEmpty && sideCutout.bottom > top.roundToPx() &&
            sideCutout.top < (top + Theme.Size.Touch).roundToPx()) {
            safeStart
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val waterfall = cutout.waterfallInsets
            maxOf(controlInset, (if (left) waterfall.left else waterfall.right).toDp())
        } else controlInset
    }
}

/** 输入：播放状态与回调；输出：横竖屏共用的进度条与控制行，竖屏时贴在视频下方。 */
@Composable
private fun PlaybackControls(
    fullscreen: Boolean,
    playbackKey: String,
    position: Long,
    knownDuration: Long,
    bufferedPosition: Long,
    playing: Boolean,
    canNext: Boolean,
    speed: Float,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onPanel: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onIntroSettings: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Theme.Space.Medium, vertical = Theme.Space.Tiny)) {
        key(playbackKey) {
            SeekBar(position, knownDuration, onSeek, modifier = Modifier.height(Theme.Size.PlayerSeekHeight),
                bufferedPosition = bufferedPosition, trackOffset = Theme.Size.PlayerSeekLowerOffset)
        }
        Row(Modifier.fillMaxWidth().height(Theme.Size.PlayerControlsRow), verticalAlignment = Alignment.CenterVertically) {
            IconAction(if (playing) "暂停播放" else "开始播放", if (playing) Theme.IconPaths.Pause else Theme.IconPaths.Play, onPlayPause,
                iconOffsetX = PlaybackToggleIconOffset)
            // 输入：下一集是否存在；输出：紧邻播放键的三角加双竖线图标，末集禁用。
            IconAction("下一集", Theme.IconPaths.Next, onNext, enabled = canNext,
                iconOffsetX = -Theme.Size.PlayerIconInset - Theme.Size.PlayerRowShift)
            AppText("${formatTime(position)} / ${formatTime(knownDuration)}", style = Theme.Type.Caption, color = Theme.Colors.Secondary, modifier = Modifier.weight(Theme.Size.Weight))
            PlayerChip("${speed}×", onSpeed)
            onIntroSettings?.let { PlayerChip("片头", it) }
            if (fullscreen) {
                PlayerChip("选集", { onPanel("选集") })
                PlayerChip("换源", { onPanel("换源") })
            }
            IconAction(if (fullscreen) "退出全屏" else "全屏播放", Theme.IconPaths.Fullscreen, onToggleFullscreen)
        }
    }
}

/** 输入：文本、点击回调和选中状态；输出：共用播放器触摸按钮。 */
@Composable
private fun PlayerChip(text: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = Theme.Size.Touch).clip(RoundedCornerShape(Theme.Radius.Control)).background(if (selected) Theme.Colors.AccentSurface else Theme.Colors.Transparent).hapticClickable(onClick = onClick).padding(horizontal = Theme.Space.Small), contentAlignment = Alignment.Center) {
        AppText(text, style = Theme.Type.Caption, color = if (selected) Theme.Colors.Accent else Theme.Colors.Secondary, maxLines = Playback.FirstEpisode)
    }
}

/** 输入：选集格与主动缓存记录；输出：上排选集名称 + 状态按钮，下排一行缓存状态，点击主体仍只负责播放。 */
@Composable
private fun EpisodeCacheTile(
    label: String,
    selected: Boolean,
    record: OfflineRecord?,
    onPlay: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when (record?.status) {
        null -> "未缓存"
        OfflineStatus.QUEUED -> "排队中"
        OfflineStatus.WAITING -> "等 Wi-Fi"
        OfflineStatus.DOWNLOADING -> record.downloadPercent?.let { "$it%" } ?: "下载中"
        OfflineStatus.PAUSED -> "已暂停"
        OfflineStatus.COMPLETED -> "已缓存"
        OfflineStatus.FAILED -> "失败"
    }
    val statusColor = when {
        record?.status == OfflineStatus.COMPLETED || record?.status == OfflineStatus.DOWNLOADING -> Theme.Colors.Accent
        record?.status == OfflineStatus.FAILED -> Theme.Colors.BatteryLow
        else -> Theme.Colors.Muted
    }
    val icon = when (record?.status) {
        null, OfflineStatus.FAILED -> Theme.IconPaths.Download
        OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING -> Theme.IconPaths.Pause
        OfflineStatus.PAUSED -> Theme.IconPaths.Play
        OfflineStatus.COMPLETED -> Theme.IconPaths.Check
    }
    val iconTint = when {
        record?.status == OfflineStatus.COMPLETED -> Theme.Colors.Accent
        record?.status == OfflineStatus.FAILED -> Theme.Colors.BatteryLow
        record == null -> if (selected) Theme.Colors.Accent else Theme.Colors.Secondary
        else -> Theme.Colors.Secondary
    }
    Column(
        modifier
            .clip(RoundedCornerShape(Theme.Radius.Control))
            .background(if (selected) Theme.Colors.AccentSurface else Theme.Colors.Transparent)
            .hapticClickable(onClick = onPlay)
            .padding(horizontal = Theme.Space.XSmall, vertical = Theme.Space.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText(label, style = Theme.Type.Caption, color = if (selected) Theme.Colors.Accent else Theme.Colors.Secondary,
            maxLines = Playback.FirstEpisode, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText(statusText, style = Theme.Type.Micro, color = statusColor, maxLines = Playback.FirstEpisode,
                modifier = Modifier.weight(Theme.Size.Weight))
            if (record?.status == OfflineStatus.COMPLETED) {
                AppIcon(icon, Modifier.size(Theme.Size.SmallIcon), tint = iconTint)
            } else {
                Box(Modifier.size(Theme.Size.Icon).hapticClickable(onClick = onAction), contentAlignment = Alignment.Center) {
                    AppIcon(icon, Modifier.size(Theme.Size.SmallIcon), tint = iconTint, filled = icon == Theme.IconPaths.Play)
                }
            }
        }
    }
}
