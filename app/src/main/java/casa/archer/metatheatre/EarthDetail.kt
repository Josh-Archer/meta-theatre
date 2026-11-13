package casa.archer.metatheatre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.MessageDigest
import kotlin.math.*

/** Snapped coverage shrinks with magnification, so closer views receive real finer source pixels. */
internal data class EarthDetailRegion(val latitude: Double, val longitude: Double, val span: Double = 18.0) {
    val west get() = longitude - span / 2
    val north get() = latitude + span / 2
    val south get() = latitude - span / 2
    fun covers(place: WeatherPlace) = abs(place.latitude - latitude) < span * .32 &&
        abs(WeatherPlace.wrap(place.longitude - longitude)) < span * .32
    companion object {
        fun around(place: WeatherPlace, zoom: Float = 8f): EarthDetailRegion {
            val span = when { zoom >= 40f -> 1.0; zoom >= 12f -> 4.0; else -> 18.0 }
            val step = span / 3
            return EarthDetailRegion((round(place.latitude / step) * step).coerceIn(-90 + span/2, 90 - span/2),
                WeatherPlace.wrap(round(place.longitude / step) * step), span)
        }
    }
    fun requests(size: Int = 4096): List<EarthDetailRequest> {
        val start = WeatherPlace.wrap(west)
        val first = minOf(span, 180 - start)
        val split = round(first / span * size).toInt().coerceIn(1, size)
        val parts = mutableListOf(EarthDetailRequest(start, south, start + first, north, 0, split, size))
        if (split < size) parts.add(EarthDetailRequest(-180.0, south, -180 + span - first, north, split, size - split, size))
        return parts
    }
}

internal data class EarthDetailRequest(val west: Double, val south: Double, val east: Double, val north: Double,
    val x: Int, val width: Int, val height: Int) {
    fun url(endpoint: String) = "$endpoint?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1" +
        // The unsuffixed layer is the global 2016/2017 mosaic. The 2017 layer
        // returns valid-sized, entirely white images for Carrollton and other regions.
        "&LAYERS=s2cloudless&STYLES=&FORMAT=image/jpeg&SRS=EPSG:4326" +
        "&BBOX=$west,$south,$east,$north&WIDTH=$width&HEIGHT=$height"
}

/** Normal tile bounds in normalized Web Mercator coordinates; X may cross the date line. */
internal data class EarthTerrain(val west: Double, val north: Double, val width: Double, val height: Double)
internal data class EarthTerrainImage(val bounds: EarthTerrain, val bitmap: Bitmap)
internal interface EarthImageSource {
    suspend fun image(region: EarthDetailRegion, size: Int): Bitmap
    suspend fun terrain(region: EarthDetailRegion): EarthTerrainImage? = null
}

