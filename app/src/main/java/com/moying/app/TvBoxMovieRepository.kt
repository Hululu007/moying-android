package com.moying.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TvBoxSource(
    val id: String,
    val name: String,
    val api: String,
    val type: Int = 1,
    val origin: String = "",
    val format: String = "cms",
)

/** 直接兼容 AppleCMS JSON/XML 与 M3U；不执行 TVBox Spider/Jar。网络由调用方调度到 IO。 */
class TvBoxMovieRepository(context: Context) : MovieRepository, SourceAvailabilityRepository {
    private val local = SourceMovieRepository(context)
    private val preferences = context.getSharedPreferences("moying_tvbox_v1", Context.MODE_PRIVATE)
    private val deletedSourceKeys = preferences.getStringSet("deletedSourceKeys", emptySet()).orEmpty().toMutableSet()
    private data class Entry(val movie: Movie, val routes: List<PlaybackRoute>)
    private val library = ConcurrentHashMap<String, Entry>()
    private val pages = ConcurrentHashMap<String, MoviePage>()
    private val playlists = ConcurrentHashMap<String, List<Movie>>()
    private val types = ConcurrentHashMap<String, Map<String, String>>()
    private val cmsCatalogs = ConcurrentHashMap<String, CmsSourceCatalog>()
    private data class ObservedCatalog(
        val categories: List<SourceCmsCategory>,
        val facets: Map<String, List<CatalogFacet>>,
    )
    private val observedCatalogs = ConcurrentHashMap<String, ObservedCatalog>()
    private data class CatalogPageKey(
        val sourceId: String,
        val page: Int,
        val type: String?,
        val query: String,
        val filters: Map<String, String>,
    )
    private val catalogPages = ConcurrentHashMap<CatalogPageKey, MoviePage>()
    private val records = mutableStateListOf<WatchHistory>()
    private val favoriteEntries = mutableStateOf<List<Entry>>(emptyList())
    private val sourceState = mutableStateOf<List<TvBoxSource>>(emptyList())
    private val selectedState = mutableStateOf("")
    private val revisionState = mutableIntStateOf(0)
    override val categories = local.categories
    override val history: List<WatchHistory> get() = records
    val favorites: List<Movie> get() = favoriteEntries.value.map { it.movie }
    val sources: List<TvBoxSource> get() = sourceState.value
    val selectedSourceId: String get() = selectedState.value
    val sourceRevision: Int get() = revisionState.intValue
    val fallbackMovie: Movie? get() = local.getMovies(Catalog.FirstPage, categories.first()).items.firstOrNull()

    /** 输入：独立片源配置；输出：真实接口检测结果，不更改当前选择、片单或观看记录。 */
    override suspend fun checkSource(source: TvBoxSource): SourceCheckResult = SourceAvailabilityProbe.check(source)

