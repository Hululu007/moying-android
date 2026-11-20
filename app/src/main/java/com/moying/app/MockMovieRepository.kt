package com.moying.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** 本地假仓库，SharedPreferences 仅保存历史，不依赖任何播放器或 UI。 */
class MockMovieRepository(context: Context) : MovieRepository {
    override val categories: List<String> = MockData.categories
    private val preferences = context.getSharedPreferences("moying_history", Context.MODE_PRIVATE)
    private val records = mutableStateListOf<WatchHistory>()
    override val history: List<WatchHistory> get() = records

    init {
        val stored = preferences.getString("records", null)
        records.addAll(if (stored == null) MockData.initialHistory else runCatching {
            val json = JSONArray(stored)
            List(json.length()) { index ->
                val record = json.getJSONObject(index)
                WatchHistory(record.getString("movieId"), record.getInt("episode"), record.getLong("position"), record.getLong("duration"), record.getLong("watchedAt"))
            }
        }.getOrElse { emptyList() })
    }

    override fun getMovies(page: Int, category: String, query: String): MoviePage {
        val filtered = MockData.movies.filter { (category == MockData.categories.first() || it.category == category) && it.title.contains(query.trim(), ignoreCase = true) }
        // 有限 Mock 片单：真实接口只需把这里替换成服务端分页。
        val start = page * Catalog.PageSize
        return MoviePage(filtered.drop(start).take(Catalog.PageSize), start + Catalog.PageSize < filtered.size)
    }

    override fun getMovie(id: String): Movie? = MockData.movies.find { it.id == id }
    override fun saveHistory(record: WatchHistory) {
        records.removeAll { it.movieId == record.movieId }
        records.add(0, record)
        persist()
    }
    override fun deleteHistory(movieId: String) { records.removeAll { it.movieId == movieId }; persist() }
    override fun clearHistory() { records.clear(); persist() }

    /** 输入：当前 records；输出：写入本地 JSON，供下次启动恢复。 */
    private fun persist() {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject().put("movieId", record.movieId).put("episode", record.episode).put("position", record.positionMillis).put("duration", record.durationMillis).put("watchedAt", record.watchedAt)) }
        preferences.edit().putString("records", array.toString()).apply()
    }
}
