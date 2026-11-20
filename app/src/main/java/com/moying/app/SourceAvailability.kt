package com.moying.app

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

enum class SourceAvailability { AVAILABLE, EMPTY, UNSUPPORTED, FAILED, LOCAL }

data class SourceCheckResult(
    val sourceId: String,
    val status: SourceAvailability,
    val latencyMillis: Long,
    val detail: String,
    val checkedAtMillis: Long,
)

interface SourceAvailabilityRepository {
    /** 输入：待检测片源；输出：一次真实请求的兼容性与耗时，不切换片源或播放视频。 */
    suspend fun checkSource(source: TvBoxSource): SourceCheckResult
}

/** 只检测接口/目录，不请求影片、海报或媒体分片，不写入业务缓存。 */
internal object SourceAvailabilityProbe {
    private object Limits {
        const val WORKERS = 3
        const val WORKER_IDLE_SECONDS = 30L
        const val QUEUED_REQUESTS = SourceImport.MAX_SOURCES
        const val TOTAL_MILLIS = 8_000L
        const val SEGMENT_MILLIS = 3_000
        const val BODY_BYTES = SourceImport.MAX_BYTES
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_REDIRECTS = 3
        const val FIRST_PAGE = 1
        const val ZERO = 0L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) Moying/SourceCheck"
        val PAGE_PARAMETERS = setOf("ac", "pg", "wd", "t", "ids", "h")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }

    // 固定线程和有界队列：系统 DNS 偶发不响应中断时也不会不断创建后台线程。
    private val workers = ThreadPoolExecutor(
        Limits.WORKERS, Limits.WORKERS, Limits.WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
        ArrayBlockingQueue(Limits.QUEUED_REQUESTS),
        { task -> Thread(task, "moying-source-check").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private class ProbeFailure(val status: SourceAvailability, val detail: String) : IOException()

    private class Request(val deadline: Long) {
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<HttpURLConnection?>(null)

        fun check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("片源检测已取消")
            if (SystemClock.elapsedRealtime() >= deadline) throw SocketTimeoutException()
        }

        fun timeout(): Int {
            check()
            return minOf(Limits.SEGMENT_MILLIS.toLong(), deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
        }

        fun own(connection: HttpURLConnection) {
            active.set(connection)
            if (cancelled.get()) { connection.disconnect(); throw CancellationException("片源检测已取消") }
        }

        fun release(connection: HttpURLConnection) {
            active.compareAndSet(connection, null)
            connection.disconnect()
        }

        fun cancel() {
            cancelled.set(true)
            active.getAndSet(null)?.disconnect()
        }
    }

    /** 输入：独立片源配置；输出：有界检测结果。调用方取消会断开连接，结果不覆盖取消状态。 */
    suspend fun check(source: TvBoxSource): SourceCheckResult {
        val started = SystemClock.elapsedRealtime()
        if (source.id == "local") return result(source, started, SourceAvailability.LOCAL, "本地片库无需联网检测")
        if (source.type !in listOf(0, 1) || source.format !in listOf("cms", "m3u") || !SourceImport.validUrl(source.api)) {
            return result(source, started, SourceAvailability.UNSUPPORTED, "当前版本不支持此片源格式")
        }
        return try {
            withTimeout(Limits.TOTAL_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request(started + Limits.TOTAL_MILLIS)
                    try {
                        val future = workers.submit {
                            val checked = try {
                                val body = download(request, if (source.format == "m3u") source.api else cmsUrl(source))
                                request.check()
                                inspect(source, body, started)
                            } catch (cancelled: CancellationException) {
                                continuation.cancel(cancelled)
                                return@submit
                            } catch (error: Exception) {
                                failed(source, started, error)
                            }
                            if (continuation.isActive) continuation.resume(checked)
                        }
                        continuation.invokeOnCancellation {
                            request.cancel()
                            future.cancel(true)
                            workers.remove(future as Runnable)
                        }
                    } catch (_: RejectedExecutionException) {
                        continuation.resume(result(source, started, SourceAvailability.FAILED, "检测任务繁忙，请稍后重试"))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // 外部页面/任务取消必须继续向上传递；只有本次检测自己的超时转换为结果。
            currentCoroutineContext().ensureActive()
            result(source, started, SourceAvailability.FAILED, "检测超时（8秒）")
        }
    }

    /** 输入：CMS 地址；输出：保留认证参数的首页详情请求，绕过所有业务页缓存。 */
    private fun cmsUrl(source: TvBoxSource): String {
        val uri = Uri.parse(source.api)
        val builder = uri.buildUpon().clearQuery().fragment(null)
        uri.queryParameterNames.filterNot { it in Limits.PAGE_PARAMETERS }.forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        return builder.appendQueryParameter("ac", "detail").appendQueryParameter("pg", Limits.FIRST_PAGE.toString()).build().toString()
    }

    /** 输入：受取消/总时限约束的 HTTP(S) 请求；输出：最多 4MiB 文本，沿用系统 TLS 校验。 */
    private fun download(request: Request, address: String): String {
        var current = address
        for (redirect in 0..Limits.MAX_REDIRECTS) {
            request.check()
            if (!SourceImport.validUrl(current)) throw ProbeFailure(SourceAvailability.UNSUPPORTED, "片源地址或跳转协议不受支持")
            val connection = URL(current).openConnection() as HttpURLConnection
            request.own(connection)
            try {
                connection.connectTimeout = request.timeout()
                connection.readTimeout = request.timeout()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", Limits.USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, application/x-mpegURL, text/plain, */*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("Pragma", "no-cache")
                val code = connection.responseCode
                request.check()
                if (code in Limits.REDIRECT_CODES) {
                    if (redirect >= Limits.MAX_REDIRECTS) throw ProbeFailure(SourceAvailability.FAILED, "片源跳转次数过多")
                    val location = connection.getHeaderField("Location") ?: throw ProbeFailure(SourceAvailability.FAILED, "片源跳转缺少地址")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw ProbeFailure(SourceAvailability.FAILED, "接口请求失败（HTTP $code）")
                if (connection.contentLengthLong > Limits.BODY_BYTES) throw ProbeFailure(SourceAvailability.FAILED, "接口响应超过4MiB限制")
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(Limits.BUFFER_BYTES)
                    while (true) {
                        request.check()
                        connection.readTimeout = request.timeout()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > Limits.BODY_BYTES) throw ProbeFailure(SourceAvailability.FAILED, "接口响应超过4MiB限制")
                        output.write(buffer, 0, count)
                    }
                }
                request.check()
                val charsetName = connection.contentType.orEmpty().substringAfter("charset=", "UTF-8").substringBefore(';').trim().trim('"')
                return output.toByteArray().toString(runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8))
            } finally {
                request.release(connection)
            }
        }
        throw ProbeFailure(SourceAvailability.FAILED, "片源跳转次数过多")
    }

    /** 输入：实际接口文本；输出：当前解析器是否获得可用目录样本，不把 HTTP 200 等同于可播放。 */
    private fun inspect(source: TvBoxSource, body: String, started: Long): SourceCheckResult {
        if (body.isBlank()) return result(source, started, SourceAvailability.EMPTY, "接口返回空内容，未发现目录样本")
        if (source.format == "m3u") {
            if (body.lineSequence().any { it.trimStart().startsWith("#EXT-X-", true) }) {
                return result(source, started, SourceAvailability.UNSUPPORTED, "返回HLS视频清单，请按视频直链导入")
            }
            val rows = SourceImport.playlistRows(body, source.api).first
            return when {
                rows.isNotEmpty() -> result(source, started, SourceAvailability.AVAILABLE, "频道目录可读，含${rows.size}条直链；未逐一验证播放")
                body.trimStart('\uFEFF').trimStart().startsWith("#EXTM3U", true) -> result(source, started, SourceAvailability.EMPTY, "频道目录可读，暂无媒体条目")
                else -> result(source, started, SourceAvailability.UNSUPPORTED, "未返回兼容的M3U/TXT频道目录")
            }
        }
        val document = try { SourceImport.cmsDocument(body) } catch (_: Exception) {
            return result(source, started, SourceAvailability.UNSUPPORTED, "未返回兼容的AppleCMS JSON/XML目录")
        }
        if (document.rows.isEmpty()) return result(source, started, SourceAvailability.EMPTY, "接口目录可读，暂无影片样本")
        val playable = document.rows.count { SourceImport.clean(it.optString("vod_name")).isNotBlank() && SourceImport.cmsRoutes(it).isNotEmpty() }
        return if (playable > 0) result(source, started, SourceAvailability.AVAILABLE, "接口可读，首页含${playable}部直链影片；未逐片验证播放")
        else result(source, started, SourceAvailability.UNSUPPORTED, "目录可读，首页样本没有当前版本支持的播放直链")
    }

    private fun failed(source: TvBoxSource, started: Long, error: Exception): SourceCheckResult = when (error) {
        is ProbeFailure -> result(source, started, error.status, error.detail)
        is SocketTimeoutException -> result(source, started, SourceAvailability.FAILED, "网络连接或读取超时")
        is UnknownHostException -> result(source, started, SourceAvailability.FAILED, "片源域名解析失败")
        is SSLException -> result(source, started, SourceAvailability.FAILED, "安全连接或证书校验失败")
        is SecurityException -> result(source, started, SourceAvailability.FAILED, "网络请求被系统拒绝")
        is IOException -> result(source, started, SourceAvailability.FAILED, "片源网络连接失败")
        else -> result(source, started, SourceAvailability.FAILED, "检测未完成，请重试")
    }

    private fun result(source: TvBoxSource, started: Long, status: SourceAvailability, detail: String) = SourceCheckResult(
        source.id, status, (SystemClock.elapsedRealtime() - started).coerceAtLeast(Limits.ZERO), detail, System.currentTimeMillis(),
    )
}
