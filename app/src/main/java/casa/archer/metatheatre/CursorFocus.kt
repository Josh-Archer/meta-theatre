package casa.archer.metatheatre

internal data class CursorCandidate(val id: Long, val distance: Float, val pressed: Boolean)

/** Keep one ray in charge of the shared cursor until it leaves or another input selects. */
internal class CursorFocus {
    private var owner: Long? = null
    private var lastSeen = 0L

    fun update(candidates: List<CursorCandidate>, now: Long): Long? {
        val valid = candidates.filter { it.distance.isFinite() && it.distance >= 0f }
        val selected = valid.firstOrNull { it.pressed }
            ?: valid.firstOrNull { it.id == owner }
        if (selected != null) {
            owner = selected.id
            lastSeen = now
        } else if (owner == null || now - lastSeen >= 150L) {
            owner = valid.minByOrNull { it.distance }?.id
            lastSeen = now
        }
        return owner
    }

    fun clear() { owner = null }
}
