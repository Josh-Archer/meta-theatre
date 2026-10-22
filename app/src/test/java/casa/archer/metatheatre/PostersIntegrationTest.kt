package casa.archer.metatheatre

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostersIntegrationTest {
    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    @Test fun simultaneousCardsShareOneDownloadAndDecodedImage() = runBlocking {
        val server = MockWebServer()
        val bytes = image()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setBody(Buffer().write(bytes)).setBodyDelay(200, TimeUnit.MILLISECONDS)
        }
        server.start()
        try {
            val url = server.url("/poster").toString()
            val results = List(12) { async { Posters.load(url) } }.awaitAll()
            val first = checkNotNull(results.first())
            assertEquals(40, first.width)
            assertEquals(60, first.height)
            results.forEach { assertSame(first, it) }
            assertSame(first, Posters.load(url))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun distinctPostersKeepThreeDownloadSlotsAndWaitingCancellationDoesNotCancelTheOwner() = runBlocking {
        val server = MockWebServer()
        val release = CountDownLatch(1)
        val bytes = image()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody(Buffer().write(bytes))
            }
        }
        server.start()
        try {
            val urls = List(6) { server.url("/poster/$it").toString() }
            val results = urls.map { async { Posters.load(it) } }
            withContext(Dispatchers.IO) {
                repeat(3) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
                assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { Posters.load(urls.first()) }
            waiter.cancelAndJoin()
            release.countDown()
            results.awaitAll().forEach { assertNotNull(it) }
            assertEquals(6, server.requestCount)
            assertNotNull(Posters.load(urls.first()))
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun failedDownloadsCanBeRetried() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(image())))
        server.start()
        try {
            val url = server.url("/retry").toString()
            assertNull(Posters.load(url))
            assertNotNull(Posters.load(url))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
