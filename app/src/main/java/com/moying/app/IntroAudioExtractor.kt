package com.moying.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal open class IntroAudioScanException(message: String) : IOException(message)

internal enum class IntroAudioScanCoverage { FULL, TIMELINE_BOUNDARY, BYTE_LIMIT }

private const val EpisodeByteLimit = 48L * 1024L * 1024L

/** One budget covers both episodes in a comparison. */
internal class IntroAudioScanBudget(
    background: Boolean = false,
    private val isCancelled: () -> Boolean = { false },
) {
    val hlsDownloadWorkers = if (background) 1 else 4
    private val deadline = SystemClock.elapsedRealtime() + 75_000L
    private val bytesRead = AtomicLong()

    fun check() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消片头识别")
        if (SystemClock.elapsedRealtime() >= deadline) throw IntroAudioScanException("片头识别超时，请稍后重试。")
    }

    fun timeoutMillis(limit: Int): Int {
        check()
        return (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, limit.toLong()).toInt()
    }

    fun remainingBytes(): Long = (2L * EpisodeByteLimit - bytesRead.get()).coerceAtLeast(0L)

    fun reserveBytes(count: Int): Int = reserveReadBytes(bytesRead, 2L * EpisodeByteLimit, count)

    fun releaseBytes(count: Int) { bytesRead.addAndGet(-count.toLong()) }
}

private class IntroAudioSizeLimit : IntroAudioScanException("片头识别已达到流量上限，请尝试码率较低的线路。")

private fun scanSizeLimit() = IntroAudioSizeLimit()

private fun reserveReadBytes(counter: AtomicLong, limit: Long, requested: Int): Int {
    while (true) {
        val used = counter.get()
        val reserved = minOf(limit - used, requested.toLong()).toInt()
        if (reserved == 0 || counter.compareAndSet(used, used + reserved)) return reserved
    }
}

private class IntroAudioResponseTimeout(part: String) : SocketTimeoutException("${part}响应超时，已重试一次。")

internal class IntroAudioExtractor(context: Context) {
    private val app = context.applicationContext

    /** Run on a background thread. The PCM buffer is available only during the callback. */
    fun scan(
        url: String,
        budget: IntroAudioScanBudget,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ) = scan(url, budget, emptyMap(), onPcm)

    /** Reports whether an HLS boundary or byte limit shortened the scan. */
    fun scan(
        url: String,
        budget: IntroAudioScanBudget,
        headers: Map<String, String>,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ): IntroAudioScanCoverage {
        budget.check()
        try {
            val uri = Uri.parse(url)
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> scanNetwork(url, budget, MediaRequestHeaders.normalize(headers), onPcm)
                "content" -> {
                    decode(budget, false, onPcm) { it.setDataSource(app, uri, null) }
                    IntroAudioScanCoverage.FULL
                }
                "file", null -> {
                    val file = File(uri.path.orEmpty())
                    if (!file.isAbsolute || !file.isFile) throw IntroAudioScanException("无法读取这集的本地视频。")
                    decodeFile(file, budget, false, onPcm)
                    IntroAudioScanCoverage.FULL
                }
                else -> throw IntroAudioScanException("这条线路的视频格式暂不支持片头识别。")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IntroAudioScanException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            budget.check()
            val message = (failure as? IntroAudioResponseTimeout)?.message
            throw IntroAudioScanException(message ?: "片源响应超时，无法读取片头音频。")
        } catch (failure: Exception) {
            budget.check()
            throw IntroAudioScanException("无法读取这集的音频，请尝试另一条线路。")
        }
    }

    private fun scanNetwork(
        url: String,
        budget: IntroAudioScanBudget,
        headers: Map<String, String>,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ): IntroAudioScanCoverage {
        val network = ScanNetwork(budget)
        val probe = network.probe(url, headers)
        val playlist = probe.playlist
        if (playlist != null) {
            val file = File.createTempFile("intro-audio-", ".media", app.cacheDir)
            try {
                val coverage = downloadHlsPrefix(probe.url, playlist, probe.headers, file, network, budget)
                decodeFile(file, budget, true, onPcm)
                return coverage
            } finally {
                file.delete()
            }
        } else {
            val totalBytes = probe.totalBytes ?: throw IntroAudioScanException("片源不支持分段读取，无法在限额内识别片头。")
            NetworkMediaDataSource(probe.url, totalBytes, network, probe.headers).use { source ->
                try {
                    decode(budget, false, onPcm) { it.setDataSource(source) }
                } finally {
                    source.failure?.let { throw it }
                }
            }
            return IntroAudioScanCoverage.FULL
        }
    }

