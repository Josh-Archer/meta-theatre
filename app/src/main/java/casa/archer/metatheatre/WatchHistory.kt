package casa.archer.metatheatre

internal data class MovieKey(val serverId: String, val ratingKey: String)
internal data class Bookmark(
    val key: MovieKey,
    val title: String,
    val subtitle: String,
    val type: String,
    val posterKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtMs: Long,
    val completed: Boolean = false,
) {
    val resumeMs: Long get() = if (completed) 0 else positionMs.coerceAtLeast(0L)
        .let { if (durationMs > 0) it.coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L)) else it }
}

internal class WatchHistory(entries: List<Bookmark> = emptyList(), active: MovieKey? = null) {
    private val records = entries.associateBy { it.key }.toMutableMap()
    var active: MovieKey? = active?.takeIf { records[it]?.completed == false }
        private set
    fun recent(): List<Bookmark> = records.values.sortedByDescending { it.lastPlayedAtMs }.take(LIMIT)
    fun find(key: MovieKey) = records[key]
    fun begin(bookmark: Bookmark) {
        records[bookmark.key] = bookmark.copy(completed = false)
        active = bookmark.key
        trim()
    }
    fun progress(key: MovieKey, positionMs: Long, durationMs: Long, ended: Boolean) {
        val current = records[key] ?: return
        records[key] = current.copy(positionMs = if (ended) 0 else positionMs.coerceAtLeast(0),
            durationMs = durationMs.takeIf { it > 0 } ?: current.durationMs, completed = ended)
        if (ended && active == key) active = null
    }
    fun importRecent(entries: List<Bookmark>) {
        for (entry in entries) if (entry.lastPlayedAtMs > (records[entry.key]?.lastPlayedAtMs ?: 0)) records[entry.key] = entry
        trim()
    }
    fun clear() { records.clear(); active = null }
    fun leavePlayer() { active = null }
    private fun trim() {
        val keep = recent().map { it.key }.toMutableSet()
        active?.let(keep::add)
        records.keys.retainAll(keep)
    }
    companion object { const val LIMIT = 100 }
}
