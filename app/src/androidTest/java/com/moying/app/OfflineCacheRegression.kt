package com.moying.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Generated loopback bytes only; no device connectivity, user preferences or real cache changes. */
internal object OfflineCacheRegression {
    fun run(instrumentation: Instrumentation, arguments: Bundle) = Suite(instrumentation).run(arguments.getString("offlineCase"))

    private class Suite(private val instrumentation: Instrumentation) {
        private var assertions = 0
        private var completed = 0
        private val failures = mutableListOf<String>()

        fun run(only: String?) {
            case("policy", only) { context ->
                val allowed = AtomicBoolean(true)
                Origin(slow = true).use { origin ->
                    withProxy(context, allowed) { proxy ->
                        OfflineCacheManager(context, proxy, { allowed.get() }, { true }).use { queue ->
                            val movie = movie("policy")
                            val source = origin.url("/large.mp4")
                            val key = ContentCacheKey.root(movie.id, "route", 1)
                            queue.enqueue(listOf(OfflineRequest(movie, "route", "Route", 1, Episode(1, "One", source), source)))
                            await("download starts on allowed policy") {
                                queue.state.recordFor(key)?.status == OfflineStatus.DOWNLOADING && origin.bytesSent.get() >= 131_072L
                            }
                            allowed.set(false)
                            await("in-flight download becomes WAITING after policy switches", 5_000L) {
                                queue.state.recordFor(key)?.status == OfflineStatus.WAITING
                            }
                            val stopped = origin.bytesSent.get()
                            val requests = origin.requests.size
                            Thread.sleep(500L)
                            checkThat(origin.bytesSent.get() - stopped <= 65_536L, "origin body stops promptly instead of completing on disallowed network")
                            checkThat(origin.requests.size == requests, "waiting policy opens no new network requests")
                            checkThat(queue.state.recordFor(key)?.message == "等待 Wi-Fi", "waiting state explains Wi-Fi policy")
                            checkThat(queue.state.recordFor(key)?.bytes?.let { it > 0L && it < Origin.LargeSize } == true, "download prefix is retained")
                            allowed.set(true)
                            await("allowed policy resumes to completion", 15_000L) {
                                queue.state.recordFor(key)?.status == OfflineStatus.COMPLETED
                            }
                            checkThat(origin.requests.any { it.path == "/large.mp4" && it.start > 0L }, "resumed HTTP uses a real nonzero Range")
                            origin.online.set(false)
                            checkThat(get(proxy.proxyUrl(source, key)).contentEquals(pattern(0, Origin.LargeSize.toInt())), "completed file is readable without origin")
                        }
                    }
                }
            }
            case("hls_progress", only) { context ->
                val audioPlaylist = "/progress-audio.m3u8"
                val audioSegment = "/progress-audio.ts"
                val lastVideoSegment = "/progress-last.ts"
                Origin(heldResponses = setOf(audioPlaylist, audioSegment, lastVideoSegment)).use { origin ->
                    withProxy(context) { proxy ->
                        val source = origin.url("/progress.m3u8")
                        val key = ContentCacheKey.root("hls-progress", "route", 1)
                        val oneSegment = 1f / 3f
                        val twoSegments = 2f / 3f
                        var sawIncompletePlan = false
                        var paused = false
                        var deadline = SystemClock.elapsedRealtime() + 8_000L
                        val firstRun = proxy.downloadOffline(source, key, onProgress = { progress ->
                            if (!sawIncompletePlan && progress.bytes >= Origin.SmallSize &&
                                origin.requests.any { it.path == audioPlaylist }
                            ) {
                                checkThat(progress.fraction == null, "a cached video segment alone cannot determine progress while the selected audio playlist is pending")
                                sawIncompletePlan = true
                                origin.releaseResponse(audioPlaylist)
                            }
                            if (sawIncompletePlan && progress.fraction == oneSegment) paused = true
                        }, cancelled = { paused || SystemClock.elapsedRealtime() >= deadline })
                        checkThat(firstRun.cancelled && sawIncompletePlan && paused, "pausing preserves the first of three required segments")

                        val resumedSamples = mutableListOf<OfflineDownloadProgress>()
                        deadline = SystemClock.elapsedRealtime() + 8_000L
                        val resumed = proxy.downloadOffline(source, key, onProgress = { progress ->
                            resumedSamples += progress
                            if (progress.fraction == oneSegment) origin.releaseResponse(audioSegment)
                            if (progress.fraction == twoSegments) origin.releaseResponse(lastVideoSegment)
                        }, cancelled = { SystemClock.elapsedRealtime() >= deadline })
                        checkThat(resumed.success, "HLS resumes and completes after the remaining segments are downloaded")
                        val determinate = resumedSamples.mapNotNull { it.fraction }
                        checkThat(determinate.firstOrNull() == oneSegment, "resumed progress starts at one completed segment")
                        checkThat(twoSegments in determinate, "progress reaches two of three completed segments before the final segment")
                        checkThat(determinate.zipWithNext().all { (before, after) -> before <= after }, "discovering segment lengths never decreases progress")
                        checkThat(determinate.lastOrNull() == 1f && determinate.dropLast(1).all { it < 1f }, "only the complete offline tree reports 100 percent")
                        checkThat(origin.requests.none { it.path == "/progress-unused.m3u8" }, "unselected variants are excluded from the download")
                    }
                }
            }
            case("unknown_size_progress", only) { context ->
                val path = "/unknown-size.mp4"
                val finishBody = CountDownLatch(1)
                Origin(unknownSizeFinish = finishBody).use { origin ->
                    withProxy(context) { proxy ->
                        val source = origin.url(path)
                        val key = ContentCacheKey.root("unknown-size-progress", "route", 1)
                        val samples = mutableListOf<OfflineDownloadProgress>()
                        val deadline = SystemClock.elapsedRealtime() + 8_000L
                        val result = proxy.downloadOffline(source, key, onProgress = { progress ->
                            samples += progress
                            if (progress.bytes > 0L) finishBody.countDown()
                        }, cancelled = { SystemClock.elapsedRealtime() >= deadline })
                        checkThat(result.success, "unknown-length file completes when the response ends")
                        val incomplete = samples.dropLast(1)
                        checkThat(incomplete.any { it.bytes > 0L } && incomplete.all { it.fraction == null }, "unknown-length file reports bytes without a percentage")
                        checkThat(samples.lastOrNull()?.let { it.fraction == 1f && it.bytes == Origin.SmallSize } == true, "completed file reports 100 percent and all bytes")
                    }
                }
            }
            case("full_body_sources", only) { context ->
                Origin(ignoreRangeFor = setOf("/full.mp4", "/common.ts")).use { origin ->
                    withProxy(context) { proxy ->
                        val full = origin.url("/full.mp4")
                        val fullKey = ContentCacheKey.root("full-body", "route", 1)
                        // Playback's Range probe rejects the origin before the user chooses to download.
                        proxy.setCurrent(full, 0L, fullKey)
                        await("passive playback falls back to the origin") { proxy.proxyUrl(full, fullKey) == full }
                        checkThat(get(proxy.proxyUrl(full, fullKey)).contentEquals(pattern(0, Origin.SmallSize.toInt())),
                            "passive playback can still read a 200-only source")
                        val deadline = SystemClock.elapsedRealtime() + 8_000L
                        val result = proxy.downloadOffline(full, fullKey, cancelled = { SystemClock.elapsedRealtime() >= deadline })
                        checkThat(result.success, "200-only MP4 downloads after passive playback: ${result.error}")

                        val hls = origin.url("/a.m3u8")
                        val hlsKey = ContentCacheKey.root("full-segment", "route", 1)
                        val hlsDeadline = SystemClock.elapsedRealtime() + 8_000L
                        val hlsResult = proxy.downloadOffline(hls, hlsKey, cancelled = { SystemClock.elapsedRealtime() >= hlsDeadline })
                        checkThat(hlsResult.success, "HLS downloads a segment returned with HTTP 200: ${hlsResult.error}")
                        origin.online.set(false)
                        val attempts = origin.requests.size
                        checkThat(get(proxy.proxyUrl(full, fullKey)).contentEquals(pattern(0, Origin.SmallSize.toInt())),
                            "full-body MP4 is read offline")
                        val playlist = String(get(proxy.proxyUrl(hls, hlsKey)))
                        val segment = playlist.lineSequence().first { it.startsWith("http://127.0.0.1") }
                        checkThat(get(segment).contentEquals(pattern(0, Origin.SmallSize.toInt())), "the complete HLS segment is available offline")
                        checkThat(origin.requests.size == attempts, "offline playback made no origin requests")
                    }
                }
            }
            case("full_body_after_resume", only) { context ->
                Origin(truncateThenFull = "/restart.mp4").use { origin ->
                    withProxy(context) { proxy ->
                        val url = origin.url("/restart.mp4")
                        val key = ContentCacheKey.root("200-after-prefix", "route", 1)
                        val deadline = SystemClock.elapsedRealtime() + 12_000L
                        val result = proxy.downloadOffline(url, key, cancelled = { SystemClock.elapsedRealtime() >= deadline })
                        checkThat(result.success, "retries a full-body response without appending to the prefix: ${result.error}")
                        checkThat(origin.requests.any { it.path == "/restart.mp4" && it.start > 0L }, "tries to resume from the saved prefix")
                        origin.online.set(false)
                        checkThat(get(proxy.proxyUrl(url, key)).contentEquals(pattern(0, Origin.SmallSize.toInt())), "no duplicated prefix after restart")
                        checkThat(proxy.offlineBytes(url, key) == Origin.SmallSize, "storage accounting reflects the final file")
                    }
                }
            }
            case("segment_http_failure", only) { context ->
                Origin(deniedPath = "/common.ts").use { origin ->
                    withProxy(context) { proxy ->
                        val url = origin.url("/a.m3u8")
                        val key = ContentCacheKey.root("denied-segment", "route", 1)
                        val deadline = SystemClock.elapsedRealtime() + 8_000L
                        val result = proxy.downloadOffline(url, key, cancelled = { SystemClock.elapsedRealtime() >= deadline })
                        checkThat(!result.success && !result.cancelled && result.error?.contains("403") == true,
                            "HLS segment rejection reports HTTP 403 before idle timeout: ${result.error}")
                        checkThat(!proxy.offlineReady(url, key), "rejected segment never marks the download complete")
                    }
                }
            }
            case("queue_survives_storage_failure", only) { context ->
                Origin().use { origin ->
                    val bad = movie("blocked-storage")
                    val good = movie("working-storage")
                    val badKey = ContentCacheKey.root(bad.id, "route", 1)
                    val goodKey = ContentCacheKey.root(good.id, "route", 1)
                    val blockedFile = File(context.filesDir, "video-stream-cache/$badKey.bin")
                    check(blockedFile.mkdirs())
                    File(blockedFile, "fixture").writeText("Blocked cache file")
                    check(blockedFile.length() > 0L)
                    val badUrl = origin.url("/blocked.mp4")
                    val goodUrl = origin.url("/working.mp4")
                    withProxy(context) { proxy ->
                        OfflineCacheManager(context, proxy, { true }, { true }).use { queue ->
                            queue.enqueue(listOf(
                                OfflineRequest(bad, "route", "Route", 1, Episode(1, "One", badUrl), badUrl),
                                OfflineRequest(good, "route", "Route", 1, Episode(1, "One", goodUrl), goodUrl),
                            ))
                            await("blocked cache entry becomes failed") { queue.state.recordFor(badKey)?.status == OfflineStatus.FAILED }
                            checkThat(queue.state.recordFor(badKey)?.message?.contains("读写") == true, "blocked file reports a storage error")
                            await("queue proceeds to the next episode") { queue.state.recordFor(goodKey)?.status == OfflineStatus.COMPLETED }
                            checkThat(origin.requests.none { it.path == "/blocked.mp4" }, "storage failure never starts the bad origin request")
                        }
                    }
                }
            }

            case("shared_url", only) { context ->
                Origin().use { origin ->
                    val source = origin.url("/same.mp4")
                    val keyA = ContentCacheKey.root("A", "route", 1)
                    val keyB = ContentCacheKey.root("B", "route", 1)
                    withProxy(context) { proxy ->
                        checkThat(proxy.downloadOffline(source, keyA).success, "first logical record completes")
                        checkThat(proxy.downloadOffline(source, keyB).success, "same-URL second logical record completes")
                    }
                    // Reopen actual metadata, emulating the queue JSON's logical owners.
                    withProxy(context) { proxy ->
                        proxy.restoreOfflineOwners(listOf(source to keyA, source to keyB))
                        origin.online.set(false)
                        val requests = origin.requests.size
                        proxy.deleteOffline(source, keyA)
                        checkThat(proxy.offlineReady(source, keyB), "deleting A preserves B readiness after cold restoration")
                        checkThat(get(proxy.proxyUrl(source, keyB)).contentEquals(pattern(0, Origin.SmallSize.toInt())), "B retains all shared bytes offline")
                        checkThat(origin.requests.size == requests, "remaining record never falls back to origin")
                        proxy.deleteOffline(source, keyB)
                        await("last owner releases physical media files") { cacheFiles(context).none { it.extension == "bin" } }
                    }
                }
            }
            case("shared_hls", only) { context ->
                Origin().use { origin ->
                    val sourceA = origin.url("/a.m3u8")
                    val sourceB = origin.url("/b.m3u8")
                    val keyA = ContentCacheKey.root("hls-A", "route", 1)
                    val keyB = ContentCacheKey.root("hls-B", "route", 1)
                    withProxy(context) { proxy ->
                        checkThat(proxy.downloadOffline(sourceA, keyA).success, "first HLS root completes")
                        checkThat(proxy.downloadOffline(sourceB, keyB).success, "second HLS root reuses shared dependencies")
                    }
                    withProxy(context) { proxy ->
                        proxy.restoreOfflineOwners(listOf(sourceB to keyB, sourceA to keyA))
                        origin.online.set(false)
                        val requests = origin.requests.size
                        proxy.deleteOffline(sourceA, keyA)
                        val playlist = String(get(proxy.proxyUrl(sourceB, keyB)))
                        val keyUrl = Regex("URI=\"([^\"]+)\"").find(playlist)?.groupValues?.get(1)
                        val segmentUrl = playlist.lineSequence().firstOrNull { it.startsWith("http://127.0.0.1") }
                        checkThat(keyUrl != null && segmentUrl != null, "surviving HLS rewrites both key and segment locally")
                        checkThat(get(keyUrl!!).contentEquals(pattern(0, 16)), "shared AES key survives deleting another master")
                        checkThat(get(segmentUrl!!).contentEquals(pattern(0, Origin.SmallSize.toInt())), "shared HLS segment survives deletion and cold restart")
                        checkThat(origin.requests.size == requests, "surviving HLS tree makes no remote requests")
                        proxy.deleteOffline(sourceB, keyB)
                        await("last HLS owner releases shared children") { cacheFiles(context).none { it.extension == "bin" } }
                    }
                }
            }
            case("active_reader", only) { context ->
                Origin().use { origin ->
                    withProxy(context) { proxy ->
                        val source = origin.url("/large.mp4")
                        val key = ContentCacheKey.root("active", "route", 1)
                        checkThat(proxy.downloadOffline(source, key).success, "active-reader fixture is complete")
                        proxy.setCurrent(source, 0L, key)
                        origin.online.set(false)
                        val connection = URL(proxy.proxyUrl(source, key)).openConnection() as HttpURLConnection
                        try {
                            connection.readTimeout = 5_000
                            checkThat(connection.responseCode == 200, "playback reader starts from local media")
                            connection.inputStream.use { input ->
                                val first = ByteArray(4096)
                                var count = 0
                                while (count < first.size) { val n = input.read(first, count, first.size - count); check(n > 0); count += n }
                                proxy.deleteOffline(source, key)
                                checkThat(first.contentEquals(pattern(0, first.size)), "initial active bytes are correct")
                                val remainder = input.readBytes()
                                checkThat(remainder.contentEquals(pattern(first.size.toLong(), Origin.LargeSize.toInt() - first.size)), "deleting current cache does not truncate its active reader")
                            }
                        } finally { connection.disconnect() }
                        checkThat(get(proxy.proxyUrl(source, key), "bytes=100000-104095").contentEquals(pattern(100000L, 4096)), "current playback can still seek after cache record deletion")
                        proxy.releasePlayback()
                        await("deferred deletion completes once playback releases file") { cacheFiles(context).none { it.extension == "bin" } }
                    }
                }
            }
            if (completed == 0 && failures.isEmpty()) failures += "Unknown offlineCase=$only"
            instrumentation.finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putInt("assertions", assertions)
                putInt("completedCases", completed)
                putInt("failureCount", failures.size)
                putString("stream", "Offline cache regression: $assertions assertions, $completed cases, ${failures.size} failures.\n" + failures.joinToString("\n"))
            })
        }

        private fun case(name: String, only: String?, action: (FixtureContext) -> Unit) {
            if (!only.isNullOrBlank() && only != name) return
            try {
                FixtureContext(instrumentation.targetContext).use(action)
                completed++
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
            } catch (error: Throwable) {
                failures += "$name: ${error.javaClass.simpleName}: ${error.message}"
                android.util.Log.e("OfflineCacheRegression", name, error)
            }
        }
        private fun checkThat(value: Boolean, message: String) { assertions++; check(value) { message } }
        private fun await(message: String, timeout: Long = 6_000L, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(25L)
            checkThat(condition(), message)
        }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base), Closeable {
        private val parent = base.cacheDir.canonicalFile
        private val root = File(parent, "offline-regression-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val prefs = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val unique = "${root.name}-$name"
            synchronized(prefs) { prefs += unique }
            return baseContext.getSharedPreferences(unique, mode)
        }
        override fun close() {
            synchronized(prefs) { prefs.forEach { baseContext.deleteSharedPreferences(it) } }
            check(root.canonicalFile.parentFile == parent)
            root.deleteRecursively()
        }
    }

    private inline fun withProxy(context: Context, allowed: AtomicBoolean = AtomicBoolean(true), action: (StreamingCacheProxy) -> Unit) {
        val proxy = StreamingCacheProxy(context) { allowed.get() }
        try { action(proxy) } finally {
            proxy.close()
            for (name in listOf("downloads", "jobs", "clients", "acceptor", "control")) runCatching {
                val field = StreamingCacheProxy::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(proxy) as ExecutorService).awaitTermination(3, TimeUnit.SECONDS)
            }
        }
    }
    private fun cacheFiles(context: Context) = File(context.filesDir, "video-stream-cache").listFiles().orEmpty().toList()
    private fun movie(id: String) = Movie(id, "Fixture", "电影", "2026", "", "", "", "", "", "", "", 1, 90_000L)
    private fun pattern(start: Long, size: Int) = ByteArray(size) { ((start + it) * 31L + ((start + it) shr 15) + 17L).toByte() }
    private fun get(url: String, range: String? = null): ByteArray {
        check(URL(url).host == "127.0.0.1")
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            range?.let { connection.setRequestProperty("Range", it) }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}: $url" }
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private data class Request(val path: String, val start: Long)
    private class Origin(
        private val slow: Boolean = false,
        heldResponses: Set<String> = emptySet(),
        private val unknownSizeFinish: CountDownLatch? = null,
        private val ignoreRangeFor: Set<String> = emptySet(),
        private val truncateThenFull: String? = null,
        private val deniedPath: String? = null,
    ) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool { task -> Thread(task, "Offline-Fixture").apply { isDaemon = true } }
        private val responseGates = heldResponses.associateWith { CountDownLatch(1) }
        val requests = CopyOnWriteArrayList<Request>()
        val bytesSent = AtomicLong()
        val online = AtomicBoolean(true)
        private val truncated = AtomicBoolean(false)
        init {
            pool.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    pool.execute { socket.use { runCatching { serve(it) } } }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
        fun releaseResponse(path: String) = responseGates.getValue(path).countDown()
        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val method = reader.readLine()?.split(' ') ?: return
            val path = method.getOrNull(1) ?: return
            var range: String? = null
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim() }
            val requestedStart = range?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            requests += Request(path, requestedStart)
            val truncate = path == truncateThenFull && truncated.compareAndSet(false, true)
            if (path in ignoreRangeFor || (path == truncateThenFull && !truncate)) range = null
            val start = if (range == null) 0L else requestedStart
            responseGates[path]?.let { check(it.await(10, TimeUnit.SECONDS)) }
            val output = socket.getOutputStream()
            if (!online.get()) { output.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
            if (path == deniedPath) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (path.endsWith(".m3u8")) {
                val data = when (path) {
                    "/progress.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Default\",DEFAULT=YES,URI=\"/progress-audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO=\"audio\"\n/progress-video.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200000\n/progress-unused.m3u8\n"
                    "/progress-video.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\n/progress-first.ts\n#EXTINF:2.0,\n/progress-last.ts\n#EXT-X-ENDLIST\n"
                    "/progress-audio.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\n/progress-audio.ts\n#EXT-X-ENDLIST\n"
                    else -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key.bin\"\n#EXTINF:2.0,\n/common.ts\n#EXT-X-ENDLIST\n"
                }.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(data)
                return
            }
            val total = when (path) { "/key.bin" -> 16L; "/large.mp4" -> LargeSize; else -> SmallSize }
            val requestedEnd = range?.substringAfter('-', "")?.toLongOrNull() ?: total - 1L
            val end = min(requestedEnd, total - 1L)
            if (start >= total) { output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            val partial = range != null
            val finish = unknownSizeFinish?.takeIf { path == "/unknown-size.mp4" }
            val header = buildString {
                append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: application/octet-stream\r\n")
                if (finish == null) append("Content-Length: ${end - start + 1L}\r\n")
                append("ETag: \"fixture\"\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${if (finish == null) total.toString() else "*"}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            var cursor = start
            while (cursor <= end) {
                val count = min(16_384L, end - cursor + 1L).toInt()
                output.write(pattern(cursor, count)); output.flush()
                bytesSent.addAndGet(count.toLong())
                cursor += count
                if (truncate && cursor >= 65_536L) return
                if (slow) Thread.sleep(12L)
            }
            finish?.let { check(it.await(10, TimeUnit.SECONDS)) }
        }
        override fun close() {
            server.close()
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
        }
        companion object { const val SmallSize = 262_144L; const val LargeSize = 2_097_152L }
    }
}