    init {
        val bundled = context.assets.open("TvBoxSources.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("sites") }
        val combined = readSources(bundled).filterNot { SourceImport.sourceKey(it.api) in deletedSourceKeys }.toMutableList()
        runCatching { readSources(JSONArray(preferences.getString("importedSources", "[]"))) }.getOrDefault(emptyList()).forEach { source ->
            if (SourceImport.sourceKey(source.api) !in deletedSourceKeys) {
                val index = combined.indexOfFirst { SourceImport.sourceKey(it.api) == SourceImport.sourceKey(source.api) }
                // 重新导入内置站时沿用导入项的 ID，重启后仍能恢复选择和观看记录。
                if (index < 0) combined += source else combined[index] = source
            }
        }
        sourceState.value = combined + TvBoxSource("local", "本地 / 直链", "")
        selectedState.value = preferences.getString("source", null)?.takeIf { saved -> sources.any { it.id == saved } } ?: sources.first().id
        runCatching {
            val saved = JSONArray(preferences.getString("watchedMovies", "[]"))
            for (index in 0 until saved.length()) {
                val row = saved.optJSONObject(index) ?: continue
                val routes = SourceImport.readRoutes(row)
                if (routes.isNotEmpty()) {
                    val movie = readMovie(row, routes)
                    library[movie.id] = Entry(movie, routes)
                }
            }
            val savedHistory = JSONArray(preferences.getString("history", "[]"))
            for (index in 0 until savedHistory.length()) {
                val row = savedHistory.getJSONObject(index)
                val id = row.getString("movieId")
                if (getMovie(id) != null) records += WatchHistory(id, row.getInt("episode"), row.getLong("position"), row.getLong("duration"), row.getLong("watchedAt"))
            }
        }
        records.addAll(local.history.filter { old -> records.none { it.movieId == old.movieId } })

        favoriteEntries.value = readFavorites()
        favoriteEntries.value.forEach { entry ->
            // Keep previously watched and local entries authoritative. A saved
            // remote entry can still be opened after its source has been removed.
            if (local.getMovie(entry.movie.id) == null) library.putIfAbsent(entry.movie.id, entry)
        }
    }

    private fun readFavorites(): List<Entry> {
        val rows = runCatching {
            JSONArray(preferences.getString(FAVORITE_MOVIES, "[]"))
        }.getOrElse { return emptyList() }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val routes = SourceImport.readRoutes(row)
            if (row.optString("id").isBlank() || row.optString("title").isBlank() || routes.isEmpty()) {
                return@mapNotNull null
            }
            Entry(readMovie(row, routes), routes)
        }.distinctBy { it.movie.id }
    }

    fun isFavorite(movieId: String): Boolean = favoriteEntries.value.any { it.movie.id == movieId }

    /** Keeps a playable copy of a favorite independently of source and watch history. */
    @Synchronized
    fun setFavorite(movie: Movie, favorite: Boolean): Boolean {
        val current = favoriteEntries.value
        if (current.any { it.movie.id == movie.id } == favorite) return true

        val updated = if (favorite) {
            val routes = getPlaybackRoutes(movie.id).filter { it.episodes.isNotEmpty() }
            if (movie.id.isBlank() || movie.title.isBlank() || routes.isEmpty()) return false
            listOf(Entry(movie, routes)) + current
        } else {
            current.filterNot { it.movie.id == movie.id }
        }
        val rows = JSONArray().apply { updated.forEach { put(writeMovie(it)) } }
        preferences.edit().putString(FAVORITE_MOVIES, rows.toString()).apply()
        Snapshot.withMutableSnapshot { favoriteEntries.value = updated }
        return true
    }

    private fun readSources(array: JSONArray): List<TvBoxSource> = (0 until array.length()).mapNotNull { i ->
        val row = array.optJSONObject(i) ?: return@mapNotNull null
        val type = row.optInt("type", 1)
        val api = row.optString("api")
        if (type !in listOf(0, 1) || !SourceImport.validUrl(api)) null else TvBoxSource(
            row.optString("key").ifBlank { "import:" + SourceImport.hash(SourceImport.sourceKey(api)) },
            SourceImport.clean(row.optString("name")).ifBlank { Uri.parse(api).host.orEmpty() }, api, type,
            row.optString("config", row.optString("origin")), row.optString("format", "cms"),
        )
    }.distinctBy { SourceImport.sourceKey(it.api) }

    @Synchronized
    fun selectSource(id: String) {
        if (sources.none { it.id == id }) return
        Snapshot.withMutableSnapshot { selectedState.value = id }
        preferences.edit().putString("source", id).apply()
    }

    /** 删除站点配置并记住内置站的删除状态；历史和播放地址独立保留。 */
    @Synchronized
    fun deleteSource(id: String): Boolean {
        val source = sources.firstOrNull { it.id == id && it.id != "local" } ?: return false
        val remaining = sources.filterNot { it.id == id }
        val selected = if (selectedSourceId == id) remaining.first().id else selectedSourceId
        deletedSourceKeys += SourceImport.sourceKey(source.api)
        persistSources(remaining, selected)
        pages.keys.removeAll { it.startsWith("$id|") }
        playlists.remove(id)
        types.remove(id)
        cmsCatalogs.remove(id)
        observedCatalogs.remove(id)
        catalogPages.keys.removeAll { it.sourceId == id }
        Snapshot.withMutableSnapshot {
            sourceState.value = remaining
            selectedState.value = selected
            revisionState.intValue++
        }
        return true
    }

