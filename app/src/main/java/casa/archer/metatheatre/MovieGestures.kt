package casa.archer.metatheatre

import kotlin.math.abs
import kotlin.math.sqrt

internal data class HandInput(
    val id: Long,
    val pinching: Boolean,
    val x: Float,
    val y: Float,
    val z: Float,
    val onVideo: Boolean,
)

internal data class HandActions(
    val size: ScreenSize? = null,
    val seekDirections: List<Int> = emptyList(),
    val taps: List<Long> = emptyList(),
    val resizing: Boolean = false,
)

/** Each pinch owns one action. Resizing consumes both pinches until they are released. */
internal class MovieHandGestures {
    private class Pinch(val start: HandInput, val startedMs: Long) {
        var consumed = false
        var travel = 0f
    }
    private data class Stretch(val first: Long, val second: Long, val distance: Float, val size: ScreenSize)
    private val previous = mutableMapOf<Long, Boolean>()
    private val pinches = mutableMapOf<Long, Pinch>()
    private var stretch: Stretch? = null

    fun reset() {
        previous.clear()
        pinches.clear()
        stretch = null
    }

    fun update(hands: List<HandInput>, size: ScreenSize, nowMs: Long, allowSeek: Boolean = true): HandActions {
        val tracked = hands.associateBy { it.id }
        val seeks = mutableListOf<Int>()
        val taps = mutableListOf<Long>()
        for (hand in hands) {
            val wasDown = previous[hand.id]
            // A newly tracked hand must open first: reacquisition cannot start a gesture.
            if (wasDown == false && hand.pinching && hand.onVideo) pinches[hand.id] = Pinch(hand, nowMs)
            val pinch = pinches[hand.id]
            if (pinch != null) {
                pinch.travel = maxOf(pinch.travel, distance(pinch.start, hand))
                if (!hand.pinching) {
                    if (!pinch.consumed && hand.onVideo && nowMs - pinch.startedMs <= TAP_MS && pinch.travel < TAP_TRAVEL) {
                        taps.add(hand.id)
                    }
                    pinches.remove(hand.id)
                }
            }
            previous[hand.id] = hand.pinching
        }
        previous.keys.retainAll(tracked.keys)
        pinches.keys.retainAll(tracked.keys)

        val active = stretch
        if (active != null && (tracked[active.first]?.pinching != true || tracked[active.second]?.pinching != true)) {
            stretch = null
        }
        if (stretch == null) {
            val candidates = pinches.entries.filter { !it.value.consumed && tracked[it.key]?.pinching == true }
            if (candidates.size == 2) {
                val a = candidates[0]
                val b = candidates[1]
                val separation = distance(tracked.getValue(a.key), tracked.getValue(b.key))
                // A held pinch can wait for the other hand. Requiring both edges within
                // 500 ms made a deliberate pinch-then-pinch silently fail to resize.
                if (separation >= MIN_SPAN) {
                    stretch = Stretch(a.key, b.key, separation, size)
                    a.value.consumed = true
                    b.value.consumed = true
                }
            }
        }
        stretch?.let {
            val separation = distance(tracked.getValue(it.first), tracked.getValue(it.second))
            return HandActions(size = it.size.resized(separation / it.distance), resizing = true)
        }
        for ((id, pinch) in pinches) {
            val hand = tracked.getValue(id)
            if (!allowSeek || pinch.consumed || !hand.pinching) continue
            val dx = hand.x - pinch.start.x
            val dy = hand.y - pinch.start.y
            val dz = hand.z - pinch.start.z
            if (abs(dx) >= SWIPE_METERS && abs(dx) > abs(dy) * 1.5f && abs(dx) > abs(dz) * 1.5f) {
                seeks.add(if (dx > 0f) 1 else -1)
                pinch.consumed = true
            }
        }
        return HandActions(seekDirections = seeks, taps = taps)
    }

    private fun distance(a: HandInput, b: HandInput): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    companion object {
        const val SWIPE_METERS = 0.12f
        const val MIN_SPAN = 0.08f
        const val TAP_MS = 500L
        const val TAP_TRAVEL = 0.06f
    }
}

/** Shared routing for the library and movie; hidden screens and navigation own no screen input. */
internal class ScreenHandGestures {
    private val gestures = MovieHandGestures()
    private var movie: Boolean? = null

    fun reset() { gestures.reset(); movie = null }

    fun update(hands: List<HandInput>, size: ScreenSize, nowMs: Long, watching: Boolean,
               hidden: Boolean = false, navigating: Boolean = false): HandActions {
        if (hidden || navigating) { reset(); return HandActions() }
        if (movie != watching) { gestures.reset(); movie = watching }
        return gestures.update(hands, size, nowMs, allowSeek = watching)
    }
}

/** Deflection seeks immediately, then repeats at a bounded rate until the stick is centered. */
internal class MovieStickSeek {
    private var direction = 0
    private var nextRepeatMs = 0L

    fun update(axis: Int, nowMs: Long): Int {
        if (axis == 0) {
            direction = 0
            return 0
        }
        if (axis != direction) {
            direction = axis
            nextRepeatMs = nowMs + FIRST_REPEAT_MS
            return axis
        }
        if (nowMs < nextRepeatMs) return 0
        nextRepeatMs = nowMs + REPEAT_MS
        return axis
    }

    companion object {
        const val FIRST_REPEAT_MS = 750L
        const val REPEAT_MS = 500L
    }
}

/** Tracks actual pointer hits, including entry into the rail while its render mesh is hidden. */
internal class RailRevealMotion {
    private val hits = mutableMapOf<Long, Pair<Float, Float>>()

    fun update(source: Long, x: Float?, y: Float?): Boolean {
        if (x == null || y == null) {
            hits.remove(source)
            return false
        }
        val previous = hits[source]
        val moved = previous == null || abs(x - previous.first) > MOVE_METERS || abs(y - previous.second) > MOVE_METERS
        if (moved) hits[source] = x to y
        return moved
    }

    fun retain(sources: Set<Long>) { hits.keys.retainAll(sources) }
    fun reset() { hits.clear() }

    companion object { const val MOVE_METERS = 0.015f }
}
