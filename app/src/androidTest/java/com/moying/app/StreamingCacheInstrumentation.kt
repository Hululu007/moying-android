package com.moying.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Real HTTP regression fixtures; every byte and playlist comes from this process's loopback server. */
object StreamingCacheRegression {
    fun run(instrumentation: Instrumentation, arguments: Bundle) {
        Suite(instrumentation).run(arguments.getString("streamCase"))
    }

    private class Suite(private val instrumentation: Instrumentation) {
        private var assertions = 0
        private var passed = 0
        private val failures = mutableListOf<String>()

        fun run(only: String?) {
            if (!only.isNullOrBlank() && only !in setOf("hls_window", "next_gate", "next_native_budget", "range_reuse", "range_cross_chunk", "restored_budget", "obsolete_requests")) {
                instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply {
                    putInt("failureCount", 1)
                    putString("stream", "Unknown streamCase\n")
                })
                return
            }
            case("hls_window", only) { context ->
                val releaseAudio = CountDownLatch(1)
                val audioStarted = CountDownLatch(1)
                val videoStarted = CountDownLatch(1)
                Origin { request, socket ->
                    when (request.path) {
                        "/master.m3u8" -> text(socket, """
                            #EXTM3U
                            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Default",DEFAULT=YES,AUTOSELECT=YES,URI="/audio.m3u8"
                            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="sound"
                            /video.m3u8
                        """.trimIndent())
                        "/audio.m3u8" -> text(socket, playlist("audio"))
                        "/video.m3u8" -> text(socket, playlist("video"))
                        else -> {
                            if (request.path == "/audio/0.ts") audioStarted.countDown()
                            if (request.path.startsWith("/video/")) videoStarted.countDown()
                            generated(request, socket, 8_192L,
                                gate = if (request.path == "/audio/0.ts") releaseAudio else null)
                        }
                    }
                }.use { origin ->
                    withProxy(context) { proxy ->
                        try {
                            val source = origin.url("/master.m3u8")
                            proxy.foregroundBuffering = false
                            proxy.setCurrent(source, 0L, ContentCacheKey.fallback(source))
                            val master = get(proxy.proxyUrl(source))
                            val playlist = String(master.bytes)
                            checkThat(master.code == 200 && playlist.contains("127.0.0.1"), "master playlist is served and rewritten by proxy")
                            val audio = checkNotNull(Regex("URI=\"([^\"]+)\"").find(playlist)?.groupValues?.get(1))
                            val video = playlist.lineSequence().first { it.startsWith("http://127.0.0.1") }
                            checkThat(get(audio).code == 200, "player selects the audio rendition")
                            checkThat(audioStarted.await(6, TimeUnit.SECONDS), "audio lookahead starts")
                            checkThat(get(video).code == 200, "player selects the video variant")
                            checkThat(videoStarted.await(3, TimeUnit.SECONDS), "video lookahead starts while first audio segment is still blocked")
                            releaseAudio.countDown()
                            Thread.sleep(1_300L)
                            val segmentRequests = origin.requests.filter { it.path.startsWith("/audio/") || it.path.startsWith("/video/") }
                            checkThat(segmentRequests.isNotEmpty(), "real HLS segment requests reached origin")
                            checkThat(segmentRequests.none { it.path.endsWith("/119.ts") }, "finite lookahead does not download the end of a twenty-minute fixture")
                            checkThat(segmentRequests.map { it.path }.distinct().size < 80, "lookahead remains bounded for both tracks")
                        } finally { releaseAudio.countDown() }
                    }
                }
            }

            case("next_gate", only) { context ->
                val holdCurrent = CountDownLatch(1)
                Origin { request, socket ->
                    generated(request, socket, if (request.path == "/current.bin") 32L * MiB else 4_096L,
                        gate = if (request.path == "/current.bin") holdCurrent else null)
                }.use { origin ->
                    withProxy(context) { proxy ->
                        try {
                            val current = origin.url("/current.bin")
                            val next = origin.url("/next.bin")
                            proxy.foregroundBuffering = false
                            proxy.setCurrent(current, 0L, ContentCacheKey.fallback(current))
                            await("current request has begun") { origin.requests.any { it.path == "/current.bin" && it.bytes.get() > 0L } }
                            proxy.nativeBufferedPosition = 0L
                            proxy.setNext(next)
                            val unsafe = get(proxy.proxyUrl(next))
                            checkThat(unsafe.code == 503 && unsafe.location == null, "unsafe next request gets retryable local response, without origin redirect")
                            checkThat(!proxy.canPreload(), "insufficient foreground buffer rejects native next preparation")
                            checkThat(origin.requests.none { it.path == "/next.bin" }, "unsafe next request does not contact origin")

                            proxy.nativeBufferedPosition = 120_000L
                            proxy.foregroundBuffering = true
                            val buffering = get(proxy.proxyUrl(next))
                            checkThat(buffering.code == 503 && buffering.location == null, "foreground buffering also rejects next HTTP request")
                            checkThat(!proxy.canPreload() && origin.requests.none { it.path == "/next.bin" }, "next remains gated despite a stale large native buffer value")

                            proxy.foregroundBuffering = false
                            checkThat(proxy.canPreload(), "safe current buffer enables next preparation")
                            val allowed = get(proxy.proxyUrl(next))
                            checkThat(allowed.code == 200 && allowed.bytes.contentEquals(pattern(0L, 4_096)), "next becomes readable after safety gate opens")
                        } finally { holdCurrent.countDown() }
                    }
                }
            }

