package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.math.*

/** Geographic coordinates use the native Earth's texture meridian, including manual spin. */
internal data class WeatherPlace(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun normal(): Vector3 {
        val lat = Math.toRadians(latitude); val lon = Math.toRadians(longitude)
        return Vector3((cos(lat) * sin(lon)).toFloat(), sin(lat).toFloat(), (cos(lat) * cos(lon)).toFloat())
    }
    fun distance(other: WeatherPlace): Double {
        val a = normal(); val b = other.normal()
        return Math.toDegrees(acos((a.x * b.x + a.y * b.y + a.z * b.z).toDouble().coerceIn(-1.0, 1.0)))
    }
    fun label() = String.format(Locale.US, "%.1f°%s · %.1f°%s", abs(latitude), if (latitude < 0) "S" else "N",
        abs(longitude), if (longitude < 0) "W" else "E")
    companion object {
        fun viewed(earth: Pose, viewer: Vector3): WeatherPlace {
            val local = (earth.q.inverse() * (viewer - earth.t).normalize()).normalize()
            return WeatherPlace(Math.toDegrees(asin(local.y.toDouble().coerceIn(-1.0, 1.0))),
                wrap(-100.0 + Math.toDegrees(atan2(local.x.toDouble(), local.z.toDouble()))))
        }
        fun wrap(degrees: Double) = ((degrees + 180) % 360 + 360) % 360 - 180
        /** 49 readings span the visible hemisphere. Tangent construction also works at both poles. */
        fun grid(center: WeatherPlace): List<WeatherPlace> {
            val lat = Math.toRadians(center.latitude); val lon = Math.toRadians(center.longitude)
            val n = center.normal()
            val east = Vector3(cos(lon).toFloat(), 0f, -sin(lon).toFloat())
            val north = Vector3((-sin(lat)*sin(lon)).toFloat(), cos(lat).toFloat(), (-sin(lat)*cos(lon)).toFloat())
            return (-3..3).flatMap { row -> (-3..3).map { col ->
                val a = Math.toRadians(col * 15.0); val b = Math.toRadians(row * 15.0)
                val v = (n * (cos(a)*cos(b)).toFloat() + east * (sin(a)*cos(b)).toFloat() + north * sin(b).toFloat()).normalize()
                WeatherPlace(Math.toDegrees(asin(v.y.toDouble().coerceIn(-1.0,1.0))),
                    wrap(Math.toDegrees(atan2(v.x.toDouble(),v.z.toDouble()))))
            } }
        }
    }
}

internal enum class CloudPattern(val title: String) {
    CLEAR("Clear"), CUMULUS("Scattered clouds"), OVERCAST("Overcast"), FOG("Fog / low cloud"),
    RAIN_FRONT("Rain clouds"), SNOW_BANDS("Snow clouds"), THUNDERHEAD("Thunderstorm clouds"), CIRRUS("High cloud");
    companion object {
        fun forCode(code: Int, cover: Int): CloudPattern = when (code) {
            0 -> if (cover < 10) CLEAR else CUMULUS
            1, 2 -> CUMULUS
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN_FRONT
            71, 73, 75, 77, 85, 86 -> SNOW_BANDS
            95, 96, 99 -> THUNDERHEAD
            else -> if (cover >= 75) OVERCAST else CUMULUS
        }
    }
}

internal data class WeatherReading(val place: WeatherPlace, val time: Instant, val code: Int, val cloudCover: Int,
    val temperature: Double, val windDirection: Double) {
    val pattern get() = CloudPattern.forCode(code, cloudCover)
}
internal data class WeatherSnapshot(val center: WeatherPlace, val readings: List<WeatherReading>) {
    val central get() = readings[readings.size / 2]
    val time get() = readings.minOf { it.time }
    fun fresh(now: Instant) = time <= now.plusSeconds(300) && time >= now.minusSeconds(30 * 60)
}
internal data class WeatherUi(val enabled: Boolean = false, val place: WeatherPlace? = null,
    val message: String = "Off · original cloud map", val reading: WeatherReading? = null, val stale: Boolean = false)

internal fun interface WeatherSource { suspend fun fetch(place: WeatherPlace): WeatherSnapshot }

