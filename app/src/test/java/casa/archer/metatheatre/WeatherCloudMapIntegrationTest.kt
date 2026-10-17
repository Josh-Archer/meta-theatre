package casa.archer.metatheatre

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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherCloudMapIntegrationTest {
    @Test fun authoredAtlasChangesViewedCloudsAtFullResolutionPreservesTheFarSideAndWrapsPoles() = runTest {
        val assets=RuntimeEnvironment.getApplication().assets
        val original=assets.open("earth/clouds.png").use { BitmapFactory.decodeStream(it)!! }
        val map=WeatherCloudMap.load(assets)
        fun grid(center: WeatherPlace,code:Int,cover:Int)=WeatherSnapshot(center,WeatherPlace.grid(center).map{
            WeatherReading(it,Instant.parse("2026-10-17T12:00:00Z"),code,cover,10.0,140.0)})
        val center=WeatherPlace(0.0,-100.0)
        val clear=map.build(grid(center,0,0))
        val cloudy=map.build(grid(center,3,100))
        assertEquals(original.width,clear.width);assertEquals(original.height,clear.height)
        val x=(80.0/360*clear.width).toInt();val y=clear.height/2
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
}
