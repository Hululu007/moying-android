package com.moying.app

import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

internal object IntroAudioExtractorRegression {
    fun run(context: Context, args: Bundle): Bundle {
        val base = checkNotNull(args.getString("introFixture")) { "Pass -e introFixture with the test server URL" }.trimEnd('/')
        val extractor = IntroAudioExtractor(context)
        val result = Bundle()
        val failures = mutableListOf<String>()

        fun read(path: String, budget: IntroAudioScanBudget): Pair<IntroAudioFingerprint, String> {
            val collector = IntroAudioFingerprint.Collector()
            var firstTimeUs = -1L
            var nextTimeUs = -1L
            var frames = 0L
            var discontinuities = 0
            extractor.scan("$base/$path", budget) { pcm, rate, channels, timeUs ->
                if (firstTimeUs < 0L) firstTimeUs = timeUs
                if (nextTimeUs >= 0L && abs(nextTimeUs - timeUs) > 12_000L) discontinuities++
                val count = pcm.remaining() / (Short.SIZE_BYTES * channels)
                frames += count
                nextTimeUs = timeUs + count * 1_000_000L / rate
                collector.appendPcm16(pcm, rate, channels, timeUs)
            }
            return collector.finish() to "$frames PCM frames, ${firstTimeUs / 1_000L}–${nextTimeUs / 1_000L} ms, $discontinuities timestamp gaps"
        }

        fun verify(label: String, episode: (Int) -> String) {
            val budget = IntroAudioScanBudget()
            try {
                val (first, firstAudio) = read(episode(1), budget)
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

        fun verifyRejected(path: String, reason: String) {
            val failure = runCatching { read(path, IntroAudioScanBudget()) }.exceptionOrNull()
            check(failure is IntroAudioScanException && failure.message.orEmpty().contains(reason)) {
                "Expected '$reason' rejection for $path, got $failure"
            }
        }

        verifyRejected("negative/live.m3u8", "直播")
        verifyRejected("negative/encrypted.m3u8", "已加密")
        check(runCatching { read("episode-1.mp4", IntroAudioScanBudget { true }) }.exceptionOrNull() is CancellationException)
        result.putString("rejections", "live HLS, encrypted HLS, cancelled scan")
        check(failures.isEmpty()) { "${failures.joinToString("\n")}\nCompleted results: $result" }
        return result
    }
}
