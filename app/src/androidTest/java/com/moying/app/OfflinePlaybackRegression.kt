package com.moying.app

import android.os.Bundle

/** Exercises the actual bottom-navigation resolver without opening media or changing app data. */
internal object OfflinePlaybackRegression {
    fun run(): Bundle {
        var assertions = 0
        var cases = 0
        fun verify(value: Boolean, message: String) { assertions++; check(value) { message } }
        fun movie(id: String) = Movie(id, id, "电影", "2026", "", "", "", "", "", "", "", 6, 90_000L)
        val cached = movie("cms-only-in-downloads")
        val local = movie("local-demo")
        val online = movie("online-movie")
        fun route(id: String, vararg available: Int) = PlaybackRoute(id, id,
            (1..6).map { Episode(it, "第${it}集", if (it in available) "https://example.invalid/$id/$it.mp4" else "") })
        val routeA = route("route-a", 2, 3)
        val routeB = route("route-b", 5, 6)
        var routes = listOf(routeA, routeB)
        var history = emptyList<WatchHistory>()
        fun resolve(request: PlaybackPageRequest, session: RetainedPlayback? = null, position: Long = 0L) =
            resolvePlaybackPageTarget(request, session, position, history,
                getMovie = { when (it) { local.id -> local; online.id -> online; else -> null } },
                getOfflineMovie = { if (it == cached.id) cached else null },
                getOfflineRoutes = { if (it == cached.id) routes else emptyList() }, fallbackMovie = local)

        // Simulate clearing history, opening a downloaded episode, going home
        // and pressing Play. The catalogue intentionally has only its demo.
        val request = PlaybackPageRequest(cached.id, true, routeA.id, 3, 17_000L)
        val retained = RetainedPlayback(cached.id, routeA.id, 3, routeA.episodes[2].url)
        val returned = checkNotNull(resolve(request, retained, 42_000L))
        verify(returned.movie.id == cached.id, "offline-only movie must not become the local demo after returning home")
        verify(returned.offline && returned.offlineRouteId == routeA.id, "offline route must survive bottom navigation")
        verify(returned.episode == 3 && returned.positionMillis == 42_000L, "bottom navigation resumes the actual retained episode and position")
        val returnedAgain = checkNotNull(resolve(
            PlaybackPageRequest(returned.movie.id, returned.offline, returned.offlineRouteId.orEmpty(), returned.episode, returned.positionMillis),
            retained, 44_000L))
        verify(returnedAgain.movie.id == cached.id && returnedAgain.offlineRouteId == routeA.id && returnedAgain.episode == 3,
            "repeated home/play navigation keeps the same offline identity")
        cases++

        val switched = checkNotNull(resolve(request, RetainedPlayback(cached.id, routeB.id, 6, routeB.episodes[5].url), 63_000L))
        verify(switched.offlineRouteId == routeB.id && switched.episode == 6, "a route/episode selected inside the player supersedes the old cache entry selection")
        verify(switched.positionMillis == 63_000L, "switching route retains its confirmed position")
        cases++

        history = listOf(WatchHistory(cached.id, 3, 22_000L, 90_000L, 1L))
        val cold = checkNotNull(resolve(request.copy(episode = 2, positionMillis = 0L)))
        verify(cold.movie.id == cached.id && cold.offline && cold.episode == 3, "cold restore uses the saved downloaded episode without catalogue metadata")
        verify(cold.positionMillis == 22_000L, "cold restore uses saved progress")
        cases++

        history = listOf(WatchHistory(cached.id, 4, 33_000L, 90_000L, 1L))
        val uncachedHistory = checkNotNull(resolve(request.copy(episode = 2, positionMillis = 5_000L)))
        verify(uncachedHistory.episode == 2, "uncached history placeholders must not replace an available requested episode")
        verify(uncachedHistory.positionMillis == 5_000L, "an uncached episode's position must not leak into a cached episode")
        cases++

        history = emptyList()
        val onlineTarget = checkNotNull(resolve(PlaybackPageRequest(online.id, false, "", 1, 0L)))
        verify(onlineTarget.movie.id == online.id && !onlineTarget.offline, "ordinary catalogue playback keeps its normal route source")
        val implicitOffline = checkNotNull(resolve(request.copy(offline = false)))
        verify(implicitOffline.movie.id == cached.id && implicitOffline.offline, "missing catalogue metadata can still resume an available offline snapshot")
        cases++

        routes = emptyList()
        val deleted = checkNotNull(resolve(request))
        verify(deleted.movie.id == local.id && !deleted.offline, "deleted offline data falls back without exposing an empty offline route")
        cases++
        return Bundle().apply {
            putInt("assertions", assertions)
            putInt("completedCases", cases)
            putString("stream", "Offline playback navigation regression passed: $assertions assertions, $cases cases\n")
        }
    }
}
