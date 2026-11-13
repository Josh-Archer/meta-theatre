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
internal class EarthExploration(private val now: () -> Instant = Instant::now) {
    var latitude = 0f; private set
    var longitude = 0f; private set
    var spin = 0f; private set
    var zoom = 1f; private set
    private var nearClearance = NEAR_CLEARANCE
    private val canonicalDistance get() = BASE_DISTANCE / zoom
    // Expand the planet for close inspection instead of pushing it through the screen.
    val renderScale get() = maxOf(1f, nearClearance / (canonicalDistance - EarthRenderer.RADIUS * SOLID_RADIUS))
    val renderRadius get() = EarthRenderer.RADIUS * renderScale
    val distance get() = canonicalDistance * renderScale
    val detailZoom get() = (BASE_DISTANCE - EarthRenderer.RADIUS) / (canonicalDistance - EarthRenderer.RADIUS)
    var realRotation = false; private set
    private var rotationAnchor = now()
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
            EarthControl.HOME -> {
                latitude = 0f; longitude = 0f; spin = 0f; zoom = 1f; rotationAnchor = now()
                worldFrame = Pose(); skyFrame = Pose(); return
            }
        }
        rebase(viewer)
    }

    private fun rebase(viewer: Pose) {
        val yaw = viewer.q.removePitchAndRoll()
        val anchor = Pose(viewer.t - yaw * EarthRenderer.SEAT_EYE, yaw)
        skyFrame = anchor
        worldFrame = anchor * observerFrame()
    }

    fun spinBy(degrees: Float) { if (degrees.isFinite()) spin = wrap(spin + degrees) }
    fun spinAt(instant: Instant = now()): Float = wrap(spin - if (realRotation) RealEarthTime.rotationSince(rotationAnchor, instant) else 0f)
    fun setRealRotation(enabled: Boolean, instant: Instant = now()) {
        if (enabled == realRotation) return
        spin = spinAt(instant)
        rotationAnchor = instant
        realRotation = enabled
    }
    fun zoomBy(factor: Float, viewer: Pose, nearClearance: Float = NEAR_CLEARANCE) {
        if (!factor.isFinite() || factor <= 0) return
        val center = earthPose().t
        val direction = center - viewer.t
        if (direction.length() < .01f) return
        this.nearClearance = maxOf(NEAR_CLEARANCE, nearClearance)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val target = viewer.t + direction.normalize() * distance
        worldFrame = Pose(worldFrame.t + target - center, worldFrame.q.copy())
    }
    fun zoomDetailBy(factor: Float, viewer: Pose, nearClearance: Float = NEAR_CLEARANCE) {
        if (!factor.isFinite() || factor <= 0) return
        val targetDistance = EarthRenderer.RADIUS + (canonicalDistance - EarthRenderer.RADIUS) / factor
        zoomBy(canonicalDistance / targetDistance, viewer, nearClearance)
    }
    fun ensureClearance(viewer: Pose, nearClearance: Float): Boolean {
        // Avoid reapplying the same boundary transform every frame when float
        // round-off puts the already-clamped distance a few micrometres short.
        if ((earthPose().t-viewer.t).length() + .001f >= renderRadius*SOLID_RADIUS + maxOf(NEAR_CLEARANCE,nearClearance)) return false
        zoomBy(1f,viewer,nearClearance)
        return true
    }
    fun resetZoom(viewer: Pose, nearClearance: Float = NEAR_CLEARANCE) { zoomBy(1f / zoom, viewer, nearClearance) }
    fun viewLocation(place: WeatherPlace, viewer: Pose, instant: Instant = now()) {
        latitude = place.latitude.toFloat()
        // The SDK's imported glTF globe reflects source X: geographic east is
        // native -X. Verified against actual on-device surface-hit UVs.
        longitude = wrap((EarthLighting.CENTER_LONGITUDE - place.longitude).toFloat() + spinAt(instant))
        rebase(viewer)
    }
    fun restore(preset: ViewingPreset, viewer: Pose) {
        require(preset.valid())
        latitude = preset.latitude; longitude = preset.longitude; spin = preset.spin; zoom = preset.zoom
        rotationAnchor = now()
        rebase(viewer)
    }
    fun earthPose(instant: Instant = now()) = renderPose(worldFrame, spinAt(instant))
    fun sunPosition(instant: Instant, zone: ZoneId): Vector3 = if (!realRotation)
        skyFrame * SolarSystem.sunPosition(instant, zone)
    else earthPose(instant).let { it.t + it.q * RealEarthTime.localSun(instant) * SolarSystem.SUN_DISTANCE }
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
        if (latitude == 0f && longitude == 0f && zoom == 1f) return Pose()
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
        val BASE_DISTANCE = (EarthRenderer.SEAT_EYE - EarthRenderer.CENTER).length()
        const val MIN_ZOOM = .75f
        // Nearby flybys move around the limb; they no longer dictate Earth viewing distance.
        const val NEAR_CLEARANCE = 6f
        // Cloud deck is 1.0035 R. Stay outside it and preserve a physical head/screen gap.
        const val SOLID_RADIUS = 1.004f
        val MAX_ZOOM = BASE_DISTANCE / (EarthRenderer.RADIUS * SOLID_RADIUS + .2f)
        fun renderPose(frame: Pose, spin: Float) = Pose(frame * EarthRenderer.CENTER,
            frame.q * EarthRenderer.orientation() * Quaternion(pitch = 0f, yaw = spin, roll = 0f))
    }
}