    private fun decodeFile(
        file: File,
        budget: IntroAudioScanBudget,
        normalizeStart: Boolean,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ) {
        file.inputStream().use { input -> decode(budget, normalizeStart, onPcm) { it.setDataSource(input.fd) } }
    }

    private fun decode(
        budget: IntroAudioScanBudget,
        normalizeStart: Boolean,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
        open: (MediaExtractor) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            open(extractor)
            budget.check()
            val audioTracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
                .filter { (_, format) -> format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val (trackIndex, format) = audioTracks.firstOrNull { (_, candidate) ->
                candidate.containsKey(MediaFormat.KEY_IS_DEFAULT) && candidate.getInteger(MediaFormat.KEY_IS_DEFAULT) == 1
            } ?: audioTracks.firstOrNull() ?: throw IntroAudioScanException("这集没有可用于片头识别的音轨。")
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            extractor.selectTrack(trackIndex)
            val originUs = if (normalizeStart) extractor.sampleTime.coerceAtLeast(0L) else 0L
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                codec.configure(format, null, null, 0)
                codec.start()
                decodeSamples(extractor, codec, originUs, budget, onPcm)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeSamples(
        extractor: MediaExtractor,
        codec: MediaCodec,
        originUs: Long,
        budget: IntroAudioScanBudget,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var outputFormat: MediaFormat? = null
        var inputEnded = false
        var outputEnded = false
        var delivered = false
        var lastProgress = SystemClock.elapsedRealtime()
        while (!outputEnded) {
            budget.check()
            if (SystemClock.elapsedRealtime() - lastProgress >= 3_000L) {
                throw IntroAudioScanException("读取音轨超时，无法识别片头。")
            }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(5_000L)
                if (index >= 0) {
                    val timestampUs = extractor.sampleTime
                    val data = codec.getInputBuffer(index) ?: throw IntroAudioScanException("无法读取音轨。")
                    // AAC priming samples can have negative presentation timestamps in MP4 edit lists.
                    val size = if (extractor.sampleTrackIndex < 0 || timestampUs - originUs >= ScanDurationUs) -1 else {
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                            throw IntroAudioScanException("这条线路的音轨已加密，无法识别片头。")
                        }
                        extractor.readSampleData(data, 0)
                    }
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, timestampUs, 0)
                        extractor.advance()
                    }
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 5_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                in 0..Int.MAX_VALUE -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val format = outputFormat ?: codec.getOutputFormat(index)
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                            ) throw IntroAudioScanException("设备无法将这条音轨解码为可识别的格式。")
                            val data = codec.getOutputBuffer(index) ?: throw IntroAudioScanException("无法读取解码音轨。")
                            val timestampUs = (info.presentationTimeUs - originUs).coerceAtLeast(0L)
                            data.limit(info.offset + info.size)
                            data.position(info.offset)
                            onPcm(data, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), timestampUs)
                            delivered = true
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
        }
        if (!delivered) throw IntroAudioScanException("没有读到可用于片头识别的音频。")
    }

    private fun downloadHlsPrefix(
        initialUrl: String,
        firstPlaylist: String,
        initialHeaders: Map<String, String>,
        file: File,
        network: ScanNetwork,
        budget: IntroAudioScanBudget,
    ): IntroAudioScanCoverage {
        var url = initialUrl
        var text = firstPlaylist
        var headers = initialHeaders
        repeat(3) {
            budget.check()
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val variant = chooseVariant(url, lines)
            if (variant == null) {
                val prefix = mediaPrefix(url, lines)
                return downloadResources(prefix, file, network, budget, headers)
            }
            val next = network.playlist(variant, headers)
            url = next.url
            text = next.text
            headers = next.headers
        }
        throw IntroAudioScanException("HLS 播放列表层级过多，无法识别片头。")
    }

    private fun downloadResources(
        prefix: MediaPrefix,
        file: File,
        network: ScanNetwork,
        budget: IntroAudioScanBudget,
        headers: Map<String, String>,
    ): IntroAudioScanCoverage {
        val resources = prefix.resources
        if (resources.size == 1) {
            file.outputStream().use { network.download(resources.single(), it, headers) }
            return prefix.coverage
        }
        // Keep a small window in flight and append finished parts in playlist order.
        // Each retry rewinds its own part, including encrypted and byte-range resources.
        val directory = File(file.parentFile, "${file.name}.parts")
        if (!directory.mkdir()) throw IOException("Could not create HLS scan directory")
        val workers = Executors.newFixedThreadPool(minOf(budget.hlsDownloadWorkers, resources.size))
        val pending = ArrayDeque<Pair<File, Future<*>>>()
        var next = 0
        var stopped = false
        fun enqueue() {
            budget.check()
            val resource = resources[next]
            val part = File(directory, (next++).toString())
            pending.addLast(part to workers.submit {
                try {
                    val output = synchronized(directory) {
                        if (stopped) throw CancellationException("已取消片头识别")
                        part.outputStream()
                    }
                    output.use { network.download(resource, it, headers) }
                } finally {
                    synchronized(directory) {
                        if (stopped) {
                            part.delete()
                            directory.delete()
                        }
                    }
                }
            })
        }
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var completed = 0
                while (completed < resources.size) {
                    // Drain existing requests near the limit, then give the next segment priority.
                    val window = if (network.remaining() > 2L * SegmentBytes) budget.hlsDownloadWorkers else 1
                    while (next < resources.size && pending.size < window) enqueue()
                    val (part, future) = pending.first()
                    try {
                        awaitPart(future, budget)
                    } catch (limit: IntroAudioSizeLimit) {
                        if (completed <= prefix.initCount) throw limit
                        return IntroAudioScanCoverage.BYTE_LIMIT
                    }
                    part.inputStream().use { input ->
                        while (true) {
                            budget.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    part.delete()
                    pending.removeFirst()
                    completed++
                }
            }
        } finally {
            // On Android an open file can be unlinked. Prevent any worker from reopening
            // its part after cleanup, and interrupt unneeded requests before deleting it.
            synchronized(directory) { stopped = true }
            pending.forEach { (_, future) -> future.cancel(true) }
            workers.shutdownNow()
            directory.deleteRecursively()
        }
        return prefix.coverage
    }

    private fun awaitPart(future: Future<*>, budget: IntroAudioScanBudget) {
        while (true) {
            try {
                future.get(budget.timeoutMillis(200).toLong(), TimeUnit.MILLISECONDS)
                return
            } catch (_: TimeoutException) {
                // Check cancellation and the shared deadline again, even during a stalled response.
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("已取消片头识别")
            }
        }
    }

    private data class KeyTag(val declaration: Int, val attributes: Map<String, String>)
    private data class Encryption(val declaration: Int, val keyUrl: String, val ivHex: String)
    private data class Resource(val url: String, val range: LongRange? = null, val encryption: Encryption? = null)
    private data class MapTag(val attributes: Map<String, String>, val keys: Map<String, KeyTag>)
    private data class MediaPrefix(val resources: List<Resource>, val initCount: Int, val coverage: IntroAudioScanCoverage)
    private data class Variant(val url: String, val audioGroup: String?, val bandwidth: Long)

    private fun chooseVariant(base: String, lines: List<String>): String? {
        val variants = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val uri = lines.getOrNull(index + 1)?.takeUnless { it.startsWith('#') } ?: return@mapIndexedNotNull null
            val attrs = attributes(line)
            Variant(resolve(base, uri), attrs["AUDIO"], attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                ?: attrs["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE)
        }
        if (variants.isEmpty()) return null
        val variant = variants.minBy { it.bandwidth }
        val audio = lines.asSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == variant.audioGroup }.toList()
        val rendition = audio.firstOrNull { it["DEFAULT"] == "YES" } ?: audio.firstOrNull()
        return rendition?.get("URI")?.let { resolve(base, it) } ?: variant.url
    }

    private fun mediaPrefix(base: String, lines: List<String>): MediaPrefix {
        if ("#EXT-X-ENDLIST" !in lines) throw IntroAudioScanException("直播或未完结的 HLS 列表暂不支持片头识别。")
        if ("#EXT-X-I-FRAMES-ONLY" in lines) throw IntroAudioScanException("仅含关键帧的 HLS 列表暂不支持片头识别。")
        val prefix = mutableListOf<Resource>()
        val keys = mutableMapOf<String, KeyTag>()
        var init: Resource? = null
        var pendingMap: MapTag? = null
        var elapsedUs = 0L
        var segmentDurationUs = 0L
        var segmentCount = 0
        var mediaSequence = 0UL
        var pendingRange: String? = null
        var previous: Resource? = null
        fun result(coverage: IntroAudioScanCoverage) = MediaPrefix(prefix, if (init == null) 0 else 1, coverage)
        for ((lineIndex, line) in lines.withIndex()) {
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line)
                    when (attrs["METHOD"]) {
                        "NONE" -> keys.clear()
                        null -> throw invalidPlaylist()
                        else -> keys[attrs["KEYFORMAT"] ?: "identity"] = KeyTag(lineIndex, attrs)
                    }
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    if (segmentCount > 0) throw invalidPlaylist()
                    mediaSequence = line.substringAfter(':').toULongOrNull() ?: throw invalidPlaylist()
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    // Preserve the keys at the declaration. Resolve the map only if its segment is scanned.
                    pendingMap = MapTag(attributes(line), keys.toMap())
                }
                // The decoded timestamps are normalized once, at the start of the playlist.
                // Keep only media that can share that origin.
                line == "#EXT-X-DISCONTINUITY" && segmentCount > 0 -> return result(IntroAudioScanCoverage.TIMELINE_BOUNDARY)
                line == "#EXT-X-GAP" -> {
                    if (segmentCount == 0) throw IntroAudioScanException("这条 HLS 线路的首个分片缺失，无法定位片头。可以手动设置片头结束点。")
                    return result(IntroAudioScanCoverage.TIMELINE_BOUNDARY)
                }
                line.startsWith("#EXTINF:") -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) throw invalidPlaylist()
                    segmentDurationUs = (seconds.coerceAtMost(300.0) * 1_000_000).toLong()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':')
                !line.startsWith('#') -> {
                    pendingMap?.let { tag ->
                        val nextInit = try {
                            val url = resolve(base, tag.attributes["URI"] ?: throw invalidPlaylist())
                            val range = tag.attributes["BYTERANGE"]?.let { byteRange(it, 0L) }
                            if (segmentCount > 0 && (init?.url != url || init?.range != range)) {
                                return result(IntroAudioScanCoverage.TIMELINE_BOUNDARY)
                            }
                            Resource(url, range, encryption(base, tag.keys, null))
                        } catch (failure: IOException) {
                            if (segmentCount > 0) return result(IntroAudioScanCoverage.TIMELINE_BOUNDARY)
                            throw failure
                        }
                        if (segmentCount > 0 && nextInit != init) return result(IntroAudioScanCoverage.TIMELINE_BOUNDARY)
                        init = nextInit
                        pendingMap = null
                    }
                    if (segmentDurationUs <= 0L) throw invalidPlaylist()
                    if (segmentCount == 0) init?.let(prefix::add)
                    val url = resolve(base, line)
                    val implicitOffset = previous?.takeIf { it.url == url }?.range?.last?.plus(1L)
                    val sequence = mediaSequence + segmentCount.toULong()
                    if (sequence < mediaSequence) throw invalidPlaylist()
                    val resource = Resource(url, pendingRange?.let { byteRange(it, implicitOffset) }, encryption(base, keys, sequence))
                    prefix += resource
                    previous = resource
                    pendingRange = null
                    elapsedUs += segmentDurationUs
                    segmentDurationUs = 0L
                    segmentCount++
                    if (elapsedUs >= ScanDurationUs || segmentCount >= SegmentLimit) break
                }
            }
        }
        if (segmentCount == 0) throw invalidPlaylist()
        return result(IntroAudioScanCoverage.FULL)
    }

    private fun encryption(base: String, keys: Map<String, KeyTag>, sequence: ULong?): Encryption? {
        if (keys.isEmpty()) return null
        val key = keys["identity"] ?: throw unsupportedEncryption()
        if (keys.values.any { it.attributes["METHOD"] != "AES-128" }) throw unsupportedEncryption()
        val versions = key.attributes["KEYFORMATVERSIONS"]?.split('/') ?: listOf("1")
        if ("1" !in versions) throw unsupportedEncryption()
        val keyUri = key.attributes["URI"]?.takeIf { it.isNotBlank() } ?: throw invalidKey()
        val keyUrl = try { resolve(base, keyUri) } catch (_: IOException) {
            throw invalidKey()
        }
        if (!keyUrl.startsWith("https://", true) && !keyUrl.startsWith("http://", true)) throw invalidKey()
        val explicit = key.attributes["IV"]
        val iv = if (explicit != null) {
            if (!HexIv.matches(explicit)) throw IntroAudioScanException("HLS 播放列表的 AES-128 IV 无效。")
            explicit.substring(2)
        } else {
            sequence?.toString(16) ?: throw IntroAudioScanException("加密 HLS 的初始化分片缺少 AES-128 IV。")
        }
        return Encryption(key.declaration, keyUrl, iv.lowercase().padStart(32, '0'))
    }

    private fun byteRange(value: String, implicitOffset: Long?): LongRange {
        val length = value.substringBefore('@').toLongOrNull() ?: throw invalidPlaylist()
        val offset = if ('@' in value) value.substringAfter('@').toLongOrNull() else implicitOffset
        if (offset == null || offset < 0L || length <= 0L || offset > Long.MAX_VALUE - length) throw invalidPlaylist()
        return offset until offset + length
    }

    private fun attributes(line: String): Map<String, String> = Attribute.findAll(line.substringAfter(':'))
        .associate { match -> match.groupValues[1] to match.groupValues[2].removeSurrounding("\"") }

    private fun invalidPlaylist() = IntroAudioScanException("HLS 播放列表格式无法识别。")
    private fun unsupportedEncryption() = IntroAudioScanException("这条 HLS 线路使用暂不支持的加密方式，无法识别片头。")
    private fun invalidKey() = IntroAudioScanException("HLS 播放列表的 AES-128 密钥地址无效。")

    private fun resolve(base: String, relative: String): String = URL(URL(base), relative).toString()

    private class ScanNetwork(private val budget: IntroAudioScanBudget) {
        private val episodeBytes = AtomicLong()
        private val keys = ConcurrentHashMap<Int, ByteArray>()

        data class Probe(
            val url: String,
            val playlist: String?,
            val totalBytes: Long?,
            val headers: Map<String, String>,
            val incompletePlaylist: Boolean = false,
        )
        data class Playlist(val url: String, val text: String, val headers: Map<String, String>)
        private data class ResponseRange(val start: Long, val end: Long, val total: Long?)

        fun check() = budget.check()

        fun probe(url: String, headers: Map<String, String>): Probe =
            open(url, "片源入口", headers, 0L..(ProbeBytes - 1L)) { connection, effectiveHeaders ->
            val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val range = if (partial) responseRange(connection, 0L..(ProbeBytes - 1L)) else null
            val prefix = ByteArrayOutputStream(ProbeBytes)
            connection.inputStream.use { input ->
                val buffer = ByteArray(ProbeBytes)
                val length = range?.let { (it.end - it.start + 1L).toInt() } ?: ProbeBytes
                if (length > remaining()) throw scanSizeLimit()
                while (prefix.size() < length) {
                    budget.check()
                    val size = readBytes(input, buffer, 0, length - prefix.size())
                    if (size < 0) break
                    prefix.write(buffer, 0, size)
                }
                if (partial && prefix.size() != length) throw IntroAudioScanException("片源返回的数据不完整。")
                val manifest = prefix.toString(Charsets.UTF_8.name()).trimStart('\ufeff', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
                if (manifest && !partial) {
                    copy(input, prefix, ManifestBytes - prefix.size(), null)
                }
                val text = if (manifest) prefix.toString(Charsets.UTF_8.name()) else null
                val incomplete = manifest && range != null && (range.total?.let { it > range.end + 1L } ?: true)
                Probe(connection.url.toString(), text, range?.total, effectiveHeaders, incomplete)
            }
        }.let { probe ->
            if (probe.incompletePlaylist) {
                val next = playlist(probe.url, probe.headers)
                Probe(next.url, next.text, null, next.headers)
            } else probe
        }

        fun playlist(url: String, headers: Map<String, String>): Playlist =
            open(url, "HLS 清单", headers) { connection, effectiveHeaders ->
            val output = ByteArrayOutputStream()
            connection.inputStream.use { copy(it, output, ManifestBytes, null) }
            val text = output.toString(Charsets.UTF_8.name()).trimStart('\ufeff', ' ', '\r', '\n', '\t')
            if (!text.startsWith("#EXTM3U")) throw IntroAudioScanException("片源返回了无效的 HLS 播放列表。")
            Playlist(connection.url.toString(), text, effectiveHeaders)
        }

        fun download(resource: Resource, output: FileOutputStream, headers: Map<String, String>) {
            val decryption = resource.encryption?.let { encryption ->
                val key = keys.computeIfAbsent(encryption.declaration) { downloadKey(encryption.keyUrl, headers) }
                val iv = ByteArray(16) { index -> encryption.ivHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                key to iv
            }
            val checkpoint = output.channel.position()
            val rewind: () -> Unit = {
                output.channel.truncate(checkpoint)
                output.channel.position(checkpoint)
            }
            open(resource.url, "HLS 分片", headers, resource.range, rewind) { connection, _ ->
                val cipher = decryption?.let { (key, iv) ->
                    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    }
                }
                val expected = if (resource.range != null) {
                    val range = responseRange(connection, resource.range)
                    if (range.end != resource.range.last) throw IntroAudioScanException("HLS 分片未返回完整的字节范围。")
                    range.end - range.start + 1L
                } else connection.contentLengthLong.takeIf { it >= 0L }
                connection.inputStream.use { copy(it, output, SegmentBytes, expected, cipher) }
            }
        }

        private fun downloadKey(url: String, headers: Map<String, String>): ByteArray {
            // A declaration is used by at least one selected segment or init section, so the
            // segment cap also bounds key requests. Read one extra byte to reject oversized keys.
            val bytes = ByteArray(17)
            if (remaining() < bytes.size) throw scanSizeLimit()
            val count = try {
                open(url, "HLS 密钥", headers) { connection, _ ->
                    val length = connection.contentLengthLong
                    if (length >= 0L && length != 16L) return@open 0
                    val limit = if (length == 16L) 16 else bytes.size
                    connection.inputStream.use { input ->
                        var received = 0
                        while (received < limit) {
                            budget.check()
                            val size = readBytes(input, bytes, received, limit - received)
                            if (size < 0) break
                            received += size
                        }
                        received
                    }
                }
            } catch (timeout: IntroAudioResponseTimeout) {
                throw timeout
            } catch (limit: IntroAudioSizeLimit) {
                throw limit
            } catch (_: IOException) {
                budget.check()
                throw IntroAudioScanException("无法读取 HLS 解密密钥，请确认这条线路可正常播放。")
            }
            if (count != 16) throw IntroAudioScanException("片源返回了无效的 HLS AES-128 密钥。")
            return bytes.copyOf(count)
        }

        fun range(url: String, start: Long, end: Long, length: Long, headers: Map<String, String>): ByteArray =
            open(url, "视频字节范围", headers, start..end) { connection, _ ->
            val actual = responseRange(connection, start..end)
            if (actual.end != end || actual.total != length) throw IntroAudioScanException("片源返回了无效的视频字节范围。")
            val output = ByteArrayOutputStream((end - start + 1L).toInt())
            connection.inputStream.use { copy(it, output, (end - start + 1L).toInt(), end - start + 1L) }
            output.toByteArray()
        }

        private inline fun <T> open(
            raw: String,
            part: String,
            headers: Map<String, String>,
            range: LongRange? = null,
            rewind: () -> Unit = {},
            read: (HttpURLConnection, Map<String, String>) -> T,
        ): T {
            try {
                return openOnce(raw, headers, range, read)
            } catch (_: SocketTimeoutException) {
                budget.check()
                // Keep transferred bytes charged to the scan, even when discarding a partial response.
                rewind()
            }
            try {
                return openOnce(raw, headers, range, read)
            } catch (_: SocketTimeoutException) {
                budget.check()
                throw IntroAudioResponseTimeout(part)
            }
        }

        private inline fun <T> openOnce(
            raw: String,
            headers: Map<String, String>,
            range: LongRange?,
            read: (HttpURLConnection, Map<String, String>) -> T,
        ): T {
            var url = URL(raw)
            var effectiveHeaders = headers
            repeat(5) {
                if (url.protocol !in listOf("http", "https")) throw IntroAudioScanException("片源重定向到了不支持的地址。")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = budget.timeoutMillis(ConnectTimeoutMillis)
                    connection.readTimeout = budget.timeoutMillis(ReadTimeoutMillis)
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Moying/1.4")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    effectiveHeaders.forEach(connection::setRequestProperty)
                    range?.let { connection.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val destination = connection.getHeaderField("Location")
                            ?: throw IntroAudioScanException("片源重定向地址无效。")
                        val target = URL(url, destination)
                        effectiveHeaders = MediaRequestHeaders.forRedirect(effectiveHeaders, url, target)
                        url = target
                    } else {
                        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                            throw IntroAudioScanException("片源返回 HTTP $status，无法读取片头音频。")
                        }
                        return read(connection, effectiveHeaders)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IntroAudioScanException("片源重定向次数过多，无法读取片头音频。")
        }

        private fun responseRange(connection: HttpURLConnection, request: LongRange): ResponseRange {
            val parts = ContentRange.matchEntire(connection.getHeaderField("Content-Range").orEmpty())?.groupValues
            val start = parts?.get(1)?.toLongOrNull()
            val end = parts?.get(2)?.toLongOrNull()
            val total = parts?.get(3)?.toLongOrNull()
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL || start != request.first ||
                end == null || end !in request || (total != null && total <= end)
            ) throw IntroAudioScanException("片源不支持有效的分段读取，无法识别片头。")
            return ResponseRange(start, end, total)
        }

        private fun copy(input: InputStream, output: OutputStream, limit: Int, expected: Long?, cipher: Cipher? = null) {
            val allowed = minOf(limit.toLong(), remaining())
            if (expected != null && expected > allowed) throw scanSizeLimit()
            val effectiveLimit = expected ?: allowed
            val data = ByteArray(16 * 1024)
            var written = 0L
            while (written < effectiveLimit) {
                budget.check()
                val size = readBytes(input, data, 0, minOf(data.size.toLong(), effectiveLimit - written).toInt())
                if (size < 0) break
                if (cipher == null) output.write(data, 0, size)
                else cipher.update(data, 0, size)?.let(output::write)
                written += size
            }
            if (expected != null && written != expected) throw IntroAudioScanException("片源返回的数据不完整。")
            if (expected == null && written == effectiveLimit) throw scanSizeLimit()
            if (cipher != null) {
                if (written == 0L || written % 16L != 0L) throw invalidEncryptedSegment()
                val tail = try { cipher.doFinal() } catch (_: GeneralSecurityException) {
                    throw invalidEncryptedSegment()
                }
                output.write(tail)
            }
        }

        private fun invalidEncryptedSegment() = IntroAudioScanException("HLS 分片无法用片源提供的 AES-128 密钥解密。")

        fun remaining(): Long = minOf(EpisodeByteLimit - episodeBytes.get(), budget.remainingBytes()).coerceAtLeast(0L)

        private fun readBytes(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
            // Reserve before reading so concurrent responses share the same hard limits.
            val episodeReservation = reserveReadBytes(episodeBytes, EpisodeByteLimit, length)
            val allowed = budget.reserveBytes(episodeReservation)
            var size = 0
            try {
                if (allowed == 0) throw scanSizeLimit()
                size = input.read(buffer, offset, allowed)
                return size
            } finally {
                val consumed = size.coerceAtLeast(0)
                budget.releaseBytes(allowed - consumed)
                episodeBytes.addAndGet((consumed - episodeReservation).toLong())
            }
        }
    }

    private class NetworkMediaDataSource(
        private val url: String,
        private val length: Long,
        private val network: ScanNetwork,
        private val headers: Map<String, String>,
    ) : MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 4
        }
        @Volatile var failure: Exception? = null
            private set

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0L || position >= length || failure != null) return -1
            val requested = minOf(size.toLong(), length - position).toInt()
            var copied = 0
            try {
                network.check()
                while (copied < requested) {
                    val source = position + copied
                    val start = source / BlockBytes * BlockBytes
                    val block = blocks.getOrPut(start) {
                        network.range(url, start, start + minOf(BlockBytes.toLong(), length - start) - 1L, length, headers)
                    }
                    val insideBlock = (source - start).toInt()
                    val count = minOf(requested - copied, block.size - insideBlock)
                    block.copyInto(buffer, offset + copied, insideBlock, insideBlock + count)
                    copied += count
                }
            } catch (error: Exception) {
                failure = error
                return -1
            }
            return copied
        }

        override fun getSize(): Long = length

        @Synchronized
        override fun close() = blocks.clear()
    }

    private companion object {
        const val ScanDurationUs = 180_000_000L
        const val SegmentLimit = 120
        const val ManifestBytes = 512 * 1024
        const val ProbeBytes = 4 * 1024
        const val SegmentBytes = 12 * 1024 * 1024
        const val BlockBytes = 512 * 1024
        const val ConnectTimeoutMillis = 6_000
        const val ReadTimeoutMillis = 10_000
        val Attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        val HexIv = Regex("0[xX][0-9a-fA-F]{1,32}")
        val ContentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