    private fun persistSources(updated: List<TvBoxSource>, selected: String) {
        val sourceJson = JSONArray()
        updated.filter { it.id.startsWith("import:") }.forEach { source ->
            sourceJson.put(JSONObject().put("key", source.id).put("name", source.name).put("api", source.api).put("type", source.type).put("origin", source.origin).put("format", source.format))
        }
        preferences.edit().putString("importedSources", sourceJson.toString())
            .putStringSet("deletedSourceKeys", deletedSourceKeys.toSet()).putString("source", selected).apply()
    }

    /** 持有原始配置对象的旧请求不得给已删除、或删除后重新导入的站点回填缓存。 */
    private inline fun cacheIfActive(source: TvBoxSource, publish: () -> Unit) = synchronized(this) {
        if (sources.any { it === source }) publish()
    }

    /** 输入订阅 URL 或本地文本；输出成功与跳过数量，先完成解析再原子发布新增片源。 */
    fun importSources(text: String, origin: String = ""): SourceImportResult {
        val batch = try { SourceImport.parse(text, origin) } catch (error: Exception) {
            return SourceImportResult(0, 0, 0, error.message ?: "无法识别导入内容")
        }
        synchronized(this) {
            val updated = sources.filter { it.id != "local" }.toMutableList()
            val keys = updated.map { SourceImport.sourceKey(it.api) }.toHashSet()
            val ids = updated.map { it.id }.toHashSet()
            var addedSources = 0
            var skipped = batch.skipped
            for (source in batch.sources) {
                if (updated.size >= SourceImport.MAX_SOURCES || !keys.add(SourceImport.sourceKey(source.api))) { skipped++; continue }
                val safe = if (ids.add(source.id)) source else source.copy(id = "import:" + SourceImport.hash(SourceImport.sourceKey(source.api)))
                ids.add(safe.id)
                updated += safe
                deletedSourceKeys.remove(SourceImport.sourceKey(safe.api))
                addedSources++
            }
            val (addedMovies, duplicateMovies) = local.importMovies(batch.movies)
            skipped += duplicateMovies
            if (addedSources > 0 || addedMovies > 0) {
                Snapshot.withMutableSnapshot {
                    sourceState.value = updated + TvBoxSource("local", "本地 / 直链", "")
                    if (addedSources == 0 && addedMovies > 0) selectedState.value = "local"
                    revisionState.intValue++
                }
                persistSources(updated, selectedSourceId)
                pages.clear()
                catalogPages.clear()
            }
            val note = batch.notes.distinct().take(2).joinToString("；")
            val detail = if (note.isBlank()) "" else "。$note"
            val message = "新增 $addedSources 个片源、$addedMovies 条媒体，跳过 $skipped 条（重复、不兼容或超出上限）$detail"
            return SourceImportResult(addedSources, addedMovies, skipped, message)
        }
    }

    override fun getMovies(page: Int, category: String, query: String): MoviePage {
        val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.last()
        if (source.id == "local") return local.getMovies(page, category, query)
        val key = "${source.id}|$category|${query.trim()}|$page"
        pages[key]?.let { return it }
        if (source.format == "m3u") return playlistPage(source, page, category, query).also { chunk -> cacheIfActive(source) { pages[key] = chunk } }
        if (category != categories.first() && !types.containsKey(source.id)) {
            rememberTypes(source, SourceImport.cmsDocument(SourceImport.download(requestUrl(source, "list", 0, "", null))))
        }
        return fetch(source, page, category, query.trim()).also { chunk ->
            cacheIfActive(source) {
                if (pages.size >= 256) pages.clear()
                pages[key] = chunk
            }
        }
    }

    internal fun getSourceCatalog(sourceId: String): SourceCatalog {
        val source = catalogSource(sourceId)
        return when {
            source.id == "local" -> local.getSourceCatalog()
            source.format == "m3u" -> buildLocalCatalog(playlistMovies(source))
            else -> cmsCatalog(source).directory
        }
    }

