package com.moying.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal object PosterLoaderRegression {
    fun run(context: Context): Bundle = PosterFixture().use { fixture ->
        val key = UUID.randomUUID().toString()
        runBlocking {
            withTimeout(10_000) {
                val sequential = "${fixture.baseUrl}/sequential-$key.png"
                val small = checkNotNull(PosterLoader.load(context, sequential, 128, 192, loadingDeferred = true)) {
                    "An uncached visible poster must load during scrolling"
                }
                check(small.width >= 128 && small.height >= 192)
                val large = checkNotNull(PosterLoader.load(context, sequential, 448, 672, loadingDeferred = false))
                check(large.width >= 448 && large.height >= 672)
                check(fixture.requests.get() == 1) { "A second display size downloaded the same poster again" }

                val concurrent = "${fixture.baseUrl}/concurrent-$key.png"
                val first = async { PosterLoader.load(context, concurrent, 144, 216, loadingDeferred = false) }
                val second = async { PosterLoader.load(context, concurrent, 384, 576, loadingDeferred = false) }
                check(checkNotNull(first.await()).width >= 144)
                check(checkNotNull(second.await()).width >= 384)
                check(fixture.requests.get() == 2) { "Concurrent size requests did not share the encoded download" }
            }
        }
        Bundle().apply {
            putInt("downloads", fixture.requests.get())
            putString("stream", "Poster loading during scroll and shared size downloads passed")
        }
    }

    private class PosterFixture : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val requests = AtomicInteger()
        private val image = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff2f5b80.toInt())
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        private val worker = Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 2_000
                        val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!input.readLine().isNullOrEmpty()) Unit
                        requests.incrementAndGet()
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                                "Content-Length: ${image.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(image)
                            flush()
                        }
                    }
                } catch (closed: SocketException) {
                    if (!server.isClosed) throw closed
                }
            }
        }, "PosterLoaderFixture").apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            worker.join(2_000)
            check(!worker.isAlive)
        }
    }
}

