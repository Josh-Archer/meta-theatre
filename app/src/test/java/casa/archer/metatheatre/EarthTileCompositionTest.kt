package casa.archer.metatheatre

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EarthTileCompositionTest {
    private fun tile(size: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    @Test fun cachedSatelliteTilesKeepExactPixelsAndQuadrantPlacement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "earth-stream-v2").deleteRecursively()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val tiles = colors.map { tile(1024, it) }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bounds = request.requestUrl!!.queryParameter("BBOX")!!.split(',').map(String::toDouble)
                val index = (if (bounds[1] < 0) 2 else 0) + (if (bounds[0] >= 0) 1 else 0)
                return MockResponse().setBody(Buffer().write(tiles[index]))
            }
        }
        server.start()
        try {
            val source = EarthDetailSource(context, server.url("/wms").toString())
            repeat(2) {
                val image = source.image(EarthDetailRegion(0.0, 0.0, 4.0), 2048)
                try {
                    for (row in 0..1) for (col in 0..1) {
                        assertEquals(colors[row*2+col], image.getPixel(col*1024+511, row*1024+511))
                        assertEquals(colors[row*2+col], image.getPixel(col*1024, row*1024))
                    }
                } finally { image.recycle() }
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun terrainAlphaMetadataDoesNotAlterNormalChannelsOrTilePlacement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "earth-stream-v2").deleteRecursively()
        // Alpha metadata is intentionally nonopaque. PNG RGB must survive unchanged.
        val original = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        original.setPremultiplied(false)
        original.eraseColor(Color.argb(128, 64, 128, 240))
        val encoded = ByteArrayOutputStream()
        try { check(original.compress(Bitmap.CompressFormat.PNG, 100, encoded)) }
        finally { original.recycle() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(encoded.toByteArray()))
        }
        server.start()
        try {
            val source = EarthDetailSource(context, terrainEndpoint = server.url("/normal").toString().trimEnd('/'))
            val terrain = checkNotNull(source.terrain(EarthDetailRegion(0.0, 0.0, 4.0)))
            try {
                val image = terrain.bitmap
                val expected = Color.rgb(64, 128, 240)
                for (y in 0 until image.height step 255) for (x in 0 until image.width step 255) {
                    assertEquals(expected, image.getPixel(x, y))
                }
                assertEquals(expected, image.getPixel(image.width-1, image.height-1))
            } finally { terrain.bitmap.recycle() }
        } finally { server.shutdown() }
    }
}
