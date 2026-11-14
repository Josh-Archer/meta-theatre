package casa.archer.metatheatre

import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Mean-radius scale is shared by every body. Distances and sky layout are an artistic exhibit. */
object SolarSystem {
    const val EARTH_MEAN_RADIUS_KM = 6371.0084
    const val METRES_PER_KM = 90.0 / EARTH_MEAN_RADIUS_KM
    // One astronomical unit at the same mean-radius scale as the planets.
    const val SUN_DISTANCE = (149_597_870.7 * METRES_PER_KM).toFloat()
    const val SUN_RENDER_DISTANCE = 80_000f
    // Preserve the distant sky while giving thin vehicle surfaces three times
    // the depth separation of the old 5 cm near plane. Panels stay >= 1.5 m away.
    const val NEAR_CLIP = 0.15f
    const val FAR_CLIP = 120_000f
    const val STAR_RADIUS = 110_000f
    const val RING_INNER = 74_500f / 58_232f
    const val RING_OUTER = 136_780f / 58_232f

    data class Body(
        val name: String,
        val radiusKm: Double,
        val center: Vector3,
        val rotation: Quaternion = Quaternion(),
    ) {
        val radius: Float get() = (radiusKm * METRES_PER_KM).toFloat()
    }

    private fun direction(yaw: Double, elevation: Double): Vector3 {
        val y = Math.toRadians(yaw)
        val e = Math.toRadians(elevation)
        return Vector3((sin(y) * cos(e)).toFloat(), sin(e).toFloat(), (cos(y) * cos(e)).toFloat())
    }

    private fun body(name: String, radius: Double, distance: Float, yaw: Double, elevation: Double,
        roll: Float = 0f, viewingPitch: Float = 0f): Body {
        val center = EarthRenderer.SEAT_EYE + direction(yaw, elevation) * distance
        // Front UV meridian faces the seat. Viewing rotations are exhibit composition, not an ecliptic model.
        val facing = Quaternion.fromDirection(EarthRenderer.SEAT_EYE - center, Vector3(0f, 1f, 0f))
        return Body(name, radius, center, facing * Quaternion(pitch = viewingPitch, yaw = 0f, roll = roll))
    }

    val earth = Body("earth", EARTH_MEAN_RADIUS_KM, EarthRenderer.CENTER, EarthRenderer.orientation())
    val planets = listOf(
        body("mercury", 2439.4, 400f, -65.0, 16.0),
        body("venus", 6051.8, 650f, -34.0, 32.0),
        earth,
        body("mars", 3389.5, 480f, 48.0, 23.0, 25.2f),
        body("jupiter", 69911.0, 5200f, 97.0, 8.0, 3.1f),
        body("saturn", 58232.0, 7600f, -112.0, 22.0, roll = -18f, viewingPitch = 26.7f),
        body("uranus", 25362.0, 6500f, 148.0, 34.0, 97.8f),
        body("neptune", 24622.0, 7000f, -160.0, -10.0, 28.3f),
    )
    val moon = body("moon", 1737.4, 300f, 20.0, 40.0, 6.7f)
    val sunRadius = (695700.0 * METRES_PER_KM).toFloat()

    /** Phase is astronomical; sky placement follows the exhibit Sun, with finite-distance correction. */
    fun moonAt(instant: Instant, sun: Vector3): Body {
        val phase = LunarPhase.at(instant)
        val sunVector = sun - EarthRenderer.SEAT_EYE
        val distance = sunVector.length().toDouble()
        val towardSun = sunVector.normalize()
        // A stable orbital plane: the tangent flips between waxing and waning.
        val reference = if (kotlin.math.abs(towardSun.y) < 0.95f) Vector3(0f, 1f, 0f) else Vector3(0f, 0f, 1f)
        val tangent = reference.cross(towardSun).normalize() * if (phase.waxing) 1f else -1f
        val moonDistance = 300.0
        val target = 2 * phase.fraction - 1
        var low = 0.0; var high = PI
        repeat(40) {
            val angle = (low + high) / 2
            val incidence = (moonDistance - distance * cos(angle)) /
                sqrt(distance * distance + moonDistance * moonDistance - 2 * distance * moonDistance * cos(angle))
            if (incidence < target) low = angle else high = angle
        }
        val angle = (low + high) / 2
        val center = EarthRenderer.SEAT_EYE + (towardSun * cos(angle).toFloat() + tangent * sin(angle).toFloat()) * moonDistance.toFloat()
        return moon.copy(center = center, rotation = Quaternion.fromDirection(EarthRenderer.SEAT_EYE - center, Vector3(0f, 1f, 0f)) *
            Quaternion(pitch = 0f, yaw = 0f, roll = 6.7f))
    }

    fun sunPosition(instant: Instant, zone: ZoneId): Vector3 {
        val sun = EarthLighting.sunAt(instant, zone)
        return earth.center + earth.rotation * Vector3(sun.x, sun.y, sun.z) * SUN_DISTANCE
    }

    /** Project the distant Sun inside the sky without changing its direction or angular size. */
    fun sunDisplayPosition(position: Vector3): Vector3 =
        EarthRenderer.SEAT_EYE + (position - EarthRenderer.SEAT_EYE).normalize() * SUN_RENDER_DISTANCE

    fun sunDisplayRadius(position: Vector3): Float =
        sunRadius * SUN_RENDER_DISTANCE / (position - EarthRenderer.SEAT_EYE).length()

    /** GraphicsHSR's shader world is right-handed; custom attributes bypass the API's Z reflection. */
    fun shaderSunPosition(position: Vector3): Vector4 = Vector4(position.x, position.y, -position.z, 1f)

    /** A body uses this point in its unit-radius mesh coordinates, including its own axial tilt. */
    fun localSun(body: Body, sunPosition: Vector3): Vector3 =
        body.rotation.inverse() * (sunPosition - body.center) / body.radius
}
