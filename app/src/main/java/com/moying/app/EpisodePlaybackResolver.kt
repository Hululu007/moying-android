package com.moying.app

import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal class PlaybackResolutionException(message: String) : IOException(message)

/** 播放、缓存或片头识别需要媒体时才请求 JSON 接口。解析请求头与媒体请求头独立。 */
internal suspend fun resolvePlaybackEpisode(episode: Episode): Episode {
    val resolver = episode.resolver ?: return episode
    try {
        val endpoint = EpisodePlaybackResolver.endpoint(resolver.urlTemplate, episode.url)
        return SourceRequests.run(EpisodePlaybackResolver.TimeoutMillis) {
            EpisodePlaybackResolver.resolve(endpoint, resolver, episode)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PlaybackResolutionException) {
        throw error
    } catch (_: SocketTimeoutException) {
        throw PlaybackResolutionException("解析地址超时，请重试或换源")
    } catch (_: Exception) {
        throw PlaybackResolutionException("暂时无法获取播放地址，请重试或换源")
    }
}

private object EpisodePlaybackResolver {
    const val TimeoutMillis = 12_000L
    private const val MaxResponseBytes = 256 * 1024
    private const val MaxRedirects = 3
    private const val MaxJsonDepth = 24
    private const val Token = "{url}"
    private const val Marker = "MOYING_EPISODE_URL"
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun endpoint(template: String, episodeUrl: String): URL {
        val markerIndex = template.indexOf(Token)
        val declared = template.replace(Token, Marker)
        val probe = URI(declared)
        if (episodeUrl.isBlank() || markerIndex < 0 || markerIndex != template.lastIndexOf(Token) ||
            !SourceImport.validUrl(declared) || Marker in probe.rawAuthority.orEmpty() || probe.rawFragment != null) {
            throw PlaybackResolutionException("此线路的解析地址配置无效")
        }
        val encoded = URLEncoder.encode(episodeUrl, Charsets.UTF_8.name()).replace("+", "%20").replace("*", "%2A")
        return httpUrl(template.replace(Token, encoded))
            ?: throw PlaybackResolutionException("此线路的解析地址配置无效")
    }

    fun resolve(endpoint: URL, resolver: PlaybackResolver, episode: Episode): Episode {
        val response = fetch(endpoint, SourceImport.mergePlaybackHeaders(resolver.requestHeaders))
        val root = parseObject(response)
        val path = resolver.urlField.split('.')
        val container = path.dropLast(1).fold(root as JSONObject?) { parent, name -> parent?.optJSONObject(name) }
        val rawUrl = container?.opt(path.last()) as? String
        val useDefaultFallback = resolver.urlField == "url" && rawUrl.isNullOrBlank()
        val responseData = if (useDefaultFallback) root.optJSONObject("data") else container
        val resolved = if (useDefaultFallback) responseData?.opt("url") as? String else rawUrl
        if (requiresFurtherParsing(root) || (responseData !== root && responseData?.let(::requiresFurtherParsing) == true)) {
            throw PlaybackResolutionException("此线路要求继续网页解析，请换源")
        }
        val url = resolved?.trim()?.takeIf { httpUrl(it) != null }
            ?: throw PlaybackResolutionException("解析接口未返回可播放地址，请重试或换源")
        val headers = SourceImport.mergePlaybackHeaders(
            episode.headers,
            SourceImport.playbackHeaders(root.opt("header")),
            SourceImport.playbackHeaders(root.opt("headers")),
            SourceImport.playbackHeaders(responseData?.takeIf { it !== root }?.opt("header")),
            SourceImport.playbackHeaders(responseData?.takeIf { it !== root }?.opt("headers")),
        )
        return episode.copy(url = url, headers = headers, resolver = null)
    }

    private fun requiresFurtherParsing(value: JSONObject): Boolean =
        listOf("parse", "jx").any { name -> value.optString(name) in setOf("1", "true") }

    private fun httpUrl(value: String): URL? = value.takeIf(SourceImport::validUrl)?.let(::URL)

    private fun fetch(endpoint: URL, headers: Map<String, String>): String {
        var target = endpoint
        val deadline = SystemClock.elapsedRealtime() + TimeoutMillis
        repeat(MaxRedirects + 1) { count ->
            SourceRequests.checkActive()
            val connection = target.openConnection() as HttpURLConnection
            try {
                SourceRequests.own(connection)
                connection.connectTimeout = SourceRequests.timeout(5_000, deadline)
                connection.readTimeout = SourceRequests.timeout(5_000, deadline)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (sameOrigin(endpoint, target)) headers.forEach(connection::setRequestProperty)
                val status = connection.responseCode
                if (status in redirectCodes) {
                    val location = connection.getHeaderField("Location")
                        ?: throw PlaybackResolutionException("解析接口重定向缺少地址")
                    val next = httpUrl(URL(target, location).toString())
                        ?: throw PlaybackResolutionException("解析接口重定向地址无效")
                    if (count == MaxRedirects || (target.protocol == "https" && next.protocol != "https")) {
                        throw PlaybackResolutionException("解析接口重定向不可用，请换源")
                    }
                    target = next
                } else {
                    if (status !in 200..299) throw PlaybackResolutionException("解析接口暂不可用（HTTP $status）")
                    if (connection.contentLengthLong > MaxResponseBytes) invalidJson()
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    connection.inputStream.use { input ->
                        while (true) {
                            SourceRequests.checkActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (bytes.size() + read > MaxResponseBytes) invalidJson()
                            bytes.write(buffer, 0, read)
                        }
                    }
                    return bytes.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
                }
            } finally {
                SourceRequests.release(connection)
                connection.disconnect()
            }
        }
        throw PlaybackResolutionException("解析接口重定向不可用，请换源")
    }

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol == second.protocol && first.host.equals(second.host, true) &&
            first.port.takeIf { it != -1 }.let { it ?: first.defaultPort } ==
            second.port.takeIf { it != -1 }.let { it ?: second.defaultPort }

    private fun parseObject(body: String): JSONObject = try {
        JsonReader(StringReader(body)).use { reader ->
            val result = readJson(reader, 0) as? JSONObject ?: invalidJson()
            if (reader.peek() != JsonToken.END_DOCUMENT) invalidJson()
            result
        }
    } catch (_: Exception) {
        invalidJson()
    }

    private fun readJson(reader: JsonReader, depth: Int): Any {
        if (depth > MaxJsonDepth) invalidJson()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) put(reader.nextName(), readJson(reader, depth + 1))
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readJson(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> invalidJson()
        }
    }

    private fun invalidJson(): Nothing = throw PlaybackResolutionException("解析接口返回的 JSON 无效，请换源")
}
