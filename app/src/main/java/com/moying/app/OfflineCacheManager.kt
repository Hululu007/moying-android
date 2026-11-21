package com.moying.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Closeable
import java.io.IOException

/** 主动缓存状态；未出现在队列中即“未缓存”。 */
internal enum class OfflineStatus { QUEUED, DOWNLOADING, WAITING, PAUSED, COMPLETED, FAILED }

/** 一条单集离线缓存记录；cacheKey 直接复用 ContentCacheKey.content 身份。 */
internal data class OfflineRecord(
    val cacheKey: String,
    val movie: Movie,
    val routeId: String,
    val routeName: String,
    val routeEpisodeCount: Int,
    val episode: Int,
    val episodeName: String,
    val url: String,
    val status: OfflineStatus,
    val progress: Float? = null,
    val bytes: Long = 0L,
    val message: String? = null,
    val updatedAt: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
    val sourceEpisode: Episode? = null,
) {
    val downloadPercent: Int? get() = progress?.let { (it * 100f).toInt().coerceIn(0, 99) }
}

internal data class OfflineCacheState(
    val records: List<OfflineRecord> = emptyList(),
    val capacityBytes: Long = 0L,
) {
    /** 已落盘（含下载中的部分字节）总占用。 */
    val totalBytes: Long get() = records.sumOf { it.bytes }
    val completedCount: Int get() = records.count { it.status == OfflineStatus.COMPLETED }

    fun recordFor(cacheKey: String): OfflineRecord? = records.firstOrNull { it.cacheKey == cacheKey }
}

internal data class OfflineRequest(
    val movie: Movie,
    val routeId: String,
    val routeName: String,
    val routeEpisodeCount: Int,
    val episode: Episode,
    val url: String,
) {
    val cacheKey: String get() = ContentCacheKey.root(movie.id, routeId, episode.number)
}

/**
 * 输入：应用上下文；输出：进程内唯一的主动缓存队列。
 * 职责边界：持久化记录与串行调度；实际下载、续传、逐出保护由 StreamingCacheProxy 负责。
 */
