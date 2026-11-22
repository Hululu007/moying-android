package com.moying.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class IntroRecognitionResult(
    val match: IntroAudioFingerprint.Match?,
    val coverage: Set<IntroAudioScanCoverage>,
)

internal class IntroRecognition(context: Context) {
    private val extractor = IntroAudioExtractor(context.applicationContext)
    private val mutex = Mutex()
    private var lastReference: ScannedEpisode? = null

    suspend fun compare(
        current: Episode,
        reference: Episode,
        background: Boolean = false,
    ): IntroRecognitionResult = mutex.withLock {
        val cachedCurrent = lastReference?.takeIf { background && it.episode == current }
        val (result, scannedReference) = withContext(Dispatchers.IO) {
            val currentMedia = if (cachedCurrent == null) resolvePlaybackEpisode(current) else null
            val referenceMedia = resolvePlaybackEpisode(reference)
            val scanContext = currentCoroutineContext()
            val budget = IntroAudioScanBudget(background) { !scanContext.isActive }
            val first = cachedCurrent ?: scan(current, checkNotNull(currentMedia), budget)
            val second = scan(reference, referenceMedia, budget)
            val match = IntroAudioFingerprint.match(first.fingerprint, second.fingerprint, budget::check)
            IntroRecognitionResult(match, setOf(first.coverage, second.coverage)) to second
        }
        currentCoroutineContext().ensureActive()
        if (result.match != null) lastReference = scannedReference
        result
    }

    private fun scan(episode: Episode, media: Episode, budget: IntroAudioScanBudget): ScannedEpisode {
        val collector = IntroAudioFingerprint.Collector()
        val coverage = extractor.scan(media.url, budget, media.headers, collector::appendPcm16)
        return ScannedEpisode(episode, collector.finish(), coverage)
    }

    private data class ScannedEpisode(
        val episode: Episode,
        val fingerprint: IntroAudioFingerprint,
        val coverage: IntroAudioScanCoverage,
    )
}
