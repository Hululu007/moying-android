package com.moying.app

import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class SourceImportResult(val addedSources: Int, val addedMovies: Int, val skipped: Int, val message: String)

internal data class SourceImportBatch(
    val sources: MutableList<TvBoxSource> = mutableListOf(),
    val movies: MutableList<JSONObject> = mutableListOf(),
    var skipped: Int = 0,
    val notes: MutableList<String> = mutableListOf(),
)

internal data class SourceCmsCategory(val id: String, val name: String, val parentId: String? = null)

internal data class SourceCmsDocument(
    val rows: List<JSONObject>,
    val categories: List<SourceCmsCategory>,
    val pageCount: Int?,
    val nativeFacets: Map<String, List<CatalogFacet>> = emptyMap(),
) {
    val classes: Map<String, String> get() = categories.associate { it.name to it.id }

    fun observedCategories(): List<SourceCmsCategory> = rows.mapNotNull { row ->
        cmsCategory(row.optString("type_id"), row.optString("type_name"), row.optString("type_pid"))
    }.distinctBy { it.id }
}

private fun cmsCategory(id: String, name: String, parentId: String): SourceCmsCategory? {
    val key = id.trim().takeIf { it.isNotBlank() && it != "0" && !it.equals("null", true) } ?: return null
    val label = SourceImport.clean(name).takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
    val parent = parentId.trim().takeIf { it.isNotBlank() && it != key && it != "0" && !it.equals("null", true) }
    return SourceCmsCategory(key, label, parent)
}