internal class EarthDetailSource(context: Context,
    private val endpoint: String = "https://tiles.maps.eox.at/wms",
    private val terrainEndpoint: String = "https://elevation-tiles-prod.s3.amazonaws.com/normal") : EarthImageSource {
    private val directory = File(context.cacheDir, "earth-stream-v2").apply { mkdirs() }
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val network = Semaphore(4)
    private val terrainNetwork = Semaphore(2)
    // Keep decoded tiles bounded through composition and recycling, including cache hits.
    private val imageTiles = Semaphore(4)
    private val terrainTiles = Semaphore(2)
    private val cacheLock = Mutex()
    private val requests = java.util.concurrent.atomic.AtomicInteger()
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    internal fun stats() = "network=${requests.get()} cache=${hits.get()}"
    private suspend fun encoded(url: String): ByteArray = (if (url.startsWith("$terrainEndpoint/")) terrainNetwork else network).withPermit {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, key)
        cacheLock.withLock {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
                hits.incrementAndGet()
                return@withPermit file.readBytes()
            }
        }
        currentCoroutineContext().ensureActive()
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            requests.incrementAndGet()
            val call = http.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            check(it.isSuccessful) { "Imagery unavailable" }
                            checkNotNull(it.body).byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    check(output.size() + count <= 32 * 1024 * 1024) { "Imagery response too large" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        // Persist the provider's encoded bytes directly. No expensive 4K PNG re-encoding before display.
        cacheLock.withLock {
            val temp = File.createTempFile("pending-", ".tmp", directory)
            try { temp.writeBytes(bytes); currentCoroutineContext().ensureActive(); check(temp.renameTo(file)) }
            finally { temp.delete() }
            var total = 0L
            directory.listFiles().orEmpty().filter { it.extension != "tmp" }.sortedByDescending { it.lastModified() }.forEach {
                total += it.length()
                if (total > 192L * 1024 * 1024) it.delete()
            }
        }
        bytes
    }
    private suspend fun decode(url: String, width: Int, height: Int, data: Boolean = false): Bitmap {
        val bytes = encoded(url)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth != width || options.outHeight != height) {
            invalidate(url)
            error("Invalid imagery dimensions")
        }
        options.inJustDecodeBounds = false
        options.inPremultiplied = !data
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        if (!data && blankImagery(bitmap)) {
            bitmap.recycle()
            invalidate(url)
            error("Imagery has no coverage")
        }
        return bitmap
    }
    private suspend fun invalidate(url: String) {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        cacheLock.withLock { File(directory, key).delete() }
    }

    /** Reject the service's exact white no-data image, including stale cached responses.
     * Textured snow and ice still pass; terrain normal maps are numeric data and bypass this check. */
    private fun blankImagery(bitmap: Bitmap): Boolean {
        if (bitmap.getPixel(0, 0) and 0x00ffffff != 0x00ffffff) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it and 0x00ffffff != 0x00ffffff }) return false
        }
        return true
    }
    override suspend fun image(region: EarthDetailRegion, size: Int): Bitmap {
        if (size >= 2048) {
            val side = size / 1024
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(result)
                // Independent 1K exports retain full source resolution and avoid the slow 4K WMS export.
                coroutineScope {
                    (0 until side).flatMap { row -> (0 until side).map { col -> async {
                        val span = region.span / side
                        val sub = EarthDetailRegion(region.north - (row+.5)*span,
                            WeatherPlace.wrap(region.west + (col+.5)*span), span)
                        imageTiles.withPermit {
                            val tile = image(sub, 1024)
                            try { synchronized(canvas) { canvas.drawBitmap(tile, col*1024f, row*1024f, null) } }
                            finally { tile.recycle() }
                        }
                    } } }.awaitAll()
                }
                return result
            } catch (error: Throwable) { result.recycle(); throw error }
        }
        val requests = region.requests(size)
        if (requests.size == 1) return decode(requests[0].url(endpoint), size, size)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            for (request in requests) {
                val part = decode(request.url(endpoint), request.width, size)
                try { canvas.drawBitmap(part, request.x.toFloat(), 0f, null) } finally { part.recycle() }
            }
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
    }
    override suspend fun terrain(region: EarthDetailRegion): EarthTerrainImage? {
        if (abs(region.latitude) > 75) return null
        val z = (ceil(log2(360 / region.span)).toInt() + 1).coerceIn(5, 11)
        val count = 1 shl z
        fun y(lat: Double) = (1 - ln(tan(PI/4 + Math.toRadians(lat.coerceIn(-85.0, 85.0))/2))/PI)/2 * count
        val left = floor((region.west + 180)/360 * count).toInt()
        val right = floor((region.west + region.span + 180)/360 * count).toInt()
        val top = floor(y(region.north)).toInt().coerceIn(0, count-1)
        val bottom = floor(y(region.south)).toInt().coerceIn(0, count-1)
        check((right-left+1)*(bottom-top+1) <= 96)
        val result = Bitmap.createBitmap((right-left+1)*256, (bottom-top+1)*256, Bitmap.Config.ARGB_8888)
        try {
            coroutineScope {
                (top..bottom).flatMap { row -> (left..right).map { col -> async {
                    terrainTiles.withPermit {
                        val tile = decode("$terrainEndpoint/$z/${((col%count)+count)%count}/$row.png", 256, 256, data = true)
                        try {
                            // Alpha is elevation metadata, not opacity. Preserve the RGB data exactly.
                            val pixels = IntArray(256*256)
                            tile.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                            for (i in pixels.indices) pixels[i] = pixels[i] or 0xff000000.toInt()
                            synchronized(result) {
                                result.setPixels(pixels, 0, 256, (col-left)*256, (row-top)*256, 256, 256)
                            }
                        } finally { tile.recycle() }
                    }
                } } }.awaitAll()
            }
            return EarthTerrainImage(EarthTerrain(left.toDouble()/count, top.toDouble()/count,
                (right-left+1).toDouble()/count, (bottom-top+1).toDouble()/count), result)
        } catch (error: Throwable) { result.recycle(); throw error }
    }
}

