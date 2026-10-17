package casa.archer.metatheatre

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/** Reuses the full-resolution cloud shell. Atlas density is baked from the editable Blender library. */
internal class WeatherCloudMap private constructor(private val width: Int, private val height: Int,
    private val original: ByteArray, private val atlas: ByteArray, private val tile: Int, private val means: FloatArray) {
    suspend fun build(snapshot: WeatherSnapshot): Bitmap {
        val weight = FloatArray(width * height)
        val density = FloatArray(width * height)
        val latSin = DoubleArray(height) { sin(Math.PI * (.5 - (it + .5) / height)) }
        val latCos = DoubleArray(height) { cos(Math.PI * (.5 - (it + .5) / height)) }
        val lonSin = DoubleArray(width) { sin(Math.PI * (2 * (it + .5) / width - 1)) }
        val lonCos = DoubleArray(width) { cos(Math.PI * (2 * (it + .5) / width - 1)) }
        for (reading in snapshot.readings) {
            currentCoroutineContext().ensureActive()
            val lat = Math.toRadians(reading.place.latitude); val lon = Math.toRadians(reading.place.longitude)
            val sl = sin(lat); val cl = cos(lat); val sn = sin(lon); val cn = cos(lon)
            val wind = Math.toRadians(reading.windDirection)
            val cover = max(reading.cloudCover / 100f, when (reading.pattern) {
                CloudPattern.FOG -> .35f
                CloudPattern.RAIN_FRONT, CloudPattern.SNOW_BANDS, CloudPattern.THUNDERHEAD -> .6f
                else -> 0f
            })
            val y0 = (((90 - reading.place.latitude - 28) / 180 * height).toInt()).coerceAtLeast(0)
            val y1 = (((90 - reading.place.latitude + 28) / 180 * height).toInt()).coerceAtMost(height - 1)
            val dx = min(180.0, 28 / max(.05, cos(Math.toRadians(min(90.0, abs(reading.place.latitude) + 28)))))
            val centerX = ((reading.place.longitude + 180) / 360 * width).toInt()
            val span = min(width / 2, ceil(dx / 360 * width).toInt())
            for (y in y0..y1) for (rawX in centerX-span until centerX+span) {
                val x = (rawX % width + width) % width
                val vx = latCos[y] * lonSin[x]; val vy = latSin[y]; val vz = latCos[y] * lonCos[x]
                val dot = vx*cl*sn + vy*sl + vz*cl*cn
                if (dot < .88294759) continue // 28 degrees, including seam/pole crossings
                val east = atan2(vx*cn - vz*sn, dot)
                val north = atan2(-vx*sl*sn + vy*cl - vz*sl*cn, dot)
                val radius = east*east + north*north
                val w = (exp(-radius / .040) * ((dot - .88294759) / .05).coerceIn(0.0, 1.0)).toFloat()
                val u = (east*cos(wind) + north*sin(wind)) / .105 + .5
                val v = (-east*sin(wind) + north*cos(wind)) / .105 + .5
                val pattern = reading.pattern.ordinal
                val tx = ((u-floor(u))*tile).toInt().coerceIn(0,tile-1)
                val ty = ((v-floor(v))*tile).toInt().coerceIn(0,tile-1)
                val alpha = (atlas[((pattern / 4 * tile + ty) * tile * 4) + pattern % 4 * tile + tx].toInt() and 255) / 255f
                val value = if (pattern == 0) 0f else (alpha * cover / max(.12f,means[pattern])).coerceIn(0f,1f)
                val index = y*width+x
                weight[index] += w; density[index] += w*value
            }
        }
        currentCoroutineContext().ensureActive()
        val pixels = IntArray(width*height) { index ->
            val w = weight[index]
            val blend = min(1f,w)
            val alpha = ((original[index].toInt() and 255)*(1-blend) + if(w > 0) density[index]/w*255*blend else 0f).roundToInt().coerceIn(0,255)
            Color.argb(alpha,255,255,255)
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
    companion object {
        fun load(assets: AssetManager): WeatherCloudMap {
            fun decode(path: String) = assets.open(path).use { checkNotNull(BitmapFactory.decodeStream(it)) }
            val base = decode("earth/clouds.png")
            val pattern = decode("weather/cloud_patterns.png")
            try {
                check(pattern.width == 4096 && pattern.height == 2048)
                fun alpha(bitmap: Bitmap): ByteArray {
                    val pixels = IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    return ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
                }
                val mask = alpha(pattern); val tile=pattern.width/4
                val means = FloatArray(8) { index ->
                    var sum=0L
                    for(y in 0 until tile) for(x in 0 until tile) sum += mask[(index/4*tile+y)*pattern.width+index%4*tile+x].toInt() and 255
                    sum.toFloat()/(tile*tile*255f)
                }
                return WeatherCloudMap(base.width,base.height,alpha(base),mask,tile,means)
            } finally { base.recycle(); pattern.recycle() }
        }
    }
}
