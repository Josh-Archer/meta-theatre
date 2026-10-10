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

/** Embedded PBR GLBs inherit SolarRenderer's shared Sun; pointer targeting avoids mesh collisions. */
internal class SpaceVisitorRenderer(clockMillis: () -> Long = SystemClock::elapsedRealtime) {
    private data class Entry(val visitor: SpaceVisitors.Visitor, val entity: Entity, var visible: Boolean = false,
                             var phaseOffset: Double = 0.0, var returning: VisitorReturnFlight? = null)
    private val entries = mutableListOf<Entry>()
    private val inspection = VisitorInspection()
    private val elapsedClock = PausableElapsedClock(clockMillis)
    private var worldFrame = Pose()
    private fun pose(visitor: SpaceVisitors.Visitor, progress: Double) = worldFrame * SpaceVisitors.pose(visitor, progress)
    fun setWorldFrame(frame: Pose) {
        if (worldFrame == frame) return
        inspection.cancelGrab()
        worldFrame = Pose(frame.t.copy(), frame.q.copy())
        update()
    }

    fun create() {
        elapsedClock.reset()
        for (visitor in SpaceVisitors.all) {
            val entity = Entity.create(
                Mesh(visitor.asset.toUri(), hittable = MeshCollision.NoCollision),
                Transform(pose(visitor, 0.0)),
                Scale(Vector3(visitor.displayScale, visitor.displayScale, visitor.displayScale)),
                Visible(false),
            )
            entries.add(Entry(visitor, entity))
        }
    }

    fun update() {
        if (elapsedClock.isPaused) return
        val now = elapsedClock.elapsedMillis()
        val elapsed = now / 1000.0
        for (entry in entries) {
            val p = SpaceVisitors.progress(entry.visitor, elapsed - entry.phaseOffset)
            val inspected = inspection.overrides[entry.visitor.asset]
            val flight = entry.returning
            val show = p != null || inspected != null || flight != null
            if (show != entry.visible) {
                entry.entity.setComponent(Visible(show))
                entry.visible = show
                android.util.Log.i("TheatreVisitors", "flyby ${entry.visitor.asset} visible=$show")
            }
            if (flight != null) {
                val orbit = VisitorTarget(entry.visitor.asset, pose(entry.visitor, p ?: .5),
                    entry.visitor.displayScale, radius(entry.visitor))
                val frame = flight.frame(orbit, now)
                entry.entity.setComponent(Transform(frame.pose))
                entry.entity.setComponent(Scale(Vector3(frame.scale, frame.scale, frame.scale)))
                if (flight.finished(now)) entry.returning = null
            } else if (inspected != null) {
                entry.entity.setComponent(Transform(inspected.pose))
                entry.entity.setComponent(Scale(Vector3(inspected.scale, inspected.scale, inspected.scale)))
            } else if (p != null) {
                entry.entity.setComponent(Transform(pose(entry.visitor, p)))
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
        val targets = entries.filter { it.visible && it.returning == null }.map { entry ->
            VisitorTarget(entry.visitor.asset, entry.entity.getComponent<Transform>().transform,
                entry.entity.getComponent<Scale>().scale.x, radius(entry.visitor))
        }
        val result = inspection.update(pointers, targets, viewer, now, blocked || elapsedClock.isPaused)
        result.returned.forEach { startReturn(it.target, it.velocity) }
        return result
    }

    private fun radius(visitor: SpaceVisitors.Visitor) = if (visitor == SpaceVisitors.iss) 70f else 2.65f
    private fun startReturn(target: VisitorTarget, velocity: Vector3) {
        val entry = entries.firstOrNull { it.visitor.asset == target.asset } ?: return
        val now = elapsedClock.elapsedMillis()
        val elapsed = now / 1000.0
        entry.phaseOffset = SpaceVisitors.returnOffset(entry.visitor, elapsed, entry.phaseOffset)
        entry.returning = VisitorReturnFlight(target, velocity, now)
    }
    fun returnModels() {
        val models = inspection.overrides.values.toList()
        inspection.reset()
        models.forEach { startReturn(it, Vector3(0f, 0f, 0f)) }
        update()
    }
    fun cancelGrab() { inspection.cancelGrab() }
    fun release() { inspection.reset(); entries.clear() }
}