            case("next_native_budget", only) { context ->
                Origin { request, socket ->
                    generated(request, socket, if (request.path == "/next-native.mp4") 32L * MiB else 4_096L)
                }.use { origin ->
                    withProxy(context) { proxy ->
                        val current = origin.url("/short-current.bin")
                        val next = origin.url("/next-native.mp4")
                        proxy.foregroundBuffering = false
                        proxy.setCurrent(current, 0L, ContentCacheKey.fallback(current))
                        checkThat(get(proxy.proxyUrl(current)).code == 200, "short current source is readable")
                        await("short current source is completely cached") { proxy.stats(current).complete }
                        checkThat(proxy.canPreload(), "complete foreground source permits native next connection")
                        proxy.setNext(next)

                        val connection = URL(proxy.proxyUrl(next)).openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 2_000
                            connection.readTimeout = 8_000
                            connection.instanceFollowRedirects = false
                            checkThat(connection.responseCode == 200, "native-style next long response is admitted")
                            val first = ByteArray(4_096)
                            val count = connection.inputStream.read(first)
                            checkThat(count > 0 && first.copyOf(count).contentEquals(pattern(0L, count)), "native-style client consumes a small correct prefix")
                            // Keep the response open without reading: foreground HTTP requests must not defeat speculation limits.
                            Thread.sleep(1_500L)
                            val stats = proxy.stats(next)
                            checkThat(stats.bytes > 0L, "next long response has real cached bytes")
                            checkThat(stats.bytes <= 8L * MiB, "idle native next connection retains a bounded prefix instead of filling all 32 MiB")
                            checkThat(!stats.complete, "unconsumed next movie has not been speculatively downloaded to completion")
                            checkThat(origin.requests.any { it.path == "/next-native.mp4" && it.bytes.get() > 0L }, "budget assertion follows real next-media HTTP traffic")
                        } finally { connection.disconnect() }
                    }
                }
            }

            case("range_reuse", only) { context ->
                val holdPrefix = CountDownLatch(1)
                Origin { request, socket ->
                    generated(request, socket, 32L * MiB, gate = if (request.start == 0L) holdPrefix else null)
                }.use { origin ->
                    withProxy(context) { proxy ->
                        try {
                            val source = origin.url("/large.mp4")
                            proxy.setCurrent(source, 0L, ContentCacheKey.fallback(source))
                            await("sequential prefix is waiting at origin") { origin.requests.any { it.start == 0L && it.bytes.get() > 0L } }
                            val offset = 24L * MiB + 123L
                            val length = 65_536
                            val first = get(proxy.proxyUrl(source), "bytes=$offset-${offset + length - 1}")
                            checkThat(first.code == 206, "far seek returns HTTP partial content")
                            checkThat(first.bytes.contentEquals(pattern(offset, length)), "far seek returns correct absolute-offset bytes")
                            val before = origin.requests.count { it.start > 0L }
                            checkThat(before > 0, "far seek made a real nonzero origin Range request")
                            val second = get(proxy.proxyUrl(source), "bytes=$offset-${offset + length - 1}")
                            checkThat(second.code == 206 && second.bytes.contentEquals(first.bytes), "repeated seek reads identical cached range")
                            checkThat(origin.requests.count { it.start > 0L } == before, "repeated seek does not issue another origin Range")
                            val overlap = get(proxy.proxyUrl(source), "bytes=${offset + 1_024}-${offset + 5_119}")
                            checkThat(overlap.bytes.contentEquals(pattern(offset + 1_024, 4_096)), "overlapping subrange reads the stored span")
                            checkThat(origin.requests.count { it.start > 0L } == before, "overlapping subrange also avoids origin")
                            await("seek cancels unrelated sequential prefix", 3_000L) {
                                origin.requests.filter { it.start == 0L }.all { it.done.get() }
                            }
                            val diskRanges = context.streamDirectory.listFiles().orEmpty().filter { it.name.contains("-range-") && it.extension == "bin" }
                            checkThat(diskRanges.any { it.length() >= length }, "range response is actually retained on disk")
                        } finally { holdPrefix.countDown() }
                    }
                }
            }

