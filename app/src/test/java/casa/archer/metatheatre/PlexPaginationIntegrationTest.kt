package casa.archer.metatheatre

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlexPaginationIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlexClient
    private lateinit var plexServer: PlexServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PlexClient(RuntimeEnvironment.getApplication())
        plexServer = PlexServer("Test", server.url("/").toString().trimEnd('/'), "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun libraryLoadsMoreThanOneThousandItemsAndPreservesSortedOrder() {
        val total = 1_205
        server.dispatcher = pageDispatcher(total = total, includeTotal = false)

        val items = client.library(plexServer, movieSection())

        assertEquals(total, items.size)
        assertEquals((0 until total).map(Int::toString), items.map { it.ratingKey })
        val requests = (0 until 13).map { server.takeRequest() }
        assertEquals("test-token", requests.first().getHeader("X-Plex-Token"))
        assertEquals((0..12).map { (it * 100).toString() }, requests.map {
            it.requestUrl?.queryParameter("X-Plex-Container-Start")
        })
    }

    @Test
    fun libraryLoadsAllItemsWhenPlexDeclaresMoreThanOneThousand() {
        server.dispatcher = pageDispatcher(total = 1_205)

        val items = client.library(plexServer, movieSection())

        assertEquals(1_205, items.size)
        assertEquals("1204", items.last().ratingKey)
        assertEquals(13, server.requestCount)
    }

    @Test
    fun prematureEmptyPageReportsAnIncompleteLibrary() {
        server.enqueue(MockResponse().setBody(pageBody(0, 205, includeTotal = true)))
        server.enqueue(MockResponse().setBody(JSONObject().put("MediaContainer", JSONObject()
            .put("totalSize", 205).put("Metadata", JSONArray())).toString()))

        val error = org.junit.Assert.assertThrows(PlexException::class.java) {
            client.library(plexServer, movieSection())
        }

        assertTrue(error.message.orEmpty().contains("before the reported total"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun libraryStopsAfterShortFinalPageWhenTotalSizeIsMissing() {
        server.dispatcher = pageDispatcher(total = 205, includeTotal = false)

        val items = client.library(plexServer, movieSection())

        assertEquals(205, items.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun libraryAcceptsEmptyFinalPageWhenTotalSizeIsMissing() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.requestUrl?.queryParameter("X-Plex-Container-Start")?.toIntOrNull() ?: 0
                return MockResponse().setBody(pageBody(start, 200, includeTotal = false))
            }
        }

        val items = client.library(plexServer, movieSection())

        assertEquals(200, items.size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun repeatedPageThrowsInsteadOfReturningPartialLibrary() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setBody(pageBody(start = 0, total = 300, includeTotal = true))
        }

        val error = org.junit.Assert.assertThrows(PlexException::class.java) {
            client.library(plexServer, movieSection())
        }

        assertTrue(error.message.orEmpty().contains("repeated a library page"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun childrenUsesSamePagingAndPreservesEpisodeOrder() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.requestUrl?.queryParameter("X-Plex-Container-Start")?.toIntOrNull() ?: 0
                val body = JSONObject().put("MediaContainer", JSONObject()
                    .put("totalSize", 205)
                    .put("Metadata", metadata(start, 205, childItems = true)))
                return MockResponse().setBody(body.toString())
            }
        }

        val items = client.children(plexServer, "show-key")

        assertEquals(205, items.size)
        assertEquals((0 until 205).map(Int::toString), items.map { it.ratingKey })
        assertEquals(listOf("0", "100", "200"), List(3) {
            server.takeRequest().requestUrl?.queryParameter("X-Plex-Container-Start")
        })
    }

    @Test
    fun malformedPageThrowsClearPlexException() {
        server.enqueue(MockResponse().setBody("not json"))

        val error = org.junit.Assert.assertThrows(PlexException::class.java) {
            client.library(plexServer, movieSection())
        }

        assertTrue(error.message.orEmpty().contains("malformed library page"))
    }

    private fun pageDispatcher(total: Int, includeTotal: Boolean = true): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val start = request.requestUrl?.queryParameter("X-Plex-Container-Start")?.toIntOrNull() ?: 0
            return MockResponse().setBody(pageBody(start, total, includeTotal))
        }
    }

    private fun pageBody(start: Int, total: Int, includeTotal: Boolean): String {
        val container = JSONObject()
        if (includeTotal) container.put("totalSize", total)
        container.put("Metadata", metadata(start, total))
        return JSONObject().put("MediaContainer", container).toString()
    }

    private fun metadata(start: Int, total: Int, childItems: Boolean = false): JSONArray {
        val end = minOf(start + 100, total)
        return JSONArray().apply {
            for (index in start until end) {
                put(JSONObject()
                    .put("ratingKey", index.toString())
                    .put("title", "Title $index")
                    .put("type", if (childItems) "episode" else "movie")
                    .put("addedAt", total - index)
                    .put("parentIndex", 1)
                    .put("index", index + 1))
            }
        }
    }

    private fun movieSection() = PlexSection(key = "1", title = "Movies", type = "movie")
}
