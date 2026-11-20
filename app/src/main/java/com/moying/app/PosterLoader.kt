package com.moying.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** 海报下载、解码与缓存写入分别限流，界面拿到解码结果后即可显示。 */
internal object PosterLoader {
    private const val MEMORY_BYTES = 16 * 1024 * 1024
    private const val ENCODED_MEMORY_BYTES = 8 * 1024 * 1024
    private const val DISK_BYTES = 48L * 1024 * 1024
    private const val TRIM_DISK_BYTES = 42L * 1024 * 1024
    private const val DISK_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_DECODE_PIXELS = 4L * 1024 * 1024
    private const val MAX_TARGET_EDGE = 1536
    private const val SIZE_BUCKET = 32
    private const val SCROLL_DWELL_MILLIS = 40L

    private data class Key(val source: String, val width: Int, val height: Int) {
        fun covers(other: Key): Boolean = width >= other.width && height >= other.height
    }
    private data class Cached(val key: Key, val image: ImageBitmap, val bytes: Int)
    private class Request<T>(val work: Deferred<T>, var subscribers: Int = 0)
    private data class Thumbnail(val context: Context, val key: Key, val bitmap: Bitmap)

    private val memory = object : LruCache<String, Cached>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Cached): Int = value.bytes
    }
    private val encodedMemory = object : LruCache<String, ByteArray>(ENCODED_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A limited number of images may hold encoded bytes while waiting to decode.
    private val imageSlots = Semaphore(6)
    private val downloadSlots = Semaphore(4)
    private val decodeSlots = Semaphore(2)
    private val requestsLock = Any()
    private val imageRequests = HashMap<Key, Request<ImageBitmap?>>()
    private val downloadRequests = HashMap<String, Request<ByteArray?>>()
    private val diskLock = Any()
    private var diskDirectory: File? = null
    private var diskSize = 0L
    private val thumbnails = Channel<Thumbnail>(capacity = 3)

    init {
        workerScope.launch {
            for (thumbnail in thumbnails) writeDisk(thumbnail.context, thumbnail.key, thumbnail.bitmap)
        }
    }

    /** 显示首帧可复用同地址的内存图，不扫描缓存。 */
    fun peek(source: String): ImageBitmap? = memory.get(source)?.image

    private fun memoryImage(key: Key): ImageBitmap? =
        memory.get(key.source)?.takeIf { it.key.covers(key) }?.image

    /** 输入：系统内存等级；输出：仅缩减可重建的海报内存引用，保留磁盘和界面仍在使用的位图。 */
    fun trimMemory(level: Int) {
        when {
            // UI_HIDDEN=20 只是普通转后台，不能按数值大小误判为 RUNNING_LOW=10。
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
                memory.trimToSize(MEMORY_BYTES / 2)
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                memory.evictAll()
        }
        encodedMemory.evictAll()
        // 不 recycle：可见 Image 仍可能引用缓存中的 Bitmap；磁盘缩略图继续用于下次显示。
    }

    /** 输入：控件实际宽高（像素）；输出：有上限的尺寸桶；零尺寸不启动图片任务。 */
    fun requestSize(width: Int, height: Int): IntSize {
        if (width <= 0 || height <= 0) return IntSize.Zero
        fun bucket(value: Int) = ((value.coerceAtMost(MAX_TARGET_EDGE) + SIZE_BUCKET - 1) / SIZE_BUCKET) * SIZE_BUCKET
        return IntSize(bucket(width), bucket(height))
    }

    /** 滚动中的新卡片短暂等待，仍停留在列表的卡片会继续加载。 */
    suspend fun load(context: Context, source: String, width: Int, height: Int, loadingDeferred: Boolean): ImageBitmap? {
        val size = requestSize(width, height)
        if (size == IntSize.Zero || source.isBlank()) return null
        val key = Key(source, size.width, size.height)
        memoryImage(key)?.let { return it }
        return sharedWork(imageRequests, key) {
            if (loadingDeferred) delay(SCROLL_DWELL_MILLIS)
            imageSlots.withPermit { memoryImage(key) ?: loadUncached(context, key) }
        }
    }

    private suspend fun <K, T> sharedWork(
        requests: MutableMap<K, Request<T>>,
        key: K,
        action: suspend () -> T,
    ): T {
        val request = synchronized(requestsLock) {
            val active = requests.getOrPut(key) { Request(workerScope.async(start = CoroutineStart.LAZY) { action() }) }
            active.subscribers++
            active
        }
        request.work.start()
        try {
            return request.work.await()
        } finally {
            synchronized(requestsLock) {
                request.subscribers--
                if (request.subscribers == 0) {
                    requests.remove(key)
                    request.work.cancel()
                }
            }
        }
    }

    private suspend fun encodedPoster(source: String): ByteArray? =
        encodedMemory.get(source) ?: sharedWork(downloadRequests, source) {
            downloadSlots.withPermit {
                encodedMemory.get(source) ?: Net.downloadPoster(source)?.also { encodedMemory.put(source, it) }
            }
        }

    private suspend fun loadUncached(context: Context, key: Key): ImageBitmap? {
        try {
            decodeSlots.withPermit { readDisk(context, key)?.let { rememberImage(key, it) } }?.let { return it }
            currentCoroutineContext().ensureActive()
            val remote = key.source.startsWith("https://", true) || key.source.startsWith("http://", true)
            val bytes = if (remote) encodedPoster(key.source) ?: return null else null
            return decodeSlots.withPermit {
                memoryImage(key)?.let { return@withPermit it }
                val bitmap = if (bytes != null) {
                    decodeSized(key) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                } else {
                    decodeSized(key) { options -> context.assets.open(key.source).use { BitmapFactory.decodeStream(it, null, options) } }
                } ?: return@withPermit null
                currentCoroutineContext().ensureActive()
                rememberImage(key, bitmap).also { thumbnails.trySend(Thumbnail(context, key, bitmap)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    /** 输入：可重复打开的编码图与显示尺寸；输出：覆盖控件所需的小位图，不保留全尺寸原图。 */
    private fun decodeSized(key: Key, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= key.width && bounds.outHeight / (sample * 2) >= key.height) sample *= 2
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_DECODE_PIXELS) sample *= 2
        val decoded = decode(BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: return null
        val ratio = max(key.width.toFloat() / decoded.width, key.height.toFloat() / decoded.height).coerceAtMost(1f)
        if (ratio >= .98f) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * ratio).roundToInt()), max(1, (decoded.height * ratio).roundToInt()), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun rememberImage(key: Key, bitmap: Bitmap): ImageBitmap {
        val image = bitmap.asImageBitmap()
        synchronized(memory) {
            if (memory.get(key.source)?.key?.covers(key) != true) {
                memory.put(key.source, Cached(key, image, bitmap.byteCount))
            }
        }
        return image
    }

    private fun diskName(key: Key): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${key.source}\n${key.width}x${key.height}".toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(68) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
            append(".jpg")
        }
    }

    // 只保存已经下采样的 JPEG。初始化和超额时才扫描目录，避免每次写入都遍历整个缓存。
    private fun directory(context: Context): File {
        diskDirectory?.let { return it }
        val directory = File(context.cacheDir, "poster-thumbnails-v2")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建海报缓存")
        directory.listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() }
        diskSize = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sumOf { it.length() } ?: 0L
        diskDirectory = directory
        trimDisk(directory)
        return directory
    }

    private fun readDisk(context: Context, key: Key): Bitmap? {
        return try {
            val file = synchronized(diskLock) {
                val path = File(directory(context), diskName(key))
                if (!path.isFile) return null
                val length = path.length()
                if (length !in 1..DISK_ENTRY_BYTES.toLong()) {
                    if (path.delete()) diskSize = (diskSize - length).coerceAtLeast(0)
                    return null
                }
                path
            }
            // These files already contain a bounded thumbnail for this size bucket.
            // Decode outside the lock so cache hits don't block each other or a write.
            BitmapFactory.decodeFile(file.path)?.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) { null }
    }

    private suspend fun writeDisk(context: Context, key: Key, bitmap: Bitmap) {
        currentCoroutineContext().ensureActive()
        try {
            val encoded = ByteArrayOutputStream()
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    if (encoded.size() >= DISK_ENTRY_BYTES) throw IOException("缩略图超出单项上限")
                    encoded.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (encoded.size() + count > DISK_ENTRY_BYTES) throw IOException("缩略图超出单项上限")
                    encoded.write(bytes, offset, count)
                }
            }
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bounded)) return
            currentCoroutineContext().ensureActive()
            synchronized(diskLock) {
                val directory = directory(context)
                val file = File(directory, diskName(key))
                val temp = File(directory, file.name + ".tmp")
                val previousBytes = file.length()
                try {
                    temp.outputStream().use { encoded.writeTo(it) }
                    if (temp.renameTo(file)) {
                        diskSize += file.length() - previousBytes
                        trimDisk(directory)
                    }
                } finally { temp.delete() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 缓存写入失败不影响已经解码成功的海报。
        }
    }

    private fun trimDisk(directory: File) {
        if (diskSize <= DISK_BYTES) return
        for (file in directory.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sortedBy { it.lastModified() }.orEmpty()) {
            val length = file.length()
            if (file.delete()) diskSize = (diskSize - length).coerceAtLeast(0)
            if (diskSize <= TRIM_DISK_BYTES) break
        }
    }
}