            case("range_cross_chunk", only) { context ->
                val holdPrefix = CountDownLatch(1)
                Origin { request, socket ->
                    generated(request, socket, 32L * MiB, gate = if (request.start == 0L) holdPrefix else null)
                }.use { origin ->
                    withProxy(context) { proxy ->
                        try {
                            val source = origin.url("/cross-boundary.mp4")
                            proxy.setCurrent(source, 0L, ContentCacheKey.fallback(source))
                            await("cross-boundary fixture prefix begins") { origin.requests.any { it.start == 0L && it.bytes.get() > 0L } }
                            val offset = 16L * MiB + 17L
                            val length = (MiB + MiB / 2).toInt()
                            val range = "bytes=$offset-${offset + length - 1L}"
                            val initial = get(proxy.proxyUrl(source), range)
                            checkThat(initial.code == 206 && initial.bytes.contentEquals(pattern(offset, length)), "first request crosses the one-MiB span boundary correctly")
                            val requests = origin.requests.count { it.start > 0L }
                            checkThat(requests >= 2, "large request is fetched using bounded origin ranges")
                            val repeated = get(proxy.proxyUrl(source), range)
                            checkThat(repeated.code == 206 && repeated.bytes.contentEquals(initial.bytes), "large repeated Range reads all cached spans")
                            checkThat(origin.requests.count { it.start > 0L } == requests, "cached span boundary never produces a reversed Range or another origin request")
                            val overlapStart = offset + MiB - 1_024L
                            val overlap = get(proxy.proxyUrl(source), "bytes=$overlapStart-${overlapStart + 4_095L}")
                            checkThat(overlap.code == 206 && overlap.bytes.contentEquals(pattern(overlapStart, 4_096)), "overlap across two stored spans remains byte exact")
                            checkThat(origin.requests.count { it.start > 0L } == requests, "cross-span overlap also avoids origin")
                        } finally { holdPrefix.countDown() }
                    }
                }
            }

            case("restored_budget", only) { context ->
                Origin { request, socket -> generated(request, socket, 4_096L) }.use { origin ->
                    val obsolete = origin.url("/obsolete.bin")
                    val oldFile = File(context.streamDirectory, "${ContentCacheKey.fallback(obsolete)}.bin")
                    // A sparse logical extent exercises the real one-GiB budget without writing a GiB of data.
                    RandomAccessFile(oldFile, "rw").use { it.setLength(1_024L * MiB + 65_536L) }
                    Properties().apply {
                        setProperty("length", oldFile.length().toString())
                        setProperty("mime", "application/octet-stream")
                        setProperty("complete", "true")
                        setProperty("manifest", "false")
                        setProperty("resolvedBase", obsolete)
                        setProperty("sourceUrl", obsolete)
                        setProperty("sourcePath", ContentCacheKey.sourcePath(obsolete))
                        setProperty("savedAt", System.currentTimeMillis().toString())
                    }.also { values -> File(context.streamDirectory, "${ContentCacheKey.fallback(obsolete)}.properties").outputStream().use { values.store(it, null) } }
                    withProxy(context) { proxy ->
                        proxy.proxyUrl(obsolete) // Register the old file without initializing its Entry.available.
                        val current = origin.url("/small.bin")
                        proxy.setCurrent(current, 0L, ContentCacheKey.fallback(current))
                        val response = get(proxy.proxyUrl(current))
                        checkThat(response.code == 200 && response.bytes.contentEquals(pattern(0L, 4_096)), "foreground read succeeds after restoring a full budget")
                        await("obsolete restored file is evicted", 4_000L) { !oldFile.exists() }
                        val counterField = StreamingCacheProxy::class.java.getDeclaredField("totalBytes").apply { isAccessible = true }
                        val counter = counterField.get(proxy) as AtomicLong
                        await("restored byte counter equals real cache files", 3_000L) {
                            val actual = context.streamDirectory.listFiles().orEmpty().filter { it.extension == "bin" }.sumOf { it.length() }
                            counter.get() == actual
                        }
                        checkThat(counter.get() < MiB, "deleting the restored sparse file releases its full logical byte count")
                    }
                }
            }

