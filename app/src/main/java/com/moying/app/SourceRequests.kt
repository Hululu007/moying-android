package com.moying.app

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 为同步仓库提供可取消的目录调用；不改变导入器、TLS 或片源检测的缓存语义。 */
internal object SourceRequests {
    private const val WORKERS = 4
    private const val QUEUED_REQUESTS = 16
    private const val IDLE_SECONDS = 30L
    private const val TOTAL_MILLIS = 18_000L

    // 系统 DNS 可能不响应中断；固定线程/有限排队防止连续切源无限增加后台工作。
    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, IDLE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUED_REQUESTS),
        { work -> Thread(work, "moying-source-request").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val closers = ThreadPoolExecutor(
        WORKERS, WORKERS, IDLE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(WORKERS),
        { work -> Thread(work, "moying-source-close").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val current = ThreadLocal<Request>()

    private class Request(val deadline: Long) {
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<HttpURLConnection?>(null)

        fun check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("片单请求已取消")
            if (SystemClock.elapsedRealtime() >= deadline) throw SocketTimeoutException("片单请求超时")
        }

        fun own(connection: HttpURLConnection) {
            active.set(connection)
            // 取消与连接注册并发时，注册方负责立即关闭后来出现的连接。
            if (cancelled.get()) {
                active.compareAndSet(connection, null)
                connection.disconnect()
                throw CancellationException("片单请求已取消")
            }
            check()
        }

        fun release(connection: HttpURLConnection) { active.compareAndSet(connection, null) }

        fun cancel() {
            cancelled.set(true)
            val connection = active.getAndSet(null) ?: return
            // disconnect 可能等内部连接锁，取消回调不能在 UI 线程等待它。
            try { closers.execute { runCatching { connection.disconnect() } } }
            catch (_: RejectedExecutionException) {
                // 极端 DNS/驱动阻塞时保持线程数有界；worker 中的 finally 仍负责关闭。
            }
        }
    }

    /** 整个目录请求含排队共享期限；取消立即停止等待，并中断/关闭后台请求。 */
    suspend fun <T> run(timeoutMillis: Long = TOTAL_MILLIS, action: () -> T): T {
        require(timeoutMillis in 1..TOTAL_MILLIS)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        try {
            return withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request(deadline)
                    val task = FutureTask<Unit> {
                        current.set(request)
                        try {
                            request.check()
                            val value = action()
                            request.check()
                            if (continuation.isActive) continuation.resume(value)
                        } catch (cancelled: CancellationException) {
                            continuation.cancel(cancelled)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally { current.remove() }
                    }
                    continuation.invokeOnCancellation {
                        // 先中断 worker；连接关闭独立调度，不能阻塞协程的取消过程。
                        task.cancel(true)
                        workers.remove(task)
                        request.cancel()
                    }
                    if (continuation.isActive) {
                        try {
                            workers.execute(task)
                            // 覆盖取消回调先于 execute 的窄窗口，避免留下已取消排队项。
                            if (task.isCancelled) workers.remove(task)
                        } catch (_: RejectedExecutionException) {
                            continuation.resumeWithException(IOException("片单任务繁忙，请稍后重试"))
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("片单请求超时，请重试或切换片源")
        }
    }

    /** 独立同步导入没有 token，仍保留原来的线程中断行为。 */
    fun checkActive() {
        current.get()?.let { it.check(); return }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("片源请求已取消")
    }

    fun own(connection: HttpURLConnection) { current.get()?.own(connection) }
    fun release(connection: HttpURLConnection) { current.get()?.release(connection) }

    fun timeout(maxMillis: Int, downloadDeadline: Long): Int {
        checkActive()
        val remaining = minOf(downloadDeadline, current.get()?.deadline ?: Long.MAX_VALUE) - SystemClock.elapsedRealtime()
        if (remaining <= 0L) throw SocketTimeoutException("片源请求超时")
        return minOf(maxMillis.toLong(), remaining).coerceAtLeast(1L).toInt()
    }
}
