package com.moying.app

/** Last page choice, separate from whether the ordinary catalogue still knows the movie. */
internal data class PlaybackPageRequest(
    val movieId: String,
    val offline: Boolean,
    val routeId: String,
    val episode: Int,
    val positionMillis: Long,
)

internal data class PlaybackPageTarget(
    val movie: Movie,
    val offlineRouteId: String?,
    val episode: Int,
    val positionMillis: Long,
) {
    val offline: Boolean get() = offlineRouteId != null
}

/**
 * Resolve the bottom navigation's resume target before starting a decoder.
 * Offline snapshots/routes are deliberately independent of catalogue history:
 * clearing history must not make a downloaded movie turn into the local demo.
 */
internal fun resolvePlaybackPageTarget(
    request: PlaybackPageRequest,
    retained: RetainedPlayback?,
    retainedPosition: Long,
    history: List<WatchHistory>,
    getMovie: (String) -> Movie?,
    getOfflineMovie: (String) -> Movie?,
    getOfflineRoutes: (String) -> List<PlaybackRoute>,
    fallbackMovie: Movie?,
): PlaybackPageTarget? {
    fun offlineTarget(id: String): PlaybackPageTarget? {
        val movie = getOfflineMovie(id) ?: return null
        val routes = getOfflineRoutes(id).filter { route -> route.episodes.any { it.url.isNotBlank() } }
        if (routes.isEmpty()) return null
        val session = retained?.takeIf { it.movieId == id }
        val route = routes.firstOrNull { it.id == session?.routeId }
            ?: routes.firstOrNull { it.id == request.routeId }
            ?: routes.first()
        val saved = history.firstOrNull { it.movieId == id }
        val available = route.episodes.filter { it.url.isNotBlank() }
        val episode = available.firstOrNull { session != null && it.number == session.episode && route.id == session.routeId }
            ?: available.firstOrNull { it.number == saved?.episode }
            ?: available.firstOrNull { it.number == request.episode && request.movieId == id }
            ?: available.first()
        val position = when {
            session != null && session.routeId == route.id && session.episode == episode.number -> retainedPosition
            saved != null && saved.episode == episode.number -> saved.positionMillis
            request.movieId == id && request.routeId == route.id && request.episode == episode.number -> request.positionMillis
            else -> Playback.Start
        }
        return PlaybackPageTarget(movie, route.id, episode.number, position.coerceAtLeast(Playback.Start))
    }
    val requestedMovie = getMovie(request.movieId)
    if (request.offline || requestedMovie == null) offlineTarget(request.movieId)?.let { return it }
    val saved = history.firstOrNull { it.movieId == request.movieId } ?: history.firstOrNull()
    val movie = requestedMovie ?: saved?.let { getMovie(it.movieId) }
    if (movie == null && saved != null) offlineTarget(saved.movieId)?.let { return it }
    val selected = movie ?: fallbackMovie ?: return null
    val matching = saved?.takeIf { it.movieId == selected.id }
    val session = retained?.takeIf { it.movieId == selected.id }
    return PlaybackPageTarget(selected, null,
        session?.episode ?: matching?.episode ?: Playback.FirstEpisode,
        (if (session != null) retainedPosition else matching?.positionMillis ?: Playback.Start).coerceAtLeast(Playback.Start))
}
