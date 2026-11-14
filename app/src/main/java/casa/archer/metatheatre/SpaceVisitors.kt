package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.*

/** Staged theatre flybys, not live ephemerides. The Roadster's real orbit is heliocentric. */
object SpaceVisitors {
    data class Visitor(val asset: String, val periodSeconds: Double, val durationSeconds: Double,
        val firstPassSeconds: Double, val distance: Float, val displayScale: Float, val elevation: Double,
        val reverse: Boolean)
    val iss = Visitor("iss.glb", 120.0, 85.0, 3.0, 420f, 1.35f, 20.0, false)
    val roadster = Visitor("space_roadster.glb", 150.0, 95.0, 12.0, 24f, 1.5f, 16.0, true)
    val all = listOf(iss, roadster)
    internal fun radius(visitor: Visitor) = if (visitor == iss) 70f else 2.65f

    fun progress(visitor: Visitor, elapsedSeconds: Double): Double? {
        if (!elapsedSeconds.isFinite() || elapsedSeconds < visitor.firstPassSeconds) return null
        val age = (elapsedSeconds - visitor.firstPassSeconds) % visitor.periodSeconds
        return if (age < visitor.durationSeconds) age / visitor.durationSeconds else null
    }

    /** A returned model should visibly resume flying even if its original pass has ended. */
    internal fun replayOffset(visitor: Visitor, elapsedSeconds: Double) =
        elapsedSeconds - visitor.firstPassSeconds - visitor.durationSeconds * .5

    internal fun returnOffset(visitor: Visitor, elapsedSeconds: Double, offset: Double): Double {
        val current = progress(visitor, elapsedSeconds - offset)
        return if (current == null || current > .75) replayOffset(visitor, elapsedSeconds)
            else offset
    }

    fun pose(visitor: Visitor, progress: Double): Pose {
        val p = progress.coerceIn(0.0, 1.0)
        // Keep the entire pass in front of the seat and within a comfortable upward glance.
        val yaw = Math.toRadians((-42 + p * 84) * if (visitor.reverse) -1 else 1)
        val elevation = Math.toRadians(visitor.elevation + 5 * sin(PI * p))
        val center = EarthRenderer.SEAT_EYE + Vector3(
            (sin(yaw) * cos(elevation)).toFloat(), sin(elevation).toFloat(),
            (cos(yaw) * cos(elevation)).toFloat()) * visitor.distance
        val facing = Quaternion.fromDirection(EarthRenderer.SEAT_EYE - center, Vector3(0f, 1f, 0f))
        // Slow roll exposes array cells, the open cockpit and Starman without rapid motion in VR.
        val turn = if (visitor == roadster) Quaternion(pitch = -25f, yaw = 35f, roll = (p * 22 - 11).toFloat())
            else Quaternion(pitch = -28f, yaw = (p * 35 - 17.5).toFloat(), roll = 12f)
        return Pose(center, facing * turn)
    }
}
