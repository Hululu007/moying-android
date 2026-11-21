package com.moying.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException

private object PlaybackRecoveryTuning {
    const val MaxAutomaticRetries = 2
    const val RetryDelayMillis = 1_000L
    const val NetworkWaitMillis = 20_000L
    const val StablePlaybackMillis = 15_000L
    const val PreloadRetryMillis = 2_000L
    const val MaxPreloadRetryMillis = 30_000L
    const val PreloadPrepareMillis = 20_000L
    const val NetworkRecoveryDelayMillis = 500L
    const val NetworkRecoveryCooldownMillis = 5_000L
}

/** 使用稳定的 Android MediaPlayer 播放网络 MP4/HLS，并保留一个下一集预准备实例。 */
class AndroidPlayerController(private val context: Context) : PlayerController {
    private class PreloadedMedia(
        val url: String,
        val contentKey: String,
        val headers: Map<String, String>,
        val player: MediaPlayer,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var prepared = false
        var bufferedPercent = 0
        var durationMillis = 0L
        var valid = true
    }

    private var player: MediaPlayer? = null
    private var preloaded: PreloadedMedia? = null
    private var streamCache: StreamingCacheProxy? = null
    private var holder: SurfaceHolder? = null
    private var source = ""
    private var sourceCacheKey = ""
    private var sourceHeaders = emptyMap<String, String>()
    private var desiredNextCacheKey = ""
    private var desiredNextHeaders = emptyMap<String, String>()
    private var prepared = false
    private var hasStarted = false
    private var completed = false
    private var awaitingResumeSeek = false
    private var queuedSeekPosition: Long? = null
    private var nativeBuffering = false
    private var playRequested = false
    private var bufferedPercent = 0
    private var resumePosition = Playback.Start
    private var durationHint = Playback.Start
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var retryTask: Runnable? = null
    private var watchdogTask: Runnable? = null
    private var sourceGeneration = 0L
    private var automaticRetries = 0
    private var desiredNextSource = ""
    private var preloadRetryAt = 0L
    private var preloadFailures = 0
    private var stablePlaybackSince = 0L
    private var lastAdvanceAt = 0L
    private var lastObservedPosition = 0L
    private var waitingForNetworkRecovery = false
    private var networkRecoveryTask: Runnable? = null
    private var lastNetworkRecoveryAt = -PlaybackRecoveryTuning.NetworkRecoveryCooldownMillis
    private var networkCallbackRegistered = false
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var usableNetwork: Network? = null
        override fun onAvailable(network: Network) { scheduleNetworkRecovery() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable && usableNetwork != network) { usableNetwork = network; scheduleNetworkRecovery() }
            else if (!usable && usableNetwork == network) usableNetwork = null
        }
        override fun onLost(network: Network) { if (usableNetwork == network) usableNetwork = null }
    }
    private var playbackSpeed by mutableFloatStateOf(Playback.DefaultSpeed)
    override var isPlaying by mutableStateOf(false)
        private set
    override var isBuffering by mutableStateOf(false)
        private set
    override var errorMessage: String? by mutableStateOf(null)
        private set
    override var videoAspectRatio: Float? by mutableStateOf<Float?>(null)
        private set
    override var bufferedPosition by mutableLongStateOf(0L)
        private set
    override var nextPreloadReady by mutableStateOf(false)
        private set
    override var nextBufferedDuration by mutableLongStateOf(0L)
        private set
    override var nextBufferedBytes by mutableLongStateOf(0L)
        private set
    override var nextPreloadDuration by mutableLongStateOf(0L)
        private set
    override var nextPreloadMessage: String? by mutableStateOf(null)
        private set
    override val speed: Float get() = playbackSpeed
    override val playbackRequested: Boolean get() = playRequested

    /** 输入：可用的 SurfaceHolder；输出：准备完成后绑定画布并恢复用户要求的播放。 */
    override fun attachSurface(holder: SurfaceHolder) {
        this.holder = holder
        val current = player
        if (current == null && source.isNotBlank() && errorMessage == null) {
            registerCurrentCache()
            openSource()
        }
        else if (current != null && prepared) {
            runCatching { current.setDisplay(holder) }
                .onFailure { fail("视频画面暂不可用") }
            if (awaitingResumeSeek || nativeBuffering) {
                isBuffering = playRequested
                armNetworkWatchdog(current)
            }
            if (playRequested) startPrepared()
        } else if (current != null && playRequested) {
            isBuffering = true
            armNetworkWatchdog(current)
        }
    }

    override fun detachSurface(holder: SurfaceHolder?) {
        // 旧 Surface 销毁可能晚于新 Surface 创建，只允许解绑自己。
        if (holder != null && this.holder !== holder) return
        captureConfirmedProgress()
        cancelWatchdog()
        this.holder = null
        player?.let { current ->
            if (prepared) {
                // Prepared 状态尚未 start 时不能 pause。
                if (hasStarted && isPlaying) runCatching { current.pause() }
            }
            // Preparing 阶段同样解除 display，单例不能持有已销毁 Activity 的画布。
            runCatching { current.setDisplay(null) }
        }
        isPlaying = false
        isBuffering = false
    }

    /** Legacy callers still prepare real media, using the same fallback identity as the cache. */
    override fun setDataSource(url: String, startMillis: Long) =
        setDataSource(url, startMillis, ContentCacheKey.fallback(url.trim()))

    override fun preload(url: String) = preload(url, ContentCacheKey.fallback(url.trim()))

    override fun setDataSource(url: String, startMillis: Long, contentKey: String) =
        setDataSource(url, startMillis, contentKey, emptyMap())

    override fun preload(url: String, contentKey: String) = preload(url, contentKey, emptyMap())

    /** 输入：媒体 URL、续播毫秒与内容身份键；输出：接管同地址预加载，否则清理旧实例并异步连接。 */
    override fun setDataSource(url: String, startMillis: Long, contentKey: String, headers: Map<String, String>) {
        captureConfirmedProgress()
        sourceGeneration++
        cancelNetworkRecovery()
        waitingForNetworkRecovery = false
        stablePlaybackSince = 0L
        lastAdvanceAt = 0L
        automaticRetries = 0
        ensureNetworkCallback()
        val nextSource = url.trim()
        val requestHeaders = MediaRequestHeaders.normalize(headers)
        val warm = preloaded?.takeIf {
            it.url == nextSource && it.contentKey == contentKey && it.headers == requestHeaders && it.valid
        }
        if (warm != null) {
            // 先转移所有权，旧预加载回调随后只会作为当前播放器回调处理。
            preloaded = null
            nextPreloadReady = false
            nextBufferedDuration = 0L
            nextBufferedBytes = 0L
            nextPreloadDuration = 0L
        } else releasePreload()
        desiredNextSource = ""
        desiredNextCacheKey = ""
        desiredNextHeaders = emptyMap()
        preloadRetryAt = 0L
        preloadFailures = 0
        releasePlayer()
        source = nextSource
        sourceCacheKey = contentKey
        sourceHeaders = requestHeaders
        resumePosition = startMillis.coerceAtLeast(Playback.Start)
        durationHint = Playback.Start
        errorMessage = null
        playRequested = source.isNotBlank()
        isBuffering = playRequested
        if (source.isBlank()) {
            streamCache?.releasePlayback()
            errorMessage = "片源地址为空"
            return
        }
        registerCurrentCache()
        if (warm != null) {
            player = warm.player
            bufferedPercent = warm.bufferedPercent
            // 正在 Preparing 的实例继续原连接；onPrepared 再绑定 Surface/seek/start。
            if (warm.prepared) finishPreparation(warm.player)
            else armNetworkWatchdog(warm.player)
        } else if (holder != null) openSource()
    }

    /** 当前集有足够的实际播放余量才建立下一集 native 连接；磁盘角色独立保留。 */
    override fun preload(url: String, contentKey: String, headers: Map<String, String>) {
        val nextSource = url.trim()
        val requestHeaders = MediaRequestHeaders.normalize(headers)
        if (nextSource.isBlank() || (nextSource == source && requestHeaders == sourceHeaders)) {
            releasePreload()
            return
        }
        // 下一集预取是显式开关，默认关闭；关闭时不建立磁盘角色，也不建立 native 预准备。
        if (!PlaybackNetworkPreferences.readNextEpisode(context.applicationContext)) {
            releasePreload()
            nextPreloadMessage = "下一集预取已关闭"
            refreshCacheProgress()
            return
        }
        if (desiredNextSource != nextSource || desiredNextCacheKey != contentKey || desiredNextHeaders != requestHeaders) {
            releasePreload()
            desiredNextSource = nextSource
            desiredNextCacheKey = contentKey
            desiredNextHeaders = requestHeaders
        }
        syncCacheState()
        if (!playRequested || !isPlaying || isBuffering || holder == null || streamCache?.canPreload() != true) {
            releasePreload(clearCache = false)
            // 暂停、缓冲或移动数据时立即停下下一集磁盘预取，恢复播放后由轮询重新建立。
            runCatching { streamCache?.setNext("") }
            nextPreloadMessage = "下一集等待当前集缓冲"
            refreshCacheProgress()
            return
        }
        if (SystemClock.elapsedRealtime() < preloadRetryAt) {
            nextPreloadMessage = "下一集预缓冲稍后重试"
            refreshCacheProgress()
            return
        }
        preloaded?.let { existing ->
            if (!existing.prepared && SystemClock.elapsedRealtime() - existing.startedAt >= PlaybackRecoveryTuning.PreloadPrepareMillis)
                failPreload(existing)
            return
        }
        val nextPlayer = runCatching { MediaPlayer() }.getOrNull() ?: return
        runCatching { cache().setNext(nextSource, contentKey, requestHeaders) }
        val next = PreloadedMedia(nextSource, contentKey, requestHeaders, nextPlayer)
        preloaded = next
        nextPreloadMessage = "下一集预缓冲中"
        runCatching {
            configurePlayer(nextPlayer)
            nextPlayer.setVolume(0f, 0f)
            nextPlayer.setDataSource(playableUrl(nextSource, contentKey, requestHeaders))
            nextPlayer.prepareAsync()
        }.onFailure {
            // 预加载失败不覆盖当前集的播放、缓冲、错误或尺寸状态。
            failPreload(next)
        }
    }

    private fun failPreload(next: PreloadedMedia) {
        if (preloaded !== next) return
        next.valid = false
        preloaded = null
        dispose(next.player)
        preloadFailures = (preloadFailures + 1).coerceAtMost(15)
        preloadRetryAt = SystemClock.elapsedRealtime() +
            (PlaybackRecoveryTuning.PreloadRetryMillis * preloadFailures).coerceAtMost(PlaybackRecoveryTuning.MaxPreloadRetryMillis)
        nextPreloadMessage = "下一集预缓冲稍后重试"
        refreshCacheProgress()
    }

    /** 输入：已保存的当前片源；输出：异步准备新播放器，准备完成后再绑定画布。 */
    private fun openSource() {
        if (source.isBlank() || player != null) return
        cancelRetry()
        val current = runCatching { MediaPlayer() }.getOrElse {
            fail("播放器暂时无法启动")
            return
        }
        player = current
        errorMessage = null
        isBuffering = playRequested
        runCatching {
            configurePlayer(current)
            current.setDataSource(playableUrl(source, sourceCacheKey, sourceHeaders))
            current.prepareAsync()
            armNetworkWatchdog(current)
        }.onFailure { if (player === current) fail("片源连接失败，请重试或更换地址", recoverable = it is IOException) }
    }

    /** 输入：新建播放器；输出：设置媒体属性和身份过滤监听，预加载回调不修改当前集状态。 */
    private fun configurePlayer(current: MediaPlayer) {
        current.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        )
        current.setOnVideoSizeChangedListener { active, width, height ->
            if (player === active) updateVideoSize(width, height)
        }
        current.setOnPreparedListener { ready ->
            if (player === ready) finishPreparation(ready)
            else preloaded?.takeIf { it.player === ready }?.let { next ->
                next.prepared = true
                preloadFailures = 0
                preloadRetryAt = 0L
                next.durationMillis = runCatching { ready.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
                nextPreloadReady = true
                refreshCacheProgress()
            }
        }
        current.setOnBufferingUpdateListener { active, percent ->
            if (player === active) {
                bufferedPercent = percent.coerceIn(0, 100)
                updateBufferedPosition()
            } else preloaded?.takeIf { it.player === active }?.let {
                it.bufferedPercent = percent.coerceIn(0, 100)
                refreshCacheProgress()
            }
        }
        current.setOnSeekCompleteListener { active ->
            if (player === active && awaitingResumeSeek) {
                val queued = queuedSeekPosition
                queuedSeekPosition = null
                if (queued != null) {
                    // 连续拖动只串行提交最后目标，旧 seekComplete 不得确认新目标的旧位置。
                    seekPrepared(active, queued)
                    return@setOnSeekCompleteListener
                }
                cancelWatchdog()
                awaitingResumeSeek = false
                captureConfirmedProgress()
                isBuffering = playRequested && nativeBuffering
                if (nativeBuffering) armNetworkWatchdog(active)
                if (playRequested && holder != null) startPrepared()
            }
        }
        current.setOnCompletionListener { finished ->
            if (player === finished && !awaitingResumeSeek) {
                captureConfirmedProgress()
                if (durationHint > Playback.Start) resumePosition = maxOf(resumePosition, durationHint)
                cancelWatchdog()
                completed = true
                queuedSeekPosition = null
                nativeBuffering = false
                awaitingResumeSeek = false
                isPlaying = false
                playRequested = false
                isBuffering = false
            }
        }
        current.setOnInfoListener { active, what, _ ->
            if (player === active && prepared) when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    captureConfirmedProgress()
                    nativeBuffering = true
                    isBuffering = playRequested
                    waitingForNetworkRecovery = playRequested
                    stablePlaybackSince = 0L
                    syncCacheState()
                    releasePreload(clearCache = false)
                    armNetworkWatchdog(active)
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    nativeBuffering = false
                    captureConfirmedProgress()
                    isBuffering = playRequested && awaitingResumeSeek
                    waitingForNetworkRecovery = false
                    lastAdvanceAt = SystemClock.elapsedRealtime()
                    syncCacheState()
                    if (awaitingResumeSeek) armNetworkWatchdog(active) else cancelWatchdog()
                }
            }
            false
        }
        current.setOnErrorListener { active, what, extra ->
            if (player === active) fail(
                "片源暂时无法播放（$what/$extra）",
                recoverable = extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT ||
                    what == MediaPlayer.MEDIA_ERROR_IO || what == MediaPlayer.MEDIA_ERROR_TIMED_OUT,
            )
            else preloaded?.takeIf { it.player === active }?.let { next ->
                // 解码预准备失败不取消磁盘续缓存；下一次切集重新创建解码实例。
                failPreload(next)
            }
            true
        }
    }

    /** 输入：已 Prepared 的当前实例；输出：恢复音量、尺寸、缓冲位置和续播进度，再按意愿播放。 */
    private fun finishPreparation(ready: MediaPlayer) {
        if (player !== ready) return
        runCatching {
            cancelWatchdog()
            prepared = true
            ready.setVolume(1f, 1f)
            ready.setDisplay(holder)
            updateVideoSize(ready.videoWidth, ready.videoHeight)
            ready.duration.toLong().takeIf { it > Playback.Start }?.let { durationHint = it }
            updateBufferedPosition()
            // 不同返回时长只能收敛到片尾，不能把错误恢复或换源续播改成从头播放。
            val resume = if (durationHint > Playback.Start) resumePosition.coerceAtMost(durationHint) else resumePosition
            resumePosition = resume
            awaitingResumeSeek = resume > Playback.Start
            nativeBuffering = false
            isBuffering = playRequested && awaitingResumeSeek
            if (awaitingResumeSeek) {
                seekPrepared(ready, resume)
            }
            else if (playRequested && holder != null) startPrepared()
        }.onFailure { if (player === ready) fail("片源准备失败，请重试或更换地址") }
    }

    /** 输入：系统缓冲百分比及真实媒体时长；输出：可观察的已缓冲位置，不推测磁盘缓存。 */
    private fun updateBufferedPosition() {
        bufferedPosition = if (prepared && durationHint > Playback.Start) {
            (durationHint * bufferedPercent / 100L).coerceIn(Playback.Start, durationHint)
        } else Playback.Start
        streamCache?.nativeBufferedPosition = bufferedPosition
    }

    /** 输入：视频宽高像素；输出：有效尺寸更新宽高比，无效尺寸保留当前值。 */
    private fun updateVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) videoAspectRatio = width.toFloat() / height
    }

    private fun startPrepared() {
        val current = player ?: return
        if (!prepared || awaitingResumeSeek || !playRequested || holder == null) return
        runCatching {
            current.start()
            hasStarted = true
            completed = false
            isPlaying = true
            isBuffering = nativeBuffering
            applyPlaybackSpeed(current, playbackSpeed, keepPaused = false)
            if (nativeBuffering) armNetworkWatchdog(current)
        }.onFailure { fail("视频暂时无法播放") }
    }

    override fun prepare(durationMillis: Long, startMillis: Long) {
        if (durationHint <= Playback.Start) durationHint = durationMillis.coerceAtLeast(Playback.Start)
    }

    override fun play() {
        if (source.isBlank()) return
        ensureNetworkCallback()
        lastAdvanceAt = SystemClock.elapsedRealtime()
        lastObservedPosition = resumePosition
        stablePlaybackSince = 0L
        if (player == null && completed) { resumePosition = Playback.Start; completed = false }
        playRequested = true
        automaticRetries = 0
        cancelRetry()
        if (errorMessage != null || player == null) {
            captureConfirmedProgress()
            errorMessage = null
            releasePlayer()
            isBuffering = holder != null
            if (holder != null) {
                // 闲置回收后代理可能已关闭，重建时先保护当前集并恢复连续缓存角色。
                registerCurrentCache()
                openSource()
            }
        } else if (prepared) {
            if (completed) {
                // 播完后只响应显式播放；恢复画布或调整倍速不会重播。
                completed = false
                seek(Playback.Start)
            }
            if (awaitingResumeSeek) {
                isBuffering = holder != null
                player?.let(::armNetworkWatchdog)
            }
            startPrepared()
        } else {
            isBuffering = holder != null
            player?.let(::armNetworkWatchdog)
        }
    }

    override fun pause() {
        captureConfirmedProgress()
        playRequested = false
        cancelNetworkRecovery()
        cancelRetry()
        cancelWatchdog()
        if (prepared && hasStarted && isPlaying) player?.let { runCatching { it.pause() } }
        isPlaying = false
        isBuffering = false
        stablePlaybackSince = 0L
        syncCacheState()
        releasePreload(clearCache = false)
        // 暂停立即取消下一集预取，不等离页后的延迟清理。
        runCatching { streamCache?.setNext("") }
    }

    override fun seek(positionMillis: Long) {
        val duration = getDuration()
        val target = positionMillis.coerceAtLeast(Playback.Start).let { if (duration > Playback.Start) it.coerceAtMost(duration) else it }
        resumePosition = target
        stablePlaybackSince = 0L
        lastAdvanceAt = SystemClock.elapsedRealtime()
        lastObservedPosition = target
        completed = false
        if (prepared) player?.let { current ->
            if (awaitingResumeSeek) queuedSeekPosition = target
            else seekPrepared(current, target)
        }
    }

    /** 输入：已准备实例和确认目标；输出：串行定位，回调前只暴露用户请求的续播点。 */
    private fun seekPrepared(current: MediaPlayer, target: Long) {
        awaitingResumeSeek = true
        isBuffering = playRequested
        syncCacheState()
        releasePreload(clearCache = false)
        runCatching { current.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
            .onSuccess { armNetworkWatchdog(current) }
            .onFailure { if (player === current) fail("暂时无法跳转到此进度") }
    }

    override fun setSpeed(speed: Float) {
        if (speed !in Playback.AllowedSpeeds) return
        // 正倍速参数可能隐式 start，尚未播放或已结束时留到显式播放再应用。
        if (prepared && hasStarted && !completed) player?.let { current ->
            applyPlaybackSpeed(current, speed, keepPaused = !isPlaying)
        } else playbackSpeed = speed
        syncCacheState()
        if (desiredNextSource.isNotBlank()) preload(desiredNextSource, desiredNextCacheKey, desiredNextHeaders)
    }

    /** 输入：目标倍速及暂停状态；输出：成功更新倍速，拒绝时恢复旧参数并保留正在播放的影片。 */
    private fun applyPlaybackSpeed(current: MediaPlayer, speed: Float, keepPaused: Boolean) {
        val previous = runCatching { current.playbackParams }.getOrNull()
        val previousSpeed = previous?.let { runCatching { it.speed }.getOrNull() }
            ?.takeIf { it in Playback.AllowedSpeeds } ?: Playback.DefaultSpeed
        val requested = PlaybackParams().allowDefaults().setSpeed(speed).setPitch(Playback.DefaultSpeed)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_MUTE)
        runCatching {
            current.playbackParams = requested
            if (keepPaused) current.pause()
        }.onSuccess { playbackSpeed = speed }.onFailure {
            // 部分设备不能处理三倍速；参数失败不释放播放器，也不改变用户的暂停状态。
            runCatching {
                current.playbackParams = previous ?: PlaybackParams().allowDefaults().setSpeed(previousSpeed)
                if (keepPaused) current.pause()
            }
            playbackSpeed = previousSpeed
        }
    }

    /** 输入：当前 native 状态；输出：确认后的进度，只允许显式 seek/换集/重播向后重设。 */
    private fun captureConfirmedProgress() {
        val current = player ?: return
        if (!prepared) return
        if (!awaitingResumeSeek) {
            runCatching { current.currentPosition.toLong() }.getOrNull()
                ?.takeIf { it >= resumePosition }?.let { resumePosition = it }
        }
        runCatching { current.duration.toLong() }.getOrNull()
            ?.takeIf { it > Playback.Start }?.let { durationHint = it }
    }

    override fun getPosition(): Long {
        captureConfirmedProgress()
        observePlaybackProgress()
        syncCacheState()
        if (desiredNextSource.isNotBlank()) preload(desiredNextSource, desiredNextCacheKey, desiredNextHeaders)
        refreshCacheProgress()
        return resumePosition
    }
    override fun getDuration(): Long { captureConfirmedProgress(); return durationHint }

    /** 输入：错误及是否为网络故障；输出：保留确认进度/时长/倍速/意愿，只对网络有界恢复。 */
    private fun fail(message: String, recoverable: Boolean = false) {
        captureConfirmedProgress()
        waitingForNetworkRecovery = recoverable
        stablePlaybackSince = 0L
        releasePreload(clearCache = false)
        releasePlayer()
        if (recoverable && playRequested && source.isNotBlank() && automaticRetries < PlaybackRecoveryTuning.MaxAutomaticRetries) {
            automaticRetries++
            errorMessage = null
            isBuffering = true
            val generation = sourceGeneration
            lateinit var retry: Runnable
            retry = Runnable {
                if (retryTask !== retry) return@Runnable
                retryTask = null
                if (sourceGeneration == generation && playRequested && holder != null) openSource()
            }
            retryTask = retry
            recoveryHandler.postDelayed(retry, PlaybackRecoveryTuning.RetryDelayMillis * automaticRetries)
        } else errorMessage = message
        syncCacheState()
    }

    /** 输入：当前等待网络的实例；输出：准备/续播/再缓冲超时后有界重连，旧集定时器无效。 */
    private fun armNetworkWatchdog(current: MediaPlayer) {
        cancelWatchdog()
        if (!playRequested || holder == null) return
        val generation = sourceGeneration
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (watchdogTask !== watchdog) return@Runnable
            watchdogTask = null
            if (sourceGeneration == generation && player === current && playRequested &&
                (!prepared || awaitingResumeSeek || nativeBuffering)) {
                fail("片源连接超时，已保留观看进度，请重试或换源", recoverable = true)
            }
        }
        watchdogTask = watchdog
        recoveryHandler.postDelayed(watchdog, PlaybackRecoveryTuning.NetworkWaitMillis)
    }

    private fun ensureNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback, recoveryHandler) }
            .onSuccess { networkCallbackRegistered = true }
    }

    /** 系统恢复可用网络时只恢复仍有播放意愿的等待会话，暂停或旧集不能被唤醒。 */
    private fun scheduleNetworkRecovery() {
        fun needsRecovery() = (waitingForNetworkRecovery || nativeBuffering) &&
            (player == null || !prepared || nativeBuffering || awaitingResumeSeek || errorMessage != null)
        if (!playRequested || holder == null || !needsRecovery()) return
        cancelNetworkRecovery()
        val generation = sourceGeneration
        val recovery = Runnable {
            networkRecoveryTask = null
            val now = SystemClock.elapsedRealtime()
            if (generation != sourceGeneration || !playRequested || holder == null ||
                !needsRecovery() ||
                now - lastNetworkRecoveryAt < PlaybackRecoveryTuning.NetworkRecoveryCooldownMillis) return@Runnable
            lastNetworkRecoveryAt = now
            captureConfirmedProgress()
            waitingForNetworkRecovery = false
            automaticRetries = 0
            releasePlayer()
            isBuffering = true
            syncCacheState()
            openSource()
        }
        networkRecoveryTask = recovery
        val delay = maxOf(PlaybackRecoveryTuning.NetworkRecoveryDelayMillis,
            lastNetworkRecoveryAt + PlaybackRecoveryTuning.NetworkRecoveryCooldownMillis - SystemClock.elapsedRealtime())
        recoveryHandler.postDelayed(recovery, delay)
    }

    private fun cancelNetworkRecovery() {
        networkRecoveryTask?.let(recoveryHandler::removeCallbacks)
        networkRecoveryTask = null
    }

    private fun syncCacheState() {
        streamCache?.let {
            it.playbackSpeed = playbackSpeed
            it.foregroundBuffering = playRequested && isBuffering
            it.updatePosition(resumePosition)
        }
    }

    /** 页面采样只读取平台进度；持续正常播放恢复重试额度，静默停滞另有有界兜底。 */
    private fun observePlaybackProgress() {
        val now = SystemClock.elapsedRealtime()
        if (!prepared || !hasStarted || !playRequested || holder == null || completed || awaitingResumeSeek || nativeBuffering) {
            stablePlaybackSince = 0L
            lastAdvanceAt = now
            lastObservedPosition = resumePosition
            return
        }
        if (resumePosition > lastObservedPosition) {
            lastAdvanceAt = now
            lastObservedPosition = resumePosition
            if (stablePlaybackSince == 0L) stablePlaybackSince = now
            if (now - stablePlaybackSince >= PlaybackRecoveryTuning.StablePlaybackMillis) {
                automaticRetries = 0
                waitingForNetworkRecovery = false
            }
        } else if (lastAdvanceAt == 0L) lastAdvanceAt = now
        else if (durationHint > 0L && resumePosition < durationHint - 500L &&
            now - lastAdvanceAt >= PlaybackRecoveryTuning.NetworkWaitMillis) {
            fail("播放暂时停滞，已保留观看进度，请重试或换源", recoverable = true)
        }
    }

    private fun cancelRetry() {
        retryTask?.let(recoveryHandler::removeCallbacks)
        retryTask = null
    }

    private fun cancelWatchdog() {
        watchdogTask?.let(recoveryHandler::removeCallbacks)
        watchdogTask = null
    }

    /** 输入：待销毁实例；输出：先解除全部回调，再释放系统资源。 */
    private fun dispose(old: MediaPlayer) {
        runCatching {
            old.setOnPreparedListener(null)
            old.setOnCompletionListener(null)
            old.setOnErrorListener(null)
            old.setOnInfoListener(null)
            old.setOnVideoSizeChangedListener(null)
            old.setOnBufferingUpdateListener(null)
            old.setOnSeekCompleteListener(null)
        }
        runCatching { old.release() }
    }

    /** 输入：无；输出：释放当前播放器并清空当前集状态，预加载实例单独管理。 */
    private fun releasePlayer() {
        cancelRetry()
        cancelWatchdog()
        cancelNetworkRecovery()
        val old = player
        player = null
        prepared = false
        hasStarted = false
        completed = false
        awaitingResumeSeek = false
        queuedSeekPosition = null
        nativeBuffering = false
        isPlaying = false
        isBuffering = false
        videoAspectRatio = null
        bufferedPercent = 0
        bufferedPosition = Playback.Start
        old?.let(::dispose)
    }

    /** 输入：无；输出：取消唯一预加载，迟到回调因身份失效被忽略。 */
    private fun releasePreload(clearCache: Boolean = true) {
        val old = preloaded
        preloaded = null
        nextPreloadReady = false
        nextBufferedDuration = 0L
        nextBufferedBytes = 0L
        nextPreloadDuration = 0L
        if (clearCache) {
            desiredNextSource = ""
            desiredNextCacheKey = ""
            desiredNextHeaders = emptyMap()
            preloadRetryAt = 0L
            preloadFailures = 0
            nextPreloadMessage = null
            streamCache?.setNext("")
        }
        old?.player?.let(::dispose)
    }

    /** 输入：是否回收当前实例；输出：先收下一集，空闲超时再收当前，磁盘文件与续播点保留。 */
    override fun trimRetainedResources(releaseCurrent: Boolean) {
        releasePreload()
        if (!releaseCurrent) return
        captureConfirmedProgress()
        val wasCompleted = completed
        sourceGeneration++
        playRequested = false
        releasePlayer()
        completed = wasCompleted
        // 代理是进程级单例，主动缓存队列仍在用它；这里只释放播放角色。
        streamCache?.releasePlayback()
        streamCache = null
    }

    override fun release() {
        captureConfirmedProgress()
        sourceGeneration++
        playRequested = false
        cancelNetworkRecovery()
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
        waitingForNetworkRecovery = false
        automaticRetries = 0
        releasePlayer()
        releasePreload()
        streamCache?.releasePlayback()
        streamCache = null
        source = ""
        sourceCacheKey = ""
        sourceHeaders = emptyMap()
        desiredNextCacheKey = ""
        holder = null
        resumePosition = Playback.Start
        durationHint = Playback.Start
        errorMessage = null
    }

    /** 输入：会话上下文；输出：懒创建回环缓存，异常时使用原始 URL 保留播放兼容性。 */
    private fun cache(): StreamingCacheProxy = streamCache ?: StreamingCacheProxy.get(context.applicationContext).also { streamCache = it }

    private fun registerCurrentCache() {
        runCatching { cache().setCurrent(source, resumePosition, sourceCacheKey, sourceHeaders) }
    }

    private fun playableUrl(url: String, contentKey: String, headers: Map<String, String>): String {
        val proxied = runCatching { cache().proxyUrl(url, contentKey, headers) }.getOrDefault(url)
        if (headers.isNotEmpty() && proxied == url) throw IOException("无法准备回源请求")
        return proxied
    }

    /** 输入：已完成的真实分片/平台缓冲回调；输出：下一集时长与字节，磁盘字节不换算假秒数。 */
    private fun refreshCacheProgress() {
        val next = preloaded
        val address = desiredNextSource.takeIf { it.isNotBlank() } ?: next?.url ?: return
        val stats = streamCache?.stats(address, desiredNextCacheKey, desiredNextHeaders) ?: StreamCacheStats()
        nextBufferedBytes = stats.bytes
        nextPreloadDuration = maxOf(next?.durationMillis ?: 0L, stats.totalDurationMillis)
        val nativeDuration = if (next?.prepared == true && next.durationMillis > 0L) next.durationMillis * next.bufferedPercent / 100L else 0L
        nextBufferedDuration = maxOf(stats.durationMillis, nativeDuration, if (stats.complete) nextPreloadDuration else 0L)
        nextPreloadReady = nextBufferedDuration > 0L || (stats.complete && next?.prepared == true)
    }
}
