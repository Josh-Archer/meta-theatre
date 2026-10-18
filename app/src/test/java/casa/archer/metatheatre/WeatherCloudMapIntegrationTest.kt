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
    @Test fun packagedEarthMapsUseTheOriginal8KDetailAnd4KCloudField() {
        val assets=RuntimeEnvironment.getApplication().assets
        fun size(name:String):Pair<Int,Int>{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            assets.open(name).use{BitmapFactory.decodeStream(it,null,options)}
            return options.outWidth to options.outHeight
        }
        assertEquals(8192 to 4096,size("earth/day.jpg"))
        assertEquals(8192 to 4096,size("earth/night.jpg"))
        for(name in listOf("earth/normal.jpg","earth/ocean.jpg","earth/clouds.png","weather/cloud_structure.png"))
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
        assertEquals(4096,clear.width);assertEquals(2048,clear.height)
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
}