/** Only viewed coordinates go to the provider. No GPS, account token, disk cache, or request logging. */
internal class OpenMeteoWeather(private val endpoint: String = "https://api.open-meteo.com/v1/forecast") : WeatherSource {
    override suspend fun fetch(place: WeatherPlace): WeatherSnapshot = withContext(Dispatchers.IO) {
        val points = WeatherPlace.grid(place)
        fun number(value: Double) = String.format(Locale.US, "%.4f", value)
        val query = "latitude=" + points.joinToString(",") { number(it.latitude) } +
            "&longitude=" + points.joinToString(",") { number(it.longitude) } +
            "&current=weather_code,cloud_cover,temperature_2m,wind_direction_10m&timeformat=unixtime&timezone=GMT"
        currentCoroutineContext().ensureActive()
        val connection = URL("$endpoint?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000; connection.readTimeout = 8_000
        try {
            check(connection.responseCode == 200) { "Weather service unavailable" }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(512 * 1024 + 1)
                var count = 0
                while (count < chars.size) {
                    val read = reader.read(chars, count, chars.size-count)
                    if (read < 0) break
                    count += read
                }
                check(count < chars.size) { "Weather response too large" }
                String(chars, 0, count)
            }
            currentCoroutineContext().ensureActive()
            parse(place, points, body)
        } finally { connection.disconnect() }
    }
    companion object {
        fun parse(center: WeatherPlace, points: List<WeatherPlace>, body: String): WeatherSnapshot {
            val array = JSONArray(body)
            check(array.length() == points.size) { "Incomplete weather grid" }
            val readings = points.mapIndexed { index, point ->
                val response = array.getJSONObject(index)
                // Provider returns a nearby model grid cell, not necessarily the requested exact coordinate.
                val returned = WeatherPlace(response.getDouble("latitude"), response.getDouble("longitude"))
                check(point.distance(returned) < 2) { "Weather location mismatch" }
                val value = response.getJSONObject("current")
                val cover = value.getInt("cloud_cover")
                val temp = value.getDouble("temperature_2m")
                val wind = value.getDouble("wind_direction_10m")
                check(cover in 0..100 && temp.isFinite() && temp in -120.0..80.0 && wind.isFinite() && wind in 0.0..360.0)
                WeatherReading(point, Instant.ofEpochSecond(value.getLong("time")), value.getInt("weather_code"), cover, temp, wind)
            }
            return WeatherSnapshot(center, readings)
        }
    }
}

/** Main-thread owner. View changes settle before fetching; disabled/background sessions never poll. */
internal class WeatherSession(private val scope: CoroutineScope, private val source: WeatherSource,
    private val render: suspend (WeatherSnapshot?) -> Unit, private val publish: (WeatherUi) -> Unit,
    private val now: () -> Instant = Instant::now, private val millis: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var enabled = false
    private var active = false
    private var place: WeatherPlace? = null
    private var job: Job? = null
    private var lastRequest = Long.MIN_VALUE / 2
    private val cache = ArrayDeque<WeatherSnapshot>()
    private var shown: WeatherSnapshot? = null
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        restart()
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        restart()
    }
    fun view(value: WeatherPlace) {
        if (place?.distance(value)?.let { it < 8 } == true) return
        place = value
        if (enabled) restart()
    }
    fun refresh() { if (enabled && active) restart(force = true) }
    private fun restart(force: Boolean = false) {
        job?.cancel()
        val target = place
        if (!enabled) {
            shown = null
            publish(WeatherUi(place = target))
            job = scope.launch { render(null) }
            return
        }
        if (!active || target == null) {
            publish(WeatherUi(true, target, "Waiting for Earth view", shown?.central, shown?.let { !it.fresh(now()) } ?: false))
            return
        }
        job = scope.launch {
            if (shown?.center?.distance(target)?.let { it >= 8 } == true) {
                render(null); shown = null
            }
            val cached = if (force) null else cache.lastOrNull { it.center.distance(target) < 8 && it.fresh(now()) }
            if (cached != null) {
                render(cached); shown = cached
                publish(WeatherUi(true, target, "Current model weather", cached.central))
                delay(((cached.time.plusSeconds(15*60).toEpochMilli() - now().toEpochMilli()).coerceIn(1_000, REFRESH_MS)))
            } else delay(1_000)
            while (true) {
                publish(WeatherUi(true, target, "Updating weather…", shown?.central, shown?.let { !it.fresh(now()) } ?: false))
                delay((5_000 - (millis() - lastRequest)).coerceAtLeast(0))
                try {
                    lastRequest = millis()
                    val snapshot = source.fetch(target)
                    check(snapshot.fresh(now())) { "Weather data is not current" }
                    currentCoroutineContext().ensureActive()
                    render(snapshot)
                    shown = snapshot
                    cache.addLast(snapshot); while (cache.size > 4) cache.removeFirst()
                    publish(WeatherUi(true, target, "Current model weather", snapshot.central))
                    delay(REFRESH_MS)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // Never surface provider URLs, coordinates, response bodies, or exception messages in logs.
                    publish(WeatherUi(true, target, if (shown == null) "Weather unavailable · original cloud map" else "Offline · last update shown", shown?.central, true))
                    delay(60_000)
                }
            }
        }
    }
    companion object { const val REFRESH_MS = 15 * 60 * 1_000L }
}
