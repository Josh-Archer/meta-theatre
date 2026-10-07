package casa.archer.metatheatre

/** Use monotonic time for cadence; sample civil time afresh whenever an update is due. */
internal class EarthUpdateSchedule {
    private var lastUpdateMs: Long? = null

    fun shouldUpdate(nowMs: Long, force: Boolean = false): Boolean {
        val last = lastUpdateMs
        if (!force && last != null && nowMs >= last && nowMs - last < 1_000L) return false
        lastUpdateMs = nowMs
        return true
    }
}
