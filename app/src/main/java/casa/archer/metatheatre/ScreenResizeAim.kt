package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import kotlin.math.abs

/** Resize has a forgiving angular margin; taps still require an exact panel hit. */
internal object ScreenResizeAim {
    fun accepts(hand: Pose, screen: Pose, size: ScreenSize): Boolean {
        val direction = hand.forward()
        val normal = screen.forward()
        val denominator = direction.dot(normal)
        if (abs(denominator) < .0001f) return false
        val distance = (screen.t - hand.t).dot(normal) / denominator
        if (!distance.isFinite() || distance !in .05f..12f) return false
        val delta = hand.t + direction * distance - screen.t
        val margin = (distance * .18f).coerceIn(.15f, 1.5f)
        return abs(delta.dot(screen.right())) <= size.width / 2 + margin &&
            abs(delta.dot(screen.up())) <= size.height / 2 + margin
    }
}
