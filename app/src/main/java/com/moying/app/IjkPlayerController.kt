package com.moying.app

import android.content.Context
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 真实播放器：ijkplayer。
 * Java 层不打进工程（避免和 androidx 撞包），运行时从 assets/ijk.dex 里加载；
 * 原生库 libijkplayer/libijkffmpeg/libijksdl 放在 jniLibs/arm64-v8a/。
 */
class IjkPlayerController(private val context: Context) : PlayerController {

    private var player: Any? = null
    private var holder: SurfaceHolder? = null
    private var pendingUrl: String? = null
    private var pendingStart: Long = 0L
    private var durationHint: Long = 0L

    override var isPlaying by mutableStateOf(false)
        private set

    private var playbackSpeed by mutableFloatStateOf(Playback.DefaultSpeed)
    override val speed: Float get() = playbackSpeed

    /** 首次用到时才去解压 dex、加载类 */
    private val clazz: Class<*>? by lazy { resolveClass() }

    private fun resolveClass(): Class<*>? {
        runCatching { return Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer") }
        return runCatching {
            val dex = File(context.filesDir, "ijk.dex")
            if (!dex.exists() || dex.length() == 0L) {
                context.assets.open("ijk.dex").use { input ->
                    dex.outputStream().use { out -> input.copyTo(out) }
                }
            }
            val loader = DexClassLoader(
                dex.absolutePath, context.filesDir.absolutePath, null, javaClass.classLoader
            )
            loader.loadClass("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        }.getOrNull()
    }

    override fun attachSurface(surfaceHolder: SurfaceHolder) {
        holder = surfaceHolder
        val url = pendingUrl ?: return
        open(url, pendingStart)
    }

    override fun setDataSource(url: String, startMillis: Long) {
        pendingUrl = url
        pendingStart = startMillis
        durationHint = 0L
        if (holder != null) open(url, startMillis)
    }

    private fun open(url: String, startMillis: Long) {
        val cls = clazz ?: return
        releasePlayer()
        runCatching {
            val mp = cls.getDeclaredConstructor().newInstance()
            player = mp
            cls.getMethod("setDisplay", SurfaceHolder::class.java).invoke(mp, holder)
            cls.getMethod("setDataSource", String::class.java).invoke(mp, url)
            cls.getMethod("prepareAsync").invoke(mp)
            Thread {
                runCatching { Thread.sleep(800) }
                runCatching { cls.getMethod("start").invoke(mp) }
                if (startMillis > 5000) {
                    runCatching {
                        cls.getMethod("seekTo", Long::class.javaPrimitiveType).invoke(mp, startMillis)
                    }
                }
                pollDuration(url)
            }.start()
            isPlaying = true
        }.onFailure {
            isPlaying = false
        }
    }

    /** ijk 对 HLS 常给 0，这里按 m3u8 的 #EXTINF 累加算总时长 */
    private fun pollDuration(url: String) {
        Thread {
            repeat(3) {
                val d = rawDuration()
                if (d > 0) {
                    durationHint = d
                    return@Thread
                }
                runCatching {
                    var body = Net.getString(url)
                    if (body.contains("#EXT-X-STREAM-INF")) {
                        val lines = body.split("\n")
                        outer@ for (i in lines.indices) {
                            if (!lines[i].trim().startsWith("#EXT-X-STREAM-INF")) continue
                            for (j in i + 1 until lines.size) {
                                val c = lines[j].trim()
                                if (c.isNotEmpty() && !c.startsWith("#")) {
                                    body = Net.getString(java.net.URL(java.net.URL(url), c).toString())
                                    break@outer
                                }
                            }
                            break
                        }
                    }
                    var sum = 0L
                    body.split("\n").forEach { line ->
                        val t = line.trim()
                        if (t.startsWith("#EXTINF:")) {
                            runCatching {
                                sum += (t.substring(8).split(",")[0].trim().toDouble() * 1000).toLong()
                            }
                        }
                    }
                    if (sum > 0) {
                        durationHint = sum
                        return@Thread
                    }
                }
                Thread.sleep(1200)
            }
        }.start()
    }

    private fun rawDuration(): Long = runCatching {
        val mp = player ?: return 0L
        (mp.javaClass.getMethod("getDuration").invoke(mp) as Int).toLong()
    }.getOrDefault(0L)

    private fun rawPosition(): Long = runCatching {
        val mp = player ?: return 0L
        mp.javaClass.getMethod("getCurrentPosition").invoke(mp) as Long
    }.getOrDefault(0L)

    override fun prepare(durationMillis: Long, startMillis: Long) {
        if (durationMillis > 0 && durationHint == 0L) durationHint = durationMillis
    }

    override fun play() {
        val mp = player ?: return
        runCatching {
            mp.javaClass.getMethod("start").invoke(mp)
            isPlaying = true
        }
    }

    override fun pause() {
        val mp = player ?: return
        runCatching {
            mp.javaClass.getMethod("pause").invoke(mp)
            isPlaying = false
        }
    }

    override fun seek(positionMillis: Long) {
        val mp = player ?: return
        runCatching {
            mp.javaClass.getMethod("seekTo", Long::class.javaPrimitiveType).invoke(mp, positionMillis)
        }
    }

    override fun setSpeed(speed: Float) {
        val mp = player ?: return
        if (speed !in Playback.AllowedSpeeds) return
        runCatching {
            mp.javaClass.getMethod("setSpeed", Float::class.javaPrimitiveType).invoke(mp, speed)
            playbackSpeed = speed
        }
    }

    override fun getPosition(): Long = rawPosition()

    override fun getDuration(): Long {
        val d = rawDuration()
        return if (d > 0) d else durationHint
    }

    private fun releasePlayer() {
        val mp = player ?: return
        runCatching { mp.javaClass.getMethod("stop").invoke(mp) }
        runCatching { mp.javaClass.getMethod("release").invoke(mp) }
        player = null
        isPlaying = false
    }
}
