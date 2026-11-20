package com.moying.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

/**
 * 网络工具：普通请求 + DoH 兜底。
 * 有些图床域名会被 DNS 污染成 28.0.0.25，直连失败时用 DoH 查真实 IP 再握 SNI。
 */
object Net {

    private const val UA = "Mozilla/5.0 (Linux; Android 13)"

    fun get(url: String, connectMs: Int = 6000, readMs: Int = 12000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", UA)
        c.instanceFollowRedirects = true
        c.inputStream.use { return readAll(it) }
    }

    fun getString(url: String): String = String(get(url), Charsets.UTF_8)

    /** 图片专用：直连失败再走 DoH */
    fun getSmart(url: String, timeoutMs: Int = 6000): ByteArray? {
        runCatching { return get(url, timeoutMs, timeoutMs) }
        runCatching { return viaDoh(url, timeoutMs) }
        return null
    }

    fun loadBitmap(url: String): Bitmap? {
        val data = getSmart(url) ?: return null
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
    }

    /** 输入：海报 URL；输出：有大小/时间上限的编码数据；取消时主动断开图片连接。 */
    internal suspend fun downloadPoster(url: String): ByteArray? = PosterDownload.fetch(url)

    // ---------------- DoH ----------------

    private fun resolve(host: String, timeoutMs: Int): List<String> {
        val endpoints = listOf(
            "https://223.5.5.5/resolve?name=$host&type=A",
            "https://1.1.1.1/dns-query?name=$host&type=A"
        )
        for (ep in endpoints) {
            runCatching {
                val c = URL(ep).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/dns-json")
                c.setRequestProperty("User-Agent", UA)
                val body = c.inputStream.use { String(readAll(it), Charsets.UTF_8) }
                c.disconnect()
                val answer: JSONArray = JSONObject(body).optJSONArray("Answer") ?: return@runCatching
                val ips = ArrayList<String>()
                for (i in 0 until answer.length()) {
                    val item = answer.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        val data = item.optString("data")
                        if (data.matches(Regex("[0-9.]+"))) ips.add(data)
                    }
                }
                if (ips.isNotEmpty()) return ips
            }
        }
        return emptyList()
    }

    private fun viaDoh(urlStr: String, timeoutMs: Int): ByteArray {
        val u = URL(urlStr)
        val host = u.host
        val https = u.protocol.equals("https", true)
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        val path = if (u.file.isNullOrEmpty()) "/" else u.file

        val ips = resolve(host, timeoutMs)
        require(ips.isNotEmpty()) { "DoH 无结果: $host" }

        var raw: Socket? = null
        for (ip in ips) {
            runCatching {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                raw = s
            }
            if (raw != null) break
        }
        val plain = raw ?: error("连不上 $host")

        val sock: Socket = if (https) {
            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = f.createSocket(plain, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = Collections.singletonList(SNIHostName(host))
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl
        } else plain

        val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $UA\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }

        val all = sock.getInputStream().use { readAll(it) }
        runCatching { sock.close() }

        var headerEnd = -1
        for (i in 0 until all.size - 3) {
            if (all[i] == 13.toByte() && all[i + 1] == 10.toByte() &&
                all[i + 2] == 13.toByte() && all[i + 3] == 10.toByte()) {
                headerEnd = i + 4
                break
            }
        }
        if (headerEnd < 0) return all
        val headers = String(all, 0, headerEnd, Charsets.UTF_8).lowercase()
        val body = all.copyOfRange(headerEnd, all.size)
        if (headers.contains("transfer-encoding: chunked")) return dechunk(body)
        val idx = headers.indexOf("content-length:")
        if (idx >= 0) {
            val end = headers.indexOf('\r', idx)
            runCatching {
                val len = headers.substring(idx + 15, end).trim().toInt()
                if (len <= body.size) return body.copyOfRange(0, len)
            }
        }
        return body
    }

    private fun dechunk(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 0
        runCatching {
            while (p < input.size) {
                var lineEnd = p
                while (lineEnd + 1 < input.size &&
                    !(input[lineEnd] == 13.toByte() && input[lineEnd + 1] == 10.toByte())) lineEnd++
                var sizeLine = String(input, p, lineEnd - p, Charsets.UTF_8).trim()
                val semi = sizeLine.indexOf(';')
                if (semi > 0) sizeLine = sizeLine.substring(0, semi)
                val size = sizeLine.toInt(16)
                p = lineEnd + 2
                if (size == 0) break
                out.write(input, p, minOf(size, input.size - p))
                p += size + 2
            }
        }
        return out.toByteArray()
    }

    private fun readAll(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }
}

