package com.moying.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Audio-only comparison over the opening few minutes of two episodes. */
internal class IntroAudioFingerprint private constructor(
    private val shape: ByteArray,
    private val change: ByteArray,
    private val shapeSigns: IntArray,
    private val motion: IntArray,
    private val frameCount: Int
) {
    data class Match(
        val startMillis: Long,
        val endMillis: Long,
        val referenceStartMillis: Long,
        val referenceEndMillis: Long
    )

    class Collector(maxDurationMillis: Long = SCAN_LIMIT_MILLIS) {
        private val sampleLimit = (maxDurationMillis.coerceIn(0, SCAN_LIMIT_MILLIS) * SAMPLE_RATE / 1000).toInt()
        private val capacity = sampleLimit / HOP_SIZE
        private val shape = ByteArray(capacity * BAND_COUNT)
        private val change = ByteArray(capacity * BAND_COUNT)
        private val shapeSigns = IntArray(capacity) { -1 }
        private val motion = IntArray(capacity)
        private val signal = FloatArray(FFT_SIZE)
        private val real = FloatArray(FFT_SIZE)
        private val imaginary = FloatArray(FFT_SIZE)
        private val bandPower = DoubleArray(BAND_COUNT)
        private val logBands = Array(CHANGE_LAG + 1) { DoubleArray(BAND_COUNT) }
        private var consecutiveFrames = 0
        private var sourceRate = 0
        private var sourceChannels = 0
        private var sourceStartUs = 0L
        private var sourceFrames = 0L
        private var resamplePosition = 0L
        private var resampleWeight = 0
        private var resampleSum = 0L
        private var sampleEnd = 0
        private var windowFill = 0
        private var frameCount = 0
        private var finished = false

        /** Reads complete interleaved PCM16 frames without changing the supplied buffer. */
        fun appendPcm16(samples: ByteBuffer, sampleRate: Int, channelCount: Int, presentationTimeUs: Long) {
            check(!finished)
            if (sampleRate !in 1..384_000 || channelCount !in 1..32 || sampleEnd >= sampleLimit) return
            val sourceFrameBytes = channelCount * Short.SIZE_BYTES
            val availableFrames = samples.remaining() / sourceFrameBytes
            if (availableFrames == 0) return
            val expectedTimeUs = if (sourceRate == 0) presentationTimeUs else {
                sourceStartUs + sourceFrames * 1_000_000 / sourceRate
            }
            if (sourceRate != sampleRate || sourceChannels != channelCount ||
                abs(presentationTimeUs - expectedTimeUs) > TIMESTAMP_TOLERANCE_US
            ) {
                sourceRate = sampleRate
                sourceChannels = channelCount
                sourceStartUs = presentationTimeUs
                sourceFrames = 0L
                resamplePosition = presentationTimeUs * SAMPLE_RATE / 1_000_000
                resampleWeight = 0
                resampleSum = 0L
                windowFill = 0
                consecutiveFrames = 0
            }
            val input = samples.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            repeat(availableFrames) {
                if (resamplePosition >= sampleLimit) return
                var sum = 0
                repeat(channelCount) { sum += input.short.toInt() }
                addSourceSample(sum)
                sourceFrames++
            }
        }

        fun finish(): IntroAudioFingerprint {
            finished = true
            return IntroAudioFingerprint(shape, change, shapeSigns, motion, frameCount)
        }

        private fun addSourceSample(sum: Int) {
            // Integrate each input sample over its share of an output sample. The integer
            // weights keep 44.1 kHz and other nonintegral ratios aligned between buffers.
            var remaining = SAMPLE_RATE
            while (remaining >= sourceRate - resampleWeight) {
                val needed = sourceRate - resampleWeight
                resampleSum += sum.toLong() * needed
                val sample = resampleSum.toFloat() / (sourceRate.toFloat() * sourceChannels * 32768f)
                if (resamplePosition in sampleEnd.toLong() until sampleLimit.toLong()) emitSample(sample)
                resamplePosition++
                remaining -= needed
                resampleWeight = 0
                resampleSum = 0L
            }
            resampleSum += sum.toLong() * remaining
            resampleWeight += remaining
        }

        private fun emitSample(sample: Float) {
            val position = resamplePosition.toInt()
            if (position != sampleEnd) {
                windowFill = 0
                consecutiveFrames = 0
            }
            sampleEnd = position + 1
            signal[position and (FFT_SIZE - 1)] = sample
            windowFill = (windowFill + 1).coerceAtMost(FFT_SIZE)
            val windowStart = sampleEnd - FFT_SIZE
            if (windowFill == FFT_SIZE && windowStart % HOP_SIZE == 0) analyze(windowStart)
        }

        private fun analyze(windowStart: Int) {
            val frame = windowStart / HOP_SIZE
            frameCount = frame + 1
            var signalPower = 0f
            for (i in 0 until FFT_SIZE) {
                val sample = signal[(windowStart + i) and (FFT_SIZE - 1)] * window[i]
                signalPower += sample * sample
                real[bitReverse[i]] = sample
            }
            if (signalPower < FFT_SIZE * 0.0000006f) {
                consecutiveFrames = 0
                return
            }
            imaginary.fill(0f)
            transform(real, imaginary)
            for (band in 0 until BAND_COUNT) {
                var energy = 0.0
                for (bin in bandEdges[band] until bandEdges[band + 1]) {
                    energy += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                }
                bandPower[band] = energy / (bandEdges[band + 1] - bandEdges[band])
            }
            val peak = bandPower.max()
            // A relative floor makes empty bands insensitive to coding and rounding noise.
            val floor = peak * 0.001
            if (floor <= 0) {
                consecutiveFrames = 0
                return
            }
            val current = logBands[frame % logBands.size]
            val earlier = logBands[(frame + 1) % logBands.size]
            var mean = 0.0
            for (band in 0 until BAND_COUNT) {
                current[band] = ln(bandPower[band].coerceAtLeast(floor))
                mean += current[band]
            }
            mean /= BAND_COUNT

            val offset = frame * BAND_COUNT
            var signs = 0
            var movement = 0
            for (band in 0 until BAND_COUNT) {
                shape[offset + band] = quantize(current[band] - mean)
                if (band > 0 && current[band] > current[band - 1]) signs = signs or (1 shl (band - 1))
                if (consecutiveFrames >= CHANGE_LAG) {
                    val delta = quantize(current[band] - earlier[band])
                    change[offset + band] = delta
                    movement += abs(delta.toInt())
                }
            }
            shapeSigns[frame] = signs
            motion[frame] = movement
            consecutiveFrames = (consecutiveFrames + 1).coerceAtMost(CHANGE_LAG)
        }
    }

    private data class Segment(val currentFirst: Int, val referenceFirst: Int, val length: Int, val score: Int)

    companion object {
        const val SCAN_LIMIT_MILLIS = 180_000L
        private const val SAMPLE_RATE = 8000
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 512
        private const val HOP_MILLIS = HOP_SIZE * 1000L / SAMPLE_RATE
        private const val BAND_COUNT = 16
        private const val CHANGE_LAG = 2
        private const val TIMESTAMP_TOLERANCE_US = 12_000L
        private const val MIN_LENGTH = (18_000L / HOP_MILLIS).toInt() + 1
        private const val MAX_GAP = (760L / HOP_MILLIS).toInt()
        private const val MIN_MOTION = BAND_COUNT * 4

        private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        private val twiddleReal = FloatArray(FFT_SIZE / 2) { cos(-2 * PI * it / FFT_SIZE).toFloat() }
        private val twiddleImaginary = FloatArray(FFT_SIZE / 2) { sin(-2 * PI * it / FFT_SIZE).toFloat() }
        private val bitReverse = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(FFT_SIZE)) }
        private val bandEdges = IntArray(BAND_COUNT + 1) {
            val hz = 80 * Math.pow(3600.0 / 80, it.toDouble() / BAND_COUNT)
            (hz * FFT_SIZE / SAMPLE_RATE).roundToInt()
        }

        fun match(
            current: IntroAudioFingerprint,
            reference: IntroAudioFingerprint,
            checkActive: () -> Unit = {}
        ): Match? {
            if (current === reference || current.frameCount < MIN_LENGTH || reference.frameCount < MIN_LENGTH) return null
            val candidates = ArrayList<Segment>(8)
            // Walk fixed time offsets independently so a cold open on either side
            // cannot shift the detected bounds for the other episode.
            checkActive()
            for (shift in MIN_LENGTH - reference.frameCount..current.frameCount - MIN_LENGTH) {
                if (shift % 24 == 0) checkActive()
                val first = shift.coerceAtLeast(0)
                val end = minOf(current.frameCount, reference.frameCount + shift)
                var start = first
                var lastMatched = first - 1
                var matched = 0
                var dynamic = 0
                for (frame in first until end) {
                    val other = frame - shift
                    val agreement = current.agreement(frame, reference, other)
                    if (agreement > 0) {
                        if (matched == 0) start = frame
                        lastMatched = frame
                        matched++
                        if (agreement == 2) dynamic++
                    } else if (frame - lastMatched > MAX_GAP && matched > 0) {
                        val length = lastMatched - start + 1
                        if (length >= MIN_LENGTH && matched * 100 >= length * 78 && dynamic * 100 >= length * 37) {
                            // Penalize gaps so scattered matches in a cold open or tail do
                            // not outrank a closely aligned intro at the neighboring offset.
                            val score = matched + dynamic - 3 * (length - matched)
                            recordCandidate(candidates, Segment(start, start - shift, length, score))
                        }
                        matched = 0
                        dynamic = 0
                    }
                }
                // A run still matching when either scan ends has no confirmed end.
            }
            val best = candidates.maxByOrNull { it.score } ?: return null
            val ambiguous = candidates.any { it.score * 100 >= best.score * 89 && !sameSegment(it, best) }
            if (ambiguous) return null
            val startPad = FFT_SIZE * 500L / SAMPLE_RATE
            fun startMillis(frame: Int): Long = (frame * HOP_MILLIS + startPad).let { if (it <= 250) 0L else it }
            fun endMillis(frame: Int): Long = frame * HOP_MILLIS + startPad + HOP_MILLIS / 2
            return Match(
                startMillis(best.currentFirst),
                endMillis(best.currentFirst + best.length - 1),
                startMillis(best.referenceFirst),
                endMillis(best.referenceFirst + best.length - 1)
            )
        }

        private fun sameSegment(first: Segment, second: Segment): Boolean =
            abs((first.currentFirst - first.referenceFirst) - (second.currentFirst - second.referenceFirst)) <= 4 &&
            abs(first.currentFirst - second.currentFirst) * HOP_MILLIS <= 3000 &&
                abs(first.referenceFirst - second.referenceFirst) * HOP_MILLIS <= 3000 &&
                abs(first.length - second.length) * HOP_MILLIS <= 3000

        private fun recordCandidate(candidates: MutableList<Segment>, candidate: Segment) {
            val nearby = candidates.indexOfFirst { sameSegment(it, candidate) }
            if (nearby != -1) {
                if (candidate.score > candidates[nearby].score) candidates[nearby] = candidate
                return
            }
            if (candidates.size < 8) {
                candidates.add(candidate)
                return
            }
            val weakest = candidates.indices.minBy { candidates[it].score }
            if (candidate.score > candidates[weakest].score) candidates[weakest] = candidate
        }

        private fun quantize(value: Double): Byte = (value * 12).roundToInt().coerceIn(-127, 127).toByte()

        private fun transform(real: FloatArray, imaginary: FloatArray) {
            var size = 2
            while (size <= FFT_SIZE) {
                val half = size / 2
                val stride = FFT_SIZE / size
                for (start in 0 until FFT_SIZE step size) {
                    for (i in 0 until half) {
                        val first = start + i
                        val second = first + half
                        val weight = i * stride
                        val r = real[second] * twiddleReal[weight] - imaginary[second] * twiddleImaginary[weight]
                        val im = real[second] * twiddleImaginary[weight] + imaginary[second] * twiddleReal[weight]
                        real[second] = real[first] - r
                        imaginary[second] = imaginary[first] - im
                        real[first] += r
                        imaginary[first] += im
                    }
                }
                size *= 2
            }
        }
    }

    private fun agreement(frame: Int, reference: IntroAudioFingerprint, other: Int): Int {
        val signs = shapeSigns[frame]
        val otherSigns = reference.shapeSigns[other]
        if (signs < 0 || otherSigns < 0 || Integer.bitCount(signs xor otherSigns) > 6) return 0
        val first = frame * BAND_COUNT
        val second = other * BAND_COUNT
        var shapeDistance = 0
        for (band in 0 until BAND_COUNT) {
            shapeDistance += abs(shape[first + band].toInt() - reference.shape[second + band])
        }
        if (shapeDistance > BAND_COUNT * 10) return 0
        val firstMotion = motion[frame]
        val secondMotion = reference.motion[other]
        if (firstMotion < MIN_MOTION && secondMotion < MIN_MOTION) return 1
        var changeDistance = 0
        for (band in 0 until BAND_COUNT) {
            changeDistance += abs(change[first + band].toInt() - reference.change[second + band])
        }
        if (changeDistance * 100 > (firstMotion + secondMotion) * 69) return 0
        return if (minOf(firstMotion, secondMotion) >= MIN_MOTION) 2 else 1
    }
}
