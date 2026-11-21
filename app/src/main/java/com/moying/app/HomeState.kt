package com.moying.app

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 由 Main 常驻持有；页面离开 Composition 时仍保留片单、分页和滚动位置。 */
@Stable
class HomeState internal constructor(
    sourceId: String,
    category: String,
    private val initialCatalog: SourceCatalog,
    val gridState: LazyGridState,
    private val scope: CoroutineScope,
) {
    internal data class CatalogKey(
        val sourceId: String,
        val category: String,
        val query: String,
        val revision: Int = 0,
        val filters: Map<String, String> = emptyMap(),
    ) {
        val isRecommendation: Boolean
            get() = category == CatalogRecommend && query.isBlank() && filters.isEmpty()
    }
    internal var catalogKey by mutableStateOf(CatalogKey(sourceId, category, ""))
        private set
    val sourceId: String get() = catalogKey.sourceId
    val category: String get() = catalogKey.category
    val query: String get() = catalogKey.query
    internal val filters: Map<String, String> get() = catalogKey.filters
    internal var catalog by mutableStateOf(initialCatalog)
        private set
    internal var catalogLoading by mutableStateOf(true)
        private set
    internal var catalogError by mutableStateOf<String?>(null)
        private set
    internal var catalogDiscoveryReady by mutableStateOf(false)
        private set
    internal var catalogRetry by mutableIntStateOf(0)
        private set
    val movies = mutableStateListOf<Movie>()
    private val seenMovieIds = hashSetOf<String>()
    private val seenRecommendationTitles = hashSetOf<Pair<String, String>>()
    private val revealedRecommendationIds = hashSetOf<String>()
    internal var replacingRecommendations by mutableStateOf(false)
        private set
    internal val canReplaceRecommendations: Boolean
        get() = catalogKey.isRecommendation && movies.isNotEmpty() &&
            (hasMore || loadedPage > Catalog.FirstPage || replacingRecommendations)
    var page by mutableIntStateOf(Catalog.FirstPage)
        private set
    var loadedPage by mutableIntStateOf(Catalog.FirstPage - 1)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        internal set
    var loadError by mutableStateOf<String?>(null)
        internal set
    internal var retry by mutableIntStateOf(0)
        private set
    internal var scrollReset by mutableIntStateOf(0)
        private set
    var sourceTitle by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var importText by mutableStateOf("")
    var importMessage by mutableStateOf<String?>(null)
    var importBusy by mutableStateOf(false)
        private set
    val sourceChecks = mutableStateMapOf<String, SourceCheckResult>()
    // false 表示排队，true 表示已开始请求；确认结果与临时排队状态分开保存。
    val pendingSourceChecks = mutableStateMapOf<String, Boolean>()
    var sourceCheckRunning by mutableStateOf(false)
        private set
    var sourceCheckCompleted by mutableIntStateOf(0)
        private set
    var sourceCheckTotal by mutableIntStateOf(0)
        private set
    var sourceCheckAvailable by mutableIntStateOf(0)
        private set
    var sourceCheckStopped by mutableStateOf(false)
        private set
    private var sourceCheckJob: Job? = null

    fun selectSource(id: String) = sourceChanged(catalogKey.revision, id)

    fun selectCategory(value: String) {
        if (value == category || catalog.channels.none { it.id == value }) return
        changeCatalog(catalogKey.copy(category = value, filters = emptyMap()))
    }

    internal fun setFilter(key: String, value: String) {
        val facet = catalog.facets(category, filters).firstOrNull { it.key == key } ?: return
        if (value != facet.defaultValue && facet.options.none { it.value == value }) return
        val base = if (key == CatalogSubtype) emptyMap() else filters
        val updated = if (value == facet.defaultValue) base - key else base + (key to value)
        changeCatalog(catalogKey.copy(filters = updated), scrollToTop = gridState.firstVisibleItemIndex != 0)
    }

    internal fun resetFilters() =
        changeCatalog(catalogKey.copy(filters = emptyMap()), scrollToTop = gridState.firstVisibleItemIndex != 0)

    fun setQuery(value: String) = changeCatalog(catalogKey.copy(query = value))

    internal fun sourceChanged(revision: Int, selectedSourceId: String) {
        if (sourceId == selectedSourceId && catalogKey.revision == revision) return
        catalog = initialCatalog
        catalogLoading = true
        catalogError = null
        catalogDiscoveryReady = false
        changeCatalog(catalogKey.copy(sourceId = selectedSourceId, category = initialCatalog.channels.first().id,
            revision = revision, filters = emptyMap()))
    }

    internal fun beginCatalogDiscovery() { catalogDiscoveryReady = true }

    internal fun retryCatalog() {
        catalogLoading = true
        catalogError = null
        catalogRetry++
    }

    internal fun acceptCatalog(directory: SourceCatalog) {
        catalog = directory
        catalogLoading = false
        catalogError = null
    }

    internal fun failCatalog() {
        catalogLoading = false
        catalogError = "分类暂时无法加载"
    }

    /** 输入：海报影片ID；输出：仅本片单首次出现为true，切换导航不重播透明入场。 */
    internal fun markRecommendationShown(movieId: String): Boolean = revealedRecommendationIds.add(movieId)

    private fun changeCatalog(next: CatalogKey, scrollToTop: Boolean = true) {
        if (next == catalogKey) return
        catalogKey = next
        page = Catalog.FirstPage
        loadedPage = Catalog.FirstPage - 1
        hasMore = false
        replacingRecommendations = false
        clearMovies()
        loading = true
        loadError = null
        if (scrollToTop) scrollReset++
    }

    private fun clearMovies() {
        movies.clear()
        seenMovieIds.clear()
        seenRecommendationTitles.clear()
        revealedRecommendationIds.clear()
    }

    internal fun accept(chunk: MoviePage, requestedPage: Int) {
        // An empty page from a remote source still advances pagination, but keeps the current batch visible.
        val replace = replacingRecommendations && chunk.items.isNotEmpty()
        if (replace || (requestedPage == Catalog.FirstPage && !replacingRecommendations)) clearMovies()
        val isRecommendation = catalogKey.isRecommendation
        val unique = chunk.items.filter { movie ->
            seenMovieIds.add(movie.id) &&
                (!isRecommendation || seenRecommendationTitles.add(movie.recommendationTitleKey()))
        }
        movies.addAll(if (isRecommendation) mixHomeRecommendations(unique) else unique)
        loadedPage = requestedPage
        hasMore = chunk.hasMore
        replacingRecommendations = false
        if (replace) scrollReset++
    }

    internal fun replaceRecommendations() {
        if (!canReplaceRecommendations || loading) return
        val next = if (replacingRecommendations) page else if (hasMore) loadedPage + 1 else Catalog.FirstPage
        replacingRecommendations = true
        loading = true
        loadError = null
        if (next == page) retry++ else page = next
    }

    fun nextPage() {
        if (!loading && loadError == null && hasMore && loadedPage >= Catalog.FirstPage) {
            loading = true
            page = loadedPage + 1
        }
    }

    fun retryLoad() { loading = true; retry++ }

    /** 输入：检测接口和本次片源快照；输出：最多三个并发结果，不切源、不写观看记录。 */
    fun checkSources(repository: SourceAvailabilityRepository, sources: List<TvBoxSource>) {
        if (importBusy || sourceCheckRunning) return
        val targets = sources.distinctBy { it.id }.toList()
        if (targets.isEmpty()) return
        sourceCheckRunning = true
        sourceCheckStopped = false
        sourceCheckCompleted = 0
        sourceCheckTotal = targets.size
        sourceCheckAvailable = 0
        pendingSourceChecks.clear()
        targets.forEach { pendingSourceChecks[it.id] = false }
        sourceCheckJob = scope.launch {
            try {
                val slots = Semaphore(SOURCE_CHECK_CONCURRENCY)
                coroutineScope {
                    targets.forEach { source ->
                        launch {
                            slots.withPermit {
                                pendingSourceChecks[source.id] = true
                                val started = android.os.SystemClock.elapsedRealtime()
                                val result = try {
                                    repository.checkSource(source)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    SourceCheckResult(source.id, SourceAvailability.FAILED,
                                        android.os.SystemClock.elapsedRealtime() - started,
                                        "本次检测未完成，请稍后重试", System.currentTimeMillis())
                                }
                                currentCoroutineContext().ensureActive()
                                sourceChecks[source.id] = result
                                sourceCheckCompleted++
                                if (result.status == SourceAvailability.AVAILABLE) sourceCheckAvailable++
                                pendingSourceChecks.remove(source.id)
                            }
                        }
                    }
                }
            } finally {
                // 取消保留已确认结果，不把排队/取消的站点伪装为检测失败。
                pendingSourceChecks.clear()
                sourceCheckRunning = false
                sourceCheckJob = null
            }
        }
    }

    /** 输入：用户停止操作；输出：取消请求和排队任务，保留已经完成的结果。 */
    fun cancelSourceChecks() {
        if (!sourceCheckRunning) return
        sourceCheckStopped = true
        sourceCheckJob?.cancel()
    }

    /** 删除后同步切换片单，丢弃此源的检测结果和过期的检测汇总。 */
    fun deleteSource(repository: TvBoxMovieRepository, id: String): Boolean {
        if (importBusy || sourceCheckRunning) return false
        val source = repository.sources.firstOrNull { it.id == id } ?: return false
        if (!repository.deleteSource(id)) return false
        sourceChecks.remove(id)
        pendingSourceChecks.remove(id)
        refreshAfterSourceDeletion(repository)
        importMessage = "已删除「${source.name}」"
        return true
    }

    fun clearNetworkSources(repository: TvBoxMovieRepository): Boolean {
        if (importBusy || sourceCheckRunning) return false
        val removed = repository.clearNetworkSources()
        if (removed == 0) return false
        sourceChecks.clear()
        pendingSourceChecks.clear()
        refreshAfterSourceDeletion(repository)
        importMessage = "已清空 $removed 个网络片源"
        return true
    }

    private fun refreshAfterSourceDeletion(repository: TvBoxMovieRepository) {
        sourceCheckCompleted = 0
        sourceCheckTotal = 0
        sourceCheckAvailable = 0
        sourceCheckStopped = false
        sourceChanged(repository.sourceRevision, repository.selectedSourceId)
    }

    /** scope 在 Main 中创建；收起面板或进入播放器不会留下半途取消的导入。 */
    fun importSources(action: suspend () -> SourceImportResult) {
        if (importBusy || sourceCheckRunning) return
        importBusy = true
        importMessage = null
        scope.launch {
            try {
                val result = action()
                importMessage = result.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                importMessage = error.message?.takeIf { it.isNotBlank() } ?: "导入失败，请检查地址或文件内容"
            } finally {
                importBusy = false
            }
        }
    }

    private companion object {
        const val SOURCE_CHECK_CONCURRENCY = 3
    }
}