            case("obsolete_requests", only) { context ->
                val holdOld = CountDownLatch(1)
                Origin { request, socket ->
                    when (request.path) {
                        "/old.m3u8" -> text(socket, buildString {
                            append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n")
                            repeat(12) { index ->
                                append("#EXT-X-KEY:METHOD=AES-128,URI=\"/old/key$index.bin\"\n")
                                append("#EXTINF:10,\n/old/segment$index.ts\n")
                            }
                            append("#EXT-X-ENDLIST\n")
                        })
                        "/new.bin" -> generated(request, socket, 4_096L)
                        else -> generated(request, socket, 65_536L, gate = holdOld)
                    }
                }.use { origin ->
                    withProxy(context) { proxy ->
                        try {
                            val old = origin.url("/old.m3u8")
                            proxy.setCurrent(old, 0L, ContentCacheKey.fallback(old))
                            checkThat(get(proxy.proxyUrl(old)).code == 200, "old playlist is fetched through proxy")
                            await("old resource download is blocked") { origin.requests.any { it.path.startsWith("/old/") && it.bytes.get() > 0L } }
                            val replacement = origin.url("/new.bin")
                            val changedAt = SystemClock.elapsedRealtime()
                            proxy.setCurrent(replacement, 0L, ContentCacheKey.fallback(replacement))
                            val response = get(proxy.proxyUrl(replacement))
                            checkThat(response.code == 200 && response.bytes.contentEquals(pattern(0L, 4_096)), "replacement current source becomes readable while old bodies remain blocked")
                            checkThat(SystemClock.elapsedRealtime() - changedAt < 4_000L, "new source does not wait for old ten-second read timeout")
                            await("old source connections are disconnected", 3_000L) {
                                origin.requests.filter { it.path.startsWith("/old/") }.all { it.done.get() }
                            }
                            val oldCount = origin.requests.count { it.path.startsWith("/old/") }
                            Thread.sleep(350L)
                            checkThat(origin.requests.count { it.path.startsWith("/old/") } == oldCount, "queued obsolete tasks do not open more origin connections")
                        } finally { holdOld.countDown() }
                    }
                }
            }

