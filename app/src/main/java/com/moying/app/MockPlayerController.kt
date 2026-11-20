package com.moying.app

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 单调时钟模拟器：只演示交互/进度，不解码或请求影片。 */
class MockPlayerController : PlayerController {
    override var isPlaying by mutableStateOf(false)
        private set
    private var playbackSpeed by mutableFloatStateOf(Playback.DefaultSpeed)
    override val speed: Float get() = playbackSpeed
    private var duration = Playback.Start
    private var anchorPosition = Playback.Start
    private var anchorTime = Playback.Start

    override fun prepare(durationMillis: Long, startMillis: Long) {
        pause()
        duration = durationMillis.coerceAtLeast(Playback.Start)
        anchorPosition = startMillis.coerceIn(Playback.Start, duration)
    }
    override fun play() {
        if (isPlaying) return
        if (anchorPosition >= duration) anchorPosition = Playback.Start
        anchorTime = SystemClock.elapsedRealtime()
        isPlaying = true
    }
    override fun pause() { anchorPosition = getPosition(); isPlaying = false }
    override fun seek(positionMillis: Long) {
        anchorPosition = positionMillis.coerceIn(Playback.Start, duration)
        anchorTime = SystemClock.elapsedRealtime()
    }
    override fun setSpeed(speed: Float) {
        if (speed !in Playback.AllowedSpeeds) return
        anchorPosition = getPosition()
        anchorTime = SystemClock.elapsedRealtime()
        playbackSpeed = speed
    }
    /** 输入：内部时钟；输出：考虑倍速且限制范围的当前播放毫秒。 */
    override fun getPosition(): Long {
        val position = if (isPlaying) anchorPosition + ((SystemClock.elapsedRealtime() - anchorTime) * speed).toLong() else anchorPosition
        return position.coerceIn(Playback.Start, duration)
    }
    override fun getDuration(): Long = duration
}