/** Preview arrives first. Full imagery and terrain load independently, with no planet atlas rebuild. */
internal class EarthDetailSession(private val context: Context, private val scope: CoroutineScope,
    private val render: (EarthDetailRegion, Bitmap) -> Unit, private val publish: (String) -> Unit,
    private val source: EarthImageSource = EarthDetailSource(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val renderTerrain: (EarthTerrain, Bitmap) -> Unit = { _, _ -> }) {
    private var active = false
    private var requested: EarthDetailRegion? = null
    private var shown: EarthDetailRegion? = null
    private var job: Job? = null
    private val worker = Mutex()
    private var retryAfter = 0L
    fun setActive(value: Boolean) {
        active = value
        if (!value) { job?.cancel(); requested = null }
    }
    fun view(place: WeatherPlace, zoom: Float) {
        if (!active) return
        if (zoom < 1.3f) { job?.cancel(); requested = null; publish("Zoom in for terrain detail"); return }
        val region = EarthDetailRegion.around(place, zoom)
        if (shown?.let { it.span == region.span && it.covers(place) } == true) {
            if (requested != shown) { job?.cancel(); requested = null }
            return
        }
        if (requested?.let { it.span == region.span && it.covers(place) } == true && job?.isActive == true) return
        if (SystemClock.elapsedRealtime() < retryAfter) return
        job?.cancel(); requested = region
        job = scope.launch {
            val start = SystemClock.elapsedRealtime()
            var previewShown = false
            try {
                delay(150)
                publish("Loading terrain preview…")
                worker.withLock {
                    var displayed = 0
                    suspend fun display(size: Int) {
                        var bitmap: Bitmap? = null
                        try {
                            withContext(ioDispatcher) { bitmap = source.image(region, size) }
                            currentCoroutineContext().ensureActive()
                            if (size < displayed) return
                            displayed = size
                            val before = SystemClock.elapsedRealtime()
                            Log.i("Earth", "Detail ${size}px decoded=${before-start}ms; uploading")
                            render(region, checkNotNull(bitmap))
                            Log.i("Earth", "Detail ${size}px ready=${before-start}ms upload=${SystemClock.elapsedRealtime()-before}ms ${(source as? EarthDetailSource)?.stats().orEmpty()}")
                        } finally { bitmap?.recycle() }
                    }
                    display(512)
                    previewShown = true
                    publish("Terrain preview · refining…")
                    // Terrain must not hold up either the preview or the full satellite image.
                    supervisorScope {
                        val terrainJob = launch {
                            var image: EarthTerrainImage? = null
                            try {
                                withContext(ioDispatcher) { image = source.terrain(region) }
                                image?.let { renderTerrain(it.bounds, it.bitmap) }
                                if (image != null) Log.i("Earth", "Mountain normals ready=${SystemClock.elapsedRealtime()-start}ms")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Log.i("Earth", "Terrain source unavailable; imagery remains visible") }
                            finally { image?.bitmap?.recycle() }
                        }
                        val intermediate = launch {
                            try { display(2048) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Full detail continues independently. */ }
                        }
                        yield()
                        display(4096)
                        shown = region
                        publish("Satellite detail · mountain shading")
                        intermediate.join()
                        terrainJob.join()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("Earth", "Regional detail unavailable (${error.javaClass.simpleName})")
                retryAfter = SystemClock.elapsedRealtime() + 10_000
                publish(if (previewShown) "Preview shown · full detail unavailable" else "Detail unavailable · global Earth shown")
            }
        }
    }
}
