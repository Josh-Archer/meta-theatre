package casa.archer.metatheatre

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Local civil time drives an artistic, equatorial 24-hour solar cycle. */
object EarthLighting {
    const val CENTER_LONGITUDE = -100.0
    const val TWILIGHT_WIDTH = 0.08
    data class Sun(val x: Float, val y: Float, val z: Float)

    fun sunAt(instant: Instant, zone: ZoneId): Sun {
        val time = instant.atZone(zone).toLocalTime()
        val hours = time.toNanoOfDay().toDouble() / 3_600_000_000_000.0
        val angle = (hours - 12.0) * PI / 12.0
        // Asset: +Z = equator beneath USA, +Y = north, +X = geographic east.
        return Sun((-sin(angle)).toFloat(), 0f, cos(angle).toFloat())
    }

    fun dayWeight(solarElevation: Double): Double = smoothstep(-TWILIGHT_WIDTH, TWILIGHT_WIDTH, solarElevation)
    fun cityWeight(solarElevation: Double): Double = 1.0 - smoothstep(-0.12, 0.02, solarElevation)

    private fun smoothstep(low: Double, high: Double, value: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
