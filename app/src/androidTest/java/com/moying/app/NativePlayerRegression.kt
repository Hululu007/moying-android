package com.moying.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real MediaPlayer regression against a local, generated MP4; never opens a repository or preferences. */
object NativePlayerRegression {
    fun run(instrumentation: Instrumentation, args: Bundle): Bundle {
        val context = instrumentation.targetContext
        val media = File(context.getExternalFilesDir(null), args.getString("mediaFile") ?: "network-regression.mp4")
        check(media.isFile && media.length() > 0L) { "Generated regression MP4 is missing" }
        val isolatedDirectory = File(context.cacheDir, "native-network-regression-${UUID.randomUUID()}")
        check(isolatedDirectory.mkdirs()) { "Cannot create isolated regression cache" }
        val preferencePrefix = "native-player-regression-${UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = isolatedDirectory
            override fun getFilesDir(): File = File(isolatedDirectory, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$preferencePrefix-$name"
                synchronized(preferenceNames) { preferenceNames += isolatedName }
                return super.getSharedPreferences(isolatedName, mode)
            }
        }
        PlaybackNetworkPreferences.writeNextEpisode(isolated, true)
        PlaybackNetworkPreferences.write(isolated, false)
        val server = MediaOrigin(media)
        var activity: DebugPlaybackActivity? = null
        var controller: AndroidPlayerController? = null
        var assertions = 0
        val started = SystemClock.elapsedRealtime()
        fun assertThat(condition: Boolean, description: String) {
            assertions++
            check(condition) { description }
        }
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }
        fun waitFor(description: String, timeout: Long = 6_000L, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (main(condition)) return
                Thread.sleep(80)
            }
            error("Timed out: $description")
        }
        try {
            val host = instrumentation.startActivitySync(Intent(context, DebugPlaybackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DebugPlaybackActivity
            activity = host
            check(host.surfaceReady.await(4, TimeUnit.SECONDS)) { "Debug video surface did not become ready" }
            val firstUrl = server.url("first.mp4")
            val nextUrl = server.url("next.mp4")
            main {
                controller = AndroidPlayerController(isolated)
                controller!!.attachSurface(host.surfaceView.holder)
                controller!!.setDataSource(firstUrl, 0L)
                controller!!.prepare(90_000L, 0L)
                controller!!.play()
            }
            val player = checkNotNull(controller)
            waitFor("first playback advances") { player.getPosition() >= 600L && player.isPlaying }
            val firstFrameMillis = SystemClock.elapsedRealtime() - started
            assertThat(main { field(player, "sourceCacheKey") == ContentCacheKey.fallback(firstUrl) }, "Legacy data source must forward to a real fallback content identity")
            assertThat(main { player.playbackRequested && nativePlayer(player)?.isPlaying == true }, "First playback did not start native rendering")

            val pausedAt = main { player.pause(); player.getPosition() }
            Thread.sleep(650)
            assertThat(main { !player.playbackRequested && !player.isPlaying && nativePlayer(player)?.isPlaying == false &&
                kotlin.math.abs(player.getPosition() - pausedAt) < 300L }, "Pause intent or native pause was not preserved")

            main { player.seek(5_000L); player.play() }
            waitFor("seek completes") { player.getPosition() in 4_500L..8_000L && !player.isBuffering && player.isPlaying }
            assertThat(main { player.getPosition() >= 4_500L }, "Seek did not resume near its requested position")

            val beforePreload = main { player.getPosition() }
            waitFor("next native instance prepares", 8_000L) {
                player.getPosition()
                player.preload(nextUrl)
                preparedPreload(player) != null
            }
            val warm = main { checkNotNull(preparedPreload(player)) }
            assertThat(main { player.getPosition() >= beforePreload && player.getPosition() < beforePreload + 12_000L && player.isPlaying },
                "Preparing the next episode changed current progress or playback")

            main { player.setDataSource(nextUrl, 0L); player.prepare(90_000L, 0L); player.play() }
            assertThat(main { nativePlayer(player) === warm && player.getPosition() < 2_000L },
                "Warm episode switch did not reuse the prepared native instance at its beginning")
            waitFor("warm episode progresses") { player.getPosition() >= 600L && player.isPlaying }
            assertThat(main { player.getPosition() < 5_000L }, "Warm episode inherited the previous episode position")

            val explicitKey = ContentCacheKey.root("native-regression", "route", 3)
            main { player.setDataSource(server.url("paused.mp4"), 0L, explicitKey); player.prepare(90_000L, 0L); player.play(); player.pause() }
            check(server.pausedRequestStarted.await(3, TimeUnit.SECONDS)) { "Paused preparation request did not arrive" }
            server.releasePausedResponse.countDown()
            waitFor("preparation completes while paused") {
                player.getPosition()
                booleanField(player, "prepared")
            }
            assertThat(main { field(player, "sourceCacheKey") == explicitKey }, "Explicit content identity must survive the keyed entry point")
            assertThat(main { !player.playbackRequested && !player.isPlaying && nativePlayer(player)?.isPlaying == false },
                "onPrepared restarted a user-paused request")
            Thread.sleep(650)
            assertThat(main { !player.playbackRequested && player.getPosition() < 300L && nativePlayer(player)?.isPlaying == false },
                "Restored response advanced a user-paused episode")
            return Bundle().apply {
                putInt("assertions", assertions)
                putLong("firstPlaybackMillis", firstFrameMillis)
                putLong("elapsedMillis", SystemClock.elapsedRealtime() - started)
                putInt("originRequests", server.requestCount)
                putString("stream", "Native player regression passed: $assertions assertions\n")
            }
        } finally {
            try { main { controller?.release(); activity?.finish() } }
            finally {
                try { server.close() }
                finally {
                    synchronized(preferenceNames) { preferenceNames.forEach { context.deleteSharedPreferences(it) } }
                    check(isolatedDirectory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
                    isolatedDirectory.deleteRecursively()
                }
            }
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun booleanField(instance: Any, name: String): Boolean = field(instance, name) as Boolean
    private fun nativePlayer(controller: AndroidPlayerController): MediaPlayer? = field(controller, "player") as? MediaPlayer
    private fun preparedPreload(controller: AndroidPlayerController): MediaPlayer? {
        val next = field(controller, "preloaded") ?: return null
        return if (booleanField(next, "prepared")) field(next, "player") as MediaPlayer else null
    }

    /** Bounded header parsing and streamed Range bodies; media never enters a whole-file byte array. */
    private class MediaOrigin(private val media: File) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val pausedRequestStarted = CountDownLatch(1)
        val releasePausedResponse = CountDownLatch(1)
        @Volatile var requestCount = 0
            private set
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets += socket
                workers.execute {
                    try { socket.use(::serve) } catch (_: IOException) { /* Decoder cancellation closes requests. */ }
                    finally { sockets -= socket }
                }
            }
        }, "NativePlayerRegressionOrigin").apply { isDaemon = true; start() }
        fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
        private fun serve(socket: Socket) {
            socket.soTimeout = 3_000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine()?.split(' ') ?: return
            if (request.size < 2) return
            var length = 0
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                length += line.length
                if (length > 16 * 1024) return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            synchronized(this) { requestCount++ }
            if (request[1].substringBefore('?') == "/paused.mp4") {
                pausedRequestStarted.countDown()
                if (!releasePausedResponse.await(5, TimeUnit.SECONDS)) return
            }
            val size = media.length()
            val range = Regex("bytes=(\\d*)-(\\d*)").matchEntire(headers["range"].orEmpty())
            val from = range?.groupValues?.get(1).orEmpty()
            val to = range?.groupValues?.get(2).orEmpty()
            val start = if (range != null && from.isEmpty()) (size - (to.toLongOrNull() ?: size)).coerceAtLeast(0L) else from.toLongOrNull() ?: 0L
            val end = if (from.isNotEmpty()) (to.toLongOrNull() ?: size - 1).coerceAtMost(size - 1) else size - 1
            if (start > end || start >= size) return
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val output = socket.getOutputStream()
            output.write(buildString {
                append("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                append("Content-Length: ${end - start + 1}\r\nETag: \"generated-$size\"\r\nConnection: close\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII))
            if (request[0] != "HEAD") RandomAccessFile(media, "r").use { input ->
                input.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = end - start + 1
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            output.flush()
        }
        override fun close() {
            releasePausedResponse.countDown()
            server.close()
            acceptor.join(2_000)
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            check(workers.awaitTermination(3, TimeUnit.SECONDS)) { "Media fixture did not stop" }
        }
    }
}
