package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

internal data class VisitorTarget(val asset: String, val pose: Pose, val scale: Float, val radius: Float)
internal data class VisitorPointer(val id: Long, val pose: Pose, val down: Boolean, val maxDistance: Float = Float.POSITIVE_INFINITY)
internal data class VisitorHit(val asset: String, val point: Vector3, val normal: Vector3, val distance: Float)
internal data class VisitorToss(val target: VisitorTarget, val velocity: Vector3)
internal data class VisitorInputResult(val owned: Set<Long> = emptySet(), val hover: VisitorHit? = null,
                                      val returned: List<VisitorToss> = emptyList())

/** Shared by the live renderer and replay tests. A held pinch/trigger owns the whole pull. */
internal class VisitorInspection {
    private data class Grab(val driver: Long, val target: VisitorTarget, val hand: Pose,
        var pulledAt: Long? = null, var pullHand: Pose? = null, var near: Vector3? = null, var destination: VisitorTarget? = null,
        val motion: ThrowMotion = ThrowMotion())
    private class ThrowMotion {
        private data class Sample(val time: Long, val relative: Vector3)
        private val samples = mutableListOf<Sample>()
        fun record(now: Long, hand: Vector3, eye: Vector3) {
            val relative = hand - eye
            samples.lastOrNull()?.let {
                if (now <= it.time) return
                if (now - it.time > 100L || (relative - it.relative).length() > .5f) samples.clear()
            }
            samples.removeAll { now - it.time > 160L }
            samples.add(Sample(now, relative))
        }
        fun outwardVelocity(): Vector3? {
            if (samples.size < 2) return null
            val first = samples.first(); val last = samples.last()
            if (last.time - first.time < 40L) return null
            val delta = last.relative - first.relative
            val seconds = (last.time - first.time) / 1000f
            val speed = delta.length() / seconds
            if (delta.length() < .09f || speed < .6f || last.relative.length() - first.relative.length() < .05f) return null
            return delta.normalize() * speed.coerceAtMost(3f)
        }
    }
    private val previous = mutableMapOf<Long, Boolean>()
    private val parked = mutableMapOf<String, VisitorTarget>()
    private var grab: Grab? = null
    val overrides: Map<String, VisitorTarget> get() = parked

    fun reset() { grab = null; parked.clear() } // held inputs must release before capturing again
    fun remove(asset: String) {
        if (grab?.target?.asset == asset) grab = null
        parked.remove(asset)
    }
    fun cancelGrab() {
        grab?.let { if (it.pulledAt == null) parked.remove(it.target.asset) else it.destination?.let { end -> parked[it.target.asset] = end } }
        grab = null
    }

    fun update(pointers: List<VisitorPointer>, targets: List<VisitorTarget>, viewer: Pose, now: Long,
               blocked: Boolean = false): VisitorInputResult {
        val owned = mutableSetOf<Long>()
        val returned = mutableListOf<VisitorToss>()
        val tracked = pointers.associateBy { it.id }
        val held = grab
        if (held != null) {
            owned.add(held.driver)
            val hand = tracked[held.driver]
            if (!blocked && hand != null && valid(hand.pose) && held.pulledAt != null)
                held.motion.record(now, hand.pose.t, viewer.t)
            if (blocked || hand == null || !hand.down || !valid(hand.pose)) {
                if (held.pulledAt == null) parked.remove(held.target.asset)
                if (held.pulledAt != null) held.destination?.let { end ->
                    val velocity = if (!blocked && hand != null && !hand.down && valid(hand.pose) && now - held.pulledAt!! >= 260L)
                        held.motion.outwardVelocity() else null
                    if (velocity != null) {
                        parked.remove(held.target.asset)
                        returned.add(VisitorToss(end, velocity))
                    } else parked[held.target.asset] = end
                }
                grab = null
            } else {
                if (held.pulledAt == null &&
                    (hand.pose.t - held.hand.t).dot(held.hand.forward()) <= -0.06f) {
                    held.pulledAt = now
                    held.pullHand = copy(hand.pose)
                    held.near = viewer.t + (held.target.pose.t - viewer.t).normalize() * 1.5f
                    held.motion.record(now, hand.pose.t, viewer.t)
                }
                held.pulledAt?.let { started ->
                    val pullHand = held.pullHand!!
                    val center = clamp(held.near!! + (hand.pose.t - pullHand.t) * 1.5f, viewer.t)
                    val rotation = hand.pose.q * pullHand.q.inverse() * held.target.pose.q
                    held.destination = held.target.copy(pose = Pose(center, rotation), scale = INSPECT_RADIUS / held.target.radius)
                    val t = ((now - started) / 260f).coerceIn(0f, 1f)
                    val eased = t * t * (3f - 2f * t)
                    val scale = held.target.scale + (INSPECT_RADIUS / held.target.radius - held.target.scale) * eased
                    parked[held.target.asset] = if (t == 1f) held.destination!! else held.target.copy(
                        pose = Pose(held.target.pose.t + (center - held.target.pose.t) * eased, rotation), scale = scale)
                }
            }
        }
        var hover: VisitorHit? = null
        if (!blocked) for (pointer in pointers) {
            if (!valid(pointer.pose)) continue
            var hit: VisitorHit? = null
            for (target in targets) {
                if (returned.any { it.target.asset == target.asset }) continue
                val candidate = ScenePointer.hit(pointer, parked[target.asset] ?: target) ?: continue
                if (hit == null || candidate.distance < hit.distance) hit = candidate
            }
            if (hit != null && (hover == null || hit.distance < hover.distance)) hover = hit
            if (grab == null && previous[pointer.id] == false && pointer.down && hit != null) {
                val target = parked[hit.asset] ?: targets.first { it.asset == hit.asset }
                // Close models start manipulating immediately; distant models wait for a deliberate pull.
                val inspected = hit.asset in parked
                val captured = Grab(pointer.id, target.copy(pose = copy(target.pose)), copy(pointer.pose))
                if (inspected) {
                    captured.pulledAt = now - 260L; captured.pullHand = copy(pointer.pose); captured.near = target.pose.t.copy()
                    captured.motion.record(now, pointer.pose.t, viewer.t)
                }
                grab = captured
                parked[hit.asset] = target.copy(pose = copy(target.pose))
                owned.add(pointer.id)
            }
        }
        pointers.forEach { previous[it.id] = it.down }
        previous.keys.retainAll(tracked.keys)
        return VisitorInputResult(owned, hover, returned)
    }

    private fun clamp(position: Vector3, eye: Vector3): Vector3 {
        val delta = position - eye
        val distance = delta.length()
        if (distance < .001f) return eye + Vector3(0f, 0f, 1.5f)
        return eye + delta * (distance.coerceIn(1.25f, 3.5f) / distance)
    }
    private fun valid(pose: Pose) = ScenePointer.valid(pose)
    private fun copy(pose: Pose) = Pose(pose.t.copy(), pose.q.copy())
    companion object { const val INSPECT_RADIUS = .55f }
}
