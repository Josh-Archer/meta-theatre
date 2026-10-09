package casa.archer.metatheatre

import kotlin.math.abs
import kotlin.math.sqrt

internal data class HandPoint(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: HandPoint) = HandPoint(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = HandPoint(x * scale, y * scale, z * scale)
    fun dot(other: HandPoint) = x * other.x + y * other.y + z * other.z
    fun length() = sqrt(dot(this))
    fun unit() = length().let { if (it > 0.0001f) this * (1f / it) else ZERO }
    fun cross(other: HandPoint) = HandPoint(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    companion object { val ZERO = HandPoint(0f, 0f, 0f) }
}

internal data class HandJoint(val point: HandPoint, val flags: Int)
internal data class OpenPalm(val side: Int, val position: HandPoint, val normal: HandPoint)

/** Full finger extension is required; an unpressed pinch alone does not mean an open hand. */
internal object OpenHandPose {
    fun read(side: Int, joints: List<HandJoint>): OpenPalm? {
        if (joints.size != 26 || joints.any { it.flags and 2 == 0 || !it.point.finite() } || joints[0].flags and 8 == 0) return null
        for (start in intArrayOf(7, 12, 17, 22)) {
            val chain = (start..start + 3).map { joints[it].point }
            val segments = chain.zipWithNext { a, b -> b - a }
            val length = segments.sumOf { it.length().toDouble() }.toFloat()
            if (length < 0.025f || (chain.last() - chain.first()).length() / length < 0.88f ||
                segments.zipWithNext().any { (a, b) -> a.unit().dot(b.unit()) < 0.65f }) return null
        }
        // A folded thumb or a thumb/index pinch is excluded as well.
        val thumb = (joints[5].point - joints[3].point).length()
        val thumbPath = (joints[4].point - joints[3].point).length() + (joints[5].point - joints[4].point).length()
        if (thumbPath < 0.02f || thumb / thumbPath < 0.8f || (joints[5].point - joints[10].point).length() < 0.035f) return null
        val wrist = joints[1].point
        val normal = (joints[6].point - wrist).cross(joints[21].point - wrist).unit() * if (side == 0) 1f else -1f
        if (normal.length() < 0.9f) return null
        return OpenPalm(side, joints[0].point, normal)
    }
}

internal data class OpenHandInput(val id: Int, val position: HandPoint, val normal: HandPoint, val aimingAtScreen: Boolean)
internal enum class ScreenDirection { LEFT, RIGHT, UP, DOWN }
internal data class OpenHandActions(val move: ScreenDirection? = null, val close: Boolean = false, val restore: Boolean = false)

/** A deliberate open-hand stroke owns one action. Two closing palms take priority over swipes. */
internal class OpenHandGestures {
    private data class Stroke(var origin: HandPoint, var started: Long, var previous: HandPoint, var stillSince: Long, var consumed: Boolean = false)
    private data class Clap(val a: OpenHandInput, val b: OpenHandInput, val started: Long)
    private val strokes = mutableMapOf<Int, Stroke>()
    private var clap: Clap? = null
    private var spread: Clap? = null
    private var blockedUntil = 0L
    private var restoreBlockedUntil = 0L
    private var clapLatched = false

    fun reset() { strokes.clear(); clap = null; spread = null; clapLatched = false; blockedUntil = 0L; restoreBlockedUntil = 0L }

    fun update(hands: List<OpenHandInput>, watching: Boolean, pinchingOrGripping: Boolean, now: Long,
               screenHidden: Boolean = false): OpenHandActions {
        if (pinchingOrGripping) {
            strokes.clear(); clap = null; spread = null; blockedUntil = now + 400L
            restoreBlockedUntil = now + 400L
            return OpenHandActions()
        }
        if (screenHidden) {
            strokes.clear(); clap = null
            if (now < restoreBlockedUntil) { spread = null; return OpenHandActions() }
            if (hands.size != 2) { spread = null; return OpenHandActions() }
            val a = hands[0]; val b = hands[1]
            val separation = (b.position - a.position).length()
            val facing = facing(a, b)
            // No ray target is required to restore an invisible screen.
            if (spread == null && separation <= 0.20f && facing) spread = Clap(a, b, now)
            val start = spread ?: return OpenHandActions()
            val outward = (start.b.position - start.a.position).unit()
            val aTravel = (a.position - start.a.position).dot(outward * -1f)
            val bTravel = (b.position - start.b.position).dot(outward)
            val elapsed = now - start.started
            if (elapsed in 80L..900L && separation >= 0.40f && aTravel >= 0.08f && bTravel >= 0.08f && facing) {
                spread = null; blockedUntil = now + 1200L
                return OpenHandActions(restore = true)
            }
            if (elapsed > 900L || !facing || separation < 0.03f) spread = null
            return OpenHandActions()
        }
        spread = null
        if (now < blockedUntil) return OpenHandActions()
        val tracked = hands.associateBy { it.id }
        strokes.keys.retainAll(tracked.keys)
        if (hands.size == 2 && watching) {
            val a = hands[0]; val b = hands[1]
            val separation = (b.position - a.position).length()
            if (separation >= 0.30f) clapLatched = false
            val facing = facing(a, b)
            if (clap == null && !clapLatched && separation >= 0.30f && facing && a.aimingAtScreen && b.aimingAtScreen) {
                clap = Clap(a, b, now)
            }
            val start = clap
            if (start != null) {
                val initial = (start.b.position - start.a.position).length()
                val inward = (start.b.position - start.a.position).unit()
                val aTravel = (a.position - start.a.position).dot(inward)
                val bTravel = (b.position - start.b.position).dot(inward * -1f)
                val elapsed = now - start.started
                if (elapsed in 80L..900L && separation <= 0.14f && aTravel >= 0.065f && bTravel >= 0.065f &&
                    (initial - separation) / (elapsed / 1000f) >= 0.25f && facing) {
                    strokes.clear(); clap = null; clapLatched = true; blockedUntil = now + 1200L
                    return OpenHandActions(close = true)
                }
                if (elapsed > 900L || !facing || initial - separation < -0.08f) clap = null
                // Suppress individual strokes only while both palms are actually moving inward.
                if (aTravel > 0.025f && bTravel > 0.025f) { strokes.clear(); return OpenHandActions() }
            }
        } else clap = null
        var move: ScreenDirection? = null
        for (hand in hands) {
            val stroke = strokes[hand.id] ?: if (hand.aimingAtScreen) Stroke(hand.position, now, hand.position, now).also { strokes[hand.id] = it } else continue
            if ((hand.position - stroke.previous).length() > 0.008f) stroke.stillSince = now
            stroke.previous = hand.position
            val elapsed = now - stroke.started
            if (stroke.consumed || elapsed > 750L) {
                if (now - stroke.stillSince >= 250L && hand.aimingAtScreen) {
                    stroke.origin = hand.position; stroke.started = now; stroke.consumed = false
                }
                continue
            }
            val travel = hand.position - stroke.origin
            val horizontal = abs(travel.x) >= abs(travel.y)
            val dominant = if (horizontal) travel.x else travel.y
            val other = if (horizontal) travel.y else travel.x
            if (elapsed in 100L..750L && abs(dominant) >= 0.16f && abs(dominant) > abs(other) * 1.5f &&
                abs(dominant) > abs(travel.z) * 1.5f && abs(dominant) / (elapsed / 1000f) >= 0.25f) {
                move = if (horizontal) { if (dominant > 0f) ScreenDirection.RIGHT else ScreenDirection.LEFT }
                    else { if (dominant > 0f) ScreenDirection.UP else ScreenDirection.DOWN }
                stroke.consumed = true
                blockedUntil = now + 350L
                break
            }
        }
        return OpenHandActions(move = move)
    }

    private fun facing(a: OpenHandInput, b: OpenHandInput): Boolean {
        val separation = (b.position - a.position).unit()
        return a.normal.dot(b.normal) < -0.4f && abs(a.normal.dot(separation)) > 0.45f && abs(b.normal.dot(separation)) > 0.45f
    }
}
