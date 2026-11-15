package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.atan2

internal enum class CabinSeat(val label: String, val eye: Vector3) {
    LEFT("Left couch", Vector3(-3.30f, 1.45f, -.35f)),
    CENTER("Center chair", Vector3(0f, 1.28f, 0f)),
    RIGHT("Right couch", Vector3(3.30f, 1.45f, -.35f)),
}

/** Cabin coordinates remain independent of the exterior sky and Earth frame. */
internal object CabinLayout {
    const val TURN_DEGREES = 15f
    fun heading(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    fun rotate(pose: Pose, degrees: Float): Pose {
        val q = Quaternion(pitch = 0f, yaw = degrees, roll = 0f)
        return Pose(q * pose.t, q * pose.q)
    }
    fun rotate(seat: SeatSetting, degrees: Float): SeatSetting {
        val t = rotate(Pose(Vector3(seat.x, seat.y, seat.z)), degrees).t
        return SeatSetting(t.x, t.y, t.z, heading(seat.yaw + degrees))
    }
    fun seatedOrigin(seat: CabinSeat, shipYaw: Float, origin: SeatSetting, viewer: Pose): SeatSetting {
        val target = rotate(Pose(seat.eye), shipYaw).t
        val wall = rotate(SpaceshipCabin.defaultScreenPose, shipYaw).t
        val direction = wall - target
        // setViewOrigin recenters X/Z and heading on the tracked viewer; Y is a floor offset.
        val yaw = Math.toDegrees(atan2(direction.x, direction.z).toDouble()).toFloat()
        return SeatSetting(target.x, target.y - (viewer.t.y - origin.y), target.z, heading(yaw))
    }
    fun turnedOrigin(origin: SeatSetting, viewer: Pose, degrees: Float): SeatSetting {
        val target = rotate(viewer, degrees)
        val forward = target.q * Vector3(0f, 0f, 1f)
        return SeatSetting(target.t.x, origin.y, target.t.z,
            heading(Math.toDegrees(atan2(forward.x, forward.z).toDouble()).toFloat()))
    }
}
