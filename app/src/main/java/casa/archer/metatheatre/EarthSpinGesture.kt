package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

internal data class EarthSpinInput(val owned: Set<Long> = emptySet(), val hover: VisitorHit? = null, val degrees: Float = 0f)

/** A drag spins geography around its north axis; it cannot select a title on release. */
internal class EarthSpinGesture {
    private data class Grab(val id: Long, var position: Vector3, val right: Vector3, val direction: Float)
    private val previous = mutableMapOf<Long, Boolean>()
    private var grab: Grab? = null
    fun cancel() { grab = null } // retained button state requires a release before reacquiring
    fun update(pointers: List<VisitorPointer>, earth: VisitorTarget?, viewer: Pose, blocked: Boolean,
               visitorOwned: Set<Long>): EarthSpinInput {
        val owned = mutableSetOf<Long>()
        var degrees = 0f
        grab?.let { held ->
            owned.add(held.id)
            val hand = pointers.firstOrNull { it.id == held.id }
            if (blocked || hand == null || !hand.down || !ScenePointer.valid(hand.pose) || held.id in visitorOwned) grab = null
            else {
                val delta = hand.pose.t - held.position
                if (delta.length() <= .25f) degrees = (delta.dot(held.right) * 160f * held.direction).coerceIn(-5f, 5f)
                held.position = hand.pose.t.copy()
            }
        }
        var hover: VisitorHit? = null
        if (!blocked && earth != null) for (pointer in pointers) {
            if (pointer.id in visitorOwned) continue
            val hit = ScenePointer.hit(pointer, earth) ?: continue
            if (hover == null || hit.distance < hover.distance) hover = hit
            if (grab == null && pointer.down && previous[pointer.id] == false) {
                val right = viewer.q.removePitchAndRoll() * Vector3(1f, 0f, 0f)
                grab = Grab(pointer.id, pointer.pose.t.copy(), right, direction(earth.pose, hit.normal, right))
                owned.add(pointer.id)
            }
        }
        pointers.forEach { previous[it.id] = it.down }
        previous.keys.retainAll(pointers.map { it.id }.toSet())
        return EarthSpinInput(owned, hover, degrees)
    }
    companion object {
        /** Follow the grabbed surface; the apparent rotation reverses between polar viewpoints. */
        fun direction(earth: Pose, normal: Vector3, right: Vector3): Float =
            if ((earth.q * Vector3(0f, 1f, 0f)).cross(normal).dot(right) >= 0f) 1f else -1f
    }
}
