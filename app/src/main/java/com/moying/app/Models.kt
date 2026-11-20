package com.moying.app

data class Movie(
    val id: String,
    val title: String,
    val category: String,
    val year: String,
    val region: String,
    val genres: String,
    val score: String,
    val status: String,
    val poster: String,
    val backdrop: String,
    val synopsis: String,
    val episodeCount: Int,
    val durationMillis: Long,
    val sourceCredit: String = "",
)

data class WatchHistory(val movieId: String, val episode: Int, val positionMillis: Long, val durationMillis: Long, val watchedAt: Long)

data class MoviePage(val items: List<Movie>, val hasMore: Boolean)

/** 行为/领域常量，不包含任何视觉参数。 */
object Playback {
    const val FirstEpisode = 1
    const val Start = 0L
    const val MillisPerSecond = 1_000L
    const val SecondsPerMinute = 60L
    const val DefaultSpeed = 1f
    const val HoldSpeed = 3f
    val Speeds = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
    val AllowedSpeeds = Speeds + HoldSpeed
}

object Catalog {
    const val PageSize = 6
    const val FirstPage = 0
    const val ImageCacheKiB = 16 * 1024
    const val BitmapBytesPerPixel = 4
    const val BytesPerKiB = 1024
}
