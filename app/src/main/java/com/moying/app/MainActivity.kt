package com.moying.app

import android.content.res.Configuration
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val playbackSession by lazy { PlaybackSession.get(applicationContext) }
    private val controller: PlayerController get() = playbackSession.controller
    private val playbackOwner = Any()
    private val repository by lazy { TvBoxMovieRepository(applicationContext) }
    private var hostStarted = false
    private var playbackKeepsScreenOn = false
    private var windowBackgroundColor: Int? = null

    /** 输入：与当前界面一致的窗口底色；输出：避免重复设置同一张背景。 */
    private fun updateWindowBackground(backgroundColor: Int) {
        if (windowBackgroundColor != backgroundColor) {
            window.setBackgroundDrawable(ColorDrawable(backgroundColor))
            windowBackgroundColor = backgroundColor
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()), navigationBarStyle = SystemBarStyle.dark(Theme.Colors.Surface.toArgb()))
        setContent { MoyingApp() }
    }

    override fun onStart() {
        super.onStart()
        hostStarted = true
        playbackSession.setForeground(playbackOwner, true)
        updateScreenAwake()
    }
    override fun onPause() {
        // 输入：锁屏/离开前仍有效的媒体位置；输出：立即落历史，不等待下一次定时采样。
        playbackSession.saveProgress(repository)
        super.onPause()
    }
    override fun onStop() {
        hostStarted = false
        updateScreenAwake()
        playbackSession.saveProgress(repository)
        playbackSession.setForeground(playbackOwner, false)
        super.onStop()
    }
    override fun onDestroy() {
        hostStarted = false
        updateScreenAwake()
        playbackSession.detachHost(playbackOwner)
        super.onDestroy()
    }

    /** 输入：可见播放页的播放/缓冲状态；输出：仅当前前台窗口持续保屏，离页或后台即清理。 */
    private fun updateScreenAwake() {
        if (hostStarted && playbackKeepsScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PosterLoader.trimMemory(level)
        playbackSession.trimMemory(level)
    }
    override fun onLowMemory() {
        super.onLowMemory()
        PosterLoader.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        playbackSession.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** 输入：仓库与控制器成员；输出：一个简单页面状态，不建立导航图或 DI 容器。 */
    @Composable
    private fun MoyingApp() {
        val configuration = LocalConfiguration.current
        val windowLayout = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
            WindowLayout(configuration.screenWidthDp, configuration.screenHeightDp)
        }
        val appearance = rememberAppAppearance(applicationContext)
        // 主动缓存队列在首页入口和播放页选集区共用；首次创建时恢复上次进程的队列状态。
        val offlineCache = remember { OfflineCacheManager.get(applicationContext) }
        val systemDark = isSystemInDarkTheme()
        val dark = when (appearance.themeMode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> systemDark
        }
        val stylePack = appearance.stylePack
        val timeTheme = stylePack == AppStylePack.TIME
        val themeMinute = if (timeTheme) rememberLocalThemeMinute(this) else 0
        SideEffect { Theme.applyAppearance(dark, stylePack, themeMinute) }
        val panels = rememberCapsulePanelState()
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        var appearanceMessage by remember { mutableStateOf<String?>(null) }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val previous = appearance.customImageUri
                appearance.setCustomImage(uri.toString())
                appearanceMessage = null
                if (previous.isNotBlank() && previous != uri.toString()) runCatching {
                    contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        val pager = rememberPagerState(pageCount = { Theme.PagerMotion.PageCount })
        val pageNavigation = rememberScreenPagerState(pager)
        val screen = Theme.PagerMotion.PageNames[pageNavigation.committedPage]
        val playerActive = pageNavigation.activePage == Theme.PagerMotion.PlayerPage
        val playerSurfaceVisible = kotlin.math.abs(pageNavigation.position.value - Theme.PagerMotion.PlayerPage) < .5f
        val firstMovie = repository.fallbackMovie
        var movieId by rememberSaveable { mutableStateOf(playbackSession.selection?.movieId ?: repository.history.firstOrNull()?.movieId ?: "") }
        var offlineMode by rememberSaveable { mutableStateOf(false) }
        var offlineRouteId by rememberSaveable { mutableStateOf("") }
        var initialEpisode by rememberSaveable { mutableIntStateOf(repository.history.find { it.movieId == movieId }?.episode ?: Playback.FirstEpisode) }
        var initialPosition by rememberSaveable { mutableLongStateOf(repository.history.find { it.movieId == movieId }?.positionMillis ?: Playback.Start) }
        var playbackRequestId by rememberSaveable { mutableIntStateOf(0) }
        var immersive by remember { mutableStateOf(false) }
        val rotation = rememberPlayerRotation(this@MainActivity, playerActive, windowLayout.isLargeScreen)
        val videoAspect = controller.videoAspectRatio ?: Theme.Size.VideoAspect
        // 全屏沉浸由独立状态控制，与方向解耦：竖屏视频全屏仍保持竖屏最大化。
        val fullscreen = playerActive && immersive
        val background = appearance.background
        val windowColor = if (fullscreen) Theme.Colors.Black else Theme.Colors.Background
        val homeState = rememberHomeState(repository)
        val scrollNavigation = rememberScrollNavigationState(!fullscreen, screen)
        val glassLight = rememberGlassLight(
            followTilt = appearance.glassFollowsTilt && !fullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            fixedAngle = appearance.glassLightAngle,
        ) {
            scrollNavigation.isMoving || homeState.gridState.isScrollInProgress || pageNavigation.isTransitioning
        }
        val navigationGlassEnabled = appearance.navigationGlassEnabled
        val pageBackdrop = rememberPageBackdrop(
            enabled = navigationGlassEnabled && !fullscreen,
            page = screen,
            shareRenderedPage = pageNavigation.activePage == Theme.PagerMotion.HomePage,
        )
        val ambientBackdrop = rememberAmbientBackdrop()
        val interactionHaptics = rememberInteractionHaptics()
        val navigationContentPadding = Theme.Size.NavHeight + Theme.Space.Large + Theme.NavGlass.TetherUp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val movie = (if (offlineMode) offlineCache.movieFor(movieId) else null) ?: repository.getMovie(movieId) ?: firstMovie
        // 必须在组合阶段订阅状态；只在 SideEffect 中读取不会触发播放开始后的更新。
        val playing = controller.isPlaying
        val buffering = controller.isBuffering
        val shouldKeepScreenOn = playerActive && (playing || buffering)
        SideEffect {
            playbackKeepsScreenOn = shouldKeepScreenOn
            updateScreenAwake()
        }
        LaunchedEffect(playerActive) { if (!playerActive) immersive = false }

        fun openMovie(selected: Movie, history: WatchHistory?) {
            panels.dismiss()
            focusManager.clearFocus()
            keyboard?.hide()
            if (!playerActive || movieId != selected.id) playbackRequestId++
            offlineMode = false
            movieId = selected.id
            initialEpisode = history?.episode ?: Playback.FirstEpisode
            initialPosition = history?.positionMillis ?: Playback.Start
            pageNavigation.navigateTo(Theme.PagerMotion.PlayerPage)
        }

        /** 输入：缓存管理页点选的单集；输出：不依赖接口清单，直接用本地缓存线路起播。 */
        fun openOfflineEpisode(targetMovieId: String, targetRouteId: String, targetEpisode: Int) {
            val record = repository.history.find { it.movieId == targetMovieId && it.episode == targetEpisode }
            panels.dismiss()
            focusManager.clearFocus()
            keyboard?.hide()
            if (!playerActive || movieId != targetMovieId) playbackRequestId++
            offlineMode = true
            offlineRouteId = targetRouteId
            movieId = targetMovieId
            initialEpisode = targetEpisode
            initialPosition = record?.positionMillis ?: Playback.Start
            pageNavigation.navigateTo(Theme.PagerMotion.PlayerPage)
        }

        /** 输入：底栏的播放目标；输出：只选好续播内容，停稳之前不启动解码。 */
        fun preparePlayerPage() {
            val target = resolvePlaybackPageTarget(
                PlaybackPageRequest(movieId, offlineMode, offlineRouteId, initialEpisode, initialPosition),
                playbackSession.selection, controller.getPosition(), repository.history,
                repository::getMovie, offlineCache::movieFor, offlineCache::routesFor, repository.fallbackMovie,
            ) ?: return
            // A changed offline route needs a fresh page; an unchanged session
            // keeps its decoder, pause intent and confirmed progress.
            if (playbackSession.selection?.movieId != target.movie.id || offlineMode != target.offline ||
                (target.offline && offlineRouteId != target.offlineRouteId)) playbackRequestId++
            offlineMode = target.offline
            offlineRouteId = target.offlineRouteId.orEmpty()
            movieId = target.movie.id
            initialEpisode = target.episode
            initialPosition = target.positionMillis
        }
        fun navigateToPage(index: Int) {
            if (pageNavigation.activePage == index) return
            if (index == Theme.PagerMotion.PlayerPage) preparePlayerPage()
            pageNavigation.navigateTo(index)
        }

        SideEffect {
            updateWindowBackground(windowColor.toArgb())
            // 三键导航设备同步底色，浅色模式的深色按键不会落在旧深色底上。
            window.navigationBarColor = windowColor.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = !dark && !fullscreen
            insetsController.isAppearanceLightNavigationBars = !dark && !fullscreen
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) insetsController.hide(WindowInsetsCompat.Type.systemBars()) else insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        BackHandler(pageNavigation.isTransitioning || screen != "首页") {
            if (fullscreen) {
                immersive = false
                rotation.exitFullscreen()
            } else pageNavigation.navigateTo(Theme.PagerMotion.HomePage)
        }

        CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop,
            LocalAmbientBackdrop provides ambientBackdrop,
            LocalGlassTuning provides appearance.glassTuningState,
            LocalGlassLight provides glassLight,
            LocalWindowLayout provides windowLayout,
            LocalInteractionHaptics provides interactionHaptics) {
        Box(Modifier.fillMaxSize().background(windowColor)) {
            PageBackdropHost(pageBackdrop, Modifier.fillMaxSize().nestedScroll(scrollNavigation.connection)
                .then(if (panels.active != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                AmbientBackdropHost(ambientBackdrop, Modifier.fillMaxSize()) {
                    AppBackground(background, appearance.customImageUri, Modifier.fillMaxSize(),
                        appearance.builtInWallpaper, timeGradient = timeTheme)
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = Theme.PagerMotion.AdjacentPages,
                    userScrollEnabled = false,
                    key = { Theme.PagerMotion.PageNames[it] },
                ) { index ->
                    when (index) {
                        Theme.PagerMotion.HomePage -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            CompositionLocalProvider(LocalGlassTuning provides appearance.glassTuningState(GlassArea.CONTINUE)) {
                                HomeScreen(repository, homeState, ::openMovie, panels,
                                    posterGlass = appearance.glassTuningState(GlassArea.POSTER),
                                    panelGlass = appearance.glassTuningState(GlassArea.PANELS),
                                    bottomPadding = navigationContentPadding + Theme.Space.Section)
                            }
                        }
                        Theme.PagerMotion.HistoryPage -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            CompositionLocalProvider(LocalGlassTuning provides appearance.glassTuningState(GlassArea.HISTORY)) {
                                HistoryScreen(repository, ::openMovie, bottomPadding = navigationContentPadding + Theme.Space.Page)
                            }
                        }
                        Theme.PagerMotion.PlayerPage -> movie?.let { selected ->
                            key(selected.id, playbackRequestId) {
                              CompositionLocalProvider(LocalGlassTuning provides appearance.glassTuningState(GlassArea.PLAYER)) {
                                PlayerScreen(selected,
                                    WatchHistory(selected.id, initialEpisode, initialPosition, selected.durationMillis, System.currentTimeMillis()),
                                    repository, controller, fullscreen, {
                                        immersive = !immersive
                                        rotation.toggleFullscreen(fullscreen, videoAspect)
                                    },
                                    playbackSession = playbackSession, playbackOwner = playbackOwner,
                                    onSwitchMovie = { target, history -> openMovie(target, history) },
                                    bottomPadding = navigationContentPadding,
                                    active = playerActive, surfaceVisible = playerSurfaceVisible,
                                    routesOverride = if (offlineMode) {
                                        offlineCache.routesFor(selected.id).sortedBy { if (it.id == offlineRouteId) 0 else 1 }
                                    } else null,
                                    isFavorite = repository.isFavorite(selected.id),
                                    onToggleFavorite = if (repository.getPlaybackRoutes(selected.id).isNotEmpty()) {
                                        { repository.setFavorite(selected, !repository.isFavorite(selected.id)) }
                                    } else null,
                                )
                              }
                            }
                        }
                    }
                }
            }
            if (!fullscreen) Box(Modifier.align(Alignment.BottomCenter).then(if (panels.active != null) Modifier.clearAndSetSemantics { } else Modifier)) {
                CompositionLocalProvider(LocalGlassTuning provides appearance.glassTuningState(GlassArea.NAVIGATION)) {
                    BottomNavigation(screen, onNavigate = { destination ->
                        navigateToPage(Theme.PagerMotion.PageNames.indexOf(destination).coerceAtLeast(Theme.PagerMotion.HomePage))
                    }, scrollState = scrollNavigation,
                        linkedPosition = pageNavigation.position,
                        onPagerDragStart = pageNavigation::beginDrag,
                        onPagerDragPosition = pageNavigation::dragTo,
                        onPagerDragEnd = { target, velocity ->
                            if (target == Theme.PagerMotion.PlayerPage) preparePlayerPage()
                            pageNavigation.finishDrag(target, velocity)
                        },
                        onPagerDragCancel = pageNavigation::cancelDrag,
                        glassEnabled = navigationGlassEnabled,
                        homeScrollState = homeState.gridState.takeIf {
                            pageNavigation.committedPage == Theme.PagerMotion.HomePage && panels.active == null
                        },
                    )
                }
            }
            if (!fullscreen) {
                val dismissPanel = {
                    val panel = panels.active
                    panels.dismiss()
                    if (panel == HomePanel.SEARCH || panel == HomePanel.SOURCES) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                }
                CompositionLocalProvider(LocalGlassTuning provides appearance.glassTuningState(GlassArea.PANELS)) {
                  CapsulePanelHost(panels, onDismiss = dismissPanel) { panel, ready ->
                    when (panel) {
                        HomePanel.SOURCES -> SourceImportPanel(repository, homeState, appearance, ::openMovie, onDismiss = dismissPanel)
                        HomePanel.SEARCH -> SearchPanelContent(homeState, ready, onDismiss = dismissPanel)
                        HomePanel.APPEARANCE -> AppearancePanelContent(
                            appearance = appearance,
                            message = appearanceMessage,
                            ready = ready,
                            onDismiss = dismissPanel,
                            onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                            onReset = {
                                appearance.setThemeMode(AppThemeMode.DARK)
                                appearance.setStylePack(AppStylePack.ORIGINAL)
                                appearance.setNavigationGlassEnabled(true)
                                appearance.resetAllGlassTuning()
                                appearance.clearCustomImage()
                                appearance.setBackground(AppBackgroundStyle.SOLID)
                                appearanceMessage = null
                            },
                        )
                        HomePanel.FAVORITES -> FavoritesPanel(
                            movies = repository.favorites,
                            onOpenMovie = { favorite -> openMovie(favorite, repository.history.find { it.movieId == favorite.id }) },
                            onRemoveMovie = { repository.setFavorite(it, false) },
                            onDismiss = dismissPanel,
                        )
                        HomePanel.CACHE -> OfflineCachePanel(
                            cache = offlineCache,
                            onOpenEpisode = { targetMovieId, targetRouteId, targetEpisode ->
                                openOfflineEpisode(targetMovieId, targetRouteId, targetEpisode)
                            },
                            onDismiss = dismissPanel,
                        )
                    }
                  }
                }
            }
            if (!fullscreen && (background == AppBackgroundStyle.CUSTOM || background == AppBackgroundStyle.BUILT_IN)) {
                ImageNavigationBarScrim(dark, Modifier.matchParentSize())
            }
        }
        }
    }
}

@Composable
private fun ImageNavigationBarScrim(dark: Boolean, modifier: Modifier = Modifier) {
    val insets = WindowInsets.navigationBars
    val statusBars = WindowInsets.statusBars
    val shade = if (dark) Color.Black.copy(alpha = .55f) else Color.White.copy(alpha = .55f)
    Canvas(modifier) {
        val top = statusBars.getTop(this).toFloat()
        val bottom = insets.getBottom(this).toFloat()
        val left = insets.getLeft(this, layoutDirection).toFloat()
        val right = insets.getRight(this, layoutDirection).toFloat()
        val remainingHeight = (size.height - top - bottom).coerceAtLeast(0f)
        if (bottom > 0f) drawRect(shade, Offset(0f, size.height - bottom), Size(size.width, bottom))
        if (left > 0f) drawRect(shade, Offset(0f, top), Size(left, remainingHeight))
        if (right > 0f) drawRect(shade, Offset(size.width - right, top), Size(right, remainingHeight))
    }
}
