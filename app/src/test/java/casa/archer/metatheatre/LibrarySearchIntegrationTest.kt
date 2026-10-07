package casa.archer.metatheatre

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySearchIntegrationTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var model: TheatreModel
    private lateinit var player: ScreenPlayer
    private lateinit var plexServer: PlexServer

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.dispatcher = libraryDispatcher()
        server.start()
        plexServer = PlexServer("Test Plex", server.url("/").toString().trimEnd('/'), "fake-token")
        val context = RuntimeEnvironment.getApplication() as Context
        player = ScreenPlayer(context)
        model = TheatreModel(context, player, PlexClient(context), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        model.release()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun caseInsensitiveSearchAndFiltersSelectTheMatchingPlexItemAndClearCleanly() = runTest(mainDispatcher) {
        model.openServer(plexServer)
        awaitBrowseFinished()
        assertTrue(model.browse.value is Browse.Sections)

        model.openSection(plexServer, PlexSection("1", "Films", "movie"))
        awaitBrowseFinished()
        val loaded = model.browse.value as Browse.Items
        assertEquals(3, loaded.items.size)

        assertTrue(model.librarySearchPress(0.20f, PosterGrid.HEADER_END + 0.01f))
        "moon".forEach(::pressKeyboardCharacter)
        pressKeyboardKey(LibraryKeyboardKey.Done)
        assertEquals("moon", model.librarySearchQuery.value)

        model.setLibrarySearchQuery("mOoN")
        assertEquals(listOf("Moonlight", "Moon Series"), (model.browse.value as Browse.Items).items.map { it.title })
        model.setLibrarySearchFilter(LibrarySearchFilter.TV)
        assertEquals("Moon Series", (model.browse.value as Browse.Items).items.single().title)
        model.setLibrarySearchFilter(LibrarySearchFilter.MOVIES)
        val matchingMovie = (model.browse.value as Browse.Items).items.single()
        assertEquals("movie-2", matchingMovie.ratingKey)

        model.setLibrarySearchFilter(LibrarySearchFilter.CONTINUE)
        assertEquals("movie-2", (model.browse.value as Browse.Items).items.single().ratingKey)
        model.setLibrarySearchFilter(LibrarySearchFilter.MOVIES)

        (model.browse.value as Browse.Items).onOpen(matchingMovie)
        awaitBrowseFinished()
        assertEquals("Moonlight", player.transport.value.title)
        val requests = (0 until server.requestCount).map { server.takeRequest() }
        assertTrue(requests.any { it.path.orEmpty().startsWith("/library/metadata/movie-2") })

        model.setLibrarySearchQuery("no such title")
        assertTrue((model.browse.value as Browse.Items).items.isEmpty())
        model.clearLibrarySearch()
        assertEquals(3, (model.browse.value as Browse.Items).items.size)
        assertEquals("", model.librarySearchQuery.value)
        assertEquals(LibrarySearchFilter.ALL, model.librarySearchFilter.value)

        model.setPage(2)
        model.setLibrarySearchQuery("Moon")
        model.back()
        assertTrue(model.browse.value is Browse.Sections)
        assertEquals("", model.librarySearchQuery.value)
        assertEquals(LibrarySearchFilter.ALL, model.librarySearchFilter.value)
        assertEquals(0, model.page.value)
        assertFalse(model.librarySearchKeyboardOpen.value)
    }

    @Test
    fun everyRenderedOnPanelKeyboardKeyHasTheSameRayHitGeometry() {
        PosterGrid.LIBRARY_KEYBOARD_ROWS.forEachIndexed { rowIndex, row ->
            row.keys.forEachIndexed { keyIndex, key ->
                val x = row.start + row.width * (keyIndex + 0.5f) / row.keys.size
                val y = PosterGrid.KEYBOARD_START + PosterGrid.KEYBOARD_ROW_HEIGHT * (rowIndex + 0.5f)
                assertEquals(key, PosterGrid.libraryKeyboardKeyAt(x, y))
            }
        }
        assertEquals(LibraryKeyboardKey.Character('2'), PosterGrid.libraryKeyboardKeyAt(0.15f, PosterGrid.KEYBOARD_START + 0.03f))
    }

    private fun pressKeyboardCharacter(character: Char) {
        val rowIndex = PosterGrid.LIBRARY_KEYBOARD_ROWS.indexOfFirst { row ->
            row.keys.any { it == LibraryKeyboardKey.Character(character) }
        }
        pressKeyboardKey(LibraryKeyboardKey.Character(character), rowIndex)
    }

    private suspend fun TestScope.awaitBrowseFinished() {
        runCurrent()
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { model.loading.first { isLoading -> !isLoading } }
        }
        runCurrent()
    }

    private fun pressKeyboardKey(key: LibraryKeyboardKey, rowIndex: Int = PosterGrid.LIBRARY_KEYBOARD_ROWS.indexOfFirst { key in it.keys }) {
        val row = PosterGrid.LIBRARY_KEYBOARD_ROWS[rowIndex]
        val keyIndex = row.keys.indexOf(key)
        val x = row.start + row.width * (keyIndex + 0.5f) / row.keys.size
        val y = PosterGrid.KEYBOARD_START + PosterGrid.KEYBOARD_ROW_HEIGHT * (rowIndex + 0.5f)
        assertTrue(model.librarySearchPress(x, y))
    }

    private fun libraryDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.requestUrl?.encodedPath) {
                "/library/sections" -> MockResponse().setBody(JSONObject().put("MediaContainer", JSONObject()
                    .put("Directory", JSONArray().put(JSONObject().put("key", "1").put("title", "Films").put("type", "movie"))))
                    .toString())
                "/library/sections/1/all" -> MockResponse().setBody(libraryPage())
                "/library/metadata/movie-2" -> MockResponse().setBody(playableMetadata("movie-2", "Moonlight"))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun libraryPage(): String = JSONObject().put("MediaContainer", JSONObject()
        .put("totalSize", 3)
        .put("Metadata", JSONArray()
            .put(item("movie-1", "Quiet Night", "movie", 30))
            .put(item("movie-2", "Moonlight", "movie", 20).put("viewOffset", 12_000))
            .put(item("show-1", "Moon Series", "show", 10))))
        .toString()

    private fun item(ratingKey: String, title: String, type: String, addedAt: Int): JSONObject = JSONObject()
        .put("ratingKey", ratingKey)
        .put("title", title)
        .put("type", type)
        .put("addedAt", addedAt)

    private fun playableMetadata(ratingKey: String, title: String): String = JSONObject()
        .put("MediaContainer", JSONObject().put("Metadata", JSONArray().put(JSONObject()
            .put("ratingKey", ratingKey)
            .put("title", title)
            .put("Media", JSONArray().put(JSONObject()
                .put("width", 1920)
                .put("height", 1080)
                .put("bitrate", 4_000_000)
                .put("Part", JSONArray().put(JSONObject().put("key", "/video/$ratingKey.mp4"))))))))
        .toString()
}
