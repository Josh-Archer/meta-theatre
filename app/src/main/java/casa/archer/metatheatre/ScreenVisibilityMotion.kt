package casa.archer.metatheatre

/** Presentation only: never changes the user's saved screen dimensions. Reversals are continuous. */
internal class ScreenVisibilityMotion {
    private var from = 1f
    private var to = 1f
    private var started = 0L
    fun setHidden(hidden: Boolean, now: Long) { from = fraction(now); to = if (hidden) 0f else 1f; started = now }
    fun fraction(now: Long): Float {
        val t = ((now - started) / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        return from + (to - from) * (t * t * (3f - 2f * t))
    }
    fun animating(now: Long) = from != to && now - started < DURATION_MS
    companion object { const val DURATION_MS = 240L }
}