    internal fun getCatalogMovies(
        sourceId: String,
        page: Int,
        channelId: String,
        query: String,
        filters: Map<String, String>,
    ): MoviePage {
        val source = catalogSource(sourceId)
        if (source.id == "local") return local.getCatalogMovies(page, channelId, query, filters)
        if (source.format == "m3u") return localCatalogPage(playlistMovies(source), page, channelId, query, filters)

        val directory = if (channelId == CatalogRecommend) null else cmsCatalog(source)
        val facets = directory?.directory?.facets(channelId, filters).orEmpty()
        val subtype = facets.firstOrNull { it.key == CatalogSubtype }
        val type = filters[CatalogSubtype]?.takeIf { value -> subtype?.options?.any { it.value == value } == true }
            ?: directory?.typeByChannel?.get(channelId)
        if (channelId != CatalogRecommend && type == null) return MoviePage(emptyList(), false)
        val nativeFilters = facets.filter { it.key != CatalogSubtype }.mapNotNull { facet ->
            val value = filters[facet.key]?.takeIf { value -> facet.options.any { it.value == value } }
            value?.let { facet.key to it }
        }.toMap()
        val key = CatalogPageKey(sourceId, page, type, query.trim(), nativeFilters)
        catalogPages[key]?.let { return it }
        return fetchType(source, page, type, key.query, nativeFilters) { row ->
            SourceImport.clean(row.optString("type_name")).ifBlank {
                directory?.directory?.channels?.firstOrNull { it.id == channelId }?.label ?: "未分类"
            }
        }.also { chunk ->
            cacheIfActive(source) {
                if (catalogPages.size >= 256) catalogPages.clear()
                catalogPages[key] = chunk
            }
        }
    }

    private fun catalogSource(sourceId: String): TvBoxSource =
        sources.firstOrNull { it.id == sourceId } ?: error("片源已移除")

    private fun cmsCatalog(source: TvBoxSource): CmsSourceCatalog {
        cmsCatalogs[source.id]?.let { return it }
        val observed = observedCatalogs[source.id]
        val document = try {
            SourceImport.cmsDocument(SourceImport.download(requestUrl(source, "list", 0, "", null)))
        } catch (error: Exception) {
            if (error is CancellationException || error is InterruptedException || Thread.currentThread().isInterrupted ||
                observed?.categories.isNullOrEmpty()) throw error
            null
        }
        if (document != null) rememberTypes(source, document)
        val categories = document?.categories.orEmpty().ifEmpty {
            (observed?.categories.orEmpty() + document?.observedCategories().orEmpty()).distinctBy { it.id }
        }
        val nativeFacets = observed?.facets.orEmpty() + document?.nativeFacets.orEmpty()
        return buildCmsSourceCatalog(categories, nativeFacets).also { catalog ->
            cacheIfActive(source) { cmsCatalogs[source.id] = catalog }
        }
    }

