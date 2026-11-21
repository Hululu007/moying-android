package com.moying.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

internal object IntroAudioFingerprintRegression {
    @JvmStatic
    fun main(args: Array<String>) = run()

    fun run() {
        val common = Music(seed = 3408)
        val current = episode(common, startMillis = 9313, introMillis = 28_000, seed = 55, gain = 0.36, sampleRate = 44_100)
        val reference = episode(common, startMillis = 2084, introMillis = 28_000, seed = 99, channels = 2)
        val match = checkNotNull(IntroAudioFingerprint.match(current, reference)) { "shifted intro survived PCM format and gain changes" }
        near(match.startMillis, 9313, "current intro start")
        near(match.endMillis, 37_313, "current intro end")
        near(match.referenceStartMillis, 2084, "reference intro start")
        near(match.referenceEndMillis, 30_084, "reference intro end")

        // 4000 ms falls exactly halfway between the two nearest 64 ms offsets.
        val halfHopCurrent = episode(common, startMillis = 7000, introMillis = 25_000, seed = 22, gain = 0.83)
        val halfHopReference = episode(common, startMillis = 3000, introMillis = 25_000, seed = 11)
        val halfHopMatch = checkNotNull(IntroAudioFingerprint.match(halfHopCurrent, halfHopReference))
        near(halfHopMatch.startMillis, 7000, "half-hop intro start")
        near(halfHopMatch.endMillis, 32_000, "half-hop intro end")
        near(halfHopMatch.referenceStartMillis, 3000, "half-hop reference intro start")
        near(halfHopMatch.referenceEndMillis, 28_000, "half-hop reference intro end")

        val unrelated = episode(Music(seed = 7721), startMillis = 2084, introMillis = 28_000, seed = 99)
        check(IntroAudioFingerprint.match(current, unrelated) == null) { "unrelated music matched" }
        val brief = episode(common, startMillis = 2084, introMillis = 7500, seed = 99)
        check(IntroAudioFingerprint.match(current, brief) == null) { "brief common music matched" }

        val silent = episode({ 0.0 }, startMillis = 2084, introMillis = 28_000, seed = 99)
        val otherSilent = episode({ 0.0 }, startMillis = 9313, introMillis = 28_000, seed = 55)
        check(IntroAudioFingerprint.match(silent, otherSilent) == null) { "silence matched" }
        val tone: (Double) -> Double = { t -> 0.3 * sin(2 * PI * 440 * t) }
        val constant = episode(tone, startMillis = 2084, introMillis = 28_000, seed = 99)
        val otherConstant = episode(tone, startMillis = 9313, introMillis = 28_000, seed = 55)
        check(IntroAudioFingerprint.match(constant, otherConstant) == null) { "steady tone matched" }
        val loop: (Double) -> Double = { t -> common(t % 1.25) }
        check(IntroAudioFingerprint.match(episode(loop, 2084, 28_000, 99), episode(loop, 9313, 28_000, 55)) == null) {
            "ambiguous repeating music matched"
        }

        val cutoff = episode(common, startMillis = 2084, introMillis = 25_000, seed = 99, tailMillis = 0)
        check(IntroAudioFingerprint.match(current, cutoff) == null) { "cutoff was accepted as a confirmed end" }

        val gap = episode(common, startMillis = 18_628, introMillis = 28_000, seed = 55, leadingGapMillis = 16_000)
        val gapMatch = checkNotNull(IntroAudioFingerprint.match(gap, reference)) { "intro after initial audio gap was found" }
        near(gapMatch.startMillis, 18_628, "timestamp after initial audio gap")
        near(gapMatch.endMillis, 46_628, "intro end after initial audio gap")
    }

    private fun near(actual: Long, expected: Long, label: String) {
        check(abs(actual - expected) <= 350) { "$label was $actual ms, expected $expected ms" }
    }

    private fun episode(
        intro: (Double) -> Double,
        startMillis: Int,
        introMillis: Int,
        seed: Int,
        tailMillis: Int = 7000,
        gain: Double = 1.0,
        sampleRate: Int = 48_000,
        channels: Int = 1,
        leadingGapMillis: Int = 0
    ): IntroAudioFingerprint {
        val collector = IntroAudioFingerprint.Collector()
        val lead = Music(seed)
        val tail = Music(seed * 11)
        val buffer = ByteBuffer.allocate(809 * channels * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val first = (sampleRate * leadingGapMillis.toLong() / 1000).toInt()
        val count = (sampleRate * (startMillis + introMillis + tailMillis.toLong()) / 1000).toInt()
        for (chunk in first until count step 809) {
            buffer.clear()
            for (sample in chunk until minOf(chunk + 809, count)) {
                val time = sample.toDouble() / sampleRate
                val offset = time - startMillis / 1000.0
                val amplitude = when {
                    offset < 0 -> lead(time)
                    offset < introMillis / 1000.0 -> intro(offset)
                    else -> tail(time)
                }
                val pcm = (amplitude * gain * 32767).toInt().coerceIn(-32768, 32767).toShort()
                repeat(channels) { buffer.putShort(pcm) }
            }
            buffer.flip()
            collector.appendPcm16(buffer, sampleRate, channels, chunk * 1_000_000L / sampleRate)
            check(buffer.position() == 0) { "collector modified the caller's PCM buffer position" }
        }
        return collector.finish()
    }

    private class Music(seed: Int) : (Double) -> Double {
        private val pitches = Random(seed).let { random ->
            DoubleArray(320) { 120.0 * Math.pow(18.0, random.nextDouble()) }
        }

        override fun invoke(seconds: Double): Double {
            val beat = seconds * 4.8
            val note = beat.toInt() % pitches.size
            val phase = beat % 1.0
            val envelope = sin(PI * phase) * (0.45 + (note % 7) / 14.0)
            val frequency = pitches[note]
            val harmony = pitches[(note + 71) % pitches.size]
            return envelope * (0.32 * sin(2 * PI * frequency * seconds) + 0.20 * sin(2 * PI * harmony * seconds))
        }
    }
}
