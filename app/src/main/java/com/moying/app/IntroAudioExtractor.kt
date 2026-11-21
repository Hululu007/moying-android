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
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

internal class IntroAudioScanException(message: String) : IOException(message)

private const val EpisodeByteLimit = 48L * 1024L * 1024L

/** One budget covers both episodes in a comparison. */
internal class IntroAudioScanBudget(private val isCancelled: () -> Boolean = { false }) {
    private val deadline = SystemClock.elapsedRealtime() + 45_000L
    private val bytesRead = AtomicLong()

    fun check() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消片头识别")
        if (SystemClock.elapsedRealtime() >= deadline) throw IntroAudioScanException("片头识别超时，请稍后重试。")
    }

    fun timeoutMillis(): Int {
        check()
        return (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 2_500L).toInt()
    }

    fun remainingBytes(): Long = (2L * EpisodeByteLimit - bytesRead.get()).coerceAtLeast(0L)

    fun addBytes(count: Int) {
        if (bytesRead.addAndGet(count.toLong()) > 2L * EpisodeByteLimit) throw scanSizeLimit()
    }
}

private fun scanSizeLimit() = IntroAudioScanException("片头识别已达到流量上限，请尝试码率较低的线路。")

internal class IntroAudioExtractor(context: Context) {
    private val app = context.applicationContext

    /** Run on a background thread. The PCM buffer is available only during the callback. */
    fun scan(
        url: String,
        budget: IntroAudioScanBudget,
        onPcm: (ByteBuffer, Int, Int, Long) -> Unit,
    ) {
        budget.check()
        try {
            val uri = Uri.parse(url)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> scanNetwork(url, budget, onPcm)
                "content" -> decode(budget, false, onPcm) { it.setDataSource(app, uri, null) }
                "file", null -> {
                    val file = File(uri.path.orEmpty())
                    if (!file.isAbsolute || !file.isFile) throw IntroAudioScanException("无法读取这集的本地视频。")
                    decodeFile(file, budget, false, onPcm)
                }
                else -> throw IntroAudioScanException("这条线路的视频格式暂不支持片头识别。")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IntroAudioScanException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            budget.check()
            throw IntroAudioScanException("片源响应超时，无法读取片头音频。")
        } catch (failure: Exception) {
            budget.check()
            throw IntroAudioScanException("无法读取这集的音频，请尝试另一条线路。")
        }
    }

