package casa.archer.metatheatre

internal data class CursorCandidate(val id: Long, val distance: Float, val pressed: Boolean, val down: Boolean = pressed)

/** Keep one ray in charge of the shared cursor until it leaves or another input selects. */
internal class CursorFocus {
    private var owner: Long? = null
    private var lastSeen = 0L
    private var held = false

    fun update(candidates: List<CursorCandidate>, now: Long): Long? {
        // Capture includes the release frame and movement outside the panel. A second
        // pinch must never steal an in-progress button press or timeline drag.
        val current = candidates.firstOrNull { it.id == owner }
        if (held && current != null) {
            held = current.down
            lastSeen = now
            return owner
        }
        held = false
        val valid = candidates.filter { it.distance.isFinite() && it.distance >= 0f }
        val selected = valid.firstOrNull { it.id == owner && it.pressed }
            ?: valid.filter { it.pressed }.minWithOrNull(compareBy({ it.distance }, { it.id }))
            ?: valid.firstOrNull { it.id == owner }
        if (selected != null) {
            owner = selected.id
            lastSeen = now
            held = selected.pressed && selected.down
        } else if (owner == null || now - lastSeen >= 150L) {
            owner = valid.minByOrNull { it.distance }?.id
            lastSeen = now
        }
        return owner
    }

    fun clear() { owner = null; held = false }
}
