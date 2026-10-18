package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/** Facing palms do not point their SDK rays at the screen. Frame the pair using its midpoint. */
internal object OpenHandFrame {
    fun project(palms: List<OpenPalm>, screen: Pose, viewer: Pose, aimedSides: Set<Int>): List<OpenHandInput> {
        val middle = palms.takeIf { it.size == 2 }?.let {
            (vector(it[0].position) + vector(it[1].position)) * .5f
        }
        val framed = middle?.let {
            val delta = it - viewer.t
            val towardScreen = (screen.t - viewer.t).normalize()
            delta.length() in .15f..1.6f && delta.normalize().dot(towardScreen) > .55f
        } ?: false
        fun coordinates(point: HandPoint): HandPoint {
            val v = vector(point)
            return HandPoint(v.dot(screen.right() * -1f), v.dot(screen.up()), v.dot(screen.forward()))
        }
        return palms.map {
            val delta = vector(it.position) - viewer.t
            val towardEye = delta.normalize() * -1f
            val raised = delta.length() in .15f..1.6f && delta.normalize().dot(viewer.forward()) > .45f &&
                delta.dot(viewer.up()) in -0.40f..0.45f
            OpenHandInput(it.side, coordinates(it.position), coordinates(it.normal),
                it.side in aimedSides, framed, vector(it.normal).dot(towardEye), raised,
                HandPoint(delta.dot(viewer.right()), delta.dot(viewer.up()), -delta.dot(viewer.forward())))
        }
    }
    private fun vector(p: HandPoint) = Vector3(p.x, p.y, p.z)
}
