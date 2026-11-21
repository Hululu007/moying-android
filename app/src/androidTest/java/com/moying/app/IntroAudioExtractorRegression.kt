package com.moying.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import java.util.zip.CRC32
import kotlin.math.abs

internal object IntroAudioExtractorRegression {
    fun run(context: Context, args: Bundle): Bundle {
        val base = checkNotNull(args.getString("introFixture")) { "Pass -e introFixture with the test server URL" }.trimEnd('/')
        val extractor = IntroAudioExtractor(context)
        val result = Bundle()
        val failures = mutableListOf<String>()
        val episodeOnePcm = mutableMapOf<String, String>()

        fun read(path: String, budget: IntroAudioScanBudget): Pair<IntroAudioFingerprint, String> {
            val collector = IntroAudioFingerprint.Collector()
            var firstTimeUs = -1L
            var nextTimeUs = -1L
            var frames = 0L
            var discontinuities = 0
            val checksum = CRC32()
            extractor.scan("$base/$path", budget) { pcm, rate, channels, timeUs ->
                if (firstTimeUs < 0L) firstTimeUs = timeUs
                if (nextTimeUs >= 0L && abs(nextTimeUs - timeUs) > 12_000L) discontinuities++
                val count = pcm.remaining() / (Short.SIZE_BYTES * channels)
                frames += count
                nextTimeUs = timeUs + count * 1_000_000L / rate
                checksum.update(pcm.duplicate())
                collector.appendPcm16(pcm, rate, channels, timeUs)
            }
            return collector.finish() to "${checksum.value}: $frames PCM frames, ${firstTimeUs / 1_000L}–${nextTimeUs / 1_000L} ms, $discontinuities timestamp gaps"
        }

        fun verify(label: String, expectedAudioPath: String? = null, episode: (Int) -> String) {
            val budget = IntroAudioScanBudget()
            try {
                val firstPath = episode(1)
                val (first, firstAudio) = read(firstPath, budget)
                if (expectedAudioPath != null) {
                    check(firstAudio == episodeOnePcm[expectedAudioPath]) { "Recovered audio did not match $expectedAudioPath: $firstAudio" }
                }
                episodeOnePcm[firstPath] = firstAudio
                val (second, secondAudio) = read(episode(2), budget)
                val match = checkNotNull(IntroAudioFingerprint.match(second, first, budget::check)) {
                    "Intro was not detected. Episode 1: $firstAudio. Episode 2: $secondAudio."
                }
                check(abs(match.startMillis - 7_000L) < 1_000L && abs(match.endMillis - 32_000L) < 1_000L &&
                    abs(match.referenceStartMillis - 3_000L) < 1_000L && abs(match.referenceEndMillis - 28_000L) < 1_000L
                ) { "Unexpected timestamps $match" }
                val (unrelated) = read(episode(3), IntroAudioScanBudget())
                check(IntroAudioFingerprint.match(unrelated, first) == null) { "Matched an unrelated episode" }
                result.putString(label, match.toString())
            } catch (error: Exception) {
                failures += "$label: ${error.message}"
            }
        }

        verify("progressive MP4") { "episode-$it.mp4" }
        verify("MPEG-TS HLS") { "hls/episode-$it/index.m3u8" }
        verify("fragmented MP4 HLS") { "hls-fmp4/episode-$it/index.m3u8" }
        verify("AES-128 MPEG-TS HLS") { "aes128/basic/episode-$it/index.m3u8" }
        verify("rotating AES-128 MPEG-TS HLS") { "aes128/rotating/episode-$it/index.m3u8" }
        verify("AES-128 fragmented MP4 HLS") { "aes128/fmp4/episode-$it/index.m3u8" }
        verify("slow HLS response", "hls/episode-1/index.m3u8") {
            if (it == 1) "slow/episode-1/index.m3u8" else "hls/episode-$it/index.m3u8"
        }
        verify("HLS response timeout retry", "hls/episode-1/index.m3u8") {
            if (it == 1) "timeout-once/episode-1/index.m3u8" else "hls/episode-$it/index.m3u8"
        }
        verify("HLS partial body timeout retry", "hls/episode-1/index.m3u8") {
            if (it == 1) "drop/episode-1/index.m3u8" else "hls/episode-$it/index.m3u8"
        }
        verify("AES-128 HLS partial body timeout retry", "aes128/rotating/episode-1/index.m3u8") {
            if (it == 1) "aes-drop/episode-1/index.m3u8" else "aes128/rotating/episode-$it/index.m3u8"
        }
        if (args.getString("introDelayedFixture") == "true") {
            val started = SystemClock.elapsedRealtime()
            verify("many delayed HLS segments", "hls/episode-1/index.m3u8") {
                val route = if (it <= 2) "many-delays" else "hls"
                "$route/episode-$it/index.m3u8"
            }
            result.putLong("many delayed HLS segments elapsed milliseconds", SystemClock.elapsedRealtime() - started)

            fun introTemporaryFiles() = context.cacheDir.listFiles()
                .orEmpty().filter { it.name.startsWith("intro-audio-") }.toSet()
            val filesBefore = introTemporaryFiles()
            val cancelStarted = SystemClock.elapsedRealtime()
            val cancelBudget = IntroAudioScanBudget { SystemClock.elapsedRealtime() - cancelStarted >= 1_200L }
            val cancelled = runCatching { read("many-delays/episode-1/index.m3u8", cancelBudget) }.exceptionOrNull()
            check(cancelled is CancellationException) { "Expected in-flight cancellation, got $cancelled" }
            val cancelElapsed = SystemClock.elapsedRealtime() - cancelStarted
            check(cancelElapsed < 3_500L) { "Took ${cancelElapsed} ms to cancel delayed downloads" }
            check(introTemporaryFiles() == filesBefore) { "Cancelled HLS download left temporary files" }
            result.putLong("in-flight HLS cancellation elapsed milliseconds", cancelElapsed)
        }

        fun verifyRejected(path: String, reason: String) {
            val failure = runCatching { read(path, IntroAudioScanBudget()) }.exceptionOrNull()
            check(failure is IntroAudioScanException && failure.message.orEmpty().contains(reason)) {
                "Expected '$reason' rejection for $path, got $failure"
            }
        }

        verifyRejected("negative/live.m3u8", "直播")
        verifyRejected("negative/missing-key.m3u8", "无法读取 HLS 解密密钥")
        verifyRejected("negative/invalid-key.m3u8", "无效的 HLS AES-128 密钥")
        verifyRejected("negative/sample-aes.m3u8", "暂不支持的加密方式")
        verifyRejected("negative/drm.m3u8", "暂不支持的加密方式")
        check(runCatching { read("episode-1.mp4", IntroAudioScanBudget { true }) }.exceptionOrNull() is CancellationException)
        result.putString("rejections", "live HLS, missing and invalid AES-128 keys, SAMPLE-AES, DRM, cancelled scan")
        check(failures.isEmpty()) { "${failures.joinToString("\n")}\nCompleted results: $result" }
        return result
    }
}