            instrumentation.finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putInt("assertions", assertions)
                putInt("completedCases", passed)
                putInt("failureCount", failures.size)
                putString("stream", "\nStreaming cache regression: $assertions assertions, $passed cases, ${failures.size} failures.\n" + failures.joinToString("\n"))
            })
        }

        private fun case(name: String, only: String?, action: (CacheContext) -> Unit) {
            if (!only.isNullOrBlank() && name != only) return
            try {
                CacheContext(instrumentation.targetContext).use(action)
                passed++
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
            } catch (error: Throwable) {
                failures += "$name: ${Log.getStackTraceString(error)}"
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FAIL: $name\n") })
            }
        }

        private fun checkThat(condition: Boolean, message: String) {
            assertions++
            if (!condition) throw AssertionError(message)
        }

        private fun await(message: String, timeoutMillis: Long = 6_000L, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeoutMillis
            while (SystemClock.elapsedRealtime() < end) {
                if (condition()) { assertions++; return }
                Thread.sleep(25L)
            }
            checkThat(condition(), message)
        }
    }

    private class CacheContext(base: Context) : ContextWrapper(base), Closeable {
        private val parent = base.cacheDir.canonicalFile
        private val isolated = File(parent, "stream-regression-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        private val isolatedFiles = File(isolated, "files").apply { check(mkdirs()) }
        val streamDirectory = File(isolatedFiles, "video-stream-cache").apply { check(mkdirs()) }
        init {
            // These suites exercise the enabled-prefetch contract, independent
            // of the real user's defaults (next prefetch is off by default).
            PlaybackNetworkPreferences.write(this, false)
            PlaybackNetworkPreferences.writeNextEpisode(this, true)
        }
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = isolated
        override fun getFilesDir(): File = isolatedFiles
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${isolated.name}-$name"
            synchronized(preferenceNames) { preferenceNames += isolatedName }
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        override fun close() {
            synchronized(preferenceNames) { preferenceNames.forEach { baseContext.deleteSharedPreferences(it) } }
            check(isolated.canonicalFile.parentFile == parent && isolated.name.startsWith("stream-regression-"))
            check(isolated.deleteRecursively()) { "Cannot remove isolated streaming fixture cache" }
        }
    }

    private inline fun withProxy(context: Context, action: (StreamingCacheProxy) -> Unit) {
        val proxy = StreamingCacheProxy(context)
        try { action(proxy) } finally {
            proxy.close()
            // Await only the isolated test proxy's workers before its directory is removed.
            for (name in listOf("downloads", "jobs", "clients", "acceptor", "control")) {
                runCatching {
                    val field = StreamingCacheProxy::class.java.getDeclaredField(name).apply { isAccessible = true }
                    (field.get(proxy) as? ExecutorService)?.awaitTermination(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private data class Reply(val code: Int, val bytes: ByteArray, val location: String?)
    private fun get(url: String, range: String? = null): Reply {
        check(URL(url).host == "127.0.0.1") { "Fixture may only request loopback" }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            if (range != null) connection.setRequestProperty("Range", range)
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = input?.use { it.readBytes() } ?: byteArrayOf()
            check(data.size <= 2 * MiB) { "Unexpected fixture response size" }
            Reply(status, data, connection.getHeaderField("Location"))
        } finally { connection.disconnect() }
    }

    private class Request(val method: String, val path: String, val range: String?) {
        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        val end = range?.substringAfter('-', "")?.toLongOrNull()
        val bytes = AtomicLong()
        val done = AtomicBoolean(false)
    }

    private class Origin(private val handler: (Request, Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        val requests = CopyOnWriteArrayList<Request>()
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets += socket
                workers += Thread({
                    var request: Request? = null
                    try {
                        socket.use {
                            it.soTimeout = 2_000
                            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val first = reader.readLine()?.split(' ') ?: return@use
                            check(first.size >= 2)
                            var range: String? = null
                            var headerBytes = 0
                            while (true) {
                                val header = reader.readLine() ?: break
                                headerBytes += header.length
                                check(headerBytes < 16 * 1_024)
                                if (header.isEmpty()) break
                                if (header.substringBefore(':').equals("Range", true)) range = header.substringAfter(':').trim()
                            }
                            request = Request(first[0], first[1].substringBefore('?'), range)
                            requests += request!!
                            handler(request!!, it)
                        }
                    } catch (_: IOException) { /* Proxy cancellation is an expected fixture outcome. */ }
                    catch (error: Throwable) { errors += error }
                    finally { request?.done?.set(true); sockets -= socket }
                }, "Moying-StreamFixture").apply { isDaemon = true; start() }
            }
        }, "Moying-StreamFixtureAccept").apply { isDaemon = true; start() }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            acceptor.join(2_000L)
            workers.forEach { it.join(2_000L) }
            check(!acceptor.isAlive && workers.none { it.isAlive }) { "Loopback fixture workers did not stop" }
            check(errors.isEmpty()) { "Loopback fixture failed: ${errors.joinToString { Log.getStackTraceString(it) }}" }
        }
    }

    private fun text(socket: Socket, value: String) {
        val data = (value.trimEnd() + "\n").toByteArray(Charsets.UTF_8)
        headers(socket, 200, "application/vnd.apple.mpegurl", data.size.toLong())
        socket.getOutputStream().apply { write(data); flush() }
    }

    private fun generated(request: Request, socket: Socket, total: Long, gate: CountDownLatch? = null) {
        val start = request.start
        val end = min(request.end ?: (total - 1L), total - 1L)
        if (start >= total || end < start) {
            headers(socket, 416, "application/octet-stream", 0L, "bytes */$total")
            return
        }
        headers(socket, if (request.range == null) 200 else 206, "application/octet-stream", end - start + 1L,
            if (request.range != null) "bytes $start-$end/$total" else null)
        if (request.method == "HEAD") return
        val output = socket.getOutputStream()
        var position = start
        if (gate != null && position <= end) {
            val count = min(64L, end - position + 1L).toInt()
            output.write(pattern(position, count)); output.flush()
            position += count; request.bytes.addAndGet(count.toLong())
            // Observe FIN while the response body is paused, so cancellation can be asserted without releasing it.
            socket.soTimeout = 100
            while (gate.count > 0L) {
                try { if (socket.getInputStream().read() < 0) return }
                catch (_: SocketTimeoutException) { }
            }
        }
        while (position <= end) {
            val count = min(16_384L, end - position + 1L).toInt()
            output.write(pattern(position, count))
            request.bytes.addAndGet(count.toLong())
            position += count
        }
        output.flush()
    }

    private fun headers(socket: Socket, status: Int, mime: String, length: Long, contentRange: String? = null) {
        val header = buildString {
            append("HTTP/1.1 $status Fixture\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); flush() }
    }

    private fun playlist(track: String) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        repeat(120) { append("#EXTINF:10,\n/$track/$it.ts\n") }
        append("#EXT-X-ENDLIST\n")
    }

    private fun pattern(start: Long, count: Int) = ByteArray(count) { index ->
        val absolute = start + index
        (absolute xor (absolute ushr 7) xor (absolute ushr 17)).toByte()
    }
    private const val MiB = 1_024L * 1_024L
}