    private fun scanNetwork(url: String, budget: IntroAudioScanBudget, onPcm: (ByteBuffer, Int, Int, Long) -> Unit) {
        val network = ScanNetwork(budget)
        val probe = network.probe(url)
        val playlist = probe.playlist
        if (playlist != null) {
            val file = File.createTempFile("intro-audio-", ".media", app.cacheDir)
            try {
                downloadHlsPrefix(probe.url, playlist, file, network, budget)
                decodeFile(file, budget, true, onPcm)
            } finally {
                file.delete()
            }
        } else {
            val totalBytes = probe.totalBytes ?: throw IntroAudioScanException("片源不支持分段读取，无法在限额内识别片头。")
            NetworkMediaDataSource(probe.url, totalBytes, network).use { source ->
                try {
                    decode(budget, false, onPcm) { it.setDataSource(source) }
                } finally {
                    source.failure?.let { throw it }
                }
            }
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
        file: File,
        network: ScanNetwork,
        budget: IntroAudioScanBudget,
    ) {
        var url = initialUrl
        var text = firstPlaylist
        repeat(3) {
            budget.check()
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val variant = chooseVariant(url, lines)
            if (variant == null) {
                val prefix = mediaPrefix(url, lines)
                file.outputStream().buffered().use { output ->
                    prefix.forEach { resource -> network.download(resource, output) }
                }
                return
            }
            val next = network.playlist(variant)
            url = next.first
            text = next.second
        }
        throw IntroAudioScanException("HLS 播放列表层级过多，无法识别片头。")
    }

    private data class Resource(val url: String, val range: LongRange? = null)
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

    private fun mediaPrefix(base: String, lines: List<String>): List<Resource> {
        if ("#EXT-X-ENDLIST" !in lines) throw IntroAudioScanException("直播或未完结的 HLS 列表暂不支持片头识别。")
        val prefix = mutableListOf<Resource>()
        var init: Resource? = null
        var elapsedUs = 0L
        var segmentDurationUs = 0L
        var segmentCount = 0
        var pendingRange: String? = null
        var previous: Resource? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:") && attributes(line)["METHOD"] != "NONE" -> {
                    throw IntroAudioScanException("这条 HLS 线路已加密，无法识别片头。")
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    val url = resolve(base, attrs["URI"] ?: throw invalidPlaylist())
                    val nextInit = Resource(url, attrs["BYTERANGE"]?.let { byteRange(it, 0L) })
                    if (segmentCount > 0 && init != nextInit) throw invalidTimeline()
                    init = nextInit
                }
                line == "#EXT-X-DISCONTINUITY" && segmentCount > 0 -> throw invalidTimeline()
                line == "#EXT-X-GAP" -> throw invalidTimeline()
                line.startsWith("#EXTINF:") -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) throw invalidPlaylist()
                    segmentDurationUs = (seconds.coerceAtMost(300.0) * 1_000_000).toLong()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':')
                !line.startsWith('#') -> {
                    if (segmentDurationUs <= 0L) throw invalidPlaylist()
                    if (segmentCount == 0) init?.let(prefix::add)
                    val url = resolve(base, line)
                    val implicitOffset = previous?.takeIf { it.url == url }?.range?.last?.plus(1L)
                    val resource = Resource(url, pendingRange?.let { byteRange(it, implicitOffset) })
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
        return prefix
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
    private fun invalidTimeline() = IntroAudioScanException("这条 HLS 线路开头的时间轴不连续，无法可靠识别片头。")

    private fun resolve(base: String, relative: String): String = URL(URL(base), relative).toString()

    private class ScanNetwork(private val budget: IntroAudioScanBudget) {
        private var episodeBytes = 0L

        data class Probe(val url: String, val playlist: String?, val totalBytes: Long?, val incompletePlaylist: Boolean = false)
        private data class ResponseRange(val start: Long, val end: Long, val total: Long?)

        fun check() = budget.check()

        fun probe(url: String): Probe = open(url, 0L..(ProbeBytes - 1L)) { connection ->
            val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val range = if (partial) responseRange(connection, 0L..(ProbeBytes - 1L)) else null
            val prefix = ByteArrayOutputStream(ProbeBytes)
            connection.inputStream.use { input ->
                val buffer = ByteArray(ProbeBytes)
                val length = range?.let { (it.end - it.start + 1L).toInt() } ?: ProbeBytes
                if (length > remaining()) throw scanSizeLimit()
                while (prefix.size() < length) {
                    budget.check()
                    val size = input.read(buffer, 0, length - prefix.size())
                    if (size < 0) break
                    account(size)
                    prefix.write(buffer, 0, size)
                }
                if (partial && prefix.size() != length) throw IntroAudioScanException("片源返回的数据不完整。")
                val manifest = prefix.toString(Charsets.UTF_8.name()).trimStart('\ufeff', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
                if (manifest && !partial) {
                    copy(input, prefix, ManifestBytes - prefix.size(), null)
                }
                val text = if (manifest) prefix.toString(Charsets.UTF_8.name()) else null
                val incomplete = manifest && range != null && (range.total?.let { it > range.end + 1L } ?: true)
                Probe(connection.url.toString(), text, range?.total, incomplete)
            }
        }.let { probe ->
            if (probe.incompletePlaylist) {
                val (url, playlist) = playlist(probe.url)
                Probe(url, playlist, null)
            } else probe
        }

        fun playlist(url: String): Pair<String, String> = open(url) { connection ->
            val output = ByteArrayOutputStream()
            connection.inputStream.use { copy(it, output, ManifestBytes, null) }
            val text = output.toString(Charsets.UTF_8.name()).trimStart('\ufeff', ' ', '\r', '\n', '\t')
            if (!text.startsWith("#EXTM3U")) throw IntroAudioScanException("片源返回了无效的 HLS 播放列表。")
            connection.url.toString() to text
        }

        fun download(resource: Resource, output: OutputStream) {
            open(resource.url, resource.range) { connection ->
                val expected = if (resource.range != null) {
                    val range = responseRange(connection, resource.range)
                    if (range.end != resource.range.last) throw IntroAudioScanException("HLS 分片未返回完整的字节范围。")
                    range.end - range.start + 1L
                } else connection.contentLengthLong.takeIf { it >= 0L }
                connection.inputStream.use { copy(it, output, SegmentBytes, expected) }
            }
        }

        fun range(url: String, start: Long, end: Long, length: Long): ByteArray = open(url, start..end) { connection ->
            val actual = responseRange(connection, start..end)
            if (actual.end != end || actual.total != length) throw IntroAudioScanException("片源返回了无效的视频字节范围。")
            val output = ByteArrayOutputStream((end - start + 1L).toInt())
            connection.inputStream.use { copy(it, output, (end - start + 1L).toInt(), end - start + 1L) }
            output.toByteArray()
        }

        private inline fun <T> open(raw: String, range: LongRange? = null, read: (HttpURLConnection) -> T): T {
            var url = URL(raw)
            repeat(5) {
                val timeout = budget.timeoutMillis()
                if (url.protocol !in listOf("http", "https")) throw IntroAudioScanException("片源重定向到了不支持的地址。")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = timeout
                    connection.readTimeout = timeout
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Moying/1.4")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Connection", "close")
                    range?.let { connection.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val destination = connection.getHeaderField("Location")
                            ?: throw IntroAudioScanException("片源重定向地址无效。")
                        url = URL(url, destination)
                    } else {
                        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                            throw IntroAudioScanException("片源返回 HTTP $status，无法读取片头音频。")
                        }
                        return read(connection)
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

        private fun copy(input: java.io.InputStream, output: OutputStream, limit: Int, expected: Long?) {
            val allowed = minOf(limit.toLong(), remaining())
            if (expected != null && expected > allowed) throw scanSizeLimit()
            val effectiveLimit = expected ?: allowed
            val data = ByteArray(16 * 1024)
            var written = 0L
            while (written < effectiveLimit) {
                budget.check()
                val size = input.read(data, 0, minOf(data.size.toLong(), effectiveLimit - written).toInt())
                if (size < 0) break
                account(size)
                output.write(data, 0, size)
                written += size
            }
            if (expected != null && written != expected) throw IntroAudioScanException("片源返回的数据不完整。")
            if (expected == null && written == effectiveLimit) throw scanSizeLimit()
        }

        private fun remaining(): Long = minOf(EpisodeByteLimit - episodeBytes, budget.remainingBytes()).coerceAtLeast(0L)

        private fun account(size: Int) {
            episodeBytes += size
            budget.addBytes(size)
            if (episodeBytes > EpisodeByteLimit) throw scanSizeLimit()
        }
    }

    private class NetworkMediaDataSource(
        private val url: String,
        private val length: Long,
        private val network: ScanNetwork,
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
                        network.range(url, start, start + minOf(BlockBytes.toLong(), length - start) - 1L, length)
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
        val Attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        val ContentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
