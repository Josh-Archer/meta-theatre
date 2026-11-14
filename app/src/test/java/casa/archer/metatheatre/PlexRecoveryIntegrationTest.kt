package casa.archer.metatheatre

import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlexRecoveryIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        context.getSharedPreferences("theatre", 0).edit().clear().commit()
    }
    @After fun finish() { Dispatchers.resetMain() }

    @Test fun metadataChecksFileAvailabilityBeforeOpeningThePlayerAndAllowsUnreportedAvailability() {
        val http = MockWebServer().apply { start() }
        try {
            val client = PlexClient(context)
            val server = PlexServer("Test server", http.url("/").toString(), "synthetic-token")
            val item = PlexItem("42", "Movie", "", "movie")
            for (availability in listOf("\"exists\":false,", "\"accessible\":false,", "\"exists\":true,\"accessible\":true,", "")) {
                http.enqueue(MockResponse().setBody("""{"MediaContainer":{"Metadata":[{"Media":[{"width":3840,"height":2160,"Part":[{${availability}"key":"/library/parts/1/file.mkv"}]}]}]}}"""))
                if (availability.contains("false")) {
                    val failure = assertThrows(PlexException::class.java) { client.playable(server, item) }
                    assertEquals(PlexFailure.MISSING, failure.failure)
                    assertTrue(failure.message!!.contains("Test server"))
                } else {
                    assertEquals(3840, client.playable(server, item).sourceWidth)
                }
                assertEquals("1", http.takeRequest().requestUrl!!.queryParameter("checkFiles"))
            }
        } finally { http.shutdown() }
    }

    @Test fun missingSavedMovieStaysInTheLibraryAndCannotRestoreIntoTheSameFailureLoop() {
        val plex = mock(PlexClient::class.java)
        val server = PlexServer("Test server", "https://example.invalid", "synthetic", "server")
        val otherServer = server.copy(id = "other", name = "Other server")
        val key = MovieKey(server.id, "42")
        val state = TheatreState(context)
        state.history.begin(Bookmark(key, "Movie", "", "movie", "", 42_000, 120_000, 1))
        state.saveHistory()
        `when`(plex.token).thenReturn("synthetic")
        `when`(plex.servers()).thenReturn(listOf(server, otherServer))
        `when`(plex.recentlyViewed(server)).thenReturn(emptyList())
        val savedItem = PlexItem("42", "Movie", "", "movie", viewOffsetMs = 42_000, durationMs = 120_000)
        doAnswer { throw PlexException("File unavailable on Test server. Choose another library.", PlexFailure.MISSING) }
            .`when`(plex).playable(server, savedItem)
        val screen = ScreenPlayer(context)
        val model = TheatreModel(context, screen, plex, Dispatchers.Unconfined)
        try {
            model.start()
            assertTrue(model.libraryOpen.value)
            assertEquals(listOf(server, otherServer), (model.browse.value as Browse.Servers).servers)
            assertEquals("", screen.transport.value.title)
            assertTrue(model.error.value!!.contains("File unavailable"))
            assertFalse(model.plexReconnectAvailable.value)
            assertFalse(model.loading.value)
            assertNull(TheatreState(context).history.active)
            assertEquals(42_000L, TheatreState(context).history.find(key)!!.positionMs)
            model.openLibraries()
            verify(plex, times(1)).playable(server, savedItem)
        } finally { model.release(); screen.release() }
    }

    @Test fun fileDisappearingDuringPlaybackReturnsToLibraryAndPreservesProgress() {
        val plex = mock(PlexClient::class.java)
        val native = mock(ExoPlayer::class.java)
        lateinit var listener: Player.Listener
        doAnswer { listener = it.getArgument(0); null }.`when`(native).addListener(any(Player.Listener::class.java))
        val screen = ScreenPlayer(context) { _, _, _ -> native }
        val model = TheatreModel(context, screen, plex, Dispatchers.Unconfined)
        val server = PlexServer("Test", "https://example.invalid", "synthetic", "server")
        val section = PlexSection("1", "Movies", "movie")
        val item = PlexItem("42", "Movie", "", "movie", viewOffsetMs = 42_000, durationMs = 120_000)
        `when`(plex.library(server, section)).thenReturn(listOf(item))
        `when`(plex.playable(server, item)).thenReturn(Playable("Movie", "https://example.invalid/movie.mp4", "https://example.invalid/hls", emptyMap()))
        try {
            screen.attach(mock(Surface::class.java))
            model.openSection(server, section)
            (model.browse.value as Browse.Items).onOpen(item)
            assertFalse(model.libraryOpen.value)
            val failure = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                404, "Not found", null, emptyMap(), DataSpec(android.net.Uri.parse("https://example.invalid/video")), byteArrayOf())
            listener.onPlayerError(PlaybackException("Missing", failure, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            assertTrue(model.libraryOpen.value)
            assertTrue(model.browse.value is Browse.Home)
            assertEquals("", screen.transport.value.title)
            assertTrue(model.error.value!!.contains("File missing"))
            assertNull(TheatreState(context).history.active)
            assertEquals(42_000L, TheatreState(context).history.recent().single().positionMs)
            verify(native).stop()
            verify(native, times(1)).prepare()
        } finally { model.release(); screen.release() }
    }

    @Test fun actualHttpFailuresDistinguishAccountRelinkFromServerAccessAndNeverExposeResponseBodies() {
        val http = MockWebServer().apply { start() }
        try {
            val client = PlexClient(context, http.url("/").toString().trimEnd('/')).apply { token = "synthetic-token" }
            val id = client.clientId
            for ((code, expected) in listOf(503 to PlexFailure.SERVER, 401 to PlexFailure.ACCOUNT_AUTH)) {
                http.enqueue(MockResponse().setResponseCode(code).setBody("private@example.invalid X-Plex-Token=synthetic-secret"))
                val error = assertThrows(PlexException::class.java) { client.servers() }
                assertEquals(expected, error.failure)
                assertFalse(error.message!!.contains("private@"))
                assertFalse(error.message!!.contains("synthetic-secret"))
                assertEquals("synthetic-token", PlexClient(context).token)
                assertEquals(id, PlexClient(context).clientId)
            }
            val serverClient = PlexClient(context)
            http.enqueue(MockResponse().setResponseCode(403))
            val error = assertThrows(PlexException::class.java) {
                serverClient.sections(PlexServer("Test", http.url("/").toString(), "synthetic-server-token"))
            }
            assertEquals(PlexFailure.SERVER_AUTH, error.failure)
        } finally { http.shutdown() }
    }

    @Test fun aRealUnauthorizedVideoResponseOffersRefreshWithoutDowngradingOrRetryLooping() {
        val http = MockWebServer().apply { start() }
        val native = mock(ExoPlayer::class.java)
        lateinit var listener: Player.Listener
        doAnswer { listener = it.getArgument(0); null }.`when`(native).addListener(any(Player.Listener::class.java))
        val screen = ScreenPlayer(context) { _, _, _ -> native }
        try {
            screen.attach(mock(Surface::class.java))
            screen.play(Playable("Test", "https://example.invalid/movie.mp4", "https://example.invalid/stream.m3u8", emptyMap()), 42_000)
            http.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-secret"))
            val data = DefaultHttpDataSource.Factory().createDataSource()
            val failure = try {
                data.open(DataSpec(android.net.Uri.parse(http.url("/video").toString())))
                error("Expected HTTP 401")
            } catch (problem: java.io.IOException) { problem } finally { data.close() }
            listener.onPlayerError(PlaybackException("private URL", failure, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            assertTrue(screen.transport.value.canRetry)
            assertFalse(screen.transport.value.reconnecting)
            assertTrue(screen.transport.value.detail.contains("denied"))
            assertFalse(screen.transport.value.detail.contains("synthetic-secret"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            verify(native, times(1)).prepare()
        } finally { screen.release(); http.shutdown() }
    }

    @Test fun reconnectRefreshesServerUrlsThroughTheModelWithoutLosingTheLocalBookmarkOrPausedIntent() {
        val plex = mock(PlexClient::class.java)
        val native = mock(ExoPlayer::class.java)
        var position = 0L
        var intent = true
        doAnswer { position }.`when`(native).currentPosition
        doAnswer { intent }.`when`(native).playWhenReady
        doAnswer { intent = false; null }.`when`(native).pause()
        doAnswer { intent = it.getArgument(0); null }.`when`(native).setPlayWhenReady(anyBoolean())
        doAnswer { position = it.getArgument(1); null }.`when`(native).setMediaItem(any(MediaItem::class.java), anyLong())
        `when`(native.playbackState).thenReturn(Player.STATE_READY)
        val screen = ScreenPlayer(context) { _, _, _ -> native }
        val model = TheatreModel(context, screen, plex, Dispatchers.Unconfined)
        val server = PlexServer("Test", "https://old.example.invalid", "old-synthetic", "same-server")
        val refreshed = server.copy(uri = "https://new.example.invalid", token = "new-synthetic")
        val section = PlexSection("1", "Test", "movie")
        val item = PlexItem("42", "Test movie", "", "movie")
        fun playable(server: PlexServer) = Playable(item.title, server.uri + "/movie.mp4", server.uri + "/stream.m3u8", emptyMap())
        `when`(plex.library(server, section)).thenReturn(listOf(item))
        `when`(plex.playable(server, item)).thenReturn(playable(server))
        `when`(plex.servers()).thenReturn(listOf(refreshed))
        `when`(plex.playable(refreshed, item)).thenReturn(playable(refreshed))
        try {
            screen.attach(mock(Surface::class.java))
            model.openSection(server, section)
            (model.browse.value as Browse.Items).onOpen(item)
            position = 42_000
            screen.publish()
            screen.pause()
            model.reconnectPlex()
            assertEquals(42_000L, TheatreState(context).history.recent().single().positionMs)
            val items = org.mockito.ArgumentCaptor.forClass(MediaItem::class.java)
            val positions = org.mockito.ArgumentCaptor.forClass(Long::class.javaObjectType)
            verify(native, times(2)).setMediaItem(items.capture(), positions.capture())
            assertTrue(items.allValues.last().localConfiguration!!.uri.toString().contains("new.example.invalid"))
            assertEquals(42_000L, positions.allValues.last().toLong())
            assertFalse(screen.transport.value.playWhenReady)
            assertEquals("Original", screen.transport.value.playbackMode)
            doAnswer { throw PlexException("File unavailable", PlexFailure.MISSING) }
                .`when`(plex).playable(refreshed, item)
            model.reconnectPlex()
            assertTrue(model.libraryOpen.value)
            assertEquals("File unavailable", model.error.value)
            assertNull(TheatreState(context).history.active)
            assertEquals(42_000L, TheatreState(context).history.recent().single().positionMs)
        } finally { model.release(); screen.release() }
    }

    @Test fun missingMediaKeepsItsExplanationAndExitVisibleWithoutRetryingOrLosingTheBookmark() {
        val http = MockWebServer().apply { start() }
        val native = mock(ExoPlayer::class.java)
        lateinit var listener: Player.Listener
        doAnswer { listener = it.getArgument(0); null }.`when`(native).addListener(any(Player.Listener::class.java))
        val screen = ScreenPlayer(context) { _, _, _ -> native }
        val controls = PlayerAutoHide().apply { setWatching(true, 0); tick(5_000) }
        try {
            screen.attach(mock(Surface::class.java))
            screen.play(Playable("Test", "https://example.invalid/movie.mp4", "https://example.invalid/stream.m3u8", emptyMap()), 42_000)
            http.enqueue(MockResponse().setResponseCode(404).setBody("private-server-path"))
            val data = DefaultHttpDataSource.Factory().createDataSource()
            val failure = try {
                data.open(DataSpec(android.net.Uri.parse(http.url("/video").toString())))
                error("Expected HTTP 404")
            } catch (problem: java.io.IOException) { problem } finally { data.close() }
            listener.onPlayerError(PlaybackException("private URL", failure, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            val state = screen.transport.value
            assertEquals("File missing on server. Try another library.", state.detail)
            assertEquals(42_000L, state.positionMs)
            assertEquals("Original", state.playbackMode)
            assertTrue(state.canRetry)
            assertFalse(state.reconnecting)
            controls.tick(60_000, state.canRetry || state.reconnecting)
            assertTrue(controls.visible)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            verify(native, times(1)).prepare()
            screen.close()
            controls.setWatching(false, 61_000)
            controls.tick(70_000, screen.transport.value.canRetry || screen.transport.value.reconnecting)
            assertFalse(controls.visible)
        } finally { screen.release(); http.shutdown() }
    }

    @Test fun accountFailureKeepsHistoryAndTokenAndOffersRelinkWhileOfflineFailureOffersReconnect() {
        val plex = mock(PlexClient::class.java)
        `when`(plex.token).thenReturn("synthetic-token")
        val screen = mock(ScreenPlayer::class.java)
        `when`(screen.transport).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(Transport()))
        val state = TheatreState(context)
        state.history.begin(Bookmark(MovieKey("server", "42"), "Test", "", "movie", "", 42_000, 100_000, 1))
        state.saveHistory()
        val model = TheatreModel(context, screen, plex, Dispatchers.Unconfined)
        try {
            doAnswer { throw PlexException("Connection unavailable", PlexFailure.CONNECTION) }.`when`(plex).servers()
            model.start()
            assertTrue(model.browse.value is Browse.Home)
            assertTrue(model.plexReconnectAvailable.value)
            assertFalse(model.plexLinkRequired.value)
            doAnswer { throw PlexException("Link again", PlexFailure.ACCOUNT_AUTH) }.`when`(plex).servers()
            model.reconnectPlex()
            assertTrue(model.plexLinkRequired.value)
            assertEquals(42_000L, TheatreState(context).history.recent().single().positionMs)
            verify(plex, never()).clearToken()
        } finally { model.release() }
    }
}