/** 仅供海报使用；普通片源 API 的连接、超时和 DoH 行为保持独立。 */
private object PosterDownload {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val TOTAL_MILLIS = 6500L
    private const val UA = "Mozilla/5.0 (Linux; Android 13)"
    private val closingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class DnsEntry(val ip: String?, val expires: Long)
    private val dnsCache = LinkedHashMap<String, DnsEntry>()
    private class Rejected(message: String) : IOException(message)

    private class Call {
        private val lock = Any()
        @Volatile private var cancelled = false
        private var closeActive: (() -> Unit)? = null
        private val deadline = SystemClock.elapsedRealtime() + TOTAL_MILLIS

        fun check() {
            if (cancelled) throw CancellationException("海报已离屏")
            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("海报请求超时")
        }

        fun timeout(max: Int): Int {
            check()
            return (deadline - SystemClock.elapsedRealtime()).coerceIn(1, max.toLong()).toInt()
        }

        fun own(close: () -> Unit) {
            val alreadyCancelled = synchronized(lock) {
                if (cancelled) true else {
                    closeActive = close
                    false
                }
            }
            if (alreadyCancelled) {
                runCatching { close() }
                throw CancellationException("海报已离屏")
            }
        }

        fun release() { synchronized(lock) { closeActive = null } }

