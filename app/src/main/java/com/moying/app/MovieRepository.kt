package com.moying.app

/** 一集：序号、显示名、可播放地址（m3u8） */
data class Episode(val number: Int, val name: String, val url: String)

/** 一条真实播放线路；剧集顺序只在该线路内部有意义。 */
data class PlaybackRoute(val id: String, val name: String, val episodes: List<Episode>)

/** 数据入口；网络实现可替换此接口，页面无需感知 Mock。 */
interface MovieRepository {
    val categories: List<String>
    val history: List<WatchHistory>

    /** 输入：从零开始的页码、分类、关键词；输出：当前页和是否还有下一页。 */
    fun getMovies(page: Int, category: String, query: String = ""): MoviePage
    fun getMovie(id: String): Movie?

    /** 输入：影片 id；输出：这一部片的剧集列表（含播放地址）。Mock 实现返回空。 */
    fun getEpisodes(id: String): List<Episode> = emptyList()
    /** 输入影片 id；输出来源实际返回的直链线路，默认包装已有剧集。 */
    fun getPlaybackRoutes(id: String): List<PlaybackRoute> = getEpisodes(id).takeIf { it.isNotEmpty() }
        ?.let { listOf(PlaybackRoute("default", "默认线路", it)) }.orEmpty()
    /** 输入影片 id；输出其他站点中确认同名且含直链的影片，调用方在 IO 线程执行。 */
    fun findAlternatives(movieId: String): List<Movie> = emptyList()
    /** 输入：片名和 HTTP(S) 媒体地址；输出：新增影片，地址无效时返回 null。 */
    fun addSource(title: String, url: String): Movie? = null

    /** 输入：观看记录；输出：无。相同影片覆盖旧进度并置顶。 */
    fun saveHistory(record: WatchHistory)
    fun deleteHistory(movieId: String)
    fun clearHistory()
}
