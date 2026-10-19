package casa.archer.metatheatre

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/** Continuous global morphology: weather changes coverage and cloud type, never stamps repeated tiles. */
internal class WeatherCloudMap private constructor(private val width: Int, private val height: Int,
    private val original: ByteArray, private val structure: Array<ByteArray>) {
    suspend fun build(snapshot: WeatherSnapshot): Bitmap {
        // Interpolate coarse model readings independently of high-resolution cloud detail.
        // Five small fields replace two full-resolution float buffers and repeated per-cell map splats.
        val fields = Array(7) { FloatArray(GRID_W * GRID_H) }
        data class Sample(val x: Double,val y: Double,val z: Double,val cover: Float,val front: Float,val storm: Float,val fog: Float,
            val windEast: Float, val windNorth: Float)
        val samples=snapshot.readings.map { reading ->
            val n=reading.place.normal()
            val pattern=reading.pattern
            val cover=if(pattern==CloudPattern.CLEAR) 0f else max(reading.cloudCover/100f,when(pattern){
                CloudPattern.FOG->.35f
                CloudPattern.RAIN_FRONT,CloudPattern.SNOW_BANDS,CloudPattern.THUNDERHEAD->.6f
                else->0f
            })
            val wind = Math.toRadians(reading.windDirection)
            Sample(n.x.toDouble(),n.y.toDouble(),n.z.toDouble(),cover,
                if(pattern==CloudPattern.RAIN_FRONT || pattern==CloudPattern.SNOW_BANDS)1f else 0f,
                if(pattern==CloudPattern.THUNDERHEAD)1f else 0f,if(pattern==CloudPattern.FOG)1f else 0f,
                sin(wind).toFloat(), cos(wind).toFloat())
        }
        for(y in 0 until GRID_H){
            currentCoroutineContext().ensureActive()
            val lat=Math.PI*(.5-(y+.5)/GRID_H);val cl=cos(lat);val sl=sin(lat)
            for(x in 0 until GRID_W){
                val lon=Math.PI*(2*(x+.5)/GRID_W-1);val nx=cl*sin(lon);val nz=cl*cos(lon)
                var weight=0f;var cover=0f;var front=0f;var storm=0f;var fog=0f;var east=0f;var north=0f
                for(sample in samples){
                    val dot=(nx*sample.x+sl*sample.y+nz*sample.z).coerceIn(-1.0,1.0)
                    if(dot<.88294759)continue
                    val angle=acos(dot)
                    val w=(exp(-angle*angle/.040)*((dot-.88294759)/.05).coerceIn(0.0,1.0)).toFloat()
                    weight+=w;cover+=sample.cover*w;front+=sample.front*w;storm+=sample.storm*w;fog+=sample.fog*w
                    east+=sample.windEast*w;north+=sample.windNorth*w
                }
                val i=y*GRID_W+x
                fields[0][i]=weight.coerceAtMost(1f)
                if(weight>0){fields[1][i]=cover/weight;fields[2][i]=front/weight;fields[3][i]=storm/weight;fields[4][i]=fog/weight
                    fields[5][i]=east/weight;fields[6][i]=north/weight}
            }
        }
        val pixels=IntArray(width*height)
        val x0=IntArray(width);val x1=IntArray(width);val tx=FloatArray(width)
        for(x in 0 until width){
            val gx=(x+.5f)*GRID_W/width-.5f;val floorX=floor(gx).toInt()
            x0[x]=(floorX+GRID_W)%GRID_W;x1[x]=(floorX+1+GRID_W)%GRID_W;tx[x]=gx-floorX
        }
        for(y in 0 until height){
            currentCoroutineContext().ensureActive()
            val gy=((y+.5f)*GRID_H/height-.5f).coerceIn(0f,GRID_H-1f)
            val y0=floor(gy).toInt();val y1=min(GRID_H-1,y0+1);val ty=gy-y0
            for(x in 0 until width){
                fun field(channel:Int):Float{
                    val data=fields[channel]
                    val top=data[y0*GRID_W+x0[x]]*(1-tx[x])+data[y0*GRID_W+x1[x]]*tx[x]
                    val bottom=data[y1*GRID_W+x0[x]]*(1-tx[x])+data[y1*GRID_W+x1[x]]*tx[x]
                    return top*(1-ty)+bottom*ty
                }
                val index=y*width+x;val blend=field(0)
                val old=(original[index].toInt() and 255)/255f
                val cover=field(1);val front=field(2);val storm=field(3);val fog=field(4)
                val cells=(structure[0][index].toInt() and 255)/255f
                val erosion=(structure[1][index].toInt() and 255)/255f
                val bands=(structure[2][index].toInt() and 255)/255f
                val windX = (x + (field(5) * 14).roundToInt() + width) % width
                val windY = (y - (field(6) * 14).roundToInt()).coerceIn(0, height - 1)
                val stretched = (structure[2][windY * width + windX].toInt() and 255) / 255f
                val shape=cells*(1-front*.8f)+(bands*.6f + stretched*.4f)*front*.8f
                val threshold=1-cover
                val edge=((shape-threshold+.045f)/.09f).coerceIn(0f,1f)
                val envelope=if(cover<=.001f)0f else if(cover>=.999f)1f else edge*edge*(3-2*edge)
                // Fine optical depth survives fully overcast weather; it never clips to a flat white patch.
                val depth=(.28f+.28f*erosion+.42f*old)*(1-fog*.48f)+storm*.12f
                val value=(envelope*depth).coerceIn(0f,.98f)
                val alpha=((old*(1-blend)+value*blend)*255).roundToInt().coerceIn(0,255)
                val cloudHeight = (.5f + storm * .45f - fog * .4f).coerceIn(0f,1f)
                // RGB stores a vertical profile for native shading; alpha retains full-resolution optical depth.
                // Blend back to the original middle-height deck outside the current model footprint.
                pixels[index]=Color.argb(alpha,
                    ((.5f*(1-blend)+cloudHeight*blend)*255).roundToInt(),
                    (storm*blend*255).roundToInt().coerceIn(0,255),
                    (fog*blend*255).roundToInt().coerceIn(0,255))
            }
        }
        // RGB is data, independent of density alpha. Preserve it even in optically thin clouds.
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
            setPremultiplied(false)
            setPixels(pixels,0,width,0,0,width,height)
        }
    }
    companion object {
        private const val GRID_W=128
        private const val GRID_H=64
        fun load(assets: AssetManager): WeatherCloudMap {
            fun decode(path: String)=assets.open(path).use{checkNotNull(BitmapFactory.decodeStream(it))}
            val base=decode("earth/clouds.png")
            val pattern=decode("weather/cloud_structure.png")
            try {
                check(base.width==4096 && base.height==2048 && pattern.width==base.width && pattern.height==base.height)
                // Decode a row at a time instead of allocating another full-resolution IntArray.
                val original=ByteArray(base.width*base.height)
                val channels=Array(3){ByteArray(original.size)};val row=IntArray(base.width)
                for(y in 0 until base.height){
                    base.getPixels(row,0,base.width,0,y,base.width,1)
                    for(x in row.indices)original[y*base.width+x]=(row[x] ushr 24).toByte()
                    pattern.getPixels(row,0,pattern.width,0,y,pattern.width,1)
                    for(x in row.indices){val i=y*pattern.width+x;channels[0][i]=(row[x] ushr 16).toByte();channels[1][i]=(row[x] ushr 8).toByte();channels[2][i]=row[x].toByte()}
                }
                return WeatherCloudMap(base.width,base.height,original,channels)
            }finally{base.recycle();pattern.recycle()}
        }
    }
}
