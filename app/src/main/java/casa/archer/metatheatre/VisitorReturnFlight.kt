package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/** Return to the current flyby pose, using the same paused clock as orbital movement. */
internal class VisitorReturnFlight(private val start: VisitorTarget, private val velocity: Vector3,
                                   private val started: Long) {
    fun finished(now: Long) = now - started >= DURATION_MS
    fun frame(orbit: VisitorTarget, now: Long): VisitorTarget {
        val t = ((now - started) / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        if (t == 1f) return orbit
        val eased = t * t * (3f - 2f * t)
        val toss = velocity * (.25f * t * (1f - t))
        return start.copy(pose = Pose(start.pose.t + (orbit.pose.t - start.pose.t) * eased + toss,
            start.pose.q.slerp(orbit.pose.q, eased)), scale = start.scale + (orbit.scale - start.scale) * eased)
    }
    companion object { const val DURATION_MS = 1200L }
}