/** 文本与订阅共用的有限导入器；JSON 解析配置只记录 HTTP 请求模板。 */
internal object SourceImport {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_MOVIES = 6000
    const val MAX_SOURCES = 120
    private const val MAX_REQUESTS = 8
    private const val MAX_TOTAL_BYTES = 12 * 1024 * 1024
    private const val RESOLVER_TOKEN = "{url}"
    private const val URL_PLACEHOLDER = "moying-episode-address-placeholder"
    private val mediaExtensions = listOf(".m3u8", ".mp4", ".m4v", ".webm", ".mov", ".mp3", ".aac", ".ts")
    private val resolverUrlParameter = Regex("[?&]url=$", RegexOption.IGNORE_CASE)
    private val resolverResponsePath = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){0,4}")

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    fun validUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme?.lowercase() in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }
    }.getOrDefault(false)

    fun mediaUrl(value: String): Boolean = validUrl(value) && runCatching {
        val path = URI(value).path.orEmpty().lowercase()
        mediaExtensions.any { path.endsWith(it) }
    }.getOrDefault(false)

    fun playbackHeaders(value: Any?): Map<String, String> {
        val fields = when (value) {
            is JSONObject -> value
            is String -> value.takeIf { it.length <= 16_384 }?.let { runCatching { JSONObject(it) }.getOrNull() }
            else -> null
        } ?: return emptyMap()
        val values = fields.keys().asSequence().mapNotNull { name ->
            (fields.opt(name) as? String)?.let { name to it }
        }.toMap()
        return MediaRequestHeaders.normalize(values)
    }

    fun mergePlaybackHeaders(vararg layers: Map<String, String>): Map<String, String> = buildMap {
        layers.forEach { putAll(MediaRequestHeaders.normalize(it)) }
    }

    fun declaredPlaybackHeaders(row: JSONObject): Map<String, String> = mergePlaybackHeaders(
        playbackHeaders(row.opt("vod_play_header")),
        playbackHeaders(row.opt("header")),
        playbackHeaders(row.opt("headers")),
        playbackHeaders(row.opt("playbackHeaders")),
    )

    private fun resolverTemplate(value: String): String? {
        val template = value.trim()
        if (template.length > 2048 || template.indexOf(RESOLVER_TOKEN) < 0 ||
            template.indexOf(RESOLVER_TOKEN) != template.lastIndexOf(RESOLVER_TOKEN)
        ) return null
        val sample = template.replace(RESOLVER_TOKEN, URL_PLACEHOLDER)
        if (!validUrl(sample)) return null
        val uri = URI(sample)
        return template.takeIf { uri.fragment == null && !uri.rawAuthority.orEmpty().contains(URL_PLACEHOLDER) }
    }

    fun readPlaybackResolver(row: JSONObject?): PlaybackResolver? {
        if (row == null) return null
        val template = resolverTemplate(row.optString("urlTemplate")) ?: return null
        val urlField = row.optString("urlField", "url").takeIf { it.length <= 100 && resolverResponsePath.matches(it) } ?: return null
        return PlaybackResolver(template, urlField, playbackHeaders(row.opt("requestHeaders")))
    }

    fun playbackResolverJson(resolver: PlaybackResolver): JSONObject = JSONObject()
        .put("urlTemplate", resolver.urlTemplate)
        .put("urlField", resolver.urlField)
        .apply {
            if (resolver.requestHeaders.isNotEmpty()) put("requestHeaders", JSONObject(resolver.requestHeaders))
        }

    fun readFlagResolvers(row: JSONObject?): Map<String, PlaybackResolver> {
        if (row == null) return emptyMap()
        return row.keys().asSequence().take(MAX_SOURCES).mapNotNull { flag ->
            readPlaybackResolver(row.optJSONObject(flag))?.let { flag to it }
        }.toMap()
    }

    private fun tvBoxFlagResolvers(parses: JSONArray?): Map<String, PlaybackResolver> = buildMap {
        if (parses == null) return@buildMap
        for (index in 0 until minOf(parses.length(), MAX_SOURCES)) {
            val row = parses.optJSONObject(index) ?: continue
            if (row.optInt("type", -1) != 1) continue
            val ext = row.optJSONObject("ext") ?: continue
            val flags = ext.optJSONArray("flag") ?: continue
            val address = row.optString("url").trim()
            val template = resolverTemplate(if (resolverUrlParameter.containsMatchIn(address)) address + RESOLVER_TOKEN else address) ?: continue
            val resolver = PlaybackResolver(template, requestHeaders = mergePlaybackHeaders(
                playbackHeaders(ext.opt("header")), playbackHeaders(row.opt("header")),
            ))
            for (flagIndex in 0 until minOf(flags.length(), MAX_SOURCES)) {
                val flag = (flags.opt(flagIndex) as? String)?.trim().orEmpty()
                if (size < MAX_SOURCES && flag.isNotEmpty() && flag.length <= 120) putIfAbsent(flag, resolver)
            }
        }
    }

    fun clean(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    /** 按 URL 保留稳定去重键；CMS 翻页参数不改变一个站点的身份。 */
    fun sourceKey(api: String): String = runCatching {
        val uri = Uri.parse(api)
        val builder = uri.buildUpon().clearQuery().fragment(null)
        uri.queryParameterNames.filterNot { it in setOf("ac", "pg", "wd", "t", "ids", "h") }.sorted().forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        builder.build().toString().trimEnd('/')
    }.getOrDefault(api.trim().trimEnd('/'))

    /** 输入 HTTP(S) 文本地址，限制单次大小、重定向次数及总耗时。调用方必须在 IO 线程执行。 */
    fun download(address: String): String {
        require(validUrl(address)) { "只支持 HTTP(S) 订阅地址" }
        var current = address
        val deadline = SystemClock.elapsedRealtime() + 18_000L
        repeat(5) {
            SourceRequests.timeout(6000, deadline)
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                SourceRequests.own(connection)
                connection.connectTimeout = SourceRequests.timeout(6000, deadline)
                connection.readTimeout = SourceRequests.timeout(8000, deadline)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Moying/1.3")
                val code = connection.responseCode
                SourceRequests.timeout(8000, deadline)
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("订阅重定向缺少地址")
                    current = URL(URL(current), location).toString()
                    require(validUrl(current)) { "不支持此重定向协议" }
                } else {
                    require(code in 200..299) { "订阅请求失败（HTTP $code）" }
                    require(connection.contentLengthLong <= MAX_BYTES) { "订阅文件超过 4 MiB" }
                    val result = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            connection.readTimeout = SourceRequests.timeout(8000, deadline)
                            val length = input.read(buffer)
                            if (length < 0) break
                            require(result.size() + length <= MAX_BYTES) { "订阅文件超过 4 MiB" }
                            result.write(buffer, 0, length)
                        }
                    }
                    SourceRequests.timeout(8000, deadline)
                    val charsetName = connection.contentType.orEmpty().substringAfter("charset=", "UTF-8").substringBefore(';').trim().trim('"')
                    return result.toByteArray().toString(runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8))
                }
            } finally { SourceRequests.release(connection); connection.disconnect() }
        }
        error("订阅重定向次数过多")
    }

    /** 输入粘贴的配置、列表或地址；输出已规范化内容和跳过原因，不在此处改动持久数据。 */
    fun parse(input: String, origin: String = ""): SourceImportBatch {
        val batch = SourceImportBatch()
        val visited = hashSetOf<String>()
        var totalBytes = 0
        var requestCount = 0
        val started = System.currentTimeMillis()
        fun visit(raw: String, from: String, depth: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("导入已取消")
            require(raw.toByteArray().size <= MAX_BYTES) { "导入内容超过 4 MiB" }
            val text = raw.trim().trimStart('\uFEFF').trim()
            if (text.isBlank()) return
            totalBytes += text.toByteArray().size
            require(totalBytes <= MAX_TOTAL_BYTES) { "订阅总内容超过 12 MiB" }
            if (validUrl(text)) {
                if (mediaUrl(text)) {
                    val isM3u8 = runCatching { URI(text).path.endsWith(".m3u8", true) }.getOrDefault(false)
                    // .m3u8 也可能装着频道目录：有界下载后识别内容，HLS 永远保留原 URL。
                    if (isM3u8 && depth < 3 && requestCount < MAX_REQUESTS && System.currentTimeMillis() - started <= 45_000L && visited.add(text)) {
                        requestCount++
                        val body = runCatching { download(text) }.getOrElse {
                            if (it is InterruptedException) throw it
                            batch.notes += "未能读取 m3u8 内容，已按单条媒体地址导入"
                            ""
                        }
                        val lines = body.trimStart('\uFEFF').lineSequence().map { it.trim() }
                        val hasHlsTags = lines.any { it.startsWith("#EXT-X-", true) }
                        val hasChannels = body.trimStart('\uFEFF').trimStart().startsWith("#EXTM3U", true) && body.lineSequence().any {
                            Regex("^#EXTINF\\s*:\\s*-1(?:\\.0)?(?:\\s|,)", RegexOption.IGNORE_CASE).containsMatchIn(it.trim())
                        }
                        if (!hasHlsTags && hasChannels) {
                            visit(body, text, depth + 1)
                            return
                        }
                    }
                    batch.movies += directMovie(titleFromUrl(text), text, from)
                    return
                }
                if (!visited.add(text)) { batch.skipped++; return }
                if (depth >= 3 || requestCount >= MAX_REQUESTS || System.currentTimeMillis() - started > 45_000L) {
                    batch.skipped++; batch.notes += "已达到嵌套订阅或请求上限"; return
                }
                requestCount++
                runCatching { visit(download(text), text, depth + 1) }.onFailure { batch.skipped++; batch.notes += it.message.orEmpty() }
                return
            }
            if (text.startsWith("{")) {
                val root = JSONObject(text)
                val sites = root.optJSONArray("sites")
                if (sites != null) {
                    val flagResolvers = tvBoxFlagResolvers(root.optJSONArray("parses"))
                    for (i in 0 until sites.length()) {
                        val row = sites.optJSONObject(i)
                        if (row == null) { batch.skipped++; continue }
                        val type = row.optInt("type", -1)
                        val api = row.optString("api")
                        if (type !in listOf(0, 1) || !validUrl(api) || batch.sources.size >= MAX_SOURCES) { batch.skipped++; continue }
                        val format = row.optString("format").takeIf { it == "m3u" } ?: "cms"
                        batch.sources += TvBoxSource(
                            "import:" + hash(sourceKey(api)), clean(row.optString("name")).ifBlank { URI(api).host }, api, type, from, format,
                            playbackHeaders = declaredPlaybackHeaders(row),
                            playbackResolver = if (format == "cms") readPlaybackResolver(row.optJSONObject("playbackResolver")) else null,
                            playbackResolversByFlag = if (format == "cms") flagResolvers else emptyMap(),
                        )
                    }
                }
                // 多仓订阅仅递归有限个配置；不能执行的 Spider 站点会明确计入跳过数。
                val warehouses = root.optJSONArray("urls")
                if (warehouses != null) for (i in 0 until minOf(warehouses.length(), MAX_SOURCES)) {
                    val url = warehouses.optJSONObject(i)?.optString("url").orEmpty()
                    if (validUrl(url)) visit(url, from, depth) else batch.skipped++
                }
                val list = root.optJSONArray("movies") ?: root.optJSONArray("list")
                if (list != null) {
                    if (isCmsRoot(root) && validUrl(from)) {
                        batch.sources += TvBoxSource("import:" + hash(sourceKey(from)), URI(from).host, sourceKey(from), 1, from)
                    }
                    appendRows(list, batch, from)
                } else if (sites == null && warehouses == null) {
                    normalizeMovie(root, origin = from)?.let { batch.movies += it } ?: run { batch.skipped++ }
                }
                return
            }
            if (text.startsWith("[")) { appendRows(JSONArray(text), batch, from); return }
            if (text.startsWith("<")) {
                val cms = cmsDocument(text)
                if (validUrl(from)) batch.sources += TvBoxSource("import:" + hash(sourceKey(from)), URI(from).host, sourceKey(from), 0, from)
                cms.rows.forEach { row -> normalizeMovie(row, origin = from)?.let { batch.movies += it } ?: run { batch.skipped++ } }
                return
            }
            // HLS 清单里的 TS 分片或多码率变体必须作为同一媒体播放，不能拆成影片。
            if (text.lineSequence().any { it.startsWith("#EXT-X-") }) {
                if (validUrl(from)) batch.movies += directMovie(titleFromUrl(from), from, from)
                else { batch.skipped++; batch.notes += "HLS 清单请导入原始 m3u8 地址，不能导入无地址的分片文本" }
                return
            }
            val playlist = playlistRows(text, from)
            val capacity = MAX_MOVIES - batch.movies.size.coerceAtMost(MAX_MOVIES)
            batch.movies.addAll(playlist.first.take(capacity))
            batch.skipped += (playlist.first.size - capacity).coerceAtLeast(0)
            batch.skipped += playlist.second
            if (playlist.first.isEmpty() && playlist.second == 0) { batch.skipped++; batch.notes += "未识别到兼容片源或播放地址" }
        }
        visit(input, origin, 0)
        if (batch.movies.size > MAX_MOVIES) {
            batch.skipped += batch.movies.size - MAX_MOVIES
            batch.movies.subList(MAX_MOVIES, batch.movies.size).clear()
        }
        if (batch.sources.size > MAX_SOURCES) {
            batch.skipped += batch.sources.size - MAX_SOURCES
            batch.sources.subList(MAX_SOURCES, batch.sources.size).clear()
        }
        return batch
    }

    private fun appendRows(rows: JSONArray, batch: SourceImportBatch, origin: String) {
        for (i in 0 until rows.length()) {
            if (batch.movies.size >= MAX_MOVIES) { batch.skipped += rows.length() - i; break }
            val value = rows.opt(i)
            val movie = when (value) {
                is JSONObject -> normalizeMovie(value, origin = origin)
                is String -> if (validUrl(value)) directMovie(titleFromUrl(value), value, origin) else null
                else -> null
            }
            if (movie == null) batch.skipped++ else batch.movies += movie
        }
    }

    fun titleFromUrl(url: String): String = runCatching { Uri.decode(URI(url).path.substringAfterLast('/')).substringBeforeLast('.') }
        .getOrDefault("").ifBlank { "导入媒体" }.take(120)

    fun directMovie(
        title: String, url: String, origin: String = "", poster: String = "", genre: String = "",
        headers: Map<String, String> = emptyMap(),
    ): JSONObject =
        JSONObject().put("id", "user:" + hash(url)).put("title", clean(title).ifBlank { "导入媒体" }).put("category", "电影")
            .put("poster", poster).put("genres", genre).put("credit", origin).put("status", "可直接播放")
            .put("episodes", episodesJson(listOf(Episode(1, "正片", url, headers))))

    /** 支持 EXTINF 频道名、台标、分组；TXT 只按第一个逗号切开，保留 URL 中的逗号。 */
    fun playlistRows(text: String, origin: String, headers: Map<String, String> = emptyMap()): Pair<List<JSONObject>, Int> {
        val rows = mutableListOf<JSONObject>()
        var skipped = 0
        var name = ""
        var poster = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfter(',', "").trim()
                poster = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
                group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
                continue
            }
            if (line.startsWith("#EXTGRP:", true)) {
                group = line.substringAfter(':').trim()
                continue
            }
            if (line.startsWith('#')) continue
            if (line.endsWith(",#genre#", true)) { group = line.substringBefore(','); continue }
            val delimiter = line.indexOf(',')
            val address = if (validUrl(line)) line else if (delimiter > 0) line.substring(delimiter + 1).trim() else line
            val relativeMedia = name.isNotBlank() && address.none { it.isWhitespace() } && mediaExtensions.any { address.substringBefore('?').endsWith(it, true) }
            val resolved = if (validUrl(address)) address else if (validUrl(origin) && relativeMedia) runCatching { URL(URL(origin), address).toString() }.getOrDefault("") else ""
            if (!validUrl(resolved) || address.contains("#genre#")) { skipped++; continue }
            val title = if (validUrl(line)) name.ifBlank { titleFromUrl(resolved) } else line.substringBefore(',').trim().ifBlank { name }
            if (rows.size < MAX_MOVIES) {
                val category = clean(group).ifBlank { "电影" }
                rows += directMovie(title, resolved, origin, poster, headers = headers).put("category", category)
            } else skipped++
            name = ""; poster = ""
        }
        return rows to skipped
    }

    /** 保留 CMS 直链和已经配置解析接口的 HTTP(S) 剧集。 */
    fun cmsRoutes(row: JSONObject, source: TvBoxSource? = null): List<PlaybackRoute> {
        val from = row.optString("vod_play_from").split("$$$")
        val headers = mergePlaybackHeaders(source?.playbackHeaders.orEmpty(), declaredPlaybackHeaders(row))
        return row.optString("vod_play_url").split("$$$").mapIndexedNotNull { index, raw ->
            val flag = from.getOrNull(index).orEmpty().trim()
            val configuredResolver = source?.playbackResolversByFlag?.get(flag) ?: source?.playbackResolver
            val episodes = raw.split('#').mapNotNull { part ->
                val cut = part.indexOf('$')
                val url = (if (cut >= 0) part.substring(cut + 1) else part).trim()
                val direct = mediaUrl(url)
                if (!direct && (configuredResolver == null || !validUrl(url))) return@mapNotNull null
                val name = if (cut > 0) clean(part.substring(0, cut)) else "正片"
                Episode(0, name, url, headers, if (direct) null else configuredResolver)
            }.distinctBy { it.url }.mapIndexed { i, episode -> episode.copy(number = i + 1) }
            if (episodes.isEmpty()) null else PlaybackRoute("route:$index", clean(flag).ifBlank { "线路 ${index + 1}" }, episodes)
        }.sortedByDescending { it.episodes.size }
    }

    fun readRoutes(row: JSONObject): List<PlaybackRoute> {
        val headers = declaredPlaybackHeaders(row)
        val resolver = declaredResolver(row)
        val array = row.optJSONArray("routes")
        val routes = if (array != null) (0 until array.length()).mapNotNull { i ->
            val route = array.optJSONObject(i) ?: return@mapNotNull null
            val episodes = readEpisodes(
                route.optJSONArray("episodes"),
                mergePlaybackHeaders(headers, declaredPlaybackHeaders(route)),
                declaredResolver(route) ?: resolver,
            )
            if (episodes.isEmpty()) null else PlaybackRoute(route.optString("id", "route:$i"), route.optString("name", "线路 ${i + 1}"), episodes)
        } else emptyList()
        return routes.ifEmpty {
            readEpisodes(row.optJSONArray("episodes"), headers, resolver).takeIf { it.isNotEmpty() }
                ?.let { listOf(PlaybackRoute("default", "默认线路", it)) }.orEmpty()
        }
    }

    private fun declaredResolver(row: JSONObject): PlaybackResolver? =
        readPlaybackResolver(row.optJSONObject("playbackResolver")) ?: readPlaybackResolver(row.optJSONObject("resolver"))

    fun readEpisodes(
        array: JSONArray?, headers: Map<String, String> = emptyMap(), resolver: PlaybackResolver? = null,
    ): List<Episode> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
        val track = array.optJSONObject(i) ?: return@mapNotNull null
        val url = track.optString("url")
        if (!validUrl(url)) return@mapNotNull null
        Episode(0, track.optString("name", "第 ${i + 1} 集"), url,
            mergePlaybackHeaders(headers, declaredPlaybackHeaders(track)), declaredResolver(track) ?: resolver)
    }.distinctBy { it.url }.mapIndexed { i, episode -> episode.copy(number = i + 1) }

    fun routesJson(routes: List<PlaybackRoute>): JSONArray = JSONArray().apply {
        routes.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("episodes", episodesJson(it.episodes))) }
    }
    fun episodesJson(episodes: List<Episode>): JSONArray = JSONArray().apply {
        episodes.forEach { episode ->
            put(JSONObject().put("name", episode.name).put("url", episode.url).apply {
                if (episode.headers.isNotEmpty()) put("headers", JSONObject(episode.headers))
                episode.resolver?.let { put("resolver", playbackResolverJson(it)) }
            })
        }
    }

    fun normalizeMovie(row: JSONObject, id: String = "", category: String = "电影", origin: String = "", source: TvBoxSource? = null): JSONObject? {
        val cms = row.has("vod_name") || row.has("vod_play_url")
        val title = clean(if (cms) row.optString("vod_name") else row.optString("title", row.optString("name")))
        val routes = if (cms) cmsRoutes(row, source) else readRoutes(row).ifEmpty {
            val url = row.optString("url").takeIf(::validUrl) ?: return null
            val episode = Episode(1, "正片", url, declaredPlaybackHeaders(row), declaredResolver(row))
            listOf(PlaybackRoute("default", "默认线路", listOf(episode)))
        }
        if (title.isBlank() || routes.isEmpty()) return null
        val default = routes.first().episodes
        val movieId = id.ifBlank { "user:" + hash(routes.flatMap { it.episodes }.map { it.url }.sorted().joinToString("\n")) }
        val poster = if (cms) row.optString("vod_pic") else row.optString("poster", row.optString("pic"))
        return JSONObject().put("id", movieId).put("title", title).put("category", row.optString("category", category))
            .put("year", row.optString(if (cms) "vod_year" else "year"))
            .put("region", clean(row.optString(if (cms) "vod_area" else "region")))
            .put("genres", clean(if (cms) row.optString("vod_class").ifBlank { row.optString("type_name") } else row.optString("genres")))
            .put("score", row.optString(if (cms) "vod_score" else "score").takeUnless { it == "0" || it == "0.0" }.orEmpty())
            .put("status", clean(row.optString(if (cms) "vod_remarks" else "status")).ifBlank { "共 ${default.size} 集" })
            .put("poster", poster).put("backdrop", row.optString("backdrop", poster))
            .put("synopsis", clean(if (cms) row.optString("vod_content").ifBlank { row.optString("vod_blurb") } else row.optString("synopsis")))
            .put("credit", row.optString("credit", origin)).put("license", row.optString("license"))
            .put("episodes", episodesJson(default)).put("routes", routesJson(routes))
    }

    /** 根据 CMS 字段识别 API，普通 {list:[{title,url}]} 不能误注册为采集站。 */
    private fun isCmsRoot(root: JSONObject): Boolean {
        val list = root.optJSONArray("list")
        if (list != null && (0 until list.length()).any { i ->
                list.optJSONObject(i)?.let { it.has("vod_name") && (it.has("vod_id") || it.has("vod_play_url")) } == true
            }) return true
        val classes = root.optJSONArray("class")
        if (classes != null && (0 until classes.length()).any { i ->
                classes.optJSONObject(i)?.let { it.has("type_id") && it.has("type_name") } == true
            }) return true
        return list?.length() == 0 && root.has("pagecount")
    }

    /** AppleCMS JSON 与标准 XML RSS 统一成同一结构；拒绝含外部实体的 XML。 */
    fun cmsDocument(text: String): SourceCmsDocument {
        val trimmed = text.trim().trimStart('\uFEFF')
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            require(isCmsRoot(root)) { "片源没有返回兼容的 AppleCMS 字段" }
            val list = root.optJSONArray("list") ?: JSONArray()
            val classes = root.optJSONArray("class") ?: JSONArray()
            val categories = (0 until classes.length()).mapNotNull { index ->
                val row = classes.optJSONObject(index) ?: return@mapNotNull null
                cmsCategory(row.optString("type_id"), row.optString("type_name"), row.optString("type_pid", row.optString("parent_id")))
            }.distinctBy { it.id }
            return SourceCmsDocument(
                (0 until list.length()).mapNotNull { list.optJSONObject(it) },
                categories,
                root.optInt("pagecount", -1).takeIf { it >= 0 },
                nativeFacets(root),
            )
        }
        require(!trimmed.contains("<!DOCTYPE", true) && !trimmed.contains("<!ENTITY", true)) { "不支持包含外部实体的 XML" }
        val parser = Xml.newPullParser()
        parser.setInput(trimmed.reader())
        val rows = mutableListOf<JSONObject>()
        val categories = mutableListOf<SourceCmsCategory>()
        val names = mapOf("id" to "vod_id", "tid" to "type_id", "type_id" to "type_id", "type_pid" to "type_pid",
            "name" to "vod_name", "type" to "type_name", "pic" to "vod_pic", "year" to "vod_year", "area" to "vod_area",
            "note" to "vod_remarks", "des" to "vod_content", "state" to "vod_remarks", "class" to "vod_class", "score" to "vod_score")
        var row: JSONObject? = null
        var currentTypeId = ""
        var currentParentId = ""
        var currentFlag = ""
        var value = StringBuilder()
        var routes = mutableListOf<Pair<String, String>>()
        var pageCount: Int? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    value = StringBuilder()
                    when (parser.name.lowercase()) {
                        "list" -> pageCount = parser.getAttributeValue(null, "pagecount")?.toIntOrNull()
                        "video", "vod" -> { row = JSONObject(); routes = mutableListOf() }
                        "ty" -> {
                            currentTypeId = parser.getAttributeValue(null, "id").orEmpty()
                            currentParentId = (parser.getAttributeValue(null, "pid")
                                ?: parser.getAttributeValue(null, "type_pid")).orEmpty()
                        }
                        "dd" -> currentFlag = parser.getAttributeValue(null, "flag").orEmpty()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> value.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    val content = value.toString().trim()
                    if (tag == "ty") cmsCategory(currentTypeId, content, currentParentId)?.let(categories::add)
                    if (tag == "dd") routes += currentFlag to content
                    names[tag]?.let { if (row != null) row?.put(it, content) }
                    if (tag == "video" || tag == "vod") {
                        row?.put("vod_play_from", routes.joinToString("$$$") { it.first })?.put("vod_play_url", routes.joinToString("$$$") { it.second })?.let { rows += it }
                        row = null
                    }
                    value = StringBuilder()
                }
            }
            parser.next()
        }
        require(rows.isNotEmpty() || categories.isNotEmpty() || pageCount != null) { "无法识别 AppleCMS XML" }
        return SourceCmsDocument(rows, categories.distinctBy { it.id }, pageCount)
    }

    private val filterLabels = mapOf(
        "class" to "题材", "area" to "地区", "year" to "年代", "lang" to "语言",
        "sort" to "排序", "sort_direction" to "排序", "isend" to "状态",
    )

    /** 只读取站点明确声明的筛选项，普通 CMS 不会凭空出现服务端筛选。 */
    private fun nativeFacets(root: JSONObject): Map<String, List<CatalogFacet>> {
        val filters = root.optJSONObject("filters") ?: root.optJSONObject("filter") ?: return emptyMap()
        return filters.keys().asSequence().mapNotNull { typeId ->
            val definitions = filters.optJSONArray(typeId) ?: return@mapNotNull null
            val facets = (0 until definitions.length()).mapNotNull definitions@{ index ->
                val definition = definitions.optJSONObject(index) ?: return@definitions null
                val key = definition.optString("key")
                val label = filterLabels[key] ?: return@definitions null
                val values = definition.optJSONArray("value") ?: definition.optJSONArray("options") ?: return@definitions null
                val options = (0 until values.length()).mapNotNull values@{ optionIndex ->
                    val item = values.optJSONObject(optionIndex) ?: return@values null
                    val value = item.optString("v", item.optString("value")).trim()
                    if (value.isBlank() || value.length > 128 || value.equals("null", true)) return@values null
                    val name = clean(item.optString("n", item.optString("label"))).ifBlank { value }
                    CatalogOption(value, name)
                }.distinctBy { it.value }.take(120)
                if (options.isEmpty()) null else CatalogFacet(key, label, options)
            }.distinctBy { it.key }
            if (facets.isEmpty()) null else typeId to facets
        }.toMap()
    }
}