        // 取消回调可能来自 UI 线程：这里只改标志，实际 socket/disconnect 在 IO 线程。
        fun cancel() {
            val close = synchronized(lock) {
                cancelled = true
                closeActive.also { closeActive = null }
            }
            if (close != null) closingScope.launch { runCatching { close() } }
        }
    }

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = Call()
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val result = try {
                    direct(call, URL(url), MAX_BYTES)
                } catch (rejected: Rejected) {
                    throw rejected
                } catch (_: IOException) {
                    call.check()
                    // 图床 DNS 异常才可能需要兜底；只查一个提供方、最多连接一个 IP。
                    viaDoh(call, URL(url))
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun direct(call: Call, initial: URL, byteLimit: Int, dnsQuery: Boolean = false): ByteArray {
        var url = initial
        repeat(3) { redirect ->
            call.check()
            if (url.protocol != "https" && url.protocol != "http") throw Rejected("图片协议不支持")
            val connection = url.openConnection() as HttpURLConnection
            call.own { connection.disconnect() }
            try {
                connection.connectTimeout = call.timeout(if (dnsQuery) 1000 else 1800)
                connection.readTimeout = call.timeout(if (dnsQuery) 1000 else 1800)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", UA)
                connection.setRequestProperty("Accept", if (dnsQuery) "application/dns-json" else "image/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirect == 2) throw Rejected("图片重定向过多")
                    val next = connection.getHeaderField("Location") ?: throw Rejected("图片重定向无地址")
                    url = URL(url, next)
                } else {
                    if (status !in 200..299) throw Rejected("图片 HTTP $status")
                    if (connection.contentLengthLong > byteLimit) throw Rejected("图片超出下载上限")
                    return connection.inputStream.use { readBounded(it, byteLimit, call) }
                }
            } finally {
                call.release()
                connection.disconnect()
            }
        }
        throw Rejected("图片重定向失败")
    }

    private fun resolve(call: Call, host: String): String? {
        synchronized(dnsCache) {
            dnsCache[host]?.takeIf { it.expires > SystemClock.elapsedRealtime() }?.let { return it.ip }
        }
        val ip = try {
            val query = URL("https://223.5.5.5/resolve?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=A")
            val body = direct(call, query, 32 * 1024, dnsQuery = true)
            val answers = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("Answer")
            (0 until (answers?.length() ?: 0)).firstNotNullOfOrNull { index ->
                val answer = answers!!.getJSONObject(index)
                val address = answer.optString("data")
                address.takeIf { answer.optInt("type") == 1 && validIpv4(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        synchronized(dnsCache) {
            if (dnsCache.size >= 32) dnsCache.remove(dnsCache.keys.first())
            dnsCache[host] = DnsEntry(ip, SystemClock.elapsedRealtime() + if (ip == null) 30_000L else 300_000L)
        }
        return ip
    }

    private fun validIpv4(ip: String): Boolean = ip.split('.').let { parts ->
        parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 }
    }

    private fun viaDoh(call: Call, url: URL): ByteArray {
        if (url.protocol != "https" && url.protocol != "http") throw Rejected("图片协议不支持")
        val host = url.host
        val ip = resolve(call, host) ?: throw IOException("海报 DoH 无结果")
        val secure = url.protocol == "https"
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        val plain = Socket()
        call.own { plain.close() }
        var socket: Socket = plain
        try {
            plain.soTimeout = call.timeout(1600)
            plain.connect(InetSocketAddress(ip, port), call.timeout(1600))
            if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                socket = ssl
                call.own { ssl.close() }
                ssl.soTimeout = call.timeout(1600)
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = Collections.singletonList(SNIHostName(host))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
            }
            call.check()
            val path = url.file.takeIf { !it.isNullOrEmpty() } ?: "/"
            val hostHeader = if (url.port > 0) "$host:$port" else host
            val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: $UA\r\nAccept: image/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val input = socket.getInputStream().buffered(16 * 1024)
            val header = readHeader(input, call)
            val status = header.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            if (status == null || status !in 200..299) throw Rejected("图片兜底 HTTP $status")
            val headers = header.lowercase()
            val length = Regex("(?:^|\\r\\n)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toLongOrNull()
            if (length != null && length > MAX_BYTES) throw Rejected("图片超出下载上限")
            return if (headers.contains("transfer-encoding: chunked")) readChunks(input, call)
            else readBounded(input, MAX_BYTES, call, length)
        } finally {
            call.release()
            runCatching { socket.close() }
            if (socket !== plain) runCatching { plain.close() }
        }
    }

    private fun readHeader(input: InputStream, call: Call): String {
        val bytes = ByteArrayOutputStream()
        var match = 0
        val ending = intArrayOf(13, 10, 13, 10)
        while (bytes.size() < MAX_HEADER_BYTES) {
            call.check()
            val value = input.read()
            if (value < 0) throw IOException("海报响应头不完整")
            bytes.write(value)
            match = if (value == ending[match]) match + 1 else if (value == 13) 1 else 0
            if (match == 4) return bytes.toString("US-ASCII")
        }
        throw Rejected("海报响应头超出上限")
    }

    private fun readChunks(input: InputStream, call: Call): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = StringBuilder()
            while (line.length < 128) {
                call.check()
                val value = input.read()
                if (value < 0) throw IOException("海报分块头不完整")
                if (value == 10) break
                line.append(value.toChar())
            }
            if (line.length >= 128) throw Rejected("海报分块头超出上限")
            val size = line.toString().trim().substringBefore(';').toLongOrNull(16) ?: throw IOException("海报分块无效")
            if (size == 0L) return output.toByteArray()
            if (size < 0 || size > MAX_BYTES - output.size()) throw Rejected("图片超出下载上限")
            output.write(readBounded(input, size.toInt(), call, size))
            if (input.read() != 13 || input.read() != 10) throw IOException("海报分块结尾无效")
        }
    }

    private fun readBounded(input: InputStream, limit: Int, call: Call, expected: Long? = null): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (expected == null || output.size().toLong() < expected) {
            call.check()
            val amount = if (expected == null) buffer.size else minOf(buffer.size.toLong(), expected - output.size()).toInt()
            val count = input.read(buffer, 0, amount)
            if (count < 0) break
            if (output.size() + count > limit) throw Rejected("图片超出下载上限")
            output.write(buffer, 0, count)
        }
        if (expected != null && output.size().toLong() != expected) throw IOException("海报响应不完整")
        return output.toByteArray()
    }
}