    private fun requestUrl(
        source: TvBoxSource,
        action: String,
        page: Int,
        query: String,
        type: String?,
        filters: Map<String, String> = emptyMap(),
    ): String {
        val api = Uri.parse(source.api)
        val builder = api.buildUpon().clearQuery().fragment(null)
        for (name in api.queryParameterNames) {
            if (name !in setOf("ac", "pg", "wd", "t", "ids") && name !in filters) {
                api.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
            }
        }
        builder.appendQueryParameter("ac", action).appendQueryParameter("pg", (page + 1).toString())
        if (query.isNotEmpty()) builder.appendQueryParameter("wd", query)
        if (type != null) builder.appendQueryParameter("t", type)
        filters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun rememberTypes(source: TvBoxSource, root: SourceCmsDocument) {
        if (root.classes.isEmpty()) return
        val candidates = mapOf("电影" to listOf("电影", "电影片"), "剧集" to listOf("电视剧", "连续剧", "剧集"), "动漫" to listOf("动漫", "动漫片", "动画片"))
        val mapped = candidates.mapNotNull { (categoryName, options) ->
            options.firstNotNullOfOrNull { root.classes[it] }?.let { categoryName to it }
        }.toMap()
        cacheIfActive(source) { types[source.id] = mapped }
    }

    private fun fetch(source: TvBoxSource, page: Int, category: String, query: String): MoviePage {
        val type = if (category == categories.first()) null else (types[source.id]?.get(category) ?: error("该片源未提供此分类，请切换推荐或片源"))
        return fetchType(source, page, type, query, emptyMap()) { row ->
            if (category == categories.first()) inferCategory(row.optString("type_name")) else category
        }
    }

    private fun fetchType(
        source: TvBoxSource,
        page: Int,
        type: String?,
        query: String,
        filters: Map<String, String>,
        category: (JSONObject) -> String,
    ): MoviePage {
        val root = SourceImport.cmsDocument(SourceImport.download(requestUrl(source, "detail", page, query, type, filters)))
        rememberTypes(source, root)
        if (type == null && query.isBlank() && page == Catalog.FirstPage) {
            val found = root.categories.ifEmpty { root.observedCategories() }
            if (found.isNotEmpty()) cacheIfActive(source) {
                observedCatalogs[source.id] = ObservedCatalog(found, root.nativeFacets)
            }
        }
        val movies = root.rows.mapNotNull { row ->
            val title = SourceImport.clean(row.optString("vod_name"))
            if (title.isBlank()) return@mapNotNull null
            val id = "tvbox:${source.id}:${row.optString("vod_id").ifBlank { title }}"
            val normalized = SourceImport.normalizeMovie(row, id, category(row), "片源：${source.name}") ?: return@mapNotNull null
            rememberMovie(normalized, source)
        }
        val hasMore = root.pageCount?.let { page + 1 < it } ?: root.rows.isNotEmpty()
        return MoviePage(movies, hasMore)
    }

    private fun inferCategory(type: String): String = when {
        type.contains("动漫") || type.contains("动画") -> "动漫"
        type.contains("剧") || type.contains("连续") -> "剧集"
        else -> "电影"
    }

    private fun playlistMovies(source: TvBoxSource): List<Movie> =
        playlists[source.id] ?: SourceImport.playlistRows(SourceImport.download(source.api), source.api).first.mapNotNull { row ->
            row.put("id", "tvbox:${source.id}:${SourceImport.hash(row.getJSONArray("episodes").getJSONObject(0).getString("url"))}")
                .put("status", "直播").put("credit", "公开直播目录：${source.name}")
            rememberMovie(row, source)
        }.also { movies -> cacheIfActive(source) { playlists[source.id] = movies } }
    private fun playlistPage(source: TvBoxSource, page: Int, category: String, query: String): MoviePage {
        val matches = playlistMovies(source).filter {
            (category == categories.first() || category == "电影") && it.title.contains(query.trim(), true)
        }
        val start = page.coerceAtLeast(0) * Catalog.PageSize
        return MoviePage(matches.drop(start).take(Catalog.PageSize), start + Catalog.PageSize < matches.size)
    }

    private fun rememberMovie(row: JSONObject, source: TvBoxSource): Movie? {
        val routes = SourceImport.readRoutes(row)
        if (routes.isEmpty()) return null
        val movie = readMovie(row, routes)
        cacheIfActive(source) { library[movie.id] = Entry(movie, routes) }
        return movie
    }

    override fun getMovie(id: String): Movie? = library[id]?.movie ?: local.getMovie(id)
    override fun getEpisodes(id: String): List<Episode> = library[id]?.routes?.firstOrNull()?.episodes ?: local.getEpisodes(id)
    override fun getPlaybackRoutes(id: String): List<PlaybackRoute> = library[id]?.routes ?: local.getPlaybackRoutes(id)
    override fun addSource(title: String, url: String): Movie? = local.addSource(title, url)?.also {
        synchronized(this) { Snapshot.withMutableSnapshot { revisionState.intValue++ } }
    }

    /** 最多并发三个站点查询，同名且年份不冲突才视为候选，不更改当前片源。 */
    override fun findAlternatives(movieId: String): List<Movie> {
        val original = getMovie(movieId) ?: return emptyList()
        val title = comparableTitle(original.title)
        if (title.isBlank()) return emptyList()
        val year = Regex("(?:19|20)\\d{2}").find(original.year)?.value.orEmpty()
        val targets = sources.filter { it.id != "local" && it.format == "cms" && !movieId.startsWith("tvbox:${it.id}:") }.take(8)
        val executor = Executors.newFixedThreadPool(minOf(3, targets.size.coerceAtLeast(1)))
        val futures = targets.map { source -> executor.submit(Callable {
            source to runCatching { fetch(source, 0, categories.first(), original.title).items.filter { item ->
                val otherYear = Regex("(?:19|20)\\d{2}").find(item.year)?.value.orEmpty()
                comparableTitle(item.title) == title && (year.isBlank() || otherYear.isBlank() || year == otherYear) && getEpisodes(item.id).isNotEmpty()
            } }.getOrDefault(emptyList())
        }) }
        val deadline = System.currentTimeMillis() + 22_000L
        return try {
            val found = mutableListOf<Pair<TvBoxSource, List<Movie>>>()
            futures.forEach { future ->
                val remaining = deadline - System.currentTimeMillis()
                // 总超时以后仍收集已经完成的站点，不能让排在前面的慢站掩盖快站结果。
                val waitMillis = if (future.isDone) 1L else remaining
                if (waitMillis > 0 && !Thread.currentThread().isInterrupted) {
                    try { found += future.get(waitMillis, TimeUnit.MILLISECONDS) }
                    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
                    catch (_: Exception) { /* 某站超时或失败时保留其他站已经确认的结果。 */ }
                }
            }
            synchronized(this) {
                found.filter { (source, _) -> sources.any { it === source } }.flatMap { it.second }.distinctBy { it.id }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun comparableTitle(value: String): String = SourceImport.clean(value).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    @Synchronized
    override fun saveHistory(record: WatchHistory) {
        Snapshot.withMutableSnapshot {
            records.removeAll { it.movieId == record.movieId }
            records.add(0, record)
        }
        persist()
    }
    @Synchronized override fun deleteHistory(movieId: String) { Snapshot.withMutableSnapshot { records.removeAll { it.movieId == movieId } }; local.deleteHistory(movieId); persist() }
    @Synchronized override fun clearHistory() { Snapshot.withMutableSnapshot { records.clear() }; local.clearHistory(); persist() }

    private fun persist() {
        val history = JSONArray()
        val watched = JSONArray()
        records.forEach { record ->
            history.put(JSONObject().put("movieId", record.movieId).put("episode", record.episode).put("position", record.positionMillis).put("duration", record.durationMillis).put("watchedAt", record.watchedAt))
            library[record.movieId]?.let { watched.put(writeMovie(it)) }
        }
        preferences.edit().putString("history", history.toString()).putString("watchedMovies", watched.toString()).apply()
    }

    private fun writeMovie(entry: Entry): JSONObject {
        val movie = entry.movie
        return JSONObject()
            .put("id", movie.id)
            .put("title", movie.title)
            .put("category", movie.category)
            .put("year", movie.year)
            .put("region", movie.region)
            .put("genres", movie.genres)
            .put("score", movie.score)
            .put("status", movie.status)
            .put("poster", movie.poster)
            .put("backdrop", movie.backdrop)
            .put("synopsis", movie.synopsis)
            .put("durationMillis", movie.durationMillis)
            .put("credit", movie.sourceCredit)
            .put("episodes", SourceImport.episodesJson(entry.routes.first().episodes))
            .put("routes", SourceImport.routesJson(entry.routes))
    }

    private fun readMovie(row: JSONObject, routes: List<PlaybackRoute>): Movie =
        Movie(row.getString("id"), row.getString("title"), row.optString("category", "电影"),
            row.optString("year"), row.optString("region"), row.optString("genres"), row.optString("score"),
            row.optString("status"), row.optString("poster"), row.optString("backdrop", row.optString("poster")),
            row.optString("synopsis"), routes.first().episodes.size, row.optLong("durationMillis", Playback.Start),
            row.optString("credit"))

    private companion object {
        const val FAVORITE_MOVIES = "favoriteMovies"
    }
}
