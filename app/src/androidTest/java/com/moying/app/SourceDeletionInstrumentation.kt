package com.moying.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default regression: no test-library dependency and no external network traffic. Run with:
 * adb shell am instrument -w com.moying.app.test/com.moying.app.SourceDeletionInstrumentation
 * Every case uses private, uniquely named preferences; existing app data is never cleared.
 */
class SourceDeletionInstrumentation : Instrumentation() {
    private var maintenanceArguments: Bundle? = null
    private var suiteArguments = Bundle()
    private var assertions = 0
    private var completedCases = 0
    private val failures = mutableListOf<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suiteArguments = arguments ?: Bundle()
        maintenanceArguments = arguments?.takeIf { it.getString("maintenance") in setOf("dry-run", "apply") }
        start()
    }

    override fun onStart() {
        when (suiteArguments.getString("suite")) {
            "stream-cache" -> { StreamingCacheRegression.run(this, suiteArguments); return }
            "source-requests" -> { runSuite { SourceRequestRegression.run() }; return }
            "subscription-import" -> { runSuite { subscriptionImportRegression() }; return }
            "poster-loader" -> { runSuite { PosterLoaderRegression.run(targetContext) }; return }
            "intro-audio-fingerprint" -> {
                runSuite {
                    IntroAudioFingerprintRegression.run()
                    Bundle().apply { putString("fingerprint", "passed") }
                }
                return
            }
            "intro-audio-extractor" -> { runSuite { IntroAudioExtractorRegression.run(targetContext, suiteArguments) }; return }
            "intro-next-detection" -> { runSuite { introNextDetectionRegression() }; return }
            "native-player" -> { runSuite { NativePlayerRegression.run(this, suiteArguments) }; return }
            "glass-idle" -> { runSuite { GlassIdleRegression.run(this, suiteArguments) }; return }
            "offline-playback" -> { runSuite { OfflinePlaybackRegression.run() }; return }
            "offline-cache" -> { OfflineCacheRegression.run(this, suiteArguments); return }
        }
        maintenanceArguments?.let { SourceMaintenance.run(this, it); return }
        try {
            testCase("built-in deletion and unchanged selection survive restart") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val network = repository.sources.filter { it.id != "local" }
                assertThat(network.size >= 2, "fixture must include at least two built-in sources")
                val deleted = network.first()
                val selected = network[1]
                repository.selectSource(selected.id)
                val revision = repository.sourceRevision

                assertThat(repository.deleteSource(deleted.id), "built-in source can be deleted")
                assertThat(repository.sources.none { it.id == deleted.id }, "deleted built-in disappears immediately")
                assertThat(repository.selectedSourceId == selected.id, "deleting another source keeps selection")
                assertThat(repository.sourceRevision > revision, "deletion advances catalog revision")

                val restarted = TvBoxMovieRepository(isolated)
                assertThat(restarted.sources.none { it.api == deleted.api }, "built-in stays deleted after restart")
                assertThat(restarted.selectedSourceId == selected.id, "unchanged selection is persisted")
            }

            testCase("import deletion switches selection and stays deleted") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val api = "https://source-deletion-test.invalid/api.php/provide/vod"
                val imported = importSite(repository, "Imported test source", api)
                repository.selectSource(imported.id)

                assertThat(repository.deleteSource(imported.id), "imported source can be deleted")
                assertThat(repository.selectedSourceId != imported.id, "deleting selected source switches selection")
                assertThat(repository.sources.any { it.id == repository.selectedSourceId }, "replacement selection exists")
                assertThat(repository.sources.none { it.api == api }, "imported source disappears immediately")
                val replacement = repository.selectedSourceId

                val restarted = TvBoxMovieRepository(isolated)
                assertThat(restarted.sources.none { it.api == api }, "imported source stays deleted after restart")
                assertThat(restarted.selectedSourceId == replacement, "replacement selection is persisted")
                assertThat(!restarted.deleteSource(imported.id), "deleting the same source twice is harmless")
            }

            testCase("local and missing sources cannot be deleted") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val before = repository.sources.toList()
                val selected = repository.selectedSourceId
                val revision = repository.sourceRevision
                assertThat(!repository.deleteSource("local"), "local source is protected")
                assertThat(!repository.deleteSource("source-that-does-not-exist"), "missing source returns false")
                assertThat(repository.sources == before, "rejected deletions leave source list unchanged")
                assertThat(repository.selectedSourceId == selected, "rejected deletions leave selection unchanged")
                assertThat(repository.sourceRevision == revision, "rejected deletions leave revision unchanged")
            }

            testCase("clearing all network sources preserves local and allows reimport across restart") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val builtIn = repository.sources.first { it.id != "local" }
                val imported = importSite(repository, "Last test source", "https://last-source-test.invalid/api")
                val direct = checkNotNull(repository.addSource("Direct fixture", "https://direct-test.invalid/video.mp4"))
                repository.selectSource(imported.id)
                val networkCount = repository.sources.count { it.id != "local" }
                val revision = repository.sourceRevision
                assertThat(repository.clearNetworkSources() == networkCount, "bulk clear removes built-in and imported sources")
                assertThat(repository.sources.map { it.id } == listOf("local"), "local remains after removing all network sources")
                assertThat(repository.selectedSourceId == "local", "bulk clear selects local")
                assertThat(repository.sourceRevision == revision + 1, "bulk clear refreshes the catalog once")
                assertThat(repository.clearNetworkSources() == 0 && repository.sourceRevision == revision + 1,
                    "clearing again is a no-op")
                assertThat(repository.getMovies(0, repository.categories.first(), direct.title).items.single() == direct,
                    "direct media remains browsable in the local catalog")

                val restarted = TvBoxMovieRepository(isolated)
                assertThat(restarted.sources.map { it.id } == listOf("local"), "deleted sources do not return on restart")
                assertThat(restarted.selectedSourceId == "local", "local fallback is persisted")
                assertThat(restarted.getMovie(direct.id) == direct, "direct media survives restart")
                val restored = importSite(restarted, "Restored built-in", builtIn.api)
                assertThat(TvBoxMovieRepository(isolated).sources.map { it.id } == listOf(restored.id, "local"),
                    "reimport restores only the requested site across restart")
            }

            testCase("reimported built-in has one stable identity after restart") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val original = repository.sources.first { it.id != "local" }
                assertThat(repository.deleteSource(original.id), "remove original built-in")
                val restored = importSite(repository, "Restored test source", original.api)
                assertThat(repository.sources.count { it.api == original.api } == 1, "reimport creates exactly one source")
                repository.selectSource(restored.id)

                val restarted = TvBoxMovieRepository(isolated)
                val matches = restarted.sources.filter { it.api == original.api }
                assertThat(matches.size == 1, "old built-in does not reappear beside reimported source")
                assertThat(matches.single().id == restored.id, "reimported identity survives restart")
                assertThat(restarted.selectedSourceId == restored.id, "reimported source remains selectable")
                assertThat(restarted.deleteSource(restored.id), "reimported source can be deleted again")
                assertThat(TvBoxMovieRepository(isolated).sources.none { it.api == original.api }, "second deletion also survives restart")
            }

            testCase("history and playback routes remain after deleting a fetched source") { isolated ->
                CmsFixture().use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val source = importSite(repository, "Loopback CMS", fixture.api)
                    repository.selectSource(source.id)
                    val page = repository.getMovies(0, repository.categories.first())
                    assertThat(page.items.size == 1, "loopback CMS returns one movie")
                    val movie = page.items.single()
                    assertThat(movie.title == "Source deletion fixture", "real CMS import parsed fixture title")
                    val routes = repository.getPlaybackRoutes(movie.id)
                    assertThat(routes.size == 2, "all CMS playback routes are retained")
                    assertThat(routes.first().episodes.size == 2, "default route contains both episodes")
                    val record = WatchHistory(movie.id, 2, 12_345L, 90_000L, 1_700_000_000_000L)
                    repository.saveHistory(record)

                    assertThat(repository.deleteSource(source.id), "fetched source can be deleted")
                    assertThat(repository.getMovie(movie.id) == movie, "deletion preserves watched movie metadata")
                    assertThat(repository.getPlaybackRoutes(movie.id) == routes, "deletion preserves all playback routes")
                    assertThat(repository.history.single { it.movieId == movie.id } == record, "deletion preserves watch progress")

                    val laterRecord = record.copy(positionMillis = 24_680L, watchedAt = record.watchedAt + 1)
                    repository.saveHistory(laterRecord)
                    val restarted = TvBoxMovieRepository(isolated)
                    assertThat(restarted.sources.none { it.api == fixture.api }, "watch progress does not restore deleted source")
                    assertThat(restarted.getMovie(movie.id) == movie, "watched metadata survives process recreation")
                    assertThat(restarted.getPlaybackRoutes(movie.id) == routes, "all routes survive process recreation")
                    assertThat(restarted.history.single { it.movieId == movie.id } == laterRecord, "retained playback can still save progress")

                    val restored = importSite(restarted, "Loopback CMS restored", fixture.api)
                    restarted.selectSource(restored.id)
                    assertThat(restarted.getMovies(0, restarted.categories.first()).items.single().id == movie.id,
                        "deleted and reimported source can fetch its catalog again")
                    val restoredRestart = TvBoxMovieRepository(isolated)
                    assertThat(restoredRestart.sources.count { it.api == fixture.api } == 1, "reimported CMS persists without duplicates")
                    assertThat(restoredRestartedSelection(restoredRestart, restored.id), "reimported CMS selection survives restart")
                }
            }

            testCase("favorites retain their movies and routes independently of sources and history") { isolated ->
                CmsFixture().use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val localMovie = checkNotNull(repository.fallbackMovie)
                    assertThat(repository.setFavorite(localMovie, true), "local movie can be favorited")
                    val source = importSite(repository, "Favorite source", fixture.api)
                    repository.selectSource(source.id)
                    val movie = repository.getMovies(0, repository.categories.first()).items.single()
                    val routes = repository.getPlaybackRoutes(movie.id)
                    assertThat(!repository.setFavorite(movie.copy(id = "unknown-favorite"), true),
                        "a movie with no retained playback routes is rejected")
                    assertThat(repository.setFavorite(movie, true), "remote movie can be favorited")
                    assertThat(repository.setFavorite(movie, true) && repository.isFavorite(movie.id),
                        "favoriting again is idempotent")
                    assertThat(repository.favorites == listOf(movie, localMovie), "newest favorite comes first without duplicates")

                    repository.saveHistory(WatchHistory(movie.id, 1, 2_000L, 90_000L, 1_700_000_000_000L))
                    repository.clearHistory()
                    assertThat(repository.deleteSource(source.id), "delete the source of the favorite")
                    val restarted = TvBoxMovieRepository(isolated)
                    assertThat(restarted.history.isEmpty(), "favorites do not restore cleared history")
                    assertThat(restarted.sources.none { it.id == source.id }, "favorites do not restore a deleted source")
                    assertThat(restarted.favorites == listOf(movie, localMovie), "favorite order and metadata persist")
                    assertThat(restarted.getMovie(movie.id) == movie, "the saved favorite still opens by ID")
                    assertThat(restarted.getPlaybackRoutes(movie.id) == routes && routes.size == 2,
                        "the saved favorite preserves all original playback routes")

                    assertThat(restarted.setFavorite(movie, false) && !restarted.isFavorite(movie.id),
                        "removing the favorite updates its observable state")
                    val afterRemoval = TvBoxMovieRepository(isolated)
                    assertThat(afterRemoval.favorites == listOf(localMovie), "removal persists without affecting other favorites")
                    assertThat(afterRemoval.getMovie(movie.id) == null, "removal releases the last retained copy on restart")
                }
            }

            testCase("late response cannot overwrite a deleted and reimported source") { isolated ->
                DelayedCmsFixture().use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val original = importSite(repository, "Old request source", fixture.api)
                    repository.selectSource(original.id)
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val oldRequest = executor.submit<MoviePage> {
                            repository.getMovies(0, repository.categories.first())
                        }
                        assertThat(fixture.firstRequestReceived.await(5, TimeUnit.SECONDS), "old request is waiting before its response")
                        assertThat(repository.clearNetworkSources() > 0, "clear sources with a request in flight")
                        val replacement = importSite(repository, "New request source", fixture.api)
                        assertThat(replacement.id == original.id, "same URL reuses ID, so object identity must guard stale writes")
                        repository.selectSource(replacement.id)

                        val currentPage = repository.getMovies(0, repository.categories.first())
                        assertThat(currentPage.items.size == 1 && currentPage.items.single().title == "New generation",
                            "new request completes before the old response is released")
                        val currentMovie = currentPage.items.single()
                        val currentRoutes = repository.getPlaybackRoutes(currentMovie.id)
                        assertThat(currentRoutes.single().episodes.single().url.endsWith("/new.m3u8"),
                            "new generation playback route is initially published")

                        fixture.releaseFirstResponse.countDown()
                        val oldPage = oldRequest.get(5, TimeUnit.SECONDS)
                        assertThat(oldPage.items.any { it.title == "Old generation" }, "old HTTP response completed after the replacement request")
                        assertThat(repository.getMovie(currentMovie.id) == currentMovie, "late response cannot replace shared movie metadata")
                        assertThat(repository.getPlaybackRoutes(currentMovie.id) == currentRoutes, "late response cannot replace new playback routes")
                        assertThat(repository.getMovie("tvbox:${replacement.id}:old-only") == null,
                            "late response cannot insert an old-only movie into the library")
                        val typesField = TvBoxMovieRepository::class.java.getDeclaredField("types").apply { isAccessible = true }
                        val typeCache = typesField.get(repository) as Map<*, *>
                        assertThat((typeCache[replacement.id] as? Map<*, *>)?.get("电影") == "22",
                            "late response cannot overwrite new category IDs")
                        assertThat(repository.getMovies(0, repository.categories.first()) == currentPage,
                            "page cache keeps the new generation after the old response finishes")
                        assertThat(fixture.requestCount.get() == 2, "reading the surviving page cache does not send another request")
                    } finally {
                        fixture.releaseFirstResponse.countDown()
                        executor.shutdownNow()
                        check(executor.awaitTermination(3, TimeUnit.SECONDS)) { "Old request worker did not stop" }
                    }
                }
            }
            testCase("per-source CMS directory, exact subtype and advertised filters") { isolated ->
                val requests = ConcurrentLinkedQueue<Uri>()
                CmsFixture { request ->
                    requests.add(request)
                    if (request.getQueryParameter("ac") == "list") {
                        """
                            {"list":[],"class":[
                                {"type_id":10,"type_name":"电视剧"},
                                {"type_id":11,"type_pid":10,"type_name":"国产剧"},
                                {"type_id":12,"type_pid":10,"type_name":"日剧"},
                                {"type_id":6,"type_name":"纪录片"}
                            ],"filters":{"6":[{"key":"year","name":"年份","value":[
                                {"n":"全部","v":""},{"n":"2025","v":"2025"},{"n":"2024","v":"2024"}
                            ]}]}}
                        """.trimIndent()
                    } else {
                        val type = request.getQueryParameter("t").orEmpty()
                        val year = request.getQueryParameter("year").orEmpty()
                        JSONObject().put("pagecount", 3).put("list", JSONArray().put(JSONObject()
                            .put("vod_id", "$type-$year")
                            .put("vod_name", "样片 $type $year")
                            .put("vod_play_url", "正片\$http://127.0.0.1/sample.mp4"))).toString()
                    }
                }.use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val source = importSite(repository, "Catalog fixture", fixture.api)
                    assertThat(repository.getCatalogMovies(source.id, 0, CatalogRecommend, "", emptyMap()).items.size == 1,
                        "recommendation requests can load by source id without selecting it")
                    assertThat(requests.single().getQueryParameter("ac") == "detail", "recommendation does not wait for directory")
                    val directory = repository.getSourceCatalog(source.id)
                    val docChannel = directory.channels.single { it.label == "纪录片" }
                    val drama = directory.channels.single { channel ->
                        directory.facets(channel.id).any { it.key == CatalogSubtype && it.options.any { option -> option.label == "日剧" } }
                    }
                    val subtype = directory.facets(drama.id).single { it.key == CatalogSubtype }
                    assertThat(subtype.options.any { it.label == "国产剧" } && subtype.defaultValue.isNotBlank(), "hierarchy keeps real subtype ids")
                    repository.getCatalogMovies(source.id, 0, drama.id, "", mapOf(CatalogSubtype to "12"))
                    assertThat(requests.last().getQueryParameter("t") == "12", "selected subtype is queried exactly")
                    val year = directory.facets(docChannel.id).single { it.key == "year" }
                    assertThat(year.options.any { it.label == "2025" }, "advertised year filter is available")
                    val results = repository.getCatalogMovies(source.id, 1, docChannel.id, "样片", mapOf("year" to "2025"))
                    val filtered = requests.last()
                    assertThat(filtered.getQueryParameter("t") == "6" && filtered.getQueryParameter("year") == "2025" &&
                        filtered.getQueryParameter("wd") == "样片" && filtered.getQueryParameter("pg") == "2",
                        "channel, year, search and paging reach the same request")
                    assertThat(results.items.single().title == "样片 6 2025" && results.hasMore, "filtered page keeps server pagination")
                    val other = repository.getCatalogMovies(source.id, 1, docChannel.id, "样片", mapOf("year" to "2024"))
                    assertThat(other.items.single().title == "样片 6 2024", "different filters have separate cached pages")
                    assertThat(repository.getSourceCatalog("local").channels.none { it.id == docChannel.id }, "source directory stays isolated")
                }
            }

            testCase("AppleCMS XML categories and fallback to verified detail IDs") { isolated ->
                CmsFixture { request ->
                    val action = request.getQueryParameter("ac")
                    val type = request.getQueryParameter("t")
                    when {
                        action == "list" -> "<rss><class><ty id=\"8\">综艺</ty><ty id=\"9\" pid=\"8\">旅行综艺</ty></class></rss>"
                        else -> """
                            <rss><list pagecount="1"><video><id>xml-9</id><tid>9</tid>
                            <name>旅行样片</name><type>旅行综艺</type><dl><dd flag="main">正片${'$'}http://127.0.0.1/trip.mp4</dd></dl>
                            </video></list></rss>
                        """.trimIndent().also { if (type != null) check(type == "9") }
                    }
                }.use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val source = importSite(repository, "XML catalog", fixture.api)
                    val directory = repository.getSourceCatalog(source.id)
                    val channel = directory.channels.single { it.label == "综艺" }
                    val subtype = directory.facets(channel.id).single { it.key == CatalogSubtype }
                    assertThat(subtype.defaultValue == "9", "XML category retains explicit parent and child IDs")
                    val page = repository.getCatalogMovies(source.id, 0, channel.id, "", emptyMap())
                    assertThat(page.items.single().title == "旅行样片" && !page.hasMore, "XML category opens the exact listing")
                }
                CmsFixture { request ->
                    if (request.getQueryParameter("ac") == "list") "directory unavailable"
                    else """{"pagecount":1,"list":[{"vod_id":"fixture","vod_name":"样片","type_id":"42",
                        "type_name":"短剧","vod_play_url":"正片${'$'}http://127.0.0.1/short.mp4"}]}"""
                }.use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val source = importSite(repository, "Detail-only catalog", fixture.api)
                    repository.getCatalogMovies(source.id, 0, CatalogRecommend, "", emptyMap())
                    val channel = repository.getSourceCatalog(source.id).channels.single { it.label == "短剧" }
                    assertThat(repository.getCatalogMovies(source.id, 0, channel.id, "", emptyMap()).items.size == 1,
                        "observed detail IDs stay browsable when list is unavailable")
                }
            }

            testCase("TVBox subscription negotiation resolves redirected relative sources") { isolated ->
                val normalAgent = "Mozilla/5.0 (Linux; Android 13) Moying/1.3"
                val liveAgent = "FixtureLive/1.0"
                val requests = ConcurrentLinkedQueue<Pair<Uri, String>>()
                CmsFixture(httpResponse = { request, userAgent ->
                    requests += request to userAgent
                    when (request.path) {
                        "/import/portal" -> if (userAgent.contains("TVBox", ignoreCase = true)) {
                            FixtureResponse(status = "302 Found", location = "../dir/actual.json")
                        } else {
                            FixtureResponse("<html><title>Subscription landing page</title></html>", "text/html")
                        }
                        "/dir/actual.json" -> FixtureResponse("""
                            /* Subscription header */
                            // This is a TVBox configuration.
                            {
                              "sites": [
                                {"name":"Relative CMS","type":1,"api":"./api/vod?auth=fixture"},
                                {"name":"Plugin fixture","type":3,"api":"csp_fixture","jar":"./fixture.jar"}
                              ],
                              "lives": [
                                {"name":"Relative Live","type":0,"url":"../live/channels.m3u","ua":"$liveAgent"}
                              ]
                            }
                        """.trimIndent())
                        "/dir/api/vod" -> FixtureResponse("""
                            {"pagecount":1,"list":[{
                              "vod_id":"fixture","vod_name":"Negotiated CMS sample","type_name":"电影",
                              "vod_play_url":"正片${'$'}http://127.0.0.1/sample.mp4"
                            }]}
                        """.trimIndent())
                        "/live/channels.m3u" -> FixtureResponse("""
                            #EXTM3U
                            #EXTINF:-1 group-title="新闻",Negotiated live sample
                            http://127.0.0.1/fixture.m3u8
                        """.trimIndent(), "application/x-mpegURL")
                        else -> FixtureResponse(status = "404 Not Found")
                    }
                }).use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val result = repository.importSources("${fixture.base}/import/portal")
                    assertThat(result.addedSources == 2 && result.addedMovies == 0 && result.skipped == 1,
                        "subscription adds the CMS and live directory and skips the plugin: ${result.message}")
                    val cms = repository.sources.single { it.name == "Relative CMS" }
                    val live = repository.sources.single { it.name == "Relative Live" }
                    assertThat(cms.api == "${fixture.base}/dir/api/vod?auth=fixture",
                        "CMS address uses the final response URL as its base")
                    assertThat(live.api == "${fixture.base}/live/channels.m3u" && live.format == "m3u",
                        "live address uses the final response URL and is stored as a playlist")
                    assertThat(live.playbackHeaders["User-Agent"] == liveAgent,
                        "TVBox live user agent is stored as a playback header")

                    val imports = requests.toList()
                    assertThat(imports.map { it.first.path } == listOf("/import/portal", "/import/portal", "/dir/actual.json"),
                        "subscription requests the landing page, retries with the TVBox user agent, and follows the redirect")
                    assertThat(imports[0].second == normalAgent && imports[1].second.contains("TVBox", ignoreCase = true) &&
                        imports[2].second == imports[1].second, "only the subscription retry and its redirect use the TVBox user agent")

                    repository.selectSource(live.id)
                    val restarted = TvBoxMovieRepository(isolated)
                    assertThat(restarted.sources.single { it.id == cms.id } == cms &&
                        restarted.sources.single { it.id == live.id } == live && restarted.selectedSourceId == live.id,
                        "both imported sources and the selected source survive a repository restart")

                    val cmsPage = restarted.getCatalogMovies(cms.id, 0, CatalogRecommend, "", emptyMap())
                    val (cmsRequest, cmsAgent) = requests.last()
                    assertThat(cmsPage.items.single().title == "Negotiated CMS sample" &&
                        cmsRequest.getQueryParameter("auth") == "fixture" && cmsAgent == normalAgent,
                        "the ordinary CMS request preserves the source query and uses the existing user agent")

                    val liveMovie = restarted.getCatalogMovies(live.id, 0, CatalogRecommend, "", emptyMap()).items.single()
                    assertThat(liveMovie.title == "Negotiated live sample" && requests.last().second == liveAgent,
                        "the live directory uses the declared user agent")
                    assertThat(restarted.getPlaybackRoutes(liveMovie.id).single().episodes.single().headers["User-Agent"] == liveAgent,
                        "the playlist user agent applies to media playback")
                }
            }

            testCase("commented local JSON import keeps existing M3U recognition") { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val api = "https://commented-subscription-test.invalid/vod"
                val json = """
                    // Local configuration header
                    {"sites":[{"name":"Local commented CMS","type":1,"api":"$api"}]}
                """.trimIndent()
                val imported = repository.importSources("\uFEFF\n$json")
                assertThat(imported.addedSources == 1 && imported.skipped == 0 && repository.sources.any { it.api == api },
                    "pasted JSON accepts a BOM and leading comments without altering the URL")

                val media = "https://commented-subscription-test.invalid/program.m3u8"
                val m3u = """
                    #EXTM3U
                    # Playlist comment
                    #EXTINF:-1,Local playlist sample
                    $media
                """.trimIndent()
                val playlist = repository.importSources(m3u)
                val movie = repository.getCatalogMovies("local", 0, CatalogRecommend, "Local playlist sample", emptyMap()).items.single()
                assertThat(playlist.addedMovies == 1 && playlist.skipped == 0 && repository.getEpisodes(movie.id).single().url == media,
                    "M3U headers and comments continue importing a playable local media entry")
            }

            testCase("playlist groups and local filters before pagination") { isolated ->
                val body = """
                    #EXTM3U
                    #EXTINF:-1 group-title="新闻",新闻样片
                    http://127.0.0.1/news.m3u8
                    #EXTINF:-1 group-title="体育",体育样片
                    http://127.0.0.1/sports.m3u8
                """.trimIndent()
                CmsFixture { body }.use { fixture ->
                    val repository = TvBoxMovieRepository(isolated)
                    val config = JSONObject().put("sites", JSONArray().put(JSONObject().put("name", "Playlist fixture")
                        .put("api", fixture.api).put("type", 1).put("format", "m3u")))
                    assertThat(repository.importSources(config.toString()).addedSources == 1, "M3U site imported")
                    val site = repository.sources.single { it.api == fixture.api }
                    val catalog = repository.getSourceCatalog(site.id)
                    assertThat(catalog.channels.map { it.label }.containsAll(listOf("新闻", "体育")), "M3U group names appear as channels")
                    val news = catalog.channels.single { it.label == "新闻" }
                    val newsPage = repository.getCatalogMovies(site.id, 0, news.id, "", emptyMap())
                    assertThat(newsPage.items.single().title == "新闻样片" && !newsPage.hasMore, "playlist channel only shows that group")

                    val sample = repository.getCatalogMovies("local", 0, CatalogRecommend, "", emptyMap()).items.first()
                    val movies = (0 until Catalog.PageSize + 4).map { index ->
                        sample.copy(id = "catalog-$index", title = "样片 $index", category = "电影", year = (1990 + index).toString(),
                            region = "中国大陆", genres = "科幻 / 冒险")
                    }
                    val ranked = localCatalogPage(movies, 0, "电影", "样片", mapOf("genre" to "科幻", "sort" to "year"))
                    assertThat(ranked.items.first() == movies.last() && ranked.hasMore, "local sorting precedes pagination")
                    val selected = localCatalogPage(movies, 0, "电影", "", mapOf("genre" to "冒险", "region" to "中国大陆", "year" to movies.last().year))
                    assertThat(selected.items == listOf(movies.last()) && !selected.hasMore, "combined facets can find items beyond the original first page")
                }
            }
        } catch (error: Throwable) {
            failures += "runner: ${Log.getStackTraceString(error)}"
        } finally {
            val result = Bundle().apply {
                putInt("assertions", assertions)
                putInt("completedCases", completedCases)
                putInt("failureCount", failures.size)
                putString("stream", "\nSource deletion regression: $assertions assertions, $completedCases cases, ${failures.size} failures.\n" + failures.joinToString("\n"))
            }
            finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    private fun subscriptionImportRegression(): Bundle {
        val direct = "https://xh.159e.cn/vip.json"
        listOf("https://xh.159e.cn/v1", "https://xh.159e.cn/v1/", "https://xh.159e.cn:443/v1").forEach {
            check(SourceImport.directSubscriptionAddress(it) == direct) { "小盒订阅路径应匹配直连配置" }
        }
        listOf(
            "http://xh.159e.cn/v1", "https://xh.159e.cn:8443/v1", "https://name@xh.159e.cn/v1",
            "https://xh.159e.cn/v1?q=1", "https://xh.159e.cn/v1#anchor", "https://xh.159e.cn/v10",
            "https://xh.159e.cn.invalid/v1", "https://xh.159e.cn/%76%31",
        ).forEach {
            check(SourceImport.directSubscriptionAddress(it) == null) { "无关地址应沿用普通订阅读取" }
        }
        val fixture = """
            // TVBox 配置
            /* 订阅说明 */
            {
              "spider":"https://plugins.invalid/spider.jar",
              "sites":[
                {"name":"Fixture CMS","type":1,"api":"https://cms.invalid/api","header":{"Referer":"https://cms.invalid/"}},
                {"name":"Fixture plugin","type":3,"api":"csp_XBPQ","ext":"./plugin.json"}
              ],
              "lives":[{"name":"Fixture live","type":0,"url":"./live.txt","ua":"FixtureUA"}]
            }
        """.trimIndent()
        IsolatedPreferencesContext(targetContext).use { isolated ->
            val repository = TvBoxMovieRepository(isolated)
            val imported = repository.importSources(fixture, direct)
            check(imported.addedSources == 2 && imported.skipped == 1) { "应导入 CMS 和直播并跳过插件" }
            val restart = TvBoxMovieRepository(isolated)
            val live = restart.sources.single { it.name == "Fixture live" }
            check(live.format == "m3u" && live.api == "https://xh.159e.cn/live.txt") { "应按直连配置解析相对直播地址" }
            check(live.playbackHeaders["User-Agent"] == "FixtureUA") { "应保留直播 User-Agent" }
            val cms = restart.sources.single { it.name == "Fixture CMS" }
            check(cms.playbackHeaders["Referer"] == "https://cms.invalid/") { "应保留 CMS Referer" }
            check(restart.sources.none { it.name == "Fixture plugin" }) { "不应保存需执行插件的片源" }
        }
        return Bundle().apply {
            putString("subscriptionFixture", "passed")
            val address = suiteArguments.getString("subscriptionAddress") ?: return@apply
            check(SourceImport.directSubscriptionAddress(address) != null) { "实际测试仅接受小盒订阅路径" }
            IsolatedPreferencesContext(targetContext).use { isolated ->
                val repository = TvBoxMovieRepository(isolated)
                val priorIds = repository.sources.map { it.id }.toSet()
                val result = repository.importSources(address)
                check(result.addedSources > 0) { "实际订阅未新增片源" }
                val sources = TvBoxMovieRepository(isolated).sources.filter { it.id !in priorIds }
                check(sources.size == result.addedSources && sources.any { it.format == "m3u" }) { "实际订阅应保存新增片源和直播" }
                val catalog = sources.filter { it.format == "cms" }.take(3).firstNotNullOfOrNull { source ->
                    runCatching { repository.getCatalogMovies(source.id, 0, CatalogRecommend, "", emptyMap()).items.size }
                        .getOrNull()?.takeIf { it > 0 }
                }
                putInt("subscriptionAddedSources", result.addedSources)
                putInt("subscriptionSkippedEntries", result.skipped)
                putInt("subscriptionFirstCatalogItems", checkNotNull(catalog) { "前 3 个新 CMS 均未返回影片" })
            }
        }
    }

    private fun introNextDetectionRegression(): Bundle {
        IsolatedPreferencesContext(targetContext).use { isolated ->
            fun rules(movieId: String = "series", routeId: String = "main") =
                IntroSkipPreferences(isolated, movieId, routeId)

            val preferences = rules()
            check(preferences.read() == IntroSkipRule())
            preferences.write(IntroSkipRule(endMillis = 60_000L, automatic = true))
            check(!rules().read().autoDetectNext)

            val enabled = IntroSkipRule(endMillis = 45_000L, autoDetectNext = true)
            preferences.write(enabled)
            check(rules().read() == enabled)
            check(rules(routeId = "backup").read() == IntroSkipRule())
            check(rules(movieId = "other").read() == IntroSkipRule())

            val bothEnabled = enabled.copy(automatic = true)
            preferences.write(bothEnabled)
            check(rules().read() == bothEnabled)
            val nextDisabled = bothEnabled.copy(autoDetectNext = false)
            preferences.write(nextDisabled)
            check(rules().read() == nextDisabled)

            val current = Episode(1, "Episode 1", "https://fixture.invalid/first.mp4")
            val next = Episode(2, "Episode 2", "https://fixture.invalid/second.mp4")
            val currentRange = DetectedIntro(10_000L, 22_000L)
            val nextRange = DetectedIntro(14_000L, 26_000L)
            preferences.saveDetection(current, currentRange)
            check(rules().readDetection(next) == null)
            preferences.saveDetection(next, nextRange)
            check(rules().readDetection(current) == currentRange)
            check(rules().readDetection(next) == nextRange)
            check(rules().readDetection(next.copy(url = "https://fixture.invalid/new.mp4")) == null)
            check(rules(routeId = "backup").readDetection(next) == null)
            check(rules().read() == nextDisabled)
        }

        fun checkWindow(position: Long, duration: Long, expected: Boolean) {
            check(isNearEpisodeEnd(position, duration) == expected) {
                "Unexpected next-episode trigger at position=$position duration=$duration, expected $expected"
            }
        }
        checkWindow(1_079_999L, 1_200_000L, false)
        checkWindow(1_080_000L, 1_200_000L, true)
        checkWindow(1_199_999L, 1_200_000L, true)
        checkWindow(1_200_000L, 1_200_000L, false)
        checkWindow(1_200_001L, 1_200_000L, false)
        checkWindow(29_999L, 40_000L, false)
        checkWindow(30_000L, 40_000L, true)
        checkWindow(0L, 40_000L, false)
        checkWindow(-1L, 40_000L, false)
        checkWindow(1L, 0L, false)
        checkWindow(1L, -1L, false)
        return Bundle().apply { putString("introNextDetection", "passed") }
    }

    private fun runSuite(action: () -> Bundle) {
        try { finish(Activity.RESULT_OK, action()) }
        catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putInt("failureCount", 1)
                putString("stream", Log.getStackTraceString(error))
            })
        }
    }

    private fun restoredRestartedSelection(repository: TvBoxMovieRepository, expectedId: String): Boolean =
        repository.selectedSourceId == expectedId && repository.sources.any { it.id == expectedId }

    private fun importSite(repository: TvBoxMovieRepository, name: String, api: String): TvBoxSource {
        val json = JSONObject().put("sites", JSONArray().put(JSONObject()
            .put("name", name).put("type", 1).put("api", api))).toString()
        val result = repository.importSources(json)
        assertThat(result.addedSources == 1, "site import succeeds: ${result.message}")
        return repository.sources.single { it.api == api }
    }

    private fun assertThat(condition: Boolean, message: String) {
        assertions++
        if (!condition) throw AssertionError(message)
    }

    private fun testCase(name: String, action: (IsolatedPreferencesContext) -> Unit) {
        try {
            IsolatedPreferencesContext(targetContext).use(action)
            completedCases++
            sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
        } catch (error: Throwable) {
            failures += "$name: ${Log.getStackTraceString(error)}"
            sendStatus(0, Bundle().apply { putString("stream", "FAIL: $name\n") })
        }
    }

    /** Redirect every repository preference file; cleanup touches only names created by this wrapper. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base), Closeable {
        private val prefix = "source_deletion_test_${UUID.randomUUID()}_"
        private val created = linkedSetOf<String>()

        override fun getApplicationContext(): Context = this

        @Synchronized
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            created += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        @Synchronized
        override fun close() {
            created.forEach { name ->
                check(name.startsWith(prefix))
                check(super.getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()) { "Could not clear test preferences" }
                super.deleteSharedPreferences(name)
            }
            created.clear()
        }
    }

    private data class FixtureResponse(
        val body: String = "",
        val contentType: String = "application/json",
        val status: String = "200 OK",
        val location: String? = null,
    )

    /** A bounded HTTP fixture; no media is downloaded or played. */
    private class CmsFixture(
        private val httpResponse: ((Uri, String) -> FixtureResponse)? = null,
        private val response: ((Uri) -> String)? = null,
    ) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val api = "$base/api.php/provide/vod"
        private val defaultResponse = FixtureResponse(JSONObject()
            .put("page", 1).put("pagecount", 1).put("limit", 12).put("total", 1)
            .put("class", JSONArray().put(JSONObject().put("type_id", "1").put("type_name", "电影")))
            .put("list", JSONArray().put(JSONObject()
                .put("vod_id", "fixture-1").put("vod_name", "Source deletion fixture")
                .put("vod_year", "2026").put("type_name", "电影")
                .put("vod_play_from", "main\$\$\$backup")
                .put("vod_play_url", "Episode 1\$http://127.0.0.1/first.m3u8#Episode 2\$http://127.0.0.1/second.m3u8\$\$\$Backup\$http://127.0.0.1/backup.mp4")))
            .toString())
        private val thread = Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestTarget = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        var headerBytes = 0
                        var userAgent = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            headerBytes += line.length
                            check(headerBytes <= 16 * 1024) { "Request headers exceed fixture limit" }
                            if (line.isEmpty()) break
                            if (line.substringBefore(':').equals("User-Agent", ignoreCase = true)) {
                                userAgent = line.substringAfter(':').trim()
                            }
                        }
                        val request = Uri.parse("http://fixture$requestTarget")
                        val reply = httpResponse?.invoke(request, userAgent)
                            ?: response?.invoke(request)?.let { FixtureResponse(it) }
                            ?: defaultResponse
                        val payload = reply.body.toByteArray(Charsets.UTF_8)
                        val location = reply.location?.let { "Location: $it\r\n" }.orEmpty()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 ${reply.status}\r\nContent-Type: ${reply.contentType}; charset=UTF-8\r\n" +
                                "${location}Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(payload)
                            flush()
                        }
                    }
                } catch (closed: SocketException) {
                    if (!server.isClosed) throw closed
                }
            }
        }, "SourceDeletionCmsFixture").apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            thread.join(2_000)
            check(!thread.isAlive) { "CMS fixture did not stop" }
        }
    }

    /** First response waits on a latch while later requests are served on separate bounded workers. */
    private class DelayedCmsFixture : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val api = "http://127.0.0.1:${server.localPort}/api.php/provide/vod"
        val firstRequestReceived = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val requestCount = AtomicInteger()
        private val workers = ConcurrentLinkedQueue<Thread>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val acceptThread = Thread({
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    val request = requestCount.incrementAndGet()
                    workers += Thread({
                        try {
                            socket.use {
                                it.soTimeout = 2_000
                                val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                                var headerBytes = 0
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    headerBytes += line.length
                                    check(headerBytes <= 16 * 1024) { "Request headers exceed fixture limit" }
                                    if (line.isEmpty()) break
                                }
                                if (request == 1) {
                                    firstRequestReceived.countDown()
                                    check(releaseFirstResponse.await(5, TimeUnit.SECONDS)) { "Old response was not released in time" }
                                }
                                val body = response(old = request == 1)
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                    write(body)
                                    flush()
                                }
                            }
                        } catch (error: Throwable) {
                            errors += error
                        }
                    }, "SourceDeletionDelayedResponse$request").apply { isDaemon = true; start() }
                } catch (error: Throwable) {
                    if (!server.isClosed) errors += error
                }
            }
        }, "SourceDeletionDelayedAccept").apply { isDaemon = true; start() }

        private fun response(old: Boolean): ByteArray {
            val generation = if (old) "old" else "new"
            val rows = JSONArray().put(JSONObject()
                .put("vod_id", "shared").put("vod_name", if (old) "Old generation" else "New generation")
                .put("type_name", "电影").put("vod_play_from", "main")
                .put("vod_play_url", "Episode\$http://127.0.0.1/$generation.m3u8"))
            if (old) rows.put(JSONObject()
                .put("vod_id", "old-only").put("vod_name", "Old-only movie")
                .put("type_name", "电影").put("vod_play_from", "main")
                .put("vod_play_url", "Episode\$http://127.0.0.1/old-only.m3u8"))
            return JSONObject().put("page", 1).put("pagecount", 1).put("limit", 12).put("total", rows.length())
                .put("class", JSONArray().put(JSONObject().put("type_id", if (old) "11" else "22").put("type_name", "电影")))
                .put("list", rows).toString().toByteArray(Charsets.UTF_8)
        }

        override fun close() {
            releaseFirstResponse.countDown()
            server.close()
            acceptThread.join(2_000)
            workers.forEach { it.join(3_000) }
            check(!acceptThread.isAlive && workers.none { it.isAlive }) { "Delayed CMS fixture did not stop" }
            check(errors.isEmpty()) { "Delayed CMS fixture failed: ${errors.joinToString { Log.getStackTraceString(it) }}" }
        }
    }
}
