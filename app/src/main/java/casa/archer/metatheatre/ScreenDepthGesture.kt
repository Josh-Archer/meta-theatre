package casa.archer.metatheatre

import kotlin.math.abs

/** Both palms must travel together along screen depth; a clap or pinch cannot move depth. */
internal class ScreenDepthGesture {
    data class Action(val owns: Boolean = false, val meters: Float = 0f)
    private data class PairStroke(val hands: List<OpenHandInput>, val direction: Int,
                                  var previous: List<OpenHandInput>, var sampled: Long, var moved: Boolean = false)
    private var stroke: PairStroke? = null
    fun reset() { stroke = null }

    fun update(hands: List<OpenHandInput>, now: Long): Action {
        val sorted = hands.sortedBy { it.id }
        val direction = when {
            sorted.size != 2 || sorted.any { !it.raisedInView || !it.position.finite() } -> 0
            sorted.all { it.viewerFacing < -.5f } -> 1 // Palms away: push.
            sorted.all { it.viewerFacing > .5f } -> -1 // Palms toward the viewer: pull.
            else -> 0
        }
        if (direction == 0 || (sorted[1].position - sorted[0].position).length() !in .18f..1.1f) {
            reset(); return Action()
        }
        var active = stroke
        if (active == null || active.direction != direction || active.hands.map { it.id } != sorted.map { it.id } || now - active.sampled > 500L) {
            active = PairStroke(sorted, direction, sorted, now)
            stroke = active
        }
        val steps = sorted.zip(active.previous) { hand, old -> hand.viewPosition - old.viewPosition }
        if (steps.any { it.length() > .20f }) { reset(); return Action(owns = true) }
        active.previous = sorted
        active.sampled = now
        val travel = sorted.zip(active.hands) { hand, start -> hand.viewPosition - start.viewPosition }
        // Require movement from both hands and reject lateral motion and opposed depth strokes.
        if (!active.moved && travel.all { -it.z * direction >= .055f && abs(it.z) > abs(it.x) * 1.3f && abs(it.z) > abs(it.y) * 1.3f }) {
            active.moved = true
            return Action(true, -travel.map { it.z }.average().toFloat() * GAIN)
        }
        if (!active.moved || steps.any { -it.z * direction < -.008f }) return Action(owns = true)
        return Action(true, (-steps.map { it.z }.average().toFloat() * GAIN).coerceIn(-.20f, .20f))
    }

    companion object { const val GAIN = 4f }
}

/** Pause is an explicit command, never a toggle. A held stop palm fires once. */
internal class StopPalmGesture {
    private var id: Int? = null
    private var origin = HandPoint.ZERO
    private var since = 0L
    private var latched = false
    fun reset() { id = null; latched = false }
    fun update(hands: List<OpenHandInput>, now: Long): Boolean {
        val hand = hands.filter { it.raisedInView }.singleOrNull()?.takeIf { it.viewerFacing < -.55f && it.position.finite() }
        if (hand == null) { reset(); return false }
        if (id != hand.id || (hand.position - origin).length() > .06f) {
            id = hand.id; origin = hand.position; since = now
            return false
        }
        if (latched || now - since < 500L) return false
        latched = true
        return true
    }
}
