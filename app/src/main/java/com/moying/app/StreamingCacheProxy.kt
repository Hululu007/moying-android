package com.moying.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.net.URL
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

private object StreamCacheTuning {
    const val BudgetBytes = 1_024L * 1_024L * 1_024L
    const val ReserveDiskBytes = 128L * 1_024L * 1_024L
    const val ReadBytes = 64 * 1_024
    const val ManifestLimitBytes = 4 * 1_024 * 1_024
    const val ConnectMillis = 6_000
    const val ReadMillis = 10_000
    const val WaitMillis = 100L
    const val RetryMillis = 3_000L
    const val ManifestRefreshMillis = 3_000L
    /** 下一集预取的最低当前集余量：实际已缓存的时间与字节任一满足即可。 */
    const val CurrentAheadMillis = 20_000L
    const val RequestWaitMillis = 15_000L
    const val PastKeepMillis = 30_000L
    /** 当前集预取窗口：时间与字节双上限，字节按角色跨分片累计；倍速不放大窗口。 */
    const val CurrentWindowMillis = 30_000L
    const val CurrentAheadBytes = 6L * 1_024L * 1_024L
    /** 下一集预取窗口：只做小幅提前准备。 */
    const val NextWindowMillis = 10_000L
    const val NextAheadBytes = 2L * 1_024L * 1_024L
    /** 默认“仅 Wi-Fi 预缓存”在移动网络下把当前集收缩为更短的滚动窗口。 */
    const val MeteredWindowMillis = 10_000L
    const val MeteredAheadBytes = 2L * 1_024L * 1_024L
    /** 网络计费状态缓存时长，避免每个下载分块都查询 ConnectivityManager。 */
    const val NetworkCheckMillis = 1_000L
    const val SegmentsPerTrack = 2
    const val RangeChunkBytes = 1L * 1_024L * 1_024L
    /** 片源内容保鲜期：超过后重新回源，避免换片后长期命中旧缓存。 */
    const val SourceTtlMillis = 6L * 60L * 60L * 1_000L
    /** 主动缓存连续无进度超过该时长才判定失败，给慢速源留出重试窗口。 */
    const val OfflineIdleMillis = 30_000L
    const val FullRestartLimit = 3
    val ContentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
}

internal data class StreamCacheStats(val durationMillis: Long = 0L, val totalDurationMillis: Long = 0L, val bytes: Long = 0L, val complete: Boolean = false)

internal data class OfflineDownloadProgress(val fraction: Float?, val bytes: Long)
internal data class OfflineDownloadResult(val rootId: String = "", val success: Boolean = false, val cancelled: Boolean = false, val error: String? = null, val waitingForNetwork: Boolean = false)

/**
 * 输入：远程媒体地址；输出：仅本应用会话使用的回环 HTTP 地址。
 * 平台 MediaPlayer 继续解码；后台持续写磁盘，播放器读取同一份逐渐增长的文件。
 * HLS 的播放列表、相对分片、AES key 和 init map 均经同一代理，不把媒体装进内存。
 */
internal class StreamingCacheProxy(context: Context, private val offlinePolicyOverride: (() -> Boolean)? = null) : Closeable {
    private class Entry(val url: String, val id: String, directory: File) {
        val file = File(directory, "$id.bin")
        val info = File(directory, "$id.properties")
        val signal = Object()
        @Volatile var initialized = false
        @Volatile var available = 0L
        @Volatile var length = -1L
        @Volatile var mime = "application/octet-stream"
        @Volatile var complete = false
        @Volatile var downloading = false
        @Volatile var limited = false
        @Volatile var lastAttempt = 0L
        @Volatile var fetchedAt = 0L
        @Volatile var savedAt = 0L
        @Volatile var lastAccess = System.currentTimeMillis()
        @Volatile var roles = 0
        val requestCount = AtomicInteger()
        val requests get() = requestCount.get()
        @Volatile var timelineEnd = Long.MAX_VALUE
        @Volatile var timelineStart = Long.MAX_VALUE
        @Volatile var manifest: Manifest? = null
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var selectedVariant: Entry? = null
        @Volatile var selectedAudio: Entry? = null
        @Volatile var mediaDurationMillis = 0L
        @Volatile var resolvedBase = url
        @Volatile var bypass = false
        /** HLS 密钥/init map 等辅助资源：体积小，不做 Range 预检，避免无谓的整条直连。 */
        @Volatile var auxiliary = false
        /** 用户主动缓存：永久保留，不参与保鲜期和逐出。 */
        @Volatile var pinned = false
        /** 主动缓存任务正在运行；暂停/取消后为 false，磁盘前缀仍保留供续传。 */
        @Volatile var offlineRun = false
        @Volatile var offlineIssue: OfflineIssue? = null
        var fullRestartHighWater = 0L
        var stalledFullRestarts = 0
        @Volatile var deleteWhenUnused = false
        /** 离线播放只使用这两条已完整下载的变体/音轨，重启后按 id 复原。 */
        @Volatile var offlineVariantId: String? = null
        @Volatile var offlineAudioId: String? = null
        @Volatile var epoch = 0L
        @Volatile var prefixSuppressed = false
        @Volatile var readPosition = 0L
        @Volatile var validator: String? = null
        @Volatile var rangesInitialized = false
        val ranges = ConcurrentSkipListMap<Long, RangeSpan>()
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val relays = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val parentMasters = ConcurrentHashMap.newKeySet<Entry>()
        val parentAudioMasters = ConcurrentHashMap.newKeySet<Entry>()
    }
    private data class OfflineIssue(val message: String, val terminal: Boolean = false)

    private class OfflineResponseException(message: String) : IOException(message)

    private class RangeSpan(val start: Long, val file: File) {
        val info = File(file.parentFile, file.nameWithoutExtension + ".properties")
        @Volatile var available = 0L
        @Volatile var total = -1L
        @Volatile var validator: String? = null
    }
    private data class Segment(val entry: Entry, val start: Long, val duration: Long, val neededEnd: Long?, val dependencies: List<Entry>)
    private data class Manifest(val text: String, val segments: List<Segment>, val variants: List<Entry>, val resources: List<Entry>, val live: Boolean, val defaultAudio: Entry? = null) {
        val duration get() = segments.sumOf { it.duration }
    }