/** 加载工作也常驻 Main；回到首页时已完成的页不会再请求，更不会清空首屏。 */
@Composable
fun rememberHomeState(repository: MovieRepository): HomeState {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val tvBox = repository as? TvBoxMovieRepository
    val state = remember(repository, gridState, scope) {
        val directory = if (tvBox == null) {
            SourceCatalog(repository.categories.map { CatalogChannel(it, it) })
        } else SourceCatalog.Recommendation
        HomeState(tvBox?.selectedSourceId.orEmpty(), directory.channels.first().id, directory, gridState, scope)
    }
    val sourceRevision = tvBox?.sourceRevision ?: 0
    LaunchedEffect(sourceRevision) { state.sourceChanged(sourceRevision, tvBox?.selectedSourceId.orEmpty()) }
    val sourceId = state.sourceId
    val revision = state.catalogKey.revision
    val discoveryReady = state.catalogDiscoveryReady
    LaunchedEffect(sourceId, revision, discoveryReady, state.catalogRetry) {
        if (!discoveryReady) return@LaunchedEffect
        try {
            val directory = if (tvBox == null) state.catalog else SourceRequests.run { tvBox.getSourceCatalog(sourceId) }
            if (state.sourceId == sourceId && state.catalogKey.revision == revision) state.acceptCatalog(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (state.sourceId == sourceId && state.catalogKey.revision == revision) state.failCatalog()
        }
    }
    LaunchedEffect(state.scrollReset) { gridState.scrollToItem(Catalog.FirstPage) }
    LaunchedEffect(state.catalogKey, state.page, state.retry) {
        val key = state.catalogKey
        val requestedPage = state.page
        val replacing = state.replacingRecommendations
        if (requestedPage <= state.loadedPage && !replacing) return@LaunchedEffect
        state.loading = true
        state.loadError = null
        try {
            if (key.query.isNotEmpty()) delay(Theme.Motion.SearchDebounceMillis)
            val chunk = SourceRequests.run {
                if (tvBox == null) repository.getMovies(requestedPage, key.category, key.query)
                else tvBox.getCatalogMovies(key.sourceId, requestedPage, key.category, key.query, key.filters)
            }
            if (state.catalogKey == key) state.accept(chunk, requestedPage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (state.catalogKey == key) state.loadError = if (replacing) {
                "暂时无法换一批，可重试"
            } else {
                "片源暂时不可用，可切换片源或点击重试"
            }
        } finally {
            if (state.catalogKey == key) {
                state.loading = false
                state.beginCatalogDiscovery()
            }
        }
    }
    return state
}
