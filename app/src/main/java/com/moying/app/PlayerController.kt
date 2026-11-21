package com.moying.app

import android.view.SurfaceHolder

/**
 * UI 唯一的播放器入口。
 * 界面通过这个接口控制实际播放器，不接触解码 SDK。
 */
interface PlayerController {
    val isPlaying: Boolean
    /** 用户的播放意愿；临时丢失画布或缓冲不会被误当作显式暂停。 */
    val playbackRequested: Boolean get() = isPlaying || isBuffering
    val speed: Float
    val isBuffering: Boolean get() = false
    val errorMessage: String? get() = null
    val videoAspectRatio: Float? get() = null
    /** 系统网络缓冲回调换算出的媒体位置（毫秒），不是磁盘缓存大小。 */
    val bufferedPosition: Long get() = 0L
    val nextPreloadReady: Boolean get() = false
    /** 下一集实际连续缓存/平台报告的可播放时间；未知时为 0，不按下载字节猜测秒数。 */
    val nextBufferedDuration: Long get() = 0L
    val nextBufferedBytes: Long get() = 0L
    val nextPreloadDuration: Long get() = 0L
    /** 下一集尚无可展示字节/时长时的真实等待或重试状态。 */
    val nextPreloadMessage: String? get() = null

    /** 真实播放用：绑定画布（Mock 实现忽略） */
    fun attachSurface(holder: SurfaceHolder) { }
    fun detachSurface(holder: SurfaceHolder? = null) { }
    /** 输入：是否同时回收当前解码器；输出：保留地址、进度和可复用磁盘缓存。 */
    fun trimRetainedResources(releaseCurrent: Boolean) { }
    fun release() { }

    /** 真实播放用：指定这一集的地址、续播位置与内容身份键（原始地址不参与缓存键）。 */
    fun setDataSource(url: String, startMillis: Long, contentKey: String) = setDataSource(url, startMillis)

    /** 已声明的回源播放请求头由播放器代理处理。 */
    fun setDataSource(url: String, startMillis: Long, contentKey: String, headers: Map<String, String>) =
        setDataSource(url, startMillis, contentKey)

    /** 旧实现的兼容钩子；真实播放器同时重写此入口并转发到带键的重载，保留旧调用者。 */
    fun setDataSource(url: String, startMillis: Long) { }

    /** 输入：下一集 URL 与内容身份键；输出：后台预准备，空地址取消已有预加载。 */
    fun preload(url: String, contentKey: String) = preload(url)

    fun preload(url: String, contentKey: String, headers: Map<String, String>) = preload(url, contentKey)

    /** 旧实现的兼容钩子；真实播放器也保留单参数调用，内部重估预取则始终携带原内容键。 */
    fun preload(url: String) { }

    /** 输入：单集时长及续播位置（毫秒）；输出：无，准备新的媒体状态。 */
    fun prepare(durationMillis: Long, startMillis: Long = Playback.Start)

    fun play()

    fun pause()

    fun seek(positionMillis: Long)

    fun setSpeed(speed: Float)

    /** 输入：无；输出：最近确认的毫秒进度；准备/错误/重连不能用临时 0 覆盖有效进度。 */
    fun getPosition(): Long

    /** 输入：无；输出：最近有效总时长，暂时未知时保留已确认值。 */
    fun getDuration(): Long
}
