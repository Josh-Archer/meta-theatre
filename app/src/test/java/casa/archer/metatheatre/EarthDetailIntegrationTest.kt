package casa.archer.metatheatre

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class EarthDetailIntegrationTest {
    @Test fun adaptiveRequestsImproveGroundResolutionAndJoinTheDateLineWithoutStretching() {
        for (zoom in listOf(2f, 20f, 100f)) for (place in listOf(WeatherPlace(33.58, -85.08),
            WeatherPlace(0.0,179.9),WeatherPlace(0.0,-179.9),WeatherPlace(90.0,0.0),WeatherPlace(-90.0,0.0))) {
            val region = EarthDetailRegion.around(place, zoom)
            assertTrue(region.south >= -90 && region.north <= 90)
            for (size in listOf(512,4096)) {
                val requests = region.requests(size)
                assertEquals(size, requests.sumOf { it.width })
                assertEquals(0, requests.first().x)
                requests.forEach { assertTrue(it.west >= -180 && it.east <= 180 && it.width > 0) }
                assertEquals(region.span, requests.sumOf { it.east-it.west }, .00001)
            }
        }
        assertEquals(1.0,EarthDetailRegion.around(WeatherPlace(45.83,6.86),100f).span,0.0)
    }
    @Test fun previewArrivesBeforeFullDetailAndBackgroundViewsNeverFetch() {
        val scope = TestScope()
        val requested = mutableListOf<Int>(); val uploaded = mutableListOf<Int>()
        val source = object : EarthImageSource {
            override suspend fun image(region: EarthDetailRegion,size: Int): Bitmap {
                requested.add(size)
                if (size == 4096) delay(5000)
                return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            }
        }
        val session = EarthDetailSession(RuntimeEnvironment.getApplication(),scope,render={_,bitmap ->
            uploaded.add(bitmap.width);assertEquals(Color.GREEN,bitmap.getPixel(0,0))
        },publish={},source=source,ioDispatcher=StandardTestDispatcher(scope.testScheduler))
        try {
            session.setActive(true);session.view(WeatherPlace(45.83,6.86),100f);session.setActive(false)
            scope.advanceTimeBy(2000);scope.runCurrent();assertTrue(requested.isEmpty())
            session.setActive(true);session.view(WeatherPlace(45.83,6.86),100f)
            scope.advanceTimeBy(300);scope.runCurrent()
            assertEquals(listOf(512,2048),uploaded)
            assertEquals(listOf(512,2048,4096),requested)
            scope.advanceTimeBy(5000);scope.runCurrent();assertEquals(listOf(512,2048,4096),uploaded)
            session.view(WeatherPlace(45.84,6.86),100f);scope.advanceTimeBy(2000);scope.runCurrent()
            assertEquals(3,requested.size)
        } finally { scope.cancel() }
    }
    private fun png(width:Int,height:Int,color:Int):Buffer {
        val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out=java.io.ByteArrayOutputStream()
        try { image.compress(Bitmap.CompressFormat.PNG,100,out) } finally { image.recycle() }
        return Buffer().write(out.toByteArray())
    }
    @Test fun dateLineHalvesAreCachedAsOriginalBytesAndServiceErrorsAreRejected()= runBlocking {
        val context=RuntimeEnvironment.getApplication()
        java.io.File(context.cacheDir,"earth-stream-v2").deleteRecursively()
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(png(256,512,Color.RED)))
            server.enqueue(MockResponse().setBody(png(256,512,Color.BLUE)))
            val source=EarthDetailSource(context,server.url("/wms").toString())
            val region=EarthDetailRegion(0.0,-180.0)
            repeat(2) {
                val image=source.image(region,512)
                try { assertEquals(Color.RED,image.getPixel(0,10));assertEquals(Color.BLUE,image.getPixel(511,10)) }
                finally { image.recycle() }
            }
            assertEquals(2,server.requestCount)
            assertEquals("171.0,-9.0,180.0,9.0",server.takeRequest().requestUrl!!.queryParameter("BBOX"))
            assertEquals("-180.0,-9.0,-171.0,9.0",server.takeRequest().requestUrl!!.queryParameter("BBOX"))
            server.enqueue(MockResponse().setBody("<ServiceException/>"))
            try {source.image(EarthDetailRegion(36.0,-84.0),512);fail("Must retain previous imagery")}
            catch(_:IllegalStateException){}
        } finally {server.shutdown()}
    }
    @Test fun blankCoverageNeverReplacesTerrainAndIsEvictedEvenWhenAlreadyCached() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = java.io.File(context.cacheDir,"earth-stream-v2").apply { deleteRecursively(); mkdirs() }
        val server = MockWebServer(); server.start()
        try {
            val endpoint = server.url("/wms").toString()
            val region = EarthDetailRegion.around(WeatherPlace(33.58,-85.08),100f)
            val url = region.requests(512).single().url(endpoint)
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cached = java.io.File(directory,key)
            val source = EarthDetailSource(context,endpoint)
            server.enqueue(MockResponse().setBody(png(512,512,Color.WHITE)))
            try { source.image(region,512).recycle(); fail("Blank response must retain the existing Earth") }
            catch (_: IllegalStateException) { }
            assertFalse(cached.exists())
            assertEquals("s2cloudless",server.takeRequest().requestUrl!!.queryParameter("LAYERS"))
            // Simulate an older app having cached the provider's successful HTTP white response.
            cached.writeBytes(png(512,512,Color.WHITE).readByteArray())
            try { source.image(region,512).recycle(); fail("Cached no-data must also be rejected") }
            catch (_: IllegalStateException) { }
            assertFalse(cached.exists())
            server.enqueue(MockResponse().setBody(png(512,512,Color.GREEN)))
            val valid = source.image(region,512)
            try { assertEquals(Color.GREEN,valid.getPixel(256,256)) } finally { valid.recycle() }
            assertEquals(2,server.requestCount)
            assertTrue(cached.exists())
        } finally { server.shutdown() }
    }

    @Test fun brightSnowWithSurfaceDetailIsNotMistakenForBlankCoverage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.cacheDir,"earth-stream-v2").deleteRecursively()
        val server = MockWebServer(); server.start()
        val snow = Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(256,511,Color.LTGRAY)
        }
        try {
            val bytes = java.io.ByteArrayOutputStream()
            snow.compress(Bitmap.CompressFormat.PNG,100,bytes)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes.toByteArray())))
            val result = EarthDetailSource(context,server.url("/snow").toString()).image(EarthDetailRegion(70.0,0.0),512)
            try { assertEquals(Color.WHITE,result.getPixel(0,0)); assertEquals(Color.LTGRAY,result.getPixel(256,511)) }
            finally { result.recycle() }
        } finally { snow.recycle(); server.shutdown() }
    }
    @Test fun fullDetailUsesSixteenSmallerExportsAtTheSameSourcePixelSpacing() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        java.io.File(context.cacheDir,"earth-stream-v2").deleteRecursively()
        val server=MockWebServer();server.start()
        try {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    val box=request.requestUrl!!.queryParameter("BBOX")!!.split(',').map(String::toDouble)
                    val col=kotlin.math.round((box[0]-6.5)/.25).toInt()
                    val row=kotlin.math.round((46.5-box[3])/.25).toInt()
                    return MockResponse().setBody(png(1024,1024,Color.rgb(col*60,row*60,128)))
                }
            }
            val source=EarthDetailSource(context,server.url("/wms").toString())
            val bitmap=source.image(EarthDetailRegion(46.0,7.0,1.0),4096)
            try {
                assertEquals(4096,bitmap.width)
                for (row in 0..3) for (col in 0..3) assertEquals(Color.rgb(col*60,row*60,128),bitmap.getPixel(col*1024+512,row*1024+512))
            }
            finally {bitmap.recycle()}
            assertEquals(16,server.requestCount)
            val boxes=(0 until 16).map {
                val url=server.takeRequest().requestUrl!!
                assertEquals("1024",url.queryParameter("WIDTH"))
                val box=url.queryParameter("BBOX")!!.split(',').map(String::toDouble)
                assertEquals(.25,box[2]-box[0],.000001)
                assertEquals(.25,box[3]-box[1],.000001)
                box
            }
            assertEquals(6.5,boxes.minOf {it[0]},.000001)
            assertEquals(46.5,boxes.maxOf {it[3]},.000001)
        } finally {server.shutdown()}
    }

    @Test fun cancelledZoomRequestsReleaseTheirNetworkSlotsPromptly()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        java.io.File(context.cacheDir,"earth-stream-v2").deleteRecursively()
        val server=MockWebServer();server.start()
        try {
            repeat(4) {server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))}
            server.enqueue(MockResponse().setBody(png(512,512,Color.GREEN)))
            val source=EarthDetailSource(context,server.url("/cancel").toString())
            val jobs=(0..3).map {index -> launch(Dispatchers.IO) {source.image(EarthDetailRegion(index.toDouble(),0.0),512).recycle()} }
            repeat(4) {assertNotNull(server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))}
            withTimeout(3000) {jobs.forEach {it.cancel()};jobs.joinAll()}
            val result=withTimeout(3000) {source.image(EarthDetailRegion(30.0,30.0),512)}
            try {assertEquals(Color.GREEN,result.getPixel(0,0))} finally {result.recycle()}
        } finally {server.shutdown()}
    }

}
