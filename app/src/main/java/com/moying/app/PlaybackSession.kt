package com.moying.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private object PlaybackRetention {
    /** 离开页面/暂停后几乎立即停下下一集预取，避免后台继续消耗流量。 */
    const val PreloadIdleMillis = 1_000L
    /** 内存紧张后仍保留一段冷却，避免轮询立刻重建刚回收的实例。 */
    const val MemoryTrimBlockMillis = 30_000L
    const val PlayerIdleMillis = 180_000L
}

/** 只保留最后一部影片；不持有 Activity、View、仓库或 Compose 回调。 */
data class RetainedPlayback(
    val movieId: String,
    val routeId: String,
    val episode: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val originalEpisode: Episode? = null,
) {
    /** 内容身份键：原始地址不参与计算，缓存与播放去重共用。 */
    val key: String get() = ContentCacheKey.root(movieId, routeId, episode)
}

/** 输入：应用上下文；输出：跨页面与 Activity 重建复用的单一播放会话。 */
class PlaybackSession private constructor(context: Context) {
    val controller: PlayerController = AndroidPlayerController(context.applicationContext)
    var selection by mutableStateOf<RetainedPlayback?>(null)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var hostOwner: Any? = null
    private var foregroundOwner by mutableStateOf<Any?>(null)
    private var visibleOwner: Any? = null
    private var visibleMovieId: String? = null
    private var active = false
    private var resumeRequested = false
    private var preloadBlockedUntil = 0L
    private val releaseNext = Runnable {
        if (!active) controller.trimRetainedResources(false)
    }
    private val releaseCurrent = Runnable {
        if (!active) controller.trimRetainedResources(true)
    }

    /** 输入：宿主身份和前后台状态；输出：离开时暂停，短返仅恢复先前的播放意愿。 */
    fun setForeground(owner: Any, foreground: Boolean) {
        if (foreground) { hostOwner = owner; foregroundOwner = owner }
        else if (hostOwner === owner) foregroundOwner = null
        else return
        updateActivity()
    }

    internal fun isForeground(owner: Any) = foregroundOwner === owner

    /** 输入：播放页身份、影片及可见性；输出：页面销毁不释放已准备的媒体实例。 */
    fun setVisible(owner: Any, movieId: String, visible: Boolean) {
        if (visible) { visibleOwner = owner; visibleMovieId = movieId }
        else if (visibleOwner === owner && visibleMovieId == movieId) {
            visibleOwner = null; visibleMovieId = null
        } else return
        updateActivity()
    }

    /** 输入：销毁的宿主身份；输出：解除旧画布，迟到的旧宿主不能影响新宿主。 */
    fun detachHost(owner: Any) {
        if (hostOwner !== owner) return
        foregroundOwner = null
        if (visibleOwner === owner) { visibleOwner = null; visibleMovieId = null }
        updateActivity()
        controller.detachSurface()
        hostOwner = null
    }

    /** 输入：影片/线路/集数/地址与初始进度；输出：相同媒体直接复用，换集换源才重新准备。 */
    fun ensurePlayback(media: RetainedPlayback, durationMillis: Long, startMillis: Long) {
        // 地址或请求头变化需要重新回源，解析完成前的空地址保留画布和待续播位置。
        if (selection == media) { updateActivity(); return }
        selection = media
        resumeRequested = media.url.isNotBlank()
        active = desiredActive()
        cancelIdleCleanup()
        controller.setDataSource(media.url, startMillis, media.key, media.headers)
        controller.prepare(durationMillis, startMillis)
        if (active && resumeRequested) controller.play()
        if (!active) { controller.pause(); scheduleIdleCleanup() }
    }

    /** 每次采样让控制器重估安全余量；同 URL 的失败也允许经过退避后重试。 */
    fun preload(media: RetainedPlayback) {
        if (!active || SystemClock.uptimeMillis() < preloadBlockedUntil) return
        controller.preload(media.url, media.key, media.headers)
    }

    /** 输入：历史仓库及可选页面媒体身份；输出：只写当前实例的确认进度，避免旧页覆盖新集。 */
    fun saveProgress(repository: MovieRepository, expectedKey: String? = null) {
        val current = selection ?: return
        if (expectedKey != null && expectedKey != current.key) return
        val position = controller.getPosition()
        val duration = controller.getDuration()
        if (current.url.isNotBlank() && duration > Playback.Start) {
            repository.saveHistory(WatchHistory(current.movieId, current.episode, position, duration, System.currentTimeMillis()))
        }
    }

    /** 输入：系统内存级别；输出：优先释放预载，仅真正紧张且离页时提前回收当前解码器。 */
    fun trimMemory(level: Int) {
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || critical || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            controller.trimRetainedResources(critical && !active)
            // 活跃页面轮询也不能立即重建刚因内存紧张回收的下一集实例。
            preloadBlockedUntil = SystemClock.uptimeMillis() + PlaybackRetention.MemoryTrimBlockMillis
        }
    }

    private fun desiredActive() = foregroundOwner != null && foregroundOwner === visibleOwner && visibleMovieId == selection?.movieId

    /** 输入：宿主与页面可见性；输出：重复离页不会把第一次保存的播放意愿覆盖成暂停。 */
    private fun updateActivity() {
        val next = desiredActive()
        if (next == active) return
        active = next
        if (next) {
            cancelIdleCleanup()
            if (resumeRequested) controller.play()
        } else {
            resumeRequested = controller.playbackRequested
            controller.pause()
            scheduleIdleCleanup()
        }
    }

    private fun scheduleIdleCleanup() {
        cancelIdleCleanup()
        handler.postDelayed(releaseNext, PlaybackRetention.PreloadIdleMillis)
        handler.postDelayed(releaseCurrent, PlaybackRetention.PlayerIdleMillis)
    }

    private fun cancelIdleCleanup() {
        handler.removeCallbacks(releaseNext)
        handler.removeCallbacks(releaseCurrent)
    }

    companion object {
        @Volatile private var instance: PlaybackSession? = null
        /** 输入：任意 Context；输出：仅使用 applicationContext 的进程内最近播放会话。 */
        fun get(context: Context): PlaybackSession = instance ?: synchronized(this) {
            instance ?: PlaybackSession(context.applicationContext).also { instance = it }
        }
    }
}
