package com.moying.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import org.json.JSONArray
import org.json.JSONObject

/** 独立直链片库；导入时只保存规范化媒体数据，保留旧影片 id 和观看记录。 */
class SourceMovieRepository(context: Context) : MovieRepository {
    private val preferences = context.getSharedPreferences("moying_sources_v1", Context.MODE_PRIVATE)
    private val library = linkedMapOf<String, Movie>()
    private val routes = hashMapOf<String, List<PlaybackRoute>>()
    private val customRows = linkedMapOf<String, JSONObject>()
    private val records = mutableStateListOf<WatchHistory>()
    override val categories = listOf("推荐", "电影", "剧集", "动漫")
    override val history: List<WatchHistory> get() = records

    init {
        val bundled = context.assets.open("PlaySources.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("movies") }
        readMovies(bundled)
        runCatching {
            val saved = JSONArray(preferences.getString("customMovies", "[]"))
            for (i in 0 until saved.length()) {
                val row = saved.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isNotBlank()) customRows[id] = row
            }
            readMovies(saved)
        }
        runCatching {
            val saved = JSONArray(preferences.getString("history", "[]"))
            for (index in 0 until saved.length()) {
                val row = saved.getJSONObject(index)
                val id = row.getString("movieId")
                if (library.containsKey(id)) records += WatchHistory(id, row.getInt("episode"), row.getLong("position"), row.getLong("duration"), row.getLong("watchedAt"))
            }
        }
    }

    private fun readMovies(array: JSONArray) {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.optString("id")
            val tracks = SourceImport.readRoutes(row)
            if (id.isBlank() || tracks.isEmpty()) continue
            routes[id] = tracks
            library[id] = Movie(id, row.optString("title", "导入媒体"), row.optString("category", "电影"), row.optString("year"), row.optString("region"), row.optString("genres"), row.optString("score"), row.optString("status", "可直接播放"), row.optString("poster"), row.optString("backdrop", row.optString("poster")), row.optString("synopsis"), tracks.first().episodes.size, Playback.Start, listOf(row.optString("credit"), row.optString("license")).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }

    @Synchronized
    override fun getMovies(page: Int, category: String, query: String): MoviePage {
        val matches = library.values.filter { (category == categories.first() || it.category == category) && it.title.contains(query.trim(), ignoreCase = true) }
        val start = page.coerceAtLeast(Catalog.FirstPage) * Catalog.PageSize
        return MoviePage(matches.drop(start).take(Catalog.PageSize), start + Catalog.PageSize < matches.size)
    }

    @Synchronized
    internal fun getSourceCatalog(): SourceCatalog = buildLocalCatalog(catalogMovies())

    @Synchronized
    internal fun getCatalogMovies(page: Int, channelId: String, query: String, filters: Map<String, String>): MoviePage =
        localCatalogPage(catalogMovies(), page, channelId, query, filters)

    private fun catalogMovies(): Collection<Movie> = library.values.map { movie ->
        val row = customRows[movie.id]
        // Older playlist imports stored their group in genres on a direct-link row.
        if (row != null && movie.id.startsWith("user:") && movie.category == "电影" &&
            movie.status == "可直接播放" && movie.genres.isNotBlank() && !row.has("year") && !row.has("routes")
        ) {
            movie.copy(category = movie.genres.trim(), genres = "")
        } else movie
    }

    @Synchronized override fun getMovie(id: String): Movie? = library[id]
    @Synchronized override fun getEpisodes(id: String): List<Episode> = routes[id]?.firstOrNull()?.episodes.orEmpty()
    @Synchronized override fun getPlaybackRoutes(id: String): List<PlaybackRoute> = routes[id].orEmpty()

    /** 输入规范化片单；按媒体 URL 集合去重，新增内容和所有真实线路一起持久化。 */
    @Synchronized
    internal fun importMovies(rows: List<JSONObject>): Pair<Int, Int> {
        val knownUrls = routes.values.flatMap { it.flatMap { route -> route.episodes.map { episode -> episode.url } } }.toHashSet()
        var added = 0
        var skipped = 0
        for (row in rows) {
            val urls = SourceImport.readRoutes(row).flatMap { it.episodes }.map { it.url }
            val id = row.optString("id")
            if (id.isBlank() || urls.isEmpty() || urls.all { it in knownUrls } || library.containsKey(id)) { skipped++; continue }
            if (customRows.size >= SourceImport.MAX_MOVIES) { skipped++; continue }
            customRows[id] = row
            readMovies(JSONArray().put(row))
            knownUrls.addAll(urls)
            added++
        }
        if (added > 0) {
            val saved = JSONArray()
            customRows.values.forEach { saved.put(it) }
            preferences.edit().putString("customMovies", saved.toString()).apply()
        }
        return added to skipped
    }

    /** 输入片名和 HTTP(S) 直链；输出稳定 id 的影片，重复地址复用原片。 */
    @Synchronized
    override fun addSource(title: String, url: String): Movie? {
        val address = url.trim()
        if (!SourceImport.validUrl(address)) return null
        val existing = routes.entries.firstOrNull { (_, groups) -> groups.any { route -> route.episodes.any { it.url == address } } }?.key
        if (existing != null) return library[existing]
        val row = SourceImport.directMovie(title.trim().ifBlank { "我的片源" }, address)
        importMovies(listOf(row))
        return library[row.getString("id")]
    }

    @Synchronized
    override fun saveHistory(record: WatchHistory) {
        Snapshot.withMutableSnapshot {
            records.removeAll { it.movieId == record.movieId }
            records.add(0, record)
        }
        persistHistory()
    }
    @Synchronized override fun deleteHistory(movieId: String) { Snapshot.withMutableSnapshot { records.removeAll { it.movieId == movieId } }; persistHistory() }
    @Synchronized override fun clearHistory() { Snapshot.withMutableSnapshot { records.clear() }; persistHistory() }
    private fun persistHistory() {
        val json = JSONArray()
        records.forEach { json.put(JSONObject().put("movieId", it.movieId).put("episode", it.episode).put("position", it.positionMillis).put("duration", it.durationMillis).put("watchedAt", it.watchedAt)) }
        preferences.edit().putString("history", json.toString()).apply()
    }
}
