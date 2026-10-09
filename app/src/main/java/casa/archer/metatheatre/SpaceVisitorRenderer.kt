package casa.archer.metatheatre

import android.os.SystemClock
import androidx.core.net.toUri
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/** Embedded PBR GLBs inherit SolarRenderer's shared Sun. No collisions or per-frame allocations off-pass. */
internal class SpaceVisitorRenderer(clockMillis: () -> Long = SystemClock::elapsedRealtime) {
    private data class Entry(val visitor: SpaceVisitors.Visitor, val entity: Entity, var visible: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private val inspection = VisitorInspection()
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
            val inspected = inspection.overrides[entry.visitor.asset]
            val show = p != null || inspected != null
            if (show != entry.visible) {
                entry.entity.setComponent(Visible(show))
                entry.visible = show
                android.util.Log.i("TheatreVisitors", "flyby ${entry.visitor.asset} visible=$show")
            }
            if (inspected != null) {
                entry.entity.setComponent(Transform(inspected.pose))
                entry.entity.setComponent(Scale(Vector3(inspected.scale, inspected.scale, inspected.scale)))
            } else if (p != null) {
                entry.entity.setComponent(Transform(SpaceVisitors.pose(entry.visitor, p)))
                val scale = entry.visitor.displayScale
                entry.entity.setComponent(Scale(Vector3(scale, scale, scale)))
            }
        }
    }

    /** Hide any active flyby immediately and freeze its logical phase until watching ends. */
    fun setPaused(paused: Boolean) {
        elapsedClock.setPaused(paused)
        if (!paused) return
        inspection.cancelGrab()
        for (entry in entries) {
            if (entry.visible) {
                entry.entity.setComponent(Visible(false))
                entry.visible = false
            }
        }
    }

    fun interact(pointers: List<VisitorPointer>, viewer: Pose, now: Long, blocked: Boolean): VisitorInputResult {
        val targets = entries.filter { it.visible }.map { entry ->
            VisitorTarget(entry.visitor.asset, entry.entity.getComponent<Transform>().transform,
                entry.entity.getComponent<Scale>().scale.x, if (entry.visitor == SpaceVisitors.iss) 70f else 2.65f)
        }
        return inspection.update(pointers, targets, viewer, now, blocked || elapsedClock.isPaused)
    }

    fun returnModels() { inspection.reset(); update() }
    fun cancelGrab() { inspection.cancelGrab() }
    fun release() { inspection.reset(); entries.clear() }
}
