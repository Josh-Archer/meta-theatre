package casa.archer.metatheatre

import android.os.SystemClock
import androidx.core.net.toUri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/** Five cached entities; no mesh collisions, particles or per-frame texture uploads. */
internal class StarshipRenderer(private val publish: (String) -> Unit,
                                clockMillis: () -> Long = SystemClock::elapsedRealtime) {
    private class Part(asset: String) {
        val entity = Entity.create(Mesh(asset.toUri(), hittable = MeshCollision.NoCollision),
            Transform(Pose()), Scale(Vector3(StarshipFlight.SCALE, StarshipFlight.SCALE, StarshipFlight.SCALE)), Visible(false))
        val placement = ScenePlacementCache(StarshipFlight.SCALE)
        var visible = false
        fun show(pose: Pose?, scale: Float = StarshipFlight.SCALE) {
            val show = pose != null
            if (show != visible) { entity.setComponent(Visible(show)); visible = show }
            if (pose == null) return
            if (placement.updatePose(pose)) entity.setComponent(Transform(pose))
            if (placement.updateScale(scale)) entity.setComponent(Scale(Vector3(scale, scale, scale)))
        }
    }
    private val clock = PausableElapsedClock(clockMillis)
    private var ship: Part? = null
    private var booster: Part? = null
    private var pad: Part? = null
    private var shipPlume: Part? = null
    private var boosterPlume: Part? = null
    private var world = Pose()
    private var center = EarthRenderer.CENTER
    private var viewer = EarthRenderer.SEAT_EYE
    private var radius = EarthRenderer.RADIUS
    private var viewSupported = true
    private var running = false
    private var externallyPaused = false
    private var lastPhase: StarshipFlight.Phase? = null
    private fun create() {
        if (ship != null) return
        ship = Part("starship.glb"); booster = Part("super_heavy.glb"); pad = Part("starship_pad.glb")
        shipPlume = Part("starship_plume.glb"); boosterPlume = Part("starship_plume.glb")
    }
    fun start() { create(); clock.reset(); running = true; lastPhase = null; update() }
    fun stop() { running = false; hide(); publish("Flight stopped") }
    fun setPaused(paused: Boolean) {
        externallyPaused = paused
        clock.setPaused(paused || !viewSupported)
        if (clock.isPaused) hide() else update()
    }
    fun setEarthView(frame: Pose, earth: Vector3, eye: Vector3, earthRadius: Float, zoom: Float) {
        world = Pose(frame.t.copy(), frame.q.copy())
        center = world.inverse() * earth; viewer = world.inverse() * eye; radius = earthRadius
        viewSupported = zoom <= 1.001f
        clock.setPaused(externallyPaused || !viewSupported)
        if (clock.isPaused) hide()
    }
    fun update() {
        if (!running || clock.isPaused || !viewSupported) return
        apply(StarshipFlight.frame(clock.elapsedSeconds(), center, radius, viewer))
    }
    /** Debug automation uses the same evaluator and native entities as the visible flight. */
    internal fun diagnosticFrame(seconds: Double): StarshipFlight.Frame {
        create()
        val frame = StarshipFlight.frame(seconds, center, radius, viewer)
        apply(frame)
        return frame
    }
    private fun apply(frame: StarshipFlight.Frame) {
        ship?.show(world * frame.ship); booster?.show(frame.booster?.let { world * it }); pad?.show(world * frame.pad)
        fun plume(part: Part?, pose: Pose?, power: Float, size: Float) {
            val p = if (pose != null && power > .01f)
                world * Pose(pose.t + pose.q * Vector3(0f, -.8f * StarshipFlight.SCALE, 0f), pose.q) else null
            part?.show(p, size * power)
        }
        plume(shipPlume, frame.ship, frame.shipThrust, .65f)
        plume(boosterPlume, frame.booster, frame.boosterThrust, 1.6f)
        if (lastPhase != frame.phase) {
            lastPhase = frame.phase
            publish(frame.phase.label)
            android.util.Log.i("Starship", "Flight phase: ${frame.phase}")
        }
    }
    private fun hide() { listOf(ship, booster, pad, shipPlume, boosterPlume).forEach { it?.show(null) } }
    fun release() { running = false; ship = null; booster = null; pad = null; shipPlume = null; boosterPlume = null }
}
