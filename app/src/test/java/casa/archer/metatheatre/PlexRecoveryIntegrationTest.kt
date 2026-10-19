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
        } finally { model.release(); screen.release() }
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