internal class OfflineCacheManager internal constructor(
    context: Context,
    private val cache: StreamingCacheProxy = StreamingCacheProxy.get(context.applicationContext),
    private val downloadPolicyOverride: (() -> Boolean)? = null,
    private val onlineOverride: (() -> Boolean)? = null,
) : Closeable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val records = LinkedHashMap<String, OfflineRecord>()
    private val cancelledKeys = mutableSetOf<String>()
    private val pendingDeletes = mutableMapOf<String, OfflineRecord>()
    private val stateFile = File(appContext.filesDir, "offline-cache.json")

    var state by mutableStateOf(OfflineCacheState(capacityBytes = cache.capacityBytes()))
        private set

    @Volatile private var running = true
    @Volatile private var lastUiPublish = 0L
    @Volatile private var lastPersist = 0L
    private var dirty = false
    @Volatile private var activeDownload: OfflineRecord? = null
    private var activeResolution: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val worker = Thread({
        try { workerLoop() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }, "Moying-OfflineQueue").apply { isDaemon = true }

    init {
        loadRecords()
        publish(true)
        registerNetworkCallback()
        worker.start()
    }

    /** 输入：单集/整剧请求；输出：入队提示文案，UI 据此给出明确反馈。 */
    fun enqueue(requests: List<OfflineRequest>): String {
        var added = 0
        var resumed = 0
        var existing = 0
        var invalid = 0
        synchronized(lock) {
            for (request in requests) {
                val key = request.cacheKey
                if (request.url.isBlank() || !request.url.startsWith("http", true)) { invalid++; continue }
                val current = records[key]
                when {
                    current == null -> {
                        // 用户重新点选已取消的集：撤销遗留的删除任务，避免刚入队就被清掉。
                        pendingDeletes.remove(key)
                        cancelledKeys -= key
                        records[key] = OfflineRecord(
                            cacheKey = key,
                            movie = request.movie,
                            routeId = request.routeId,
                            routeName = request.routeName,
                            routeEpisodeCount = request.routeEpisodeCount.coerceAtLeast(request.episode.number),
                            episode = request.episode.number,
                            episodeName = request.episode.name,
                            url = request.url,
                            status = OfflineStatus.QUEUED,
                            updatedAt = System.currentTimeMillis(),
                            headers = request.episode.headers,
                            sourceEpisode = request.episode.takeIf { it.resolver != null },
                        )
                        added++
                    }
                    current.status == OfflineStatus.COMPLETED || current.status == OfflineStatus.QUEUED ||
                        current.status == OfflineStatus.DOWNLOADING -> existing++
                    else -> {
                        records[key] = current.copy(status = OfflineStatus.QUEUED, message = null, updatedAt = System.currentTimeMillis())
                        cancelledKeys -= key
                        resumed++
                    }
                }
            }
            lock.notifyAll()
        }
        publish(true)
        val total = added + resumed
        if (total == 0) {
            return when {
                invalid > 0 && existing == 0 -> "该集暂无可播放地址"
                existing > 0 -> "已在缓存队列中"
                else -> "没有可缓存的剧集"
            }
        }
        val networkNotice = when {
            !online() -> "当前离线，联网后自动继续"
            !canDownloadNow() -> "已加入队列，连接 Wi-Fi 后开始下载"
            else -> null
        }
        return networkNotice ?: "已加入缓存队列（$total 集）"
    }

    /** 输入：缓存键；输出：暂停并保留已下载前缀。 */
    fun pause(cacheKey: String) {
        val record = synchronized(lock) {
            val current = records[cacheKey] ?: return
            if (current.status == OfflineStatus.COMPLETED || current.status == OfflineStatus.PAUSED || current.status == OfflineStatus.FAILED) return
            records[cacheKey] = current.copy(status = OfflineStatus.PAUSED, message = "已暂停", updatedAt = System.currentTimeMillis())
            cancelledKeys += cacheKey
            current
        }
        pauseActive(record)
        publish(true)
        wake()
    }

    /** 输入：缓存键；输出：从暂停/失败/等待状态重新排队，磁盘前缀用于续传。 */
    fun resume(cacheKey: String) {
        synchronized(lock) {
            val current = records[cacheKey] ?: return
            if (current.status == OfflineStatus.COMPLETED || current.status == OfflineStatus.QUEUED ||
                current.status == OfflineStatus.DOWNLOADING) return
            records[cacheKey] = current.copy(status = OfflineStatus.QUEUED, message = null, updatedAt = System.currentTimeMillis())
            cancelledKeys -= cacheKey
            lock.notifyAll()
        }
        publish(true)
    }

    /** 输入：缓存键；输出：取消并删除该集主动缓存，进行中的下载会被立即中断。 */
    fun cancel(cacheKey: String) {
        val record = synchronized(lock) {
            val current = records.remove(cacheKey) ?: return
            cancelledKeys += cacheKey
            pendingDeletes[cacheKey] = current
            current
        }
        pauseActive(record)
        publish(true)
        wake()
    }

    /** 输入：影片 id；输出：删除该剧所有线路的主动缓存。 */
    fun deleteMovie(movieId: String) {
        val removed = synchronized(lock) {
            val matches = records.values.filter { it.movie.id == movieId }
            matches.forEach {
                records.remove(it.cacheKey)
                cancelledKeys += it.cacheKey
                pendingDeletes[it.cacheKey] = it
            }
            matches
        }
        removed.forEach(::pauseActive)
        if (removed.isNotEmpty()) publish(true)
        wake()
    }

    /** 输入：无；输出：清空全部主动缓存与队列。 */
    fun clearAll() {
        val removed = synchronized(lock) {
            val all = records.values.toList()
            records.clear()
            all.forEach {
                cancelledKeys += it.cacheKey
                pendingDeletes[it.cacheKey] = it
            }
            all
        }
        removed.forEach(::pauseActive)
        if (removed.isNotEmpty()) publish(true)
        wake()
    }

    /** 输入：影片 id；输出：只含已完整缓存集的离线线路，未缓存的集保留空地址占位。 */
    fun routesFor(movieId: String): List<PlaybackRoute> {
        val completed = state.records.filter { it.movie.id == movieId && it.status == OfflineStatus.COMPLETED }
        return completed.groupBy { it.routeId }.map { (routeId, records) ->
            val routeName = records.first().routeName
            val count = records.maxOf { maxOf(it.routeEpisodeCount, it.episode) }
            val byNumber = records.associateBy { it.episode }
            PlaybackRoute(
                routeId,
                routeName,
                (Playback.FirstEpisode..count).map { number ->
                    val record = byNumber[number]
                    Episode(number, record?.episodeName ?: "第${number}集", record?.url.orEmpty(), record?.headers.orEmpty())
                },
            )
        }
    }

    /** 输入：影片 id；输出：缓存时保存的最小影片快照，离线也能渲染播放页。 */
    fun movieFor(movieId: String): Movie? = state.records.firstOrNull { it.movie.id == movieId }?.movie

    private fun workerLoop() {
        // JSON is the logical ownership source of truth. In old cache files two
        // records may share a URL/root and different masters may share HLS data.
        val owners = synchronized(lock) {
            records.values
                .filter { it.sourceEpisode?.url != it.url || it.bytes > 0L || it.status == OfflineStatus.COMPLETED }
                .map { OfflineCacheOwner(it.url, it.cacheKey, it.headers) }
        }
        for (owner in owners) {
            runCacheMaintenance("Restore offline cache") { cache.restoreOfflineOwnersWithHeaders(listOf(owner)) }
        }
        while (running) {
            val deletes = synchronized(lock) {
                val values = pendingDeletes.values.toList()
                pendingDeletes.clear()
                values
            }
            for (record in deletes) {
                runCacheMaintenance("Delete offline cache") { cache.deleteOffline(record.url, record.cacheKey, record.headers) }
            }

            val next = synchronized(lock) { pickNextLocked() }
            if (dirty) {
                dirty = false
                publish(true)
            }
            if (next == null) {
                synchronized(lock) { if (running) lock.wait(500L) }
                continue
            }
            runDownload(next)
        }
    }

    private inline fun runCacheMaintenance(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (failure: Exception) {
            Log.w("Moying-OfflineQueue", "$operation failed (${failure.javaClass.simpleName})")
        }
    }

    private fun pickNextLocked(): OfflineRecord? {
        // 网络不满足下载条件时，把整队都标成等待，避免只有队首显示“等 Wi-Fi”。
        if (!canDownloadNow()) {
            val message = waitMessage()
            var changed = false
            records.keys.toList().forEach { key ->
                val record = records[key] ?: return@forEach
                if (record.status != OfflineStatus.QUEUED && record.status != OfflineStatus.WAITING) return@forEach
                if (record.status != OfflineStatus.WAITING || record.message != message) {
                    records[key] = record.copy(status = OfflineStatus.WAITING, message = message, updatedAt = System.currentTimeMillis())
                    changed = true
                }
            }
            if (changed) dirty = true
            return null
        }
        for (record in records.values) {
            if (record.status != OfflineStatus.QUEUED && record.status != OfflineStatus.WAITING) continue
            if (record.status == OfflineStatus.WAITING) {
                records[record.cacheKey] = record.copy(status = OfflineStatus.QUEUED, message = null)
                dirty = true
                return records[record.cacheKey]
            }
            return record
        }
        return null
    }

    private fun runDownload(record: OfflineRecord) {
        val key = record.cacheKey
        synchronized(lock) {
            val current = records[key] ?: return
            if (current !== record) return
            if (current.status != OfflineStatus.QUEUED && current.status != OfflineStatus.WAITING) return
            records[key] = current.copy(status = OfflineStatus.DOWNLOADING, progress = null, message = null, updatedAt = System.currentTimeMillis())
            cancelledKeys -= key
            activeDownload = record
        }
        publish(true)
        var media = record
        fun cancelled(): Boolean = synchronized(lock) {
            !running || key in cancelledKeys || records[key]?.status != OfflineStatus.DOWNLOADING
        }
        val result = try {
            if (record.sourceEpisode != null) {
                val resolved = resolveDownload(record, ::cancelled) ?: throw CancellationException()
                media = resolved
            }
            cache.downloadOffline(
                url = media.url,
                contentKey = key,
                onProgress = { progress ->
                    synchronized(lock) {
                        val current = records[key]
                        if (current != null && current.status == OfflineStatus.DOWNLOADING) {
                            records[key] = current.copy(progress = progress.fraction, bytes = progress.bytes)
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUiPublish >= 300L) {
                        lastUiPublish = now
                        publish(now - lastPersist >= 3_000L)
                    }
                },
                cancelled = ::cancelled,
                networkAllowed = ::canDownloadNow,
                headers = media.headers,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            OfflineDownloadResult(cancelled = true)
        } catch (_: CancellationException) {
            OfflineDownloadResult(cancelled = true)
        } catch (failure: PlaybackResolutionException) {
            OfflineDownloadResult(error = failure.message ?: "无法解析这条缓存线路")
        } catch (failure: Exception) {
            Log.w("Moying-OfflineQueue", "Download failed (${failure.javaClass.simpleName})")
            val message = when (failure) {
                is IOException, is SecurityException -> "缓存读写失败，请检查存储空间后重试"
                else -> "下载失败，请重试"
            }
            OfflineDownloadResult(error = message)
        } finally {
            activeDownload = null
        }
        val savedBytes = runCatching { cache.offlineBytes(media.url, key, media.headers) }.getOrNull()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = records[key]
            if (current != null && current.url == media.url && current.headers == media.headers) {
                val bytes = savedBytes ?: current.bytes
                records[key] = when {
                    current.status != OfflineStatus.DOWNLOADING -> current.copy(bytes = bytes, updatedAt = now)
                    result.success -> current.copy(
                        status = OfflineStatus.COMPLETED,
                        progress = 1f,
                        bytes = bytes,
                        message = null,
                        updatedAt = now,
                    )
                    result.waitingForNetwork || !canDownloadNow() -> current.copy(
                        status = OfflineStatus.WAITING,
                        bytes = bytes,
                        message = waitMessage(),
                        updatedAt = now,
                    )
                    result.cancelled -> current.copy(
                        status = OfflineStatus.QUEUED,
                        bytes = bytes,
                        updatedAt = now,
                    )
                    else -> current.copy(
                        status = OfflineStatus.FAILED,
                        bytes = bytes,
                        message = result.error ?: "下载失败，请重试",
                        updatedAt = now,
                    )
                }
            }
        }
        publish(true)
        wake()
    }

    private fun resolveDownload(record: OfflineRecord, cancelled: () -> Boolean): OfflineRecord? {
        val source = record.sourceEpisode ?: return record
        val episode = runBlocking {
            val job = coroutineContext.job
            synchronized(lock) {
                if (cancelled()) return@runBlocking null
                activeResolution = job
            }
            try {
                resolvePlaybackEpisode(source)
            } finally {
                synchronized(lock) {
                    if (activeResolution === job) activeResolution = null
                }
            }
        } ?: return null
        val changed = record.url != episode.url || record.headers != episode.headers
        val media = synchronized(lock) {
            if (cancelled()) return null
            val current = records[record.cacheKey] ?: return null
            current.copy(url = episode.url, headers = episode.headers, bytes = if (changed) 0L else current.bytes)
                .also {
                    records[record.cacheKey] = it
                    activeDownload = it
                    if (changed) persistLocked()
                }
        }
        if (changed) runCacheMaintenance("Replace resolved offline cache") {
            cache.deleteOffline(record.url, record.cacheKey, record.headers)
        }
        return media
    }

    private fun publish(persistNow: Boolean) {
        val snapshot = synchronized(lock) { records.values.toList() }
        main.post {
            state = OfflineCacheState(snapshot, cache.capacityBytes())
        }
        if (persistNow) synchronized(lock) { persistLocked() }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun pauseActive(record: OfflineRecord) {
        val (active, resolution) = synchronized(lock) {
            val active = activeDownload?.takeIf { it.cacheKey == record.cacheKey } ?: return
            active to activeResolution
        }
        resolution?.cancel()
        cache.pauseOffline(active.url, active.cacheKey, active.headers)
    }

    private fun canDownloadNow(): Boolean {
        downloadPolicyOverride?.let { return runCatching(it).getOrDefault(false) }
        val active = runCatching { connectivity.activeNetwork }.getOrNull() ?: return false
        val capabilities = runCatching { connectivity.getNetworkCapabilities(active) }.getOrNull() ?: return false
        val onWifiOrLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        // 与“仅 Wi-Fi 预缓存”设置共用同一事实来源；关闭时才允许移动数据下载。
        return !PlaybackNetworkPreferences.read(appContext) || onWifiOrLan
    }

    private fun online(): Boolean = onlineOverride?.let { runCatching(it).getOrDefault(false) }
        ?: runCatching { connectivity.activeNetwork != null }.getOrDefault(false)

    private fun waitMessage(): String = if (online()) "等待 Wi-Fi" else "等待网络"

    private fun registerNetworkCallback() {
        runCatching {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = policyChanged()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = policyChanged()
                override fun onLost(network: Network) = policyChanged()
            }
            networkCallback = callback
            connectivity.registerDefaultNetworkCallback(callback)
        }
    }

    private fun policyChanged() {
        if (!canDownloadNow()) activeDownload?.let(::pauseActive)
        wake()
    }

    override fun close() {
        running = false
        activeDownload?.let(::pauseActive)
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        worker.interrupt()
        wake()
        if (Thread.currentThread() !== worker) runCatching { worker.join(2_000L) }
    }

    private fun loadRecords() {
        runCatching {
            if (!stateFile.exists()) return
            val root = JSONObject(stateFile.readText())
            val array = root.optJSONArray("records") ?: return
            for (index in 0 until array.length()) {
                val record = array.optJSONObject(index)?.toRecord() ?: continue
                // 进程被杀时记录仍是“下载中”，重启后回到队列继续断点续传。
                records[record.cacheKey] = if (record.status == OfflineStatus.DOWNLOADING) {
                    record.copy(status = OfflineStatus.QUEUED, message = null)
                } else record
            }
        }
    }

    private fun persistLocked() {
        lastPersist = SystemClock.uptimeMillis()
        runCatching {
            val array = JSONArray()
            records.values.forEach { array.put(it.toJson()) }
            val root = JSONObject().put("version", 1).put("records", array)
            val temp = File(appContext.filesDir, "offline-cache.json.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(stateFile)) {
                stateFile.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private fun OfflineRecord.toJson(): JSONObject = JSONObject().apply {
        put("cacheKey", cacheKey)
        put("routeId", routeId)
        put("routeName", routeName)
        put("routeEpisodeCount", routeEpisodeCount)
        put("episode", episode)
        put("episodeName", episodeName)
        put("url", url)
        if (headers.isNotEmpty()) put("headers", JSONObject(headers))
        sourceEpisode?.let { put("sourceEpisode", SourceImport.episodesJson(listOf(it))) }
        put("status", status.name)
        put("bytes", bytes)
        put("message", message.orEmpty())
        put("updatedAt", updatedAt)
        put("movie", JSONObject().apply {
            put("id", movie.id)
            put("title", movie.title)
            put("category", movie.category)
            put("year", movie.year)
            put("region", movie.region)
            put("genres", movie.genres)
            put("score", movie.score)
            put("status", movie.status)
            put("poster", movie.poster)
            put("backdrop", movie.backdrop)
            put("synopsis", movie.synopsis)
            put("episodeCount", movie.episodeCount)
            put("durationMillis", movie.durationMillis)
            put("sourceCredit", movie.sourceCredit)
        })
    }

    private fun JSONObject.toRecord(): OfflineRecord? {
        val movieJson = optJSONObject("movie") ?: return null
        val movie = Movie(
            id = movieJson.optString("id"),
            title = movieJson.optString("title"),
            category = movieJson.optString("category"),
            year = movieJson.optString("year"),
            region = movieJson.optString("region"),
            genres = movieJson.optString("genres"),
            score = movieJson.optString("score"),
            status = movieJson.optString("status"),
            poster = movieJson.optString("poster"),
            backdrop = movieJson.optString("backdrop"),
            synopsis = movieJson.optString("synopsis"),
            episodeCount = movieJson.optInt("episodeCount"),
            durationMillis = movieJson.optLong("durationMillis"),
            sourceCredit = movieJson.optString("sourceCredit"),
        )
        if (movie.id.isBlank()) return null
        return OfflineRecord(
            cacheKey = optString("cacheKey"),
            movie = movie,
            routeId = optString("routeId"),
            routeName = optString("routeName").ifBlank { "默认线路" },
            routeEpisodeCount = optInt("routeEpisodeCount", optInt("episode", 1)),
            episode = optInt("episode", 1),
            episodeName = optString("episodeName"),
            url = optString("url"),
            status = runCatching { OfflineStatus.valueOf(optString("status")) }.getOrDefault(OfflineStatus.QUEUED),
            bytes = optLong("bytes"),
            message = optString("message").takeIf { it.isNotBlank() },
            updatedAt = optLong("updatedAt"),
            headers = SourceImport.playbackHeaders(optJSONObject("headers")),
            sourceEpisode = SourceImport.readEpisodes(optJSONArray("sourceEpisode")).firstOrNull()?.takeIf { it.resolver != null },
        ).takeIf { it.cacheKey.isNotBlank() && it.url.isNotBlank() }
    }

    companion object {
        @Volatile private var shared: OfflineCacheManager? = null

        /** 输入：应用上下文；输出：进程级队列单例，首次创建时恢复持久化状态。 */
        fun get(context: Context): OfflineCacheManager = shared ?: synchronized(this) {
            shared ?: OfflineCacheManager(context.applicationContext).also { shared = it }
        }
    }
}