    private val appContext = context.applicationContext
    /** 主动缓存必须放在 filesDir：cacheDir 会被系统在低存储时回收，不能承载用户点选的离线内容。 */
    private val directory = File(appContext.filesDir, "video-stream-cache")
    private val legacyCacheDirectory = File(appContext.cacheDir, "video-stream-cache")
    private val network = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val entries = ConcurrentHashMap<String, Entry>()
    private val byId = ConcurrentHashMap<String, Entry>()
    private val registration = Any()
    private val playbackSelection = Any()
    /** Records own roots by content identity even when URLs/subresources share one physical entry. */
    private val offlineRoots = ConcurrentHashMap<String, Entry>()
    private val threads = java.util.concurrent.ThreadFactory { task -> Thread(task, "Moying-VideoCache").apply { isDaemon = true } }
    private val downloads = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue(), threads)
    private val control = Executors.newSingleThreadExecutor(threads)
    private val controlPending = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val initialized = CountDownLatch(1)
    private val activeRangeFiles = ConcurrentHashMap<String, AtomicLong>()
    private val rangeFiles = ConcurrentHashMap<String, RangeSpan>()
    private val jobs = Executors.newFixedThreadPool(2, threads)
    private val clients = Executors.newFixedThreadPool(4, threads)
    private val acceptor = Executors.newSingleThreadExecutor(threads)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val totalBytes = AtomicLong(0L)
    @Volatile private var closed = false
    @Volatile private var current: Entry? = null
    @Volatile private var next: Entry? = null
    @Volatile var positionMillis = 0L
    @Volatile var nativeBufferedPosition = 0L
    @Volatile var playbackSpeed = 1f
    @Volatile var foregroundBuffering = true
        set(value) { field = value; requestPriorityUpdate() }
    @Volatile private var restrictedNetworkCache = false
    @Volatile private var restrictedNetworkCheckedAt = 0L
    @Volatile private var wifiNetworkCache = false
    @Volatile private var nextPrefetchCache = false
    @Volatile private var nextPrefetchCheckedAt = 0L
    @Volatile private var offlinePolicyCheckedAt = 0L
    @Volatile private var offlinePolicyAllowed = false
    private var currentJob: Future<*>? = null
    private var nextJob: Future<*>? = null

    init {
        control.execute {
            try {
                directory.mkdirs()
                legacyCacheDirectory.deleteRecursively()
                purgeLegacyCache()
                restorePinnedEntries()
                totalBytes.set(directory.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L)
            } finally { initialized.countDown() }
        }
        acceptor.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                runCatching { clients.execute { socket.use { serve(it) } } }.onFailure { runCatching { socket.close() } }
            }
        }
    }

    /** 升级兼容：旧版以 SHA-256(URL) 命名，新版按内容身份命名；启动时清掉旧键文件，避免磁盘堆积无法命中的缓存。 */
    private fun purgeLegacyCache() {
        directory.listFiles()?.forEach { file ->
            if (file.extension != "bin" && file.extension != "properties") return@forEach
            val base = file.nameWithoutExtension.substringBefore("-range-")
            if (ContentCacheKey.isLegacyId(base)) file.delete()
        }
    }

    /** 输入：当前集/续播位置与内容身份键；输出：启动当前集连续缓存，已有磁盘数据可复用。 */
    fun setCurrent(url: String, position: Long, contentKey: String) = synchronized(playbackSelection) {
        val entry = entry(url, contentKey)
        current = entry
        positionMillis = position
        nativeBufferedPosition = position
        if (next === entry) next = null
        refreshRoles()
        requestPriorityUpdate()
        currentJob?.cancel(true)
        currentJob = jobs.submit { prefetchLoop(entry, false) }
    }

    /** 输入：下一集地址与内容身份键，空地址取消；输出：满足开关、网络与余量条件后才持续预缓存下一集。 */
    fun setNext(url: String, contentKey: String = "") = synchronized(playbackSelection) {
        val candidate = url.takeIf { it.isNotBlank() && it != current?.url && nextPrefetchAllowed() }
            ?.let { entry(it, contentKey) }
        if (candidate === next) return
        next = candidate
        nextJob?.cancel(true)
        refreshRoles()
        requestPriorityUpdate()
        nextJob = candidate?.let { jobs.submit { prefetchLoop(it, true) } }
    }

    fun proxyUrl(url: String, contentKey: String = ""): String =
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            entry(url, contentKey).let { if (it.bypass && !it.pinned) url else localUrl(it) }
        } else url

    /** 输入：播放页释放或换页；输出：只停止播放窗口的缓存，不关闭进程级代理，主动缓存下载不受影响。 */
    fun releasePlayback() = synchronized(playbackSelection) {
        currentJob?.cancel(true)
        nextJob?.cancel(true)
        currentJob = null
        nextJob = null
        current = null
        next = null
        positionMillis = 0L
        refreshRoles()
        requestPriorityUpdate()
    }

    /** 输入：媒体地址；输出：真实缓存字节和 HLS 连续分片时间，不按字节比例伪造时长。 */
    fun stats(url: String): StreamCacheStats = entries[url]?.let { stats(it, 0) } ?: StreamCacheStats()

    /** 输入：无；输出：与播放缓存共用的总容量上限，供管理页展示。 */
    fun capacityBytes(): Long = StreamCacheTuning.BudgetBytes

    /** 输入：无；输出：把进程内所有主动缓存条目重新登记，避免冷启动后逐出逻辑误删它们。 */
    private fun restorePinnedEntries() {
        directory.listFiles()?.filter { it.extension == "properties" }?.forEach { file ->
            val properties = runCatching {
                Properties().apply { file.inputStream().use { load(it) } }
            }.getOrNull() ?: return@forEach
            if (properties.getProperty("pinned") != "true") return@forEach
            val url = properties.getProperty("sourceUrl")?.takeIf { it.isNotBlank() } ?: return@forEach
            val id = file.nameWithoutExtension.substringBefore("-range-")
            if (ContentCacheKey.isLegacyId(id)) return@forEach
            registerEntry(url, id).pinned = true
        }
    }

    private fun entry(url: String, contentKey: String? = null): Entry =
        findOrRegisterEntry(url) { contentKey?.takeIf { it.isNotBlank() } ?: ContentCacheKey.fallback(url) }

    /** 输入：父条目与 HLS 子清单/分片地址；输出：叠加变体标识的独立缓存条目。 */
    private fun childEntry(parent: Entry, url: String): Entry =
        findOrRegisterEntry(url, parent.pinned) { ContentCacheKey.child(parent.id, url) }

    private inline fun findOrRegisterEntry(url: String, retain: Boolean = false, preferredId: () -> String): Entry = synchronized(registration) {
        val value = entries[url] ?: (
            // Recover URL-shared files written by the previous release.
            byId.values.firstOrNull { restored -> restored.url == url && restored.pinned }
                ?: registerEntry(url, preferredId())
            ).also { entries[url] = it }
        if (retain) value.deleteWhenUnused = false
        value
    }

    /** 输入：回源地址与期望键；输出：同键已被其它地址占用时追加后缀，避免两个回源共用同一份文件。 */
    private fun registerEntry(url: String, preferredId: String): Entry {
        synchronized(registration) {
            var id = preferredId
            var attempt = 0
            while (true) {
                val existing = byId[id]
                if (existing == null || existing.url == url) break
                attempt++
                id = "$preferredId-$attempt"
            }
            return byId[id] ?: Entry(url, id, directory).also { byId[id] = it }
        }
    }
    private fun localUrl(value: Entry) = "http://127.0.0.1:${server.localPort}/${value.id}/media${if (value.url.contains(".m3u", true)) ".m3u8" else ".bin"}"
    private fun online() = runCatching { network.activeNetwork != null }.getOrDefault(false)

    /** 输入：无；输出：是否降级预取（离线，或“仅 Wi-Fi 预缓存”且当前不是 Wi-Fi）。 */
    private fun restrictedNetwork(): Boolean {
        refreshNetworkFlags()
        return restrictedNetworkCache
    }

    /** 输入：无；输出：当前实际是否在 Wi-Fi 上；下一集预取只在 Wi-Fi 下进行。 */
    private fun onWifi(): Boolean {
        refreshNetworkFlags()
        return wifiNetworkCache
    }

    private fun refreshNetworkFlags() {
        val now = SystemClock.uptimeMillis()
        if (now - restrictedNetworkCheckedAt < StreamCacheTuning.NetworkCheckMillis) return
        restrictedNetworkCheckedAt = now
        val capabilities = runCatching { network.getNetworkCapabilities(network.activeNetwork) }.getOrNull()
        wifiNetworkCache = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        restrictedNetworkCache = when {
            !online() -> true
            // 用户显式允许移动数据预缓存时不再收缩当前集窗口。
            !PlaybackNetworkPreferences.read(appContext) -> false
            else -> !wifiNetworkCache
        }
    }

    /** 输入：无；输出：下一集预取开关，默认关闭，设置变更在一秒内生效。 */
    private fun nextPrefetchEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - nextPrefetchCheckedAt >= StreamCacheTuning.NetworkCheckMillis) {
            nextPrefetchCheckedAt = now
            nextPrefetchCache = PlaybackNetworkPreferences.readNextEpisode(appContext)
        }
        return nextPrefetchCache
    }

    private fun currentWindowMillis() =
        if (restrictedNetwork()) StreamCacheTuning.MeteredWindowMillis else StreamCacheTuning.CurrentWindowMillis

    /** 输入：角色 1（当前集）或 2（下一集）；输出：该角色已缓存但尚未消费的字节总量（跨分片累计）。 */
    private fun roleAheadBytes(role: Int): Long = entries.values.sumOf { value ->
        if (value.bypass || value.manifest != null || value.roles and role == 0) 0L
        else if (role == 2) value.available
        else when {
            value.timelineStart == Long.MAX_VALUE -> (value.available - value.readPosition).coerceAtLeast(0L)
            value.timelineEnd <= positionMillis -> 0L
            else -> value.available
        }
    }

    /** 输入：待下载条目；输出：该条目的角色字节预算是否已经用尽。 */
    private fun budgetExhausted(value: Entry): Boolean {
        // 用户主动缓存不参与播放窗口预算，只受总容量与磁盘余量约束。
        if (offlineDemand(value)) return false
        val role = when {
            value.roles and 1 != 0 -> 1
            value.roles and 2 != 0 -> 2
            else -> 1
        }
        val budget = when {
            role == 2 -> StreamCacheTuning.NextAheadBytes
            restrictedNetwork() -> StreamCacheTuning.MeteredAheadBytes
            else -> StreamCacheTuning.CurrentAheadBytes
        }
        return roleAheadBytes(role) >= budget
    }

    /** 输入：待调度条目；输出：预取预算是否已满；播放器正在读取的分片与连续文件不因此中断。 */
    private fun prefetchBudgetExhausted(value: Entry): Boolean =
        budgetExhausted(value) && (value.requests == 0 || value.timelineStart == Long.MAX_VALUE)

    /** 输入：无；输出：下一集预取是否允许（显式开关 + 当前集降级容差 + 实际 Wi-Fi）。 */
    private fun nextPrefetchAllowed(): Boolean = nextPrefetchEnabled() && !restrictedNetwork() && onWifi()
    private fun offlineAllowed(): Boolean {
        offlinePolicyOverride?.let { return runCatching(it).getOrDefault(false) }
        val now = SystemClock.uptimeMillis()
        if (now - offlinePolicyCheckedAt >= 100L) {
            offlinePolicyCheckedAt = now
            val capabilities = runCatching { network.getNetworkCapabilities(network.activeNetwork) }.getOrNull()
            offlinePolicyAllowed = capabilities != null && (!PlaybackNetworkPreferences.read(appContext) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        return offlinePolicyAllowed
    }
    private fun offlineDemand(value: Entry) = value.pinned && value.offlineRun && offlineAllowed()
    private fun needed(value: Entry) = !closed && (value.roles != 0 || value.requests > 0 || offlineDemand(value))
    fun canPreload(): Boolean = nextPrefetchAllowed() && !foregroundBuffering && allowedNext()
    private fun allowedNext() = nextPrefetchAllowed() && !foregroundBuffering && (current?.let {
        val state = stats(it, positionMillis)
        val required = StreamCacheTuning.CurrentAheadMillis
        state.complete || state.durationMillis >= required || nativeBufferedPosition - positionMillis >= required ||
            (positionMillis == 0L && it.manifest == null && !it.prefixSuppressed &&
                it.available - it.readPosition >= StreamCacheTuning.CurrentAheadBytes)
    } ?: true)

    fun updatePosition(position: Long) {
        if (kotlin.math.abs(position - positionMillis) > 10_000L) nativeBufferedPosition = position
        positionMillis = position.coerceAtLeast(0L)
        requestPriorityUpdate()
    }

    private fun requestPriorityUpdate() {
        if (closed || !controlPending.compareAndSet(false, true)) return
        runCatching { control.execute {
            try {
                val blockNext = !allowedNext()
                entries.values.forEach { value ->
                    // 主动缓存由离线队列驱动，播放窗口变化不能中断它。
                    if (offlineDemand(value)) return@forEach
                    val outsideWindow = value.roles == 1 && value.requests == 0 && value.timelineStart != Long.MAX_VALUE &&
                        (value.timelineEnd < positionMillis - StreamCacheTuning.PastKeepMillis ||
                            value.timelineStart > positionMillis + currentWindowMillis())
                    if (value.roles == 0 || (value.roles == 2 && blockNext) || value.prefixSuppressed || outsideWindow) {
                        value.epoch++
                        runCatching { value.connection?.disconnect() }
                        if (value.roles == 0 || (value.roles == 2 && blockNext)) {
                            value.relays.forEach { runCatching { it.disconnect() } }
                            if (value.roles == 0) value.sockets.forEach { runCatching { it.close() } }
                        }
                        value.lastAttempt = 0L
                        synchronized(value.signal) { value.signal.notifyAll() }
                    }
                }
            } finally { controlPending.set(false) }
        } }.onFailure { controlPending.set(false) }
    }

    private inner class DownloadTask(val value: Entry, val refresh: Boolean, val epoch: Long) : Runnable, Comparable<DownloadTask> {
        private val priority = when {
            value.requests > 0 -> 0
            // 主动缓存排在临时预取之前，但仍让正在播放的读取优先。
            offlineDemand(value) -> 1
            value.roles == 2 -> 2
            else -> 1
        }
        private val order = sequence.incrementAndGet()
        override fun compareTo(other: DownloadTask): Int = priority.compareTo(other.priority).takeIf { it != 0 } ?: order.compareTo(other.order)
        override fun run() {
            if (value.epoch != epoch || !needed(value) || (value.roles == 2 && value.requests == 0 && !offlineDemand(value) && !allowedNext()) ||
                (!offlineDemand(value) && prefetchBudgetExhausted(value))) {
                value.downloading = false
                synchronized(value.signal) { value.signal.notifyAll() }
                return
            }
            download(value, refresh, epoch)
        }
    }

    private fun refreshRoles() {
        entries.values.forEach { it.roles = 0 }
        fun mark(value: Entry, role: Int, seen: MutableSet<Entry>) {
            if (!seen.add(value)) return
            value.roles = value.roles or role
            value.manifest?.let { manifest ->
                manifest.resources.forEach { mark(it, role, seen) }
                manifest.variants.forEach { mark(it, role, seen) }
                manifest.segments.forEach { mark(it.entry, role, seen) }
            }
        }
        current?.let { mark(it, 1, mutableSetOf()) }
        next?.let { mark(it, 2, mutableSetOf()) }
        scheduleDeletedCleanup()
    }

    private fun prefetchLoop(root: Entry, upcoming: Boolean) {
        try {
            while (!closed && !Thread.currentThread().isInterrupted && (if (upcoming) next === root else current === root)) {
                if (online() && (!upcoming || allowedNext())) prefetch(root, upcoming, 0)
                Thread.sleep(500L)
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun prefetch(value: Entry, upcoming: Boolean, depth: Int) {
        if (depth > 4 || !needed(value)) return
        ensure(value)
        val manifest = value.manifest ?: return
        if (manifest.variants.isNotEmpty()) {
            // 只跟随播放器已选中的变体/音轨；未选中时仅保留清单，避免重复下载错误变体。
            val children = listOfNotNull(value.selectedVariant, value.selectedAudio).distinct()
            if (children.isEmpty()) return
            children.forEach(::ensure)
            children.forEach { prefetch(it, upcoming, depth + 1) }
            return
        }
        val start = if (upcoming) 0L else positionMillis
        val end = start + if (upcoming) StreamCacheTuning.NextWindowMillis else currentWindowMillis()
        for (segment in manifest.segments.filter { it.start + it.duration > start && it.start < end && !segmentReady(it) }.take(StreamCacheTuning.SegmentsPerTrack)) {
            if (!needed(value) || Thread.currentThread().isInterrupted) return
            if (upcoming && !allowedNext()) return
            if (!needed(value)) return
            segment.dependencies.forEach(::ensure)
            ensure(segment.entry)
        }
    }

    private fun ensure(value: Entry) {
        synchronized(value.signal) {
            val now = System.currentTimeMillis()
            val refresh = value.manifest?.live == true && now - value.fetchedAt >= StreamCacheTuning.ManifestRefreshMillis
            val offline = offlineDemand(value)
            if ((!offline && value.bypass) || (offline && value.offlineIssue?.terminal == true) ||
                value.downloading || (value.initialized && value.complete && !refresh) ||
                now - value.lastAttempt < StreamCacheTuning.RetryMillis || !needed(value) || !online()) return
            if ((value.roles == 2 && value.requests == 0 && !offline && !allowedNext()) ||
                (value.prefixSuppressed && value.manifest == null && !offline)) return
            if (!offline && value.initialized && value.manifest == null && prefetchBudgetExhausted(value)) return
            if (value.limited && totalBytes.get() >= StreamCacheTuning.BudgetBytes) return
            value.limited = false
            value.downloading = true
            value.lastAttempt = now
            runCatching { downloads.execute(DownloadTask(value, refresh, value.epoch)) }.onFailure { value.downloading = false }
        }
    }

    /** 输入：不支持 Range 的源；输出：放弃后续缓存并让播放器直连，已缓存的合法前缀仍可读取。 */
    private fun abandon(value: Entry) {
        value.bypass = true
        value.epoch++
        value.complete = false
        value.lastAttempt = 0L
        value.limited = false
        synchronized(value.signal) { value.signal.notifyAll() }
    }

    private fun initialize(value: Entry) = synchronized(value.signal) {
        if (value.initialized) return
        initialized.await()
        directory.mkdirs()
        if (!value.file.exists()) value.file.createNewFile()
        val restored = if (value.info.exists() && value.file.exists()) {
            runCatching {
                val properties = Properties().apply { value.info.inputStream().use { load(it) } }
                val savedAt = properties.getProperty("savedAt")?.toLongOrNull() ?: 0L
                val sourcePath = properties.getProperty("sourcePath").orEmpty()
                val currentPath = ContentCacheKey.sourcePath(value.url)
                val pinned = properties.getProperty("pinned") == "true"
                value.pinned = pinned || value.offlineRun
                value.offlineVariantId = properties.getProperty("offlineVariantId")?.takeIf { it.isNotBlank() }
                value.offlineAudioId = properties.getProperty("offlineAudioId")?.takeIf { it.isNotBlank() }
                // 主动缓存不设保鲜期：用户点选的离线内容必须跨进程重启和换片周期保留。
                if (savedAt <= 0L) return@runCatching false
                if (!pinned && (System.currentTimeMillis() - savedAt > StreamCacheTuning.SourceTtlMillis ||
                        (sourcePath.isNotBlank() && sourcePath != currentPath))) return@runCatching false
                value.savedAt = savedAt
                value.length = properties.getProperty("length", "-1").toLong()
                value.mime = properties.getProperty("mime", value.mime)
                value.resolvedBase = properties.getProperty("resolvedBase", value.url)
                value.validator = properties.getProperty("validator")
                value.available = value.file.length()
                value.complete = properties.getProperty("complete") == "true" && value.available == value.length
                if (properties.getProperty("manifest") == "true" && value.complete) {
                    value.manifest = parseManifest(value, value.file.readText(), value.resolvedBase)
                }
                true
            }.getOrDefault(false)
        } else false
        if (!restored) {
            // 保鲜期已过/换片/旧格式：清空磁盘前缀与元数据，下一次请求重新回源。
            value.info.delete()
            value.length = -1L
            value.mime = "application/octet-stream"
            value.manifest = null
            value.validator = null
            value.resolvedBase = value.url
            value.savedAt = 0L
            value.complete = false
            val bytes = value.file.length()
            if (bytes > 0L) {
                FileOutputStream(value.file, false).use { }
                totalBytes.addAndGet(-bytes)
            }
            value.available = 0L
        }
        value.initialized = true
    }

    private fun recordOfflineIssue(value: Entry, epoch: Long, issue: OfflineIssue) {
        synchronized(value.signal) {
            if (value.epoch == epoch && value.offlineRun) value.offlineIssue = issue
        }
    }

    private fun httpOfflineIssue(status: Int): OfflineIssue = when (status) {
        401, 403 -> OfflineIssue("片源拒绝下载（HTTP $status），请刷新片源或换线路", terminal = true)
        404, 410 -> OfflineIssue("视频地址已失效（HTTP $status），请刷新片源或换线路", terminal = true)
        408, 425, 429 -> OfflineIssue("片源暂时无法响应（HTTP $status），请稍后重试")
        in 500..599 -> OfflineIssue("片源服务器暂时不可用（HTTP $status），请稍后重试")
        else -> OfflineIssue("片源无法提供下载（HTTP $status），请换线路", terminal = true)
    }

    private fun download(value: Entry, refresh: Boolean, epoch: Long) {
        var connection: HttpURLConnection? = null
        val offline = offlineDemand(value)
        try {
            initialize(value)
            if (value.epoch != epoch || !needed(value) || (value.roles == 2 && value.requests == 0 && !offlineDemand(value) && !allowedNext()) ||
                (value.prefixSuppressed && !offlineDemand(value))) return
            if (value.complete && !refresh) return
            if (!online()) return
            val prefix = if (refresh) 0L else value.available
            // 播放预取沿用 Range 探测，主动缓存也接受从头发送的完整响应。
            val probeRange = prefix == 0L && !refresh && value.manifest == null && !value.auxiliary
            connection = open(value.url, when {
                prefix > 0L -> prefix
                probeRange -> 0L
                else -> null
            }, null, value.validator)
            value.connection = connection
            if (value.epoch != epoch || !needed(value)) return
            val status = connection.responseCode
            val responseValidator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified")
            val previousValidator = value.validator
            val sameRepresentation = previousValidator == null || responseValidator == null || previousValidator == responseValidator
            if (status == 416 && prefix > 0 && connection.getHeaderField("Content-Range") == "bytes */$prefix" &&
                (!offline || sameRepresentation && (value.length < 0L || value.length == prefix))) {
                value.length = prefix
                value.complete = true
                if (offline) value.bypass = false
                saveInfo(value)
                return
            }
            if (status !in 200..299 || offline && status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                if (offline) recordOfflineIssue(value, epoch, httpOfflineIssue(status))
                return
            }
            if (!offline) value.validator = responseValidator
            val mime = connection.contentType.orEmpty().substringBefore(';').trim()
            if (offline && (mime.equals("text/html", true) || mime.contains("json", true))) {
                throw OfflineResponseException("片源返回了网页或错误信息，请刷新片源或换线路")
            }
            val response = connection.inputStream.buffered()
            var isManifest = mime.contains("mpegurl", true) || value.manifest != null
            if (prefix == 0L || offline && status == HttpURLConnection.HTTP_OK) {
                response.mark(64)
                val probe = ByteArray(32)
                val count = response.read(probe)
                response.reset()
                val header = if (count > 0) probe.decodeToString(endIndex = count).trimStart('\uFEFF', ' ', '\r', '\n', '\t') else ""
                if (header.startsWith("#EXTM3U")) isManifest = true
                if (offline) {
                    if (count < 0) throw IOException("Empty response")
                    if (header.startsWith("<!doctype", true) || header.startsWith("<html", true)) {
                        throw OfflineResponseException("片源返回了网页或错误信息，请刷新片源或换线路")
                    }
                }
                if (!offline && probeRange && status != HttpURLConnection.HTTP_PARTIAL && !isManifest) {
                    // 源忽略 Range：不写任何前缀，直接让整条地址直连，避免先下前缀再放弃。
                    abandon(value)
                    return
                }
            }
            if (isManifest) {
                val text = response.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(StreamCacheTuning.ReadBytes)
                    while (output.size() <= StreamCacheTuning.ManifestLimitBytes) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > StreamCacheTuning.ManifestLimitBytes) {
                        throw OfflineResponseException("片源的播放列表过大，无法离线缓存")
                    }
                    bytes.toString(Charsets.UTF_8)
                }
                val normalized = text.removePrefix("\uFEFF")
                if (!normalized.trimStart().startsWith("#EXTM3U")) {
                    throw OfflineResponseException("片源返回了无效的播放列表，请换线路")
                }
                if (value.epoch != epoch || !needed(value)) return
                val data = normalized.toByteArray()
                val manifest = parseManifest(value, normalized, connection.url.toString())
                synchronized(value.signal) {
                    if (value.epoch != epoch || !needed(value)) return
                    if (offline && prefix > 0L && value.requests > 0) {
                        value.offlineIssue = OfflineIssue("请停止当前视频播放后重试缓存")
                        return
                    }
                    val oldLength = value.available
                    value.file.writeBytes(data)
                    totalBytes.addAndGet(data.size - oldLength)
                    value.mime = "application/vnd.apple.mpegurl"
                    value.resolvedBase = connection.url.toString()
                    value.manifest = manifest
                    value.available = data.size.toLong()
                    value.length = data.size.toLong()
                    value.validator = responseValidator
                    value.complete = true
                }
            } else {
                val rangeHeader = connection.getHeaderField("Content-Range").orEmpty()
                val contentLength = connection.contentLengthLong
                var rangeEnd: Long? = null
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    if (offline) {
                        val range = StreamCacheTuning.ContentRange.matchEntire(rangeHeader)?.groupValues
                        val start = range?.get(1)?.toLongOrNull()
                        val end = range?.get(2)?.toLongOrNull()
                        val total = range?.get(3)?.toLongOrNull()
                        if (start != prefix || end == null || end < prefix || end == Long.MAX_VALUE ||
                            (total != null && end >= total) || (contentLength >= 0L && contentLength != end - prefix + 1L) ||
                            (prefix > 0L && (!sameRepresentation || total != null && value.length >= 0L && total != value.length))) {
                            throw OfflineResponseException("片源返回的分段数据与缓存不一致，请换线路")
                        }
                        rangeEnd = end
                    } else if (prefix > 0L) {
                        val returnedStart = Regex("bytes (\\d+)-").find(rangeHeader)?.groupValues?.get(1)?.toLongOrNull()
                        if (returnedStart != prefix) {
                            // 非法 206：保留合法前缀，停止播放预取。
                            abandon(value)
                            throw IOException("Mismatched Content-Range")
                        }
                    }
                }
                val resumed = prefix > 0L && status == HttpURLConnection.HTTP_PARTIAL
                if (prefix > 0L && !resumed && !offline) {
                    // 播放预取保留合法前缀，将忽略 Range 的地址交回播放器直连。
                    abandon(value)
                    return
                }
                val start = if (resumed) prefix else 0L
                val contentRange = if (!offline || status == HttpURLConnection.HTTP_PARTIAL) {
                    rangeHeader.substringAfterLast('/', "").toLongOrNull()
                } else null
                val length = contentRange ?: contentLength.takeIf { it >= 0L }?.let { start + it } ?: -1L
                if (offline && length > StreamCacheTuning.BudgetBytes) {
                    throw OfflineResponseException("视频超过缓存容量上限，请换线路")
                }
                response.use { input ->
                    val stream = synchronized(value.signal) {
                        if (value.epoch != epoch || !needed(value)) return
                        val restarting = offline && prefix > 0L && !resumed
                        if (restarting && value.requests > 0) {
                            // 已发出响应头的播放请求读完前，保留其正在读取的旧文件。
                            value.offlineIssue = OfflineIssue("请停止当前视频播放后重试缓存")
                            return
                        }
                        if (restarting && prefix <= value.fullRestartHighWater &&
                            value.stalledFullRestarts >= StreamCacheTuning.FullRestartLimit) {
                            throw OfflineResponseException("片源多次中断且无法续传，请重试或换线路")
                        }
                        FileOutputStream(value.file, resumed).also {
                            if (restarting) {
                                if (prefix > value.fullRestartHighWater) {
                                    value.fullRestartHighWater = prefix
                                    value.stalledFullRestarts = 0
                                } else {
                                    value.stalledFullRestarts++
                                }
                                // 完整响应已校验，从零重写并扣除旧前缀的占用。
                                value.available = 0L
                                totalBytes.addAndGet(-prefix)
                            }
                            value.length = length
                            value.mime = mime.ifBlank { "application/octet-stream" }
                            if (offline) value.validator = if (resumed) responseValidator ?: previousValidator else responseValidator
                            value.signal.notifyAll()
                        }
                    }
                    stream.use { output ->
                        val buffer = ByteArray(StreamCacheTuning.ReadBytes)
                        while (needed(value) && value.epoch == epoch && (!value.prefixSuppressed || offlineDemand(value)) && !Thread.currentThread().isInterrupted) {
                            if (value.roles == 2 && value.requests == 0 && !offlineDemand(value) && !allowedNext()) break
                            if (!offlineDemand(value) && prefetchBudgetExhausted(value)) { value.lastAttempt = 0L; break }
                            if (offline && value.length > 0L && value.available == value.length) {
                                if (contentLength < 0L && input.read() >= 0) {
                                    throw OfflineResponseException("片源返回的内容超出声明长度，请换线路")
                                }
                                value.complete = true
                                break
                            }
                            if (totalBytes.get() >= StreamCacheTuning.BudgetBytes || directory.usableSpace < StreamCacheTuning.ReserveDiskBytes) {
                                // 主动缓存需要空间时只逐出临时缓存，绝不把自己或其它已点选内容删掉。
                                trim(if (value.pinned) null else value)
                                if (totalBytes.get() >= StreamCacheTuning.BudgetBytes || directory.usableSpace < StreamCacheTuning.ReserveDiskBytes) { value.limited = true; break }
                            }
                            val count = input.read(buffer)
                            if (count < 0) {
                                if (offline && (value.available == start || rangeEnd?.let { value.available <= it } == true ||
                                        status == HttpURLConnection.HTTP_OK && length >= 0L && value.available != length)) {
                                    throw IOException("Incomplete response")
                                }
                                value.complete = value.length < 0L || value.available == value.length
                                if (value.length < 0L) value.length = value.available
                                break
                            }
                            if (offline && (value.epoch != epoch || !needed(value))) break
                            if (rangeEnd?.let { count > it - value.available + 1L } == true) {
                                throw OfflineResponseException("片源返回的分段数据超出声明范围，请换线路")
                            }
                            output.write(buffer, 0, count)
                            value.available += count
                            totalBytes.addAndGet(count.toLong())
                            synchronized(value.signal) { value.signal.notifyAll() }
                        }
                    }
                }
            }
            if (offline && value.complete && value.epoch == epoch) {
                value.bypass = false
                value.offlineIssue = null
            }
            value.fetchedAt = System.currentTimeMillis()
            if (value.complete && value.manifest == null && (value === current || value === next)) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(value.file.absolutePath)
                    value.mediaDurationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                } catch (_: Exception) { /* 未知格式保持字节显示，不猜测时长。 */ }
                finally { runCatching { retriever.release() } }
            }
            saveInfo(value)
            trim(value)
        } catch (error: Exception) {
            if (offline) {
                val issue = when (error) {
                    is OfflineResponseException -> OfflineIssue(error.message.orEmpty(), terminal = true)
                    is SocketTimeoutException -> OfflineIssue("片源连接或读取超时，请重试或换线路")
                    is UnknownHostException -> OfflineIssue("无法解析片源地址，请检查网络或换线路")
                    else -> OfflineIssue("视频数据传输中断，请重试或换线路")
                }
                recordOfflineIssue(value, epoch, issue)
            }
            // 网络恢复后 ensure 从磁盘前缀续传，缓存失败不会修改播放器确认进度。
            runCatching { saveInfo(value) }
        } finally {
            connection?.disconnect()
            value.connection = null
            value.downloading = false
            synchronized(value.signal) { value.signal.notifyAll() }
            scheduleDeletedCleanup()
        }
    }

    private fun saveInfo(value: Entry) {
        if (!value.initialized || !value.file.exists()) return
        value.savedAt = System.currentTimeMillis()
        Properties().apply {
            setProperty("length", value.length.toString())
            setProperty("mime", value.mime)
            setProperty("complete", value.complete.toString())
            setProperty("manifest", (value.manifest != null).toString())
            setProperty("resolvedBase", value.resolvedBase)
            value.validator?.let { setProperty("validator", it) }
            // 原始地址仅作为回源元数据保存，不参与缓存键。
            setProperty("sourceUrl", value.url)
            setProperty("sourcePath", ContentCacheKey.sourcePath(value.url))
            setProperty("savedAt", value.savedAt.toString())
            setProperty("pinned", value.pinned.toString())
            value.offlineVariantId?.let { setProperty("offlineVariantId", it) }
            value.offlineAudioId?.let { setProperty("offlineAudioId", it) }
        }.also { properties -> value.info.outputStream().use { properties.store(it, null) } }
    }

    private fun parseManifest(value: Entry, text: String, base: String): Manifest {
        val segments = mutableListOf<Segment>()
        val variants = mutableListOf<Entry>()
        val resources = mutableListOf<Entry>()
        val result = StringBuilder()
        var pendingDuration = 0L
        var elapsed = 0L
        var variant = false
        var pendingRangeLength: Long? = null
        var pendingRangeOffset: Long? = null
        var previousRangeEnd = 0L
        var currentKey: Entry? = null
        var currentMap: Entry? = null
        var firstAudio: Entry? = null
        var defaultAudio: Entry? = null
        fun resolve(raw: String): Entry? {
            val resolved = runCatching { URI(base).resolve(raw).toString() }.getOrNull() ?: return null
            if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) return null
            return childEntry(value, resolved).also { it.roles = it.roles or value.roles }
        }
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> pendingDuration = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                line.startsWith("#EXT-X-STREAM-INF:") -> variant = true
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val range = line.substringAfter(':').split('@')
                    pendingRangeLength = range[0].toLongOrNull()
                    pendingRangeOffset = range.getOrNull(1)?.toLongOrNull()
                }
            }
            if (line.startsWith("#EXT-X-KEY:") && line.contains("METHOD=NONE")) currentKey = null
            if (line.startsWith("#") && line.contains("URI=\"")) {
                line = Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                    val child = resolve(match.groupValues[1])
                    if (child == null) match.value else {
                        resources += child
                        if (raw.startsWith("#EXT-X-KEY:")) currentKey = child
                        if (raw.startsWith("#EXT-X-MAP:")) currentMap = child
                        if (raw.startsWith("#EXT-X-KEY:") || raw.startsWith("#EXT-X-MAP:")) child.auxiliary = true
                        if (raw.startsWith("#EXT-X-MEDIA:") && raw.contains("TYPE=AUDIO")) {
                            if (firstAudio == null) firstAudio = child
                            if (raw.contains("DEFAULT=YES")) defaultAudio = child
                            child.parentAudioMasters += value
                        }
                        "URI=\"${localUrl(child)}\""
                    }
                }
                if (raw.startsWith("#EXT-X-KEY:") && raw.contains("METHOD=NONE")) currentKey = null
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val child = resolve(line)
                if (child != null) {
                    line = localUrl(child)
                    if (variant || pendingDuration == 0L) {
                        variants += child
                        child.parentMasters += value
                        variant = false
                    } else {
                        val rangeEnd = pendingRangeLength?.let { (pendingRangeOffset ?: previousRangeEnd) + it }
                        if (rangeEnd != null) previousRangeEnd = rangeEnd
                        val segment = Segment(child, elapsed, pendingDuration, rangeEnd, listOfNotNull(currentKey, currentMap))
                        segments += segment
                        child.timelineStart = min(child.timelineStart, elapsed)
                        child.timelineEnd = if (child.timelineEnd == Long.MAX_VALUE) elapsed + pendingDuration else maxOf(child.timelineEnd, elapsed + pendingDuration)
                        elapsed += pendingDuration
                        pendingDuration = 0L
                        pendingRangeLength = null
                        pendingRangeOffset = null
                    }
                }
            }
            result.append(line).append('\n')
        }
        return Manifest(result.toString(), segments, variants, resources.distinct(), !text.contains("#EXT-X-ENDLIST") && variants.isEmpty(), defaultAudio ?: firstAudio)
    }

    private fun segmentReady(segment: Segment): Boolean = segment.dependencies.all { it.complete } &&
        (segment.neededEnd?.let { segment.entry.available >= it } ?: segment.entry.complete)

    private fun stats(value: Entry, start: Long, depth: Int = 0): StreamCacheStats {
        if (depth > 4) return StreamCacheStats()
        val manifest = value.manifest
        if (manifest == null) return StreamCacheStats(durationMillis = if (value.complete) value.mediaDurationMillis else 0L, totalDurationMillis = value.mediaDurationMillis, bytes = value.available + value.ranges.values.sumOf { it.available }, complete = value.complete)
        if (manifest.variants.isNotEmpty()) {
            // 播放器尚未选择变体时只报告清单就绪，不计入任何未选中变体的分片。
            val video = value.selectedVariant?.let { stats(it, start, depth + 1) } ?: return StreamCacheStats()
            val audioEntry = value.selectedAudio ?: return video
            val audio = stats(audioEntry, start, depth + 1)
            val duration = if (audio.totalDurationMillis > 0 && video.totalDurationMillis > 0) min(audio.totalDurationMillis, video.totalDurationMillis) else maxOf(audio.totalDurationMillis, video.totalDurationMillis)
            return StreamCacheStats(min(video.durationMillis, audio.durationMillis), duration, video.bytes + audio.bytes, video.complete && audio.complete)
        }
        var duration = 0L
        var continuous = true
        for (segment in manifest.segments) {
            if (segment.start + segment.duration <= start) continue
            if (continuous && segmentReady(segment)) duration += segment.duration - (start - segment.start).coerceAtLeast(0L)
            else continuous = false
        }
        val bytes = (manifest.segments.map { it.entry } + manifest.resources).distinct().sumOf { it.available }
        return StreamCacheStats(duration, manifest.duration, bytes, !manifest.live && manifest.segments.isNotEmpty() && manifest.segments.all(::segmentReady))
    }

    /**
     * 输入：回源地址、内容身份键、进度回调与取消判断；
     * 输出：完整下载整棵播放树（清单 + 已选变体/音轨 + 全部分片 + 密钥/init map），供断网起播。
     * 该函数在离线队列工作线程阻塞执行，内部只做调度，实际网络任务仍由下载池并发完成。
     */
    fun downloadOffline(
        url: String,
        contentKey: String,
        onProgress: (OfflineDownloadProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
        networkAllowed: () -> Boolean = ::offlineAllowed,
    ): OfflineDownloadResult {
        val root = synchronized(playbackSelection) {
            entry(url, contentKey).also { value ->
                offlineRoots[contentKey] = value
                collectTree(value).forEach { it.deleteWhenUnused = false }
                markOffline(value)
            }
        }
        var lastBytes = -1L
        var idleSince = SystemClock.elapsedRealtime()
        try {
            initialize(root)
            while (!closed) {
                if (cancelled()) return OfflineDownloadResult(root.id, false, true)
                if (!networkAllowed() || !offlineAllowed()) {
                    return OfflineDownloadResult(root.id, waitingForNetwork = true)
                }
                val failure = driveOffline(root)
                val progress = offlineProgress(root)
                if (progress.fraction == 1f) {
                    root.fetchedAt = System.currentTimeMillis()
                    saveInfo(root)
                    onProgress(progress)
                    return OfflineDownloadResult(root.id, true, false)
                }
                onProgress(progress)
                if (failure != null) return offlineFailure(root, failure)
                if (!online()) return OfflineDownloadResult(root.id, waitingForNetwork = true)
                val now = SystemClock.elapsedRealtime()
                if (progress.bytes != lastBytes) {
                    lastBytes = progress.bytes
                    idleSince = now
                }
                if (now - idleSince > StreamCacheTuning.OfflineIdleMillis) {
                    val issue = collectTree(root).firstNotNullOfOrNull { value ->
                        if (value.offlineRun && !value.complete) value.offlineIssue else null
                    }
                    return offlineFailure(root, issue?.message ?: "下载超时，请重试或换线路")
                }
                Thread.sleep(StreamCacheTuning.WaitMillis)
            }
            return OfflineDownloadResult(root.id, false, true)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return OfflineDownloadResult(root.id, false, true)
        } catch (_: IOException) {
            return offlineFailure(root, "无法读写缓存文件，请检查设备存储后重试")
        } finally {
            stopOfflineTree(root)
            saveInfoSafely(root)
        }
    }

    /** 输入：根条目；输出：暂停时立即停止所有分支的下载任务，已落盘前缀保留供续传。 */
    fun pauseOffline(url: String, contentKey: String) {
        offlinePolicyCheckedAt = 0L
        val root = entries[url] ?: byId[contentKey] ?: return
        stopOfflineTree(root)
        saveInfoSafely(root)
    }

    /** Restore logical ownership from the existing queue file, including older URL-shared downloads. */
    fun restoreOfflineOwners(owners: List<Pair<String, String>>) {
        initialized.await()
        owners.forEach { (url, key) ->
            val root = entry(url, key)
            offlineRoots[key] = root
            restoreTree(root).forEach { value ->
                value.pinned = true
                value.deleteWhenUnused = false
            }
        }
    }

    private fun restoreTree(root: Entry): List<Entry> {
        val seen = mutableSetOf<Entry>()
        fun visit(value: Entry, depth: Int) {
            if (depth > 8 || !seen.add(value)) return
            initialize(value)
            value.manifest?.let { manifest ->
                (manifest.resources + manifest.variants + manifest.segments.map { it.entry } +
                    manifest.segments.flatMap { it.dependencies }).forEach { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        return seen.toList()
    }

    /** Release one record; shared HLS resources and active playback remain available. */
    fun deleteOffline(url: String, contentKey: String): Long {
        val root = offlineRoots.remove(contentKey) ?: runCatching { entry(url, contentKey) }.getOrNull() ?: return 0L
        val candidates = restoreTree(root)
        val retained = offlineRoots.values.distinct().flatMap { restoreTree(it) }.toSet()
        candidates.filter { it !in retained }.forEach { value ->
            value.pinned = false
            value.offlineRun = false
            value.deleteWhenUnused = true
            if (value.requests == 0) {
                value.epoch++
                runCatching { value.connection?.disconnect() }
            }
            saveInfoSafely(value)
        }
        val freed = cleanupDeletedEntries()
        scheduleDeletedCleanup()
        return freed
    }

    private fun scheduleDeletedCleanup() {
        if (closed || byId.values.none { it.deleteWhenUnused }) return
        runCatching { control.execute { cleanupDeletedEntries() } }
    }

    private fun cleanupDeletedEntries(): Long = synchronized(playbackSelection) {
        var freed = 0L
        val playing = listOfNotNull(current, next).flatMap { collectTree(it) }.toSet()
        val owned = offlineRoots.values.distinct().flatMap { collectTree(it) }.toSet()
        byId.values.toList().distinct().forEach { value ->
            if (value in playing || value in owned || !value.deleteWhenUnused) return@forEach
            synchronized(value.signal) {
                synchronized(registration) reclaim@{
                    if (byId[value.id] !== value || !value.deleteWhenUnused || value.pinned || value.downloading ||
                        value.requests > 0 || value.roles != 0) return@reclaim
                    val files = directory.listFiles()?.filter { it.nameWithoutExtension.substringBefore("-range-") == value.id }.orEmpty()
                    if (files.any { (activeRangeFiles[it.absolutePath]?.get() ?: 0L) > 0L }) return@reclaim
                    var removedAll = true
                    files.forEach { file ->
                        val size = if (file.extension == "bin") file.length() else 0L
                        if (file.delete()) {
                            freed += size
                            totalBytes.addAndGet(-size)
                            rangeFiles.remove(file.absolutePath)
                        } else if (file.exists()) removedAll = false
                    }
                    if (!removedAll) return@reclaim
                    entries.remove(value.url, value)
                    byId.remove(value.id, value)
                }
            }
        }
        freed
    }

    /** 输入：根条目；输出：已完成主动缓存在磁盘上的实际占用。 */
    fun offlineBytes(url: String, contentKey: String): Long {
        val root = entries[url] ?: byId[contentKey] ?: return 0L
        return directory.listFiles()?.filter { file ->
            val base = file.nameWithoutExtension.substringBefore("-range-")
            file.extension == "bin" && (base == root.id || base.startsWith("${root.id}-"))
        }?.sumOf { it.length() } ?: 0L
    }

    /** 输入：根条目；输出：主动缓存是否完整可离线播放。 */
    fun offlineReady(url: String, contentKey: String): Boolean {
        val root = entries[url] ?: byId[contentKey] ?: return false
        initialize(root)
        return offlineTreeComplete(root)
    }

    private fun offlineFailure(root: Entry, message: String): OfflineDownloadResult {
        saveInfoSafely(root)
        return OfflineDownloadResult(root.id, false, false, message)
    }

    private fun saveInfoSafely(value: Entry) {
        runCatching { if (value.initialized) saveInfo(value) }
    }

    private fun markOffline(value: Entry) {
        synchronized(value.signal) {
            if (!value.offlineRun) {
                value.offlineIssue = null
                value.fullRestartHighWater = 0L
                value.stalledFullRestarts = 0
                value.lastAttempt = 0L
            }
            value.pinned = true
            value.deleteWhenUnused = false
            value.offlineRun = true
        }
    }

    /** 调度已选清单与分片，每条轨道只提交一小批。 */
    private fun driveOffline(value: Entry, depth: Int = 0): String? {
        if (depth > 6) return "片源的播放列表嵌套过深，请换线路"
        markOffline(value)
        if (value.manifest?.live == true) return "直播内容不支持离线缓存"
        ensure(value)
        if (value.limited) return "存储空间不足，请先清理缓存"
        val issue = value.offlineIssue
        if (issue?.terminal == true) return issue.message
        val manifest = value.manifest ?: return null
        // 只下载解密/init 资源；字幕等未选中的 MEDIA 清单不进入离线产物。
        manifest.resources.filter { it.auxiliary }.forEach { child ->
            driveOffline(child, depth + 1)?.let { return it }
        }
        if (manifest.variants.isNotEmpty()) {
            val video = offlineVariant(value, manifest) ?: return null
            val audio = offlineAudio(value, manifest)
            // 固定离线轨道，播放中切换清晰度时也沿用同一份清单。
            if (value.offlineVariantId != video.id || value.offlineAudioId != audio?.id) {
                value.offlineAudioId = audio?.id
                value.offlineVariantId = video.id
                saveInfoSafely(value)
            }
            listOfNotNull(video, audio).distinct().forEach { track ->
                driveOffline(track, depth + 1)?.let { return it }
            }
            return null
        }
        if (manifest.segments.isEmpty()) return "片源的播放列表没有可下载的分片，请换线路"
        var submitted = 0
        for (segment in manifest.segments) {
            if (segmentReady(segment)) continue
            if (submitted >= StreamCacheTuning.SegmentsPerTrack) break
            segment.dependencies.forEach { dependency -> driveOffline(dependency, depth + 1)?.let { return it } }
            driveOffline(segment.entry, depth + 1)?.let { return it }
            submitted++
        }
        return null
    }

    private fun offlineVariant(value: Entry, manifest: Manifest): Entry? =
        manifest.variants.firstOrNull { it.id == value.offlineVariantId }
            ?: value.selectedVariant?.takeIf { value.offlineVariantId == null && it in manifest.variants }
            ?: manifest.variants.firstOrNull()

    private fun offlineAudio(value: Entry, manifest: Manifest): Entry? =
        manifest.resources.firstOrNull { it.id == value.offlineAudioId }
            ?: value.selectedAudio?.takeIf { value.offlineVariantId == null && it in manifest.resources }
            ?: manifest.defaultAudio

    private fun offlineTreeComplete(value: Entry, depth: Int = 0): Boolean {
        if (depth > 6 || !value.initialized || !value.complete) return false
        val manifest = value.manifest ?: return value.length >= 0L
        if (manifest.live || manifest.resources.any { it.auxiliary && !it.complete }) return false
        if (manifest.variants.isNotEmpty()) {
            val video = offlineVariant(value, manifest) ?: return false
            val audio = offlineAudio(value, manifest)
            return offlineTreeComplete(video, depth + 1) &&
                (audio == null || offlineTreeComplete(audio, depth + 1))
        }
        return manifest.segments.isNotEmpty() && manifest.segments.all(::segmentReady)
    }

    private fun offlineProgress(root: Entry): OfflineDownloadProgress {
        val length = root.length
        val complete = offlineTreeComplete(root)
        if (root.manifest == null) {
            val bytes = root.available
            val fraction = when {
                complete -> 1f
                length > 0L -> (bytes.toFloat() / length).coerceIn(0f, 0.99f)
                else -> null
            }
            return OfflineDownloadProgress(fraction, bytes)
        }

        val entries = mutableSetOf<Entry>()
        val tracks = mutableSetOf<Entry>()
        var inventoryKnown = true
        var total = 0
        var ready = 0
        fun visit(value: Entry, depth: Int) {
            if (depth > 6) {
                inventoryKnown = false
                return
            }
            if (!tracks.add(value)) return
            entries += value
            val manifest = value.manifest
            when {
                manifest == null -> {
                    // A master may reference a single-file rendition instead of a media playlist.
                    if (value.complete) {
                        total++
                        ready++
                    } else {
                        inventoryKnown = false
                    }
                }
                manifest.live -> inventoryKnown = false
                manifest.variants.isNotEmpty() -> {
                    offlineVariant(value, manifest)?.let { visit(it, depth + 1) }
                    offlineAudio(value, manifest)?.let { visit(it, depth + 1) }
                }
                else -> {
                    total += manifest.segments.size
                    for (segment in manifest.segments) {
                        entries += segment.entry
                        entries.addAll(segment.dependencies)
                        if (segmentReady(segment)) ready++
                    }
                }
            }
            manifest?.resources?.filterTo(entries) { it.auxiliary }
        }
        visit(root, 0)
        // Wait for every selected media playlist before showing a fixed segment denominator.
        val fraction = when {
            complete -> 1f
            inventoryKnown && total > 0 -> (ready.toFloat() / total).coerceAtMost(0.99f)
            else -> null
        }
        return OfflineDownloadProgress(fraction, entries.sumOf { it.available })
    }

    private fun collectTree(root: Entry): List<Entry> {
        val seen = mutableSetOf<Entry>()
        fun visit(value: Entry) {
            if (!seen.add(value)) return
            value.manifest?.let { manifest ->
                manifest.resources.forEach(::visit)
                manifest.variants.forEach(::visit)
                manifest.segments.forEach { segment ->
                    segment.dependencies.forEach(::visit)
                    visit(segment.entry)
                }
            }
        }
        visit(root)
        return seen.toList()
    }

    /** 输入：根条目；输出：立即停止离线运行标志并中断进行中的连接，暂停/取消后不会继续写盘。 */
    private fun stopOfflineTree(root: Entry) {
        collectTree(root).forEach { value ->
            value.offlineRun = false
            if (value.downloading && value.requests == 0) {
                value.epoch++
                runCatching { value.connection?.disconnect() }
            }
            synchronized(value.signal) { value.signal.notifyAll() }
        }
    }

    /** 输入：主动缓存的 master 清单；输出：只含已下载变体与音轨的本地清单，断网也能被播放器解析。 */
    private fun offlineMasterText(value: Entry, manifest: Manifest): String {
        val video = offlineVariant(value, manifest) ?: return manifest.text
        val audio = offlineAudio(value, manifest)
        val audioUrl = audio?.let(::localUrl)
        val output = StringBuilder()
        var pendingStream: String? = null
        for (raw in manifest.text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    if (line.contains("TYPE=AUDIO") && audioUrl != null &&
                        line.contains("URI=\"$audioUrl\"")) output.append(line).append('\n')
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingStream = line
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> pendingStream = null
                line.startsWith("#") -> output.append(line).append('\n')
                line.isNotBlank() -> {
                    // 只有与已下载变体匹配的那条 URI 才连同前面的 STREAM-INF 一起输出。
                    if (pendingStream != null) {
                        if (line == localUrl(video)) output.append(pendingStream).append('\n').append(line).append('\n')
                        pendingStream = null
                    } else {
                        output.append(line).append('\n')
                    }
                }
            }
        }
        return output.toString()
    }

    private fun awaitReady(value: Entry): Boolean {
        val deadline = System.currentTimeMillis() + StreamCacheTuning.RequestWaitMillis
        while (!closed && !Thread.currentThread().isInterrupted && System.currentTimeMillis() < deadline) {
            if (value.initialized && (value.manifest != null || value.available > 0L || value.length >= 0L)) return true
            ensure(value)
            synchronized(value.signal) { value.signal.wait(StreamCacheTuning.WaitMillis) }
        }
        return false
    }

    /** 输入：回环客户端请求；输出：Range 或重写列表，未缓存的远端 seek 直接转发不等待整集。 */
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = StreamCacheTuning.ReadMillis
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = reader.readLine()?.split(' ') ?: return
            if (request.size < 2) return
            val id = request[1].substringBefore('?').split('/').getOrNull(1) ?: return
            val value = byId[id] ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':', "").trim()
                line = reader.readLine()
            }
            synchronized(value.signal) {
                if (byId[id] !== value) return
                value.requestCount.incrementAndGet()
                value.sockets += socket
            }
            value.lastAccess = System.currentTimeMillis()
            value.parentMasters.forEach { it.selectedVariant = value }
            value.parentAudioMasters.forEach { it.selectedAudio = value }
            try {
                val output = socket.getOutputStream()
                // next 的原生预准备请求也必须让路，不能凭 requests>0 绕过门槛。
                if (value.roles == 2 && !value.pinned && !allowedNext()) {
                    respond(output, 503, mapOf("Retry-After" to "1", "Content-Length" to "0")); return
                }
                if (closed || (value.roles == 0 && !value.pinned)) return
                if (value.bypass && !value.pinned) { respond(output, 302, mapOf("Location" to value.url, "Content-Length" to "0")); return }
                initialize(value)
                val earlyRange = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
                val earlyStart = earlyRange?.groupValues?.get(1)?.toLongOrNull()
                val earlyEnd = earlyRange?.groupValues?.get(2)?.toLongOrNull()
                if (earlyStart != null && earlyEnd != null && earlyEnd < earlyStart) {
                    respond(output, 416, mapOf("Content-Length" to "0")); return
                }
                if (earlyStart != null && earlyStart > value.available && value.manifest == null &&
                    request[0] != "HEAD") {
                    value.prefixSuppressed = true
                    requestPriorityUpdate()
                    relay(value, output, earlyStart, earlyRange.groupValues[2].toLongOrNull(), false, true)
                    return
                }
                if (value.prefixSuppressed && (earlyStart == null || earlyStart <= value.available)) value.prefixSuppressed = false
                ensure(value)
                if (!awaitReady(value)) { respond(output, 302, mapOf("Location" to value.url, "Content-Length" to "0")); return }
                val manifest = value.manifest
                if (manifest != null) {
                    // 主动缓存的 master 清单只保留已下载的变体和音轨，断网时播放器不会去取未缓存的分片。
                    val text = if (value.pinned && manifest.variants.isNotEmpty()) offlineMasterText(value, manifest) else manifest.text
                    val bytes = text.toByteArray()
                    respond(output, 200, mapOf("Content-Type" to value.mime, "Content-Length" to bytes.size.toString()))
                    if (request[0] != "HEAD") output.write(bytes)
                    return
                }
                val range = Regex("bytes=(\\d*)-(\\d*)").matchEntire(headers["range"].orEmpty())
                val suffix = range?.groupValues?.get(1).orEmpty()
                val endText = range?.groupValues?.get(2).orEmpty()
                val start = if (range != null && suffix.isEmpty() && value.length > 0) (value.length - (endText.toLongOrNull() ?: 0L)).coerceAtLeast(0L) else suffix.toLongOrNull() ?: 0L
                val end = if (range != null && suffix.isNotEmpty()) endText.toLongOrNull() else null
                if ((end != null && end < start) || (range != null && suffix.isEmpty() && (endText.toLongOrNull() ?: 0L) <= 0L)) {
                    val invalidRange = mutableMapOf("Content-Length" to "0")
                    if (value.length >= 0L) invalidRange["Content-Range"] = "bytes */${value.length}"
                    respond(output, 416, invalidRange)
                    return
                }
                if (start > value.available || (value.limited && start >= value.available)) {
                    value.prefixSuppressed = true; requestPriorityUpdate()
                    relay(value, output, start, end, request[0] == "HEAD", true); return
                }
                val last = end?.let { if (value.length >= 0) min(it, value.length - 1) else it } ?: (value.length - 1)
                val responseHeaders = mutableMapOf("Content-Type" to value.mime, "Accept-Ranges" to "bytes")
                if (value.length >= 0) {
                    if (start >= value.length) { respond(output, 416, mapOf("Content-Range" to "bytes */${value.length}", "Content-Length" to "0")); return }
                    responseHeaders["Content-Length"] = (last - start + 1).toString()
                    if (range != null) responseHeaders["Content-Range"] = "bytes $start-$last/${value.length}"
                }
                respond(output, if (range != null && value.length >= 0) 206 else 200, responseHeaders)
                if (request[0] == "HEAD") return
                var position = start
                RandomAccessFile(value.file, "r").use { input ->
                    input.seek(start)
                    val buffer = ByteArray(StreamCacheTuning.ReadBytes)
                    var lastProgress = System.currentTimeMillis()
                    while (!closed && (last < 0L || position <= last)) {
                        val available = value.available - position
                        if (available > 0L) {
                            val remaining = if (last >= 0) min(available, last - position + 1) else available
                            val count = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                            if (count > 0) { output.write(buffer, 0, count); position += count; value.readPosition = position; lastProgress = System.currentTimeMillis(); continue }
                        }
                        if (value.complete) break
                        if (value.limited || value.bypass ||
                            (value.roles == 2 && value.timelineStart == Long.MAX_VALUE && value.available >= StreamCacheTuning.NextAheadBytes) ||
                            System.currentTimeMillis() - lastProgress > StreamCacheTuning.RequestWaitMillis) {
                            if (value.roles == 2 && value.timelineStart == Long.MAX_VALUE && value.available >= StreamCacheTuning.NextAheadBytes) {
                                value.prefixSuppressed = true
                                requestPriorityUpdate()
                            }
                            relay(value, output, position, end, false, false)
                            break
                        }
                        ensure(value)
                        synchronized(value.signal) { value.signal.wait(StreamCacheTuning.WaitMillis) }
                    }
                }
            } finally {
                synchronized(value.signal) { value.requestCount.decrementAndGet(); value.sockets -= socket }
                scheduleDeletedCleanup()
            }
        } catch (_: Exception) { /* 客户端切集/拖动关闭连接属于正常取消。 */ }
    }

    private fun respond(output: OutputStream, status: Int, headers: Map<String, String>) {
        val reason = when (status) { 206 -> "Partial Content"; 302 -> "Found"; 416 -> "Range Not Satisfiable"; 503 -> "Service Unavailable"; else -> "OK" }
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun open(url: String, start: Long?, end: Long?, validator: String? = null): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = StreamCacheTuning.ConnectMillis
        readTimeout = StreamCacheTuning.ReadMillis
        instanceFollowRedirects = true
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Moying/1.4")
        if (start != null) setRequestProperty("Range", "bytes=$start-${end ?: ""}")
        if (start != null && validator != null) setRequestProperty("If-Range", validator)
    }

    private fun initializeRanges(value: Entry) = synchronized(value.ranges) {
        if (value.rangesInitialized) return
        initialized.await()
        val prefix = "${value.id}-range-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "bin" }?.forEach { file ->
            val start = file.nameWithoutExtension.removePrefix(prefix).toLongOrNull() ?: return@forEach
            val span = RangeSpan(start, file)
            runCatching {
                val saved = Properties().apply { span.info.inputStream().use { load(it) } }
                span.available = min(file.length(), saved.getProperty("bytes", "0").toLong())
                span.total = saved.getProperty("total", "-1").toLong()
                span.validator = saved.getProperty("validator")
                if (value.validator != null && span.validator != value.validator) span.available = 0L
                if (value.length < 0 && span.total >= 0) value.length = span.total
            }
            value.ranges[start] = span
            rangeFiles[file.absolutePath] = span
        }
        value.rangesInitialized = true
    }

    /** 稀疏区间按实际请求位置落盘，边读边输出；相同区间的并发读取复用同一份数据。 */
    private fun rangeRelay(value: Entry, output: OutputStream, start: Long, end: Long?, withHeaders: Boolean): Boolean {
        initializeRanges(value)
        if (value.length >= 0 && start >= value.length) {
            if (withHeaders) respond(output, 416, mapOf("Content-Range" to "bytes */${value.length}", "Content-Length" to "0"))
            return true
        }
        var position = start
        var headersSent = !withHeaders
        val buffer = ByteArray(StreamCacheTuning.ReadBytes)
        while (!closed && value.roles != 0 && (end == null || position <= end) && (value.length < 0 || position < value.length)) {
            val existing = value.ranges.floorEntry(position)?.value?.takeIf { position < it.start + it.available }
            val span = existing ?: value.ranges.computeIfAbsent(position) { offset ->
                RangeSpan(offset, File(directory, "${value.id}-range-$offset.bin")).also { rangeFiles[it.file.absolutePath] = it }
            }
            activeRangeFiles.compute(span.file.absolutePath) { _, count -> (count ?: AtomicLong()).also { it.incrementAndGet() } }
            try { synchronized(span) {
                if (!span.file.exists()) span.available = 0L
                if (value.validator != null && span.validator != null && value.validator != span.validator) span.available = 0L
                fun sendHeaders() {
                    if (headersSent) return
                    val last = end?.let { if (value.length >= 0) min(it, value.length - 1) else it } ?: value.length - 1
                    val fields = mutableMapOf("Content-Type" to value.mime, "Accept-Ranges" to "bytes")
                    if (value.length >= 0) {
                        if (start >= value.length) { respond(output, 416, mapOf("Content-Range" to "bytes */${value.length}", "Content-Length" to "0")); headersSent = true; return }
                        fields["Content-Length"] = (last - start + 1).toString()
                        fields["Content-Range"] = "bytes $start-$last/${value.length}"
                    }
                    respond(output, if (value.length >= 0) 206 else 200, fields)
                    headersSent = true
                }
                val cachedEnd = min(span.start + span.available, end?.plus(1) ?: Long.MAX_VALUE)
                if (position < cachedEnd) {
                    sendHeaders()
                    RandomAccessFile(span.file, "r").use { file ->
                        file.seek(position - span.start)
                        while (position < cachedEnd) {
                            val count = file.read(buffer, 0, min(buffer.size.toLong(), cachedEnd - position).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count); position += count
                        }
                    }
                    value.readPosition = position
                    if ((end != null && position > end) || (value.length >= 0 && position >= value.length)) return true
                }
                if (position >= span.start + StreamCacheTuning.RangeChunkBytes ||
                    value.ranges[position]?.let { it !== span } == true) return@synchronized
                if (position != span.start + span.available) {
                    if (headersSent) { relayDirect(value, output, position, end, false, false); return true }
                    return false
                }
                val chunkEnd = min(span.start + StreamCacheTuning.RangeChunkBytes - 1, end ?: Long.MAX_VALUE)
                val connection = open(value.url, position, chunkEnd, value.validator)
                value.relays += connection
                var sink: FileOutputStream? = null
                try {
                    val status = connection.responseCode
                    val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    val actualStart = contentRange?.groupValues?.get(1)?.toLongOrNull()
                    val actualEnd = contentRange?.groupValues?.get(2)?.toLongOrNull()
                    if (status != 206 || actualStart != position || actualEnd == null || actualEnd < position || actualEnd > chunkEnd) {
                        if (headersSent) { connection.disconnect(); relayDirect(value, output, position, end, false, false); return true }
                        return false
                    }
                    val validator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: connection.getHeaderField("Last-Modified")
                    if (value.validator != null && validator != null && value.validator != validator) {
                        value.bypass = true
                        if (headersSent) throw java.io.IOException("Media changed during range playback")
                        return false
                    }
                    value.validator = validator ?: value.validator
                    span.validator = value.validator
                    val total = contentRange?.groupValues?.get(3)?.toLongOrNull()
                    if (total != null) { value.length = total; span.total = total }
                    value.mime = connection.contentType?.substringBefore(';')?.ifBlank { value.mime } ?: value.mime
                    sendHeaders()
                    val oldBytes = span.file.length()
                    if (span.available == 0L && oldBytes > 0L) { span.file.writeBytes(byteArrayOf()); totalBytes.addAndGet(-oldBytes) }
                    sink = FileOutputStream(span.file, span.available > 0)
                    connection.inputStream.use { input ->
                        var left = actualEnd + 1 - position
                        while (!closed && value.roles != 0 && left > 0L) {
                            val count = input.read(buffer, 0, min(buffer.size.toLong(), left).toInt())
                            if (count < 0) break
                            if (sink != null) {
                                if (totalBytes.get() + count >= StreamCacheTuning.BudgetBytes || directory.usableSpace < StreamCacheTuning.ReserveDiskBytes) trim(null)
                                if (totalBytes.get() + count >= StreamCacheTuning.BudgetBytes || directory.usableSpace < StreamCacheTuning.ReserveDiskBytes) {
                                    sink?.close(); sink = null
                                } else { sink?.write(buffer, 0, count); span.available += count; totalBytes.addAndGet(count.toLong()) }
                            }
                            output.write(buffer, 0, count); position += count; left -= count
                            value.readPosition = position
                        }
                        if (left > 0L) throw java.io.EOFException("Incomplete range response")
                    }
                    span.file.setLastModified(System.currentTimeMillis())
                } finally {
                    sink?.close()
                    if (span.file.exists()) Properties().apply {
                        setProperty("bytes", span.available.toString()); setProperty("total", span.total.toString())
                        span.validator?.let { setProperty("validator", it) }
                    }.also { props -> span.info.outputStream().use { props.store(it, null) } }
                    value.relays -= connection
                    connection.disconnect()
                }
            } } finally { activeRangeFiles.computeIfPresent(span.file.absolutePath) { _, count -> if (count.decrementAndGet() == 0L) null else count } }
        }
        return true
    }

    private fun relay(value: Entry, output: OutputStream, start: Long, end: Long?, head: Boolean, withHeaders: Boolean) {
        if (!head && !value.bypass && rangeRelay(value, output, start, end, withHeaders)) return
        relayDirect(value, output, start, end, head, withHeaders)
    }

    private fun relayDirect(value: Entry, output: OutputStream, start: Long, end: Long?, head: Boolean, withHeaders: Boolean) {
        val connection = open(value.url, start, end)
        value.relays += connection
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                if (withHeaders) respond(output, 302, mapOf("Location" to value.url, "Content-Length" to "0"))
                return
            }
            val partial = status == HttpURLConnection.HTTP_PARTIAL
            if (partial) {
                val returnedStart = Regex("bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (returnedStart != start) {
                    value.bypass = true
                    if (withHeaders) { respond(output, 302, mapOf("Location" to value.url, "Content-Length" to "0")); return }
                    throw java.io.IOException("Mismatched direct range")
                }
            } else if (start > 0L) {
                // 源忽略 Range 返回整段 200：放弃缓存并让播放器重新直连，不把前面字节再走一遍网络。
                value.bypass = true
                if (withHeaders) { respond(output, 302, mapOf("Location" to value.url, "Content-Length" to "0")); return }
                throw java.io.IOException("Range not supported")
            }
            val totalLength = connection.getHeaderField("Content-Range").orEmpty().substringAfterLast('/', "").toLongOrNull() ?: connection.contentLengthLong.takeIf { it >= 0L }
            val receivedLength = if (partial) connection.contentLengthLong else connection.contentLengthLong.takeIf { it >= 0 }?.minus(start) ?: -1L
            val length = end?.let { if (receivedLength >= 0L) min(receivedLength, it - start + 1L) else it - start + 1L } ?: receivedLength
            if (withHeaders) {
                val headers = mutableMapOf("Content-Type" to (connection.contentType ?: value.mime), "Accept-Ranges" to "bytes")
                if (length >= 0) headers["Content-Length"] = length.toString()
                connection.getHeaderField("Content-Range")?.let { headers["Content-Range"] = it }
                if (!partial && start > 0L && totalLength != null && length >= 0L) headers["Content-Range"] = "bytes $start-${start + length - 1}/$totalLength"
                respond(output, if (partial || (start > 0L && totalLength != null)) 206 else 200, headers)
            }
            if (head) return
            connection.inputStream.use { input ->
                val buffer = ByteArray(StreamCacheTuning.ReadBytes)
                var remaining = end?.let { it - start + 1 } ?: Long.MAX_VALUE
                while (!closed && remaining > 0) {
                    val count = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        } finally { value.relays -= connection; connection.disconnect() }
    }

    /** 输入：缓存预算；输出：仅逐出未使用/已看过分片，活动文件不边下载边逐出重下。 */
    @Synchronized private fun trim(writing: Entry?) {
        fun needsSpace() = totalBytes.get() >= StreamCacheTuning.BudgetBytes - StreamCacheTuning.CurrentAheadBytes || directory.usableSpace < StreamCacheTuning.ReserveDiskBytes
        if (!needsSpace()) return
        val candidates = entries.values.filter {
            !it.pinned && it !== writing && !it.downloading && it.requests == 0 &&
                (it.roles == 0 || it.roles == 2 || (it.roles == 1 && it.timelineEnd < positionMillis - StreamCacheTuning.PastKeepMillis))
        }.sortedWith(compareBy<Entry> { if (it.roles == 0) 0 else if (it.roles == 2) 1 else 2 }.thenBy { it.lastAccess })
        for (candidate in candidates) {
            if (!needsSpace()) break
            synchronized(candidate.signal) {
                if (!candidate.downloading && candidate.requests == 0) {
                    val bytes = candidate.file.length()
                    if (candidate.file.delete()) {
                        candidate.available = 0L; candidate.initialized = false
                        candidate.complete = false; candidate.limited = false
                        candidate.info.delete()
                        totalBytes.addAndGet(-bytes)
                    }
                }
            }
        }
        val known = byId.keys
        val pinnedPrefixes = byId.values.filter { it.pinned }.map { it.id }
        fun pinnedFile(file: File): Boolean {
            val base = file.nameWithoutExtension.substringBefore("-range-")
            return pinnedPrefixes.any { base == it || base.startsWith("$it-") }
        }
        directory.listFiles()?.filter {
            it.extension == "bin" && it.nameWithoutExtension !in known && !pinnedFile(it) &&
                (activeRangeFiles[it.absolutePath]?.get() ?: 0L) == 0L
        }?.sortedBy { it.lastModified() }?.forEach {
            if (needsSpace()) {
                activeRangeFiles.compute(it.absolutePath) { _, active ->
                    if ((active?.get() ?: 0L) == 0L) {
                        val size = it.length()
                        if (it.delete()) { totalBytes.addAndGet(-size); rangeFiles[it.absolutePath]?.available = 0L; File(directory, it.nameWithoutExtension + ".properties").delete() }
                    }
                    active
                }
            }
        }
    }

    override fun close() {
        closed = true
        currentJob?.cancel(true)
        nextJob?.cancel(true)
        runCatching { server.close() }
        entries.values.forEach { entry -> synchronized(entry.signal) { entry.signal.notifyAll() } }
        downloads.shutdownNow()
        jobs.shutdownNow()
        acceptor.shutdownNow()
        clients.shutdownNow()
        control.shutdownNow()
        // disconnect 可能触及系统网络实现，另在线程清理，不阻塞 Compose 主线程。
        Thread({ entries.values.forEach { entry ->
            runCatching { entry.connection?.disconnect() }
            entry.relays.forEach { runCatching { it.disconnect() } }
            entry.sockets.forEach { runCatching { it.close() } }
        } }, "Moying-CacheClose").apply { isDaemon = true; start() }
    }

    companion object {
        @Volatile private var shared: StreamingCacheProxy? = null

        /** 输入：应用上下文；输出：进程级单例，播放缓存与主动离线缓存共用同一份条目和端口。 */
        fun get(context: Context): StreamingCacheProxy = shared ?: synchronized(this) {
            shared ?: StreamingCacheProxy(context.applicationContext).also { shared = it }
        }
    }
}

