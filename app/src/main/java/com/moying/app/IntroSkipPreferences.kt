package com.moying.app

import android.content.Context

internal data class IntroSkipRule(val endMillis: Long = 0L, val automatic: Boolean = false) {
    fun canSkip(position: Long, duration: Long): Boolean = position < endMillis && endMillis in 1L until duration
}

internal data class DetectedIntro(val startMillis: Long, val endMillis: Long) {
    fun canSkip(position: Long, duration: Long): Boolean =
        startMillis >= 0L && position in startMillis until endMillis && endMillis < duration
}

/** Different playback routes can have different edits of the same series. */
internal class IntroSkipPreferences(context: Context, movieId: String, routeId: String) {
    private val preferences = context.getSharedPreferences("moying-intro-skip", Context.MODE_PRIVATE)
    private val key = "${movieId.length}:$movieId$routeId"
    private val endKey = "$key:end"
    private val autoKey = "$key:auto"

    fun read(): IntroSkipRule = IntroSkipRule(preferences.getLong(endKey, 0L), preferences.getBoolean(autoKey, false))

    fun write(rule: IntroSkipRule) {
        val editor = preferences.edit()
        if (rule.endMillis > 0L) editor.putLong(endKey, rule.endMillis) else editor.remove(endKey)
        if (rule.automatic) editor.putBoolean(autoKey, true) else editor.remove(autoKey)
        editor.apply()
    }

    fun readDetection(episode: Episode): DetectedIntro? {
        val fields = preferences.getString(episodeKey(episode), null)?.split('|') ?: return null
        if (fields.size != 3 || fields[0] != SourceImport.hash(episode.url)) return null
        val start = fields[1].toLongOrNull() ?: return null
        val end = fields[2].toLongOrNull() ?: return null
        return DetectedIntro(start, end).takeIf { start >= 0L && end > start }
    }

    fun saveDetections(current: Episode, range: DetectedIntro, reference: Episode, referenceRange: DetectedIntro) {
        preferences.edit()
            .putString(episodeKey(current), encode(current, range))
            .putString(episodeKey(reference), encode(reference, referenceRange))
            .apply()
    }

    fun clearDetection(episode: Episode) {
        preferences.edit().remove(episodeKey(episode)).apply()
    }

    private fun episodeKey(episode: Episode) = "$key:episode:${episode.number}"
    private fun encode(episode: Episode, range: DetectedIntro) =
        "${SourceImport.hash(episode.url)}|${range.startMillis}|${range.endMillis}"
}
