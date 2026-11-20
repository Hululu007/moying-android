package com.moying.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 真实数据仓库：并行拉多个采集站，合并同名片，按页返回。
 * 只实现 MovieRepository 接口，UI 一行都不用改。
 */
class CmsMovieRepository(private val context: Context) : MovieRepository {

    companion object {
        /** 实测可用（返回直链 m3u8）的采集站 */
        val SOURCES = listOf(
            "https://api.apibdzy.com",
            "https://caiji.moduapi.cc",
            "https://caiji.360zy.com",
            "https://cj.ffzyapi.com",
            "https://api.ffzyapi.com",
            "https://cj.lziapi.com",
            "https://caiji.dyttzyapi.com",
            "https://api.wujinapi.me",
            "https://cj.rycjapi.com"
        )

        /** 分类 → 苹果CMS 的 type_id；null 表示不限 */
        private val TYPE_IDS = mapOf(
            "推荐" to null,
            "电影" to 1,
            "剧集" to 2,
            "动漫" to 4
        )

        private const val PAGE_SIZE = 12
        private const val TIMEOUT_MS = 7000L
    }

    override val categories: List<String> = listOf("推荐", "电影", "剧集", "动漫")

    private val preferences = context.getSharedPreferences("moying_history", Context.MODE_PRIVATE)
    private val records = mutableStateListOf<WatchHistory>()
    override val history: List<WatchHistory> get() = records

    /** id → 片源信息（含各线路的播放地址） */
    private val library = HashMap<String, Video>()
    /** "分类|关键词|页码" → 已拉到的列表，翻页不重复请求 */
    private val pageCache = HashMap<String, List<Movie>>()

    private val pool = Executors.newFixedThreadPool(9)

    init {
        val stored = preferences.getString("records", null)
        if (stored != null) {
            runCatching {
                val json = JSONArray(stored)
                records.addAll(List(json.length()) { i ->
                    val r = json.getJSONObject(i)
                    WatchHistory(
                        r.getString("movieId"), r.getInt("episode"),
                        r.getLong("position"), r.getLong("duration"), r.getLong("watchedAt")
                    )
                })
            }
        }
    }

    // ---------------- 列表 ----------------

    override fun getMovies(page: Int, category: String, query: String): MoviePage {
        val key = "$category|${query.trim()}|$page"
        pageCache[key]?.let {
            return MoviePage(it, it.isNotEmpty())
        }

        val typeId = TYPE_IDS[category]
        val keyword = query.trim()
        val futures = SOURCES.map { host ->
            pool.submit<List<Video>> {
                runCatching { fetchList(host, typeId, keyword, page) }.getOrElse { emptyList() }
            }
        }

        val collected = ArrayList<Video>()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        for (f in futures) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            runCatching { collected.addAll(f.get(left, TimeUnit.MILLISECONDS)) }
        }

        val merged = ArrayList<Video>()
        for (v in collected) {
            val hit = merged.firstOrNull { it.name == v.name }
            if (hit == null) merged.add(v) else hit.plays.addAll(v.plays)
        }

        // 关键词搜索时按相关度排一下
        if (keyword.isNotEmpty()) {
            merged.sortBy { v ->
                when {
                    v.name == keyword -> 0
                    v.name.startsWith(keyword) -> 1
                    v.name.contains(keyword) -> 2
                    else -> 3
                }
            }
        }

        val movies = merged.map { toMovie(it) }
        pageCache[key] = movies
        return MoviePage(movies, movies.isNotEmpty())
    }

    override fun getMovie(id: String): Movie? = library[id]?.let { toMovie(it) }

    // ---------------- 剧集 ----------------

    override fun getEpisodes(movieId: String): List<Episode> {
        val video = library[movieId] ?: return emptyList()
        // 多线路时挑集数最全的一组
        val best = video.plays.maxByOrNull { play ->
            if (play.contains(".m3u8")) play.count { it == '#' } + 1000 else play.count { it == '#' }
        } ?: return emptyList()

        val result = ArrayList<Episode>()
        for (part in best.split("#")) {
            val cut = part.indexOf('$')
            if (cut <= 0) continue
            val name = part.substring(0, cut)
            val url = part.substring(cut + 1)
            if (!url.startsWith("http")) continue
            result.add(Episode(result.size + 1, name, url))
        }
        return result
    }

    // ---------------- 历史 ----------------

    override fun saveHistory(record: WatchHistory) {
        records.removeAll { it.movieId == record.movieId }
        records.add(0, record)
        persist()
    }

    override fun deleteHistory(movieId: String) {
        records.removeAll { it.movieId == movieId }
        persist()
    }

    override fun clearHistory() {
        records.clear()
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        records.forEach {
            array.put(
                JSONObject()
                    .put("movieId", it.movieId)
                    .put("episode", it.episode)
                    .put("position", it.positionMillis)
                    .put("duration", it.durationMillis)
                    .put("watchedAt", it.watchedAt)
            )
        }
        preferences.edit().putString("records", array.toString()).apply()
    }

    // ---------------- 内部 ----------------

    private class Video(val id: String, val name: String) {
        var remarks: String = ""
        var poster: String = ""
        var year: String = ""
        var area: String = ""
        var genre: String = ""
        var blurb: String = ""
        val plays = ArrayList<String>()
    }

    private fun fetchList(host: String, typeId: Int?, keyword: String, page: Int): List<Video> {
        val url = buildString {
            append(host).append("/api.php/provide/vod/?ac=detail&pg=").append(page + 1)
            if (typeId != null) append("&t=").append(typeId)
            if (keyword.isNotEmpty()) append("&wd=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        val root = JSONObject(Net.getString(url))
        val list = root.optJSONArray("list") ?: return emptyList()

        val out = ArrayList<Video>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val name = o.optString("vod_name").trim()
            val play = o.optString("vod_play_url")
            if (name.isEmpty() || !play.contains(".m3u8")) continue

            val id = "v:" + name
            val video = Video(id, name)
            video.remarks = o.optString("vod_remarks")
            video.poster = o.optString("vod_pic")
            video.year = o.optString("vod_year")
            video.area = o.optString("vod_area")
            video.genre = o.optString("vod_class")
            video.blurb = stripHtml(o.optString("vod_blurb").ifEmpty { o.optString("vod_content") })
            video.plays.add(play)
            out.add(video)
            library[id] = video
            if (out.size >= PAGE_SIZE) break
        }
        return out
    }

    private fun toMovie(v: Video): Movie {
        val episodeCount = runCatching {
            v.plays.maxOf { play -> play.split("#").count { it.contains('$') } }
        }.getOrDefault(1).coerceAtLeast(1)

        return Movie(
            id = v.id,
            title = v.name,
            category = categories.firstOrNull { it != "推荐" } ?: "推荐",
            year = v.year,
            region = v.area,
            genres = v.genre,
            score = "",
            status = if (v.remarks.isEmpty()) "共 $episodeCount 集" else v.remarks,
            poster = v.poster,
            backdrop = v.poster,
            synopsis = v.blurb,
            episodeCount = episodeCount,
            durationMillis = 0L
        )
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
            .take(300)
}
