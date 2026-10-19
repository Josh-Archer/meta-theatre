package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId

internal enum class EarthControl(val label: String) {
    NORTH_POLE("North Pole"), SOUTH_POLE("South Pole"), EQUATOR("Equator"), FAR_SIDE("Far side"),
    ORBIT_LEFT("Orbit left"), ORBIT_RIGHT("Orbit right"), ORBIT_UP("Orbit north"), ORBIT_DOWN("Orbit south"),
    SPIN_LEFT("Spin left"), SPIN_RIGHT("Spin right"), HOME("Home seat"), DONE("Done")
}

internal object EarthControls {
    const val HEADER = .2f
    const val ROW_HEIGHT = .2f
    val rows = EarthControl.entries.chunked(4)
    fun at(x: Float, y: Float): EarthControl? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x >= 1f || y < HEADER || y >= .8f) return null
        return rows[((y - HEADER) / ROW_HEIGHT).toInt()][(x * 4).toInt()]
    }
}

/** Geographic observer frame; rebase the environment so the seated view stays upright. */
internal class EarthExploration {
    var latitude = 0f; private set
    var longitude = 0f; private set
    var spin = 0f; private set
    val distance = (EarthRenderer.SEAT_EYE - EarthRenderer.CENTER).length()
    var worldFrame = Pose(); private set
    // Distant exhibit slots and the civil-time Sun follow the seated view, not the globe's latitude.
    var skyFrame = Pose(); private set

    fun apply(control: EarthControl, viewer: Pose) {
        when (control) {
            EarthControl.NORTH_POLE -> { latitude = 90f; longitude = 0f }
            EarthControl.SOUTH_POLE -> { latitude = -90f; longitude = 0f }
            EarthControl.EQUATOR -> { latitude = 0f; longitude = 0f }
            EarthControl.FAR_SIDE -> { latitude = 0f; longitude = 180f }
            EarthControl.ORBIT_LEFT -> longitude = wrap(longitude - 20f)
            EarthControl.ORBIT_RIGHT -> longitude = wrap(longitude + 20f)
            EarthControl.ORBIT_UP -> latitude = (latitude + 15f).coerceAtMost(90f)
            EarthControl.ORBIT_DOWN -> latitude = (latitude - 15f).coerceAtLeast(-90f)
            EarthControl.SPIN_LEFT -> { spinBy(-15f * screenSpinDirection(viewer)); return }
            EarthControl.SPIN_RIGHT -> { spinBy(15f * screenSpinDirection(viewer)); return }
            EarthControl.DONE -> return
            EarthControl.HOME -> { latitude = 0f; longitude = 0f; spin = 0f; worldFrame = Pose(); skyFrame = Pose(); return }
        }
        val yaw = viewer.q.removePitchAndRoll()
        val anchor = Pose(viewer.t - yaw * EarthRenderer.SEAT_EYE, yaw)
        skyFrame = anchor
        worldFrame = anchor * observerFrame()
    }

    fun spinBy(degrees: Float) { if (degrees.isFinite()) spin = wrap(spin + degrees) }
    fun restore(preset: ViewingPreset, viewer: Pose) {
        require(preset.valid())
        latitude = preset.latitude; longitude = preset.longitude; spin = preset.spin
        val yaw = viewer.q.removePitchAndRoll()
        skyFrame = Pose(viewer.t - yaw * EarthRenderer.SEAT_EYE, yaw)
        worldFrame = skyFrame * observerFrame()
    }
    fun earthPose() = renderPose(worldFrame, spin)
    fun sunPosition(instant: Instant, zone: ZoneId) = skyFrame * SolarSystem.sunPosition(instant, zone)
    private fun screenSpinDirection(viewer: Pose): Float {
        val earth = earthPose()
        val upperSurface = ((viewer.t - earth.t).normalize() + Vector3(0f, .5f, 0f)).normalize()
        return EarthSpinGesture.direction(earth, upperSurface, viewer.q.removePitchAndRoll() * Vector3(1f, 0f, 0f))
    }

    fun observer(): Vector3 = EarthRenderer.CENTER + EarthRenderer.orientation() * normal() * distance
    private fun normal(): Vector3 {
        val lat = Math.toRadians(latitude.toDouble()); val lon = Math.toRadians(longitude.toDouble())
        return Vector3((cos(lat) * sin(lon)).toFloat(), sin(lat).toFloat(), (cos(lat) * cos(lon)).toFloat())
    }
    private fun observerFrame(): Pose {
        if (latitude == 0f && longitude == 0f) return Pose()
        val lat = Math.toRadians(latitude.toDouble()); val lon = Math.toRadians(longitude.toDouble())
        // Geographic north tangent stays well-defined at the poles; world-up would be singular.
        val north = EarthRenderer.orientation() * Vector3((-sin(lat) * sin(lon)).toFloat(),
            cos(lat).toFloat(), (-sin(lat) * cos(lon)).toFloat())
        val eye = observer()
        val from = Quaternion.fromDirection(EarthRenderer.CENTER - eye, north)
        val to = Quaternion.fromDirection(EarthRenderer.CENTER - EarthRenderer.SEAT_EYE, Vector3(0f, 1f, 0f))
        val rotation = (to * from.inverse()).normalize()
        return Pose(EarthRenderer.SEAT_EYE - rotation * eye, rotation)
    }
    fun label(): String = when {
        latitude == 90f -> "North Pole"
        latitude == -90f -> "South Pole"
        latitude == 0f && longitude == 0f -> "Equator"
        latitude == 0f && kotlin.math.abs(longitude) == 180f -> "Far side"
        else -> "${kotlin.math.abs(latitude).roundToInt()}° ${if (latitude < 0f) "S" else "N"} · ${longitude.roundToInt()}° around"
    }
    private fun wrap(degrees: Float) = ((degrees + 180f) % 360f + 360f) % 360f - 180f
    companion object {
        fun renderPose(frame: Pose, spin: Float) = Pose(frame * EarthRenderer.CENTER,
            frame.q * EarthRenderer.orientation() * Quaternion(pitch = 0f, yaw = spin, roll = 0f))
    }
}
