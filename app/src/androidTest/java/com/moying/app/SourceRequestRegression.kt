package com.moying.app

import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 仅 loopback HTTP，无外部片源、无应用数据变更。由现有 instrumentation runner 路由调用。 */
internal object SourceRequestRegression {
    fun run(): Bundle {
        var assertions = 0
        val cases = mutableListOf<String>()
        fun verify(condition: Boolean, message: String) {
            assertions++
            check(condition) { message }
        }
        runBlocking {
            Fixture("cancel").use { server ->
                val delivered = AtomicBoolean(false)
                val request = async(Dispatchers.Default) {
                    SourceRequests.run { SourceImport.download(server.url) }.also { delivered.set(true) }
                }
                verify(server.accepted.await(3, TimeUnit.SECONDS), "cancel: request reached fixture")
                val started = SystemClock.elapsedRealtime()
                request.cancelAndJoin()
                verify(SystemClock.elapsedRealtime() - started < 1500, "cancel: caller returns promptly")
                verify(server.disconnected.await(3, TimeUnit.SECONDS), "cancel: active HTTP socket is closed")
                verify(!delivered.get(), "cancel: obsolete response is never delivered")
                cases += "active HTTP cancellation closes connection and discards response"
            }

            Fixture("drip").use { server ->
                val started = SystemClock.elapsedRealtime()
                var timedOut = false
                try { SourceRequests.run(timeoutMillis = 700) { SourceImport.download(server.url) } }
                catch (_: SocketTimeoutException) { timedOut = true }
                verify(timedOut, "deadline: whole-call timeout is reported as network timeout")
                verify(SystemClock.elapsedRealtime() - started < 2000, "deadline: trickle bytes cannot extend whole-call deadline")
                verify(server.disconnected.await(3, TimeUnit.SECONDS), "deadline: timed-out HTTP socket is closed")
                cases += "slow streaming response respects whole-call deadline"
            }

            // Occupy the fixed workers to prove cancellation removes a request before it starts.
            val workersEntered = CountDownLatch(4)
            val releaseWorkers = CountDownLatch(1)
            val blockers = List(4) {
                async(Dispatchers.Default) {
                    SourceRequests.run {
                        workersEntered.countDown()
                        releaseWorkers.await(5, TimeUnit.SECONDS)
                        "released"
                    }
                }
            }
            try {
                verify(workersEntered.await(3, TimeUnit.SECONDS), "queue: four bounded workers started")
                val executed = AtomicInteger()
                val queued = async(Dispatchers.Default) { SourceRequests.run { executed.incrementAndGet() } }
                delay(100)
                queued.cancelAndJoin()
                releaseWorkers.countDown()
                blockers.forEach { it.await() }
                verify(SourceRequests.run { "ready" } == "ready", "queue: workers remain reusable after cancellation")
                verify(executed.get() == 0, "queue: cancelled queued work never executes")
            } finally {
                releaseWorkers.countDown()
                blockers.forEach { it.cancelAndJoin() }
            }
            cases += "queued cancellation removes work and keeps fixed workers reusable"

            Fixture("normal").use { server ->
                val body = SourceRequests.run { SourceImport.download(server.url) }
                verify(JSONObject(body).getString("message") == "normal", "normal: wrapped download succeeds after cancellation")
            }
            Fixture("normal").use { server ->
                val body = SourceImport.download(server.url)
                verify(JSONObject(body).getString("message") == "normal", "normal: independent synchronous importer remains compatible")
            }
            verify(Thread.getAllStackTraces().keys.count { it.name == "moying-source-request" } <= 4, "worker count remains bounded")
            cases += "normal and independent synchronous requests still succeed"
        }
        return Bundle().apply {
            putInt("assertions", assertions)
            putInt("cases", cases.size)
            putString("stream", "Source request regression passed: $assertions assertions\n" + cases.joinToString("\n"))
        }
    }

    private class Fixture(private val mode: String) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val stopped = AtomicBoolean(false)
        @Volatile private var client: Socket? = null
        val accepted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val url = "http://127.0.0.1:${listener.localPort}/catalog.json"
        private val worker = thread(name = "moying-request-fixture", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    client = socket
                    socket.soTimeout = 4500
                    val input = socket.getInputStream().buffered()
                    var end = 0
                    val suffix = intArrayOf(13, 10, 13, 10)
                    repeat(16 * 1024) {
                        val next = input.read()
                        if (next < 0) return@thread
                        end = if (next == suffix[end]) end + 1 else if (next == 13) 1 else 0
                        if (end == 4) {
                            val output = socket.getOutputStream()
                            val body = "{\"message\":\"normal\"}".toByteArray()
                            val length = if (mode == "normal") body.size else 128
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                            output.flush()
                            accepted.countDown()
                            when (mode) {
                                "normal" -> { output.write(body); output.flush(); return@thread }
                                "cancel" -> {
                                    if (input.read() < 0) disconnected.countDown()
                                    return@thread
                                }
                                else -> {
                                    while (!stopped.get()) {
                                        output.write(' '.code)
                                        output.flush()
                                        Thread.sleep(120)
                                    }
                                    return@thread
                                }
                            }
                        }
                    }
                }
            } catch (_: SocketException) { disconnected.countDown() }
            catch (_: java.io.IOException) { disconnected.countDown() }
            catch (_: InterruptedException) { /* test cleanup */ }
        }

        override fun close() {
            stopped.set(true)
            runCatching { client?.close() }
            runCatching { listener.close() }
            worker.interrupt()
            worker.join(1000)
        }
    }
}
