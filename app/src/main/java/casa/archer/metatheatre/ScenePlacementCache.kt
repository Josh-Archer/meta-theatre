package casa.archer.metatheatre

import com.meta.spatial.core.Pose

/** Exact scalar snapshots retain tracking precision without per-frame copy allocations. */
internal class ScenePlacementCache(initialScale: Float) {
    private var initialized = false
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var qw = 0f
    private var qx = 0f
    private var qy = 0f
    private var qz = 0f
    private var scale = initialScale

    fun updatePose(next: Pose): Boolean {
        if (initialized && x == next.t.x && y == next.t.y && z == next.t.z &&
            qw == next.q.w && qx == next.q.x && qy == next.q.y && qz == next.q.z) return false
        initialized = true
        x = next.t.x; y = next.t.y; z = next.t.z
        qw = next.q.w; qx = next.q.x; qy = next.q.y; qz = next.q.z
        return true
    }

    fun updateScale(next: Float): Boolean {
        if (scale == next) return false
        scale = next
        return true
    }
}
