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
        return palms.map { OpenHandInput(it.side, coordinates(it.position), coordinates(it.normal),
            it.side in aimedSides, framed) }
    }
    private fun vector(p: HandPoint) = Vector3(p.x, p.y, p.z)
}
