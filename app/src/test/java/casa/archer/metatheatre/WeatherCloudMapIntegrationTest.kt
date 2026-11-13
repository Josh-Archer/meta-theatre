package casa.archer.metatheatre

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherCloudMapIntegrationTest {
    @Test fun liveCloudProfilesSeparateLowCloudFromStormHeightAndWeatherWindChangesFrontalDetail() = runTest {
        val map = WeatherCloudMap.load(RuntimeEnvironment.getApplication().assets)
        val center = WeatherPlace(0.0,-100.0)
        fun snapshot(code: Int, wind: Double) = WeatherSnapshot(center, WeatherPlace.grid(center).map {
            WeatherReading(it, Instant.parse("2026-10-18T12:00:00Z"), code, 75, 12.0, wind)
        })
        val storm = map.build(snapshot(95, 0.0))
        val fog = map.build(snapshot(45, 0.0))
        val x = (80.0/360*storm.width).toInt(); val y = storm.height/2
        assertFalse(storm.isPremultiplied)
        assertTrue(Color.red(storm.getPixel(x,y)) > Color.red(fog.getPixel(x,y)) + 150)
        assertTrue(Color.green(storm.getPixel(x,y)) > 240)
        assertTrue(Color.blue(fog.getPixel(x,y)) > 240)
        storm.recycle(); fog.recycle()
        val north = map.build(snapshot(61, 0.0))
        val east = map.build(snapshot(61, 90.0))
        var different = 0
        for (dy in -80..80) for (dx in -80..80) {
            if (Color.alpha(north.getPixel(x+dx,y+dy)) != Color.alpha(east.getPixel(x+dx,y+dy))) different++
        }
        assertTrue("Live wind must influence frontal cloud structure", different > 1000)
        north.recycle(); east.recycle()
    }

    @Test fun packagedEarthMapsUseTheOriginal8KDetailIncludingClouds() {
        val assets=RuntimeEnvironment.getApplication().assets
        fun size(name:String):Pair<Int,Int>{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            assets.open(name).use{BitmapFactory.decodeStream(it,null,options)}
            return options.outWidth to options.outHeight
        }
        assertEquals(8192 to 4096,size("earth/day.jpg"))
        assertEquals(8192 to 4096,size("earth/night.jpg"))
        assertEquals(8192 to 4096,size("earth/clouds.png"))
        for(name in listOf("earth/normal.jpg","earth/ocean.jpg","weather/cloud_structure.png"))
            assertEquals(4096 to 2048,size(name))
    }

    @Test fun authoredAtlasChangesViewedCloudsAtFullResolutionPreservesTheFarSideAndWrapsPoles() = runTest {
        val assets=RuntimeEnvironment.getApplication().assets
        val original=assets.open("earth/clouds.png").use { BitmapFactory.decodeStream(it)!! }
        val map=WeatherCloudMap.load(assets)
        fun grid(center: WeatherPlace,code:Int,cover:Int)=WeatherSnapshot(center,WeatherPlace.grid(center).map{
            WeatherReading(it,Instant.parse("2026-10-17T12:00:00Z"),code,cover,10.0,140.0)})
        val center=WeatherPlace(0.0,-100.0)
        val clear=map.build(grid(center,0,0))
        val cloudy=map.build(grid(center,3,100))
        assertEquals(8192,clear.width);assertEquals(4096,clear.height)
        assertEquals(original.width,clear.width);assertEquals(original.height,clear.height)
        val x=(80.0/360*clear.width).toInt();val y=clear.height/2
        val values=mutableListOf<Int>()
        for(dy in -100..100)for(dx in -100..100)values.add(Color.alpha(cloudy.getPixel(x+dx,y+dy)))
        val average=values.average();val variance=values.map{(it-average)*(it-average)}.average()
        assertTrue("Overcast must retain optical-depth detail instead of a flat clipped patch",variance>90)
        assertTrue("Overcast must have a range of dense and thin areas",values.max()-values.min()>65)
        var clearAlpha=0L;var cloudAlpha=0L
        for(dy in -35..35)for(dx in -35..35){clearAlpha+=Color.alpha(clear.getPixel(x+dx,y+dy));cloudAlpha+=Color.alpha(cloudy.getPixel(x+dx,y+dy))}
        assertTrue("Actual cloud coverage must alter the rendered mask",cloudAlpha>clearAlpha+100_000)
        val farX=(260.0/360*clear.width).toInt()
        assertEquals(Color.alpha(original.getPixel(farX,y)),Color.alpha(cloudy.getPixel(farX,y)))
        val seam=map.build(grid(WeatherPlace(0.0,179.9),0,0))
        assertTrue(Color.alpha(seam.getPixel(0,y))<10)
        assertTrue(Color.alpha(seam.getPixel(seam.width-1,y))<10)
        val polar=map.build(grid(WeatherPlace(90.0,0.0),3,100))
        assertEquals(original.width,polar.width)
        if(System.getenv("THEATRE_UI_CAPTURE")!=null){
            java.io.File(System.getenv("THEATRE_UI_CAPTURE"),"live-weather-cloud-map.png").outputStream().use{
                cloudy.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        original.recycle();clear.recycle();cloudy.recycle();seam.recycle();polar.recycle()
    }

    @Test fun verifiedHighCloudIsWispyWhileGenericPartlyCloudyStaysBillowingAt8k() = runTest {
        val map = WeatherCloudMap.load(RuntimeEnvironment.getApplication().assets)
        val center = WeatherPlace(0.0, -100.0)
        val time = Instant.parse("2026-10-17T12:00:00Z")
        fun snap(code: Int, cover: Int, low: Int? = null, mid: Int? = null, high: Int? = null) =
            WeatherSnapshot(center, WeatherPlace.grid(center).map {
                WeatherReading(it, time, code, cover, 12.0, 180.0, low, mid, high)
            })
        fun crc(bitmap: Bitmap): Long {
            val sum = CRC32()
            val row = IntArray(bitmap.width)
            val bytes = ByteBuffer.allocate(bitmap.width * 4)
            for (rowY in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, row.size, 0, rowY, row.size, 1)
                bytes.clear()
                row.forEach(bytes::putInt)
                sum.update(bytes.array())
            }
            return sum.value
        }
        val genericSnap = snap(2, 45)
        val lowerSnap = snap(2, 45, 40, 25, 10)
        val highSnap = snap(2, 45, 0, 4, 70)
        val overcastSnap = snap(3, 100, 90, 40, 15)
        val stormSnap = snap(95, 80, 30, 40, 95)
        assertEquals(CloudPattern.CUMULUS, genericSnap.central.pattern)
        assertEquals(CloudPattern.CUMULUS, lowerSnap.central.pattern)
        assertEquals(CloudPattern.CIRRUS, highSnap.central.pattern)
        assertEquals(CloudPattern.OVERCAST, overcastSnap.central.pattern)
        assertEquals(CloudPattern.THUNDERHEAD, stormSnap.central.pattern)
        assertEquals(CloudPattern.THUNDERHEAD, snap(95, 80).central.pattern)
        var genericCrc = 0L
        var genericRed = 0
        val genericAlpha = LongArray(121 * 121)
        val generic = map.build(genericSnap)
        try {
            assertEquals(8192, generic.width); assertEquals(4096, generic.height); assertFalse(generic.isPremultiplied)
            genericCrc = crc(generic)
            val x = (80.0 / 360 * generic.width).toInt(); val y = generic.height / 2
            genericRed = Color.red(generic.getPixel(x, y))
            var i = 0
            for (dy in -60..60) for (dx in -60..60) genericAlpha[i++] = Color.alpha(generic.getPixel(x + dx, y + dy)).toLong()
        } finally { generic.recycle() }
        val lower = map.build(lowerSnap)
        try {
            assertEquals(8192 to 4096, lower.width to lower.height)
            assertEquals("A weak high-cloud fraction must not change partly cloudy morphology", genericCrc, crc(lower))
        } finally { lower.recycle() }
        val high = map.build(highSnap)
        try {
            assertEquals(8192 to 4096, high.width to high.height)
            assertNotEquals("Verified high cloud must change the 8K morphology", genericCrc, crc(high))
            val x = (80.0 / 360 * high.width).toInt(); val y = high.height / 2
            val highRed = Color.red(high.getPixel(x, y))
            assertTrue(highRed > genericRed + 70)
            var highAlpha = 0L; var genericSum = 0L; var different = 0; var i = 0
            for (dy in -60..60) for (dx in -60..60) {
                val alpha = Color.alpha(high.getPixel(x + dx, y + dy))
                highAlpha += alpha; genericSum += genericAlpha[i]
                if (alpha.toLong() != genericAlpha[i]) different++
                i++
            }
            assertTrue("High-cloud filaments must differ across the viewed region", different > 1000)
            assertTrue("The same total cover should read thinner as high cloud", highAlpha < genericSum)
            val overcast = map.build(overcastSnap)
            try {
                assertEquals(8192 to 4096, overcast.width to overcast.height)
                assertTrue(highRed > Color.red(overcast.getPixel(x, y)) + 70)
            } finally { overcast.recycle() }
        } finally { high.recycle() }
        val storm = map.build(stormSnap)
        val stormPlain = map.build(snap(95, 80))
        try {
            assertEquals(8192 to 4096, storm.width to storm.height)
            assertEquals("Thunderstorms keep their profile when a high layer is also reported", crc(stormPlain), crc(storm))
            val x = (80.0 / 360 * storm.width).toInt(); val y = storm.height / 2
            assertTrue(Color.green(storm.getPixel(x, y)) > 200)
        } finally { storm.recycle(); stormPlain.recycle() }
    }
}
