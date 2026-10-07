package casa.archer.metatheatre

import android.os.SystemClock
import androidx.core.net.toUri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/** Embedded PBR GLBs inherit SolarRenderer's shared Sun. No collisions or per-frame allocations off-pass. */
class SpaceVisitorRenderer(clockMillis: () -> Long = SystemClock::elapsedRealtime) {
    private data class Entry(val visitor: SpaceVisitors.Visitor, val entity: Entity, var visible: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private val elapsedClock = PausableElapsedClock(clockMillis)

    fun create() {
        elapsedClock.reset()
        for (visitor in SpaceVisitors.all) {
            val entity = Entity.create(
                Mesh(visitor.asset.toUri(), hittable = MeshCollision.NoCollision),
                Transform(SpaceVisitors.pose(visitor, 0.0)),
                Scale(Vector3(visitor.displayScale, visitor.displayScale, visitor.displayScale)),
                Visible(false),
            )
            entries.add(Entry(visitor, entity))
        }
    }

    fun update() {
        if (elapsedClock.isPaused) return
        val elapsed = elapsedClock.elapsedSeconds()
        for (entry in entries) {
            val p = SpaceVisitors.progress(entry.visitor, elapsed)
            val show = p != null
            if (show != entry.visible) {
                entry.entity.setComponent(Visible(show))
                entry.visible = show
            }
            if (p != null) entry.entity.setComponent(Transform(SpaceVisitors.pose(entry.visitor, p)))
        }
    }

    /** Hide any active flyby immediately and freeze its logical phase until watching ends. */
    fun setPaused(paused: Boolean) {
        elapsedClock.setPaused(paused)
        if (!paused) return
        for (entry in entries) {
            if (entry.visible) {
                entry.entity.setComponent(Visible(false))
                entry.visible = false
            }
        }
    }

    fun release() { entries.clear() }
}
