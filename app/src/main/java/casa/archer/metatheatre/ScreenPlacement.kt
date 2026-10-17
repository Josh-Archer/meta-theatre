package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3

internal object ScreenPlacement {
    fun shiftDepth(viewer: Pose, current: Pose, meters: Float): Pose {
        if (!meters.isFinite()) return current
        val offset = current.t - viewer.t
        val distance = offset.length()
        if (distance < .01f) return current
        val next = (distance + meters).coerceIn(1.5f, 6f)
        return Pose(viewer.t + offset * (next / distance), current.q.copy())
    }

    /** Restore in the current view after a turn or step; keep the screen upright. */
    fun restore(viewer: Pose, current: Pose): Pose {
        val gaze = viewer.forward()
        val horizontal = Vector3(gaze.x, 0f, gaze.z)
        val direction = if (horizontal.length() > .01f) horizontal.normalize() else {
            // Looking vertically has no reliable yaw. Use the previous screen's horizontal direction.
            val previous = current.t - viewer.t
            val fallback = Vector3(previous.x, 0f, previous.z)
            if (fallback.length() > .01f) fallback.normalize() else Vector3(0f, 0f, 1f)
        }
        val distance = (current.t - viewer.t).length().coerceIn(1.5f, 6f)
        val height = (current.t.y - viewer.t.y).coerceIn(-.6f, .3f)
        return Pose(viewer.t + direction * distance + Vector3(0f, height, 0f),
            Quaternion.fromDirection(direction * -1f, Vector3(0f, 1f, 0f)))
    }
}
