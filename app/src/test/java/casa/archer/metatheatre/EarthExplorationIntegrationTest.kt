package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Geographic observer plus a stable distant exhibit and local-time lighting frame. */
class EarthExplorationIntegrationTest {
    private val viewer = Pose(EarthRenderer.SEAT_EYE.copy())
    private fun point(expected: Vector3, actual: Vector3, tolerance: Float = .001f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test fun polarEquatorialAndFarSideViewsPutTheCorrectHemisphereAheadAtTheSameSafeDistance() {
        for (control in listOf(EarthControl.NORTH_POLE, EarthControl.SOUTH_POLE, EarthControl.EQUATOR, EarthControl.FAR_SIDE)) {
            val exploration = EarthExploration()
            exploration.apply(control, viewer)
            point(viewer.t, exploration.worldFrame * exploration.observer())
            point(EarthRenderer.CENTER, exploration.earthPose().t)
            assertEquals(exploration.distance, (viewer.t - exploration.earthPose().t).length(), .001f)
            assertTrue(exploration.distance > EarthRenderer.RADIUS * 1.012f + 50f)
            val towardEye = (viewer.t - exploration.earthPose().t).normalize()
            val north = exploration.earthPose().q * Vector3(0f, 1f, 0f)
            val front = exploration.earthPose().q * Vector3(0f, 0f, 1f)
            when (control) {
                EarthControl.NORTH_POLE -> assertEquals(1f, north.dot(towardEye), .0001f)
                EarthControl.SOUTH_POLE -> assertEquals(-1f, north.dot(towardEye), .0001f)
                EarthControl.EQUATOR -> assertEquals(1f, front.dot(towardEye), .0001f)
                EarthControl.FAR_SIDE -> assertEquals(-1f, front.dot(towardEye), .0001f)
                else -> error("Unexpected preset")
            }
            assertTrue(ScenePointer.valid(exploration.earthPose()))
        }
    }

    @Test fun aTurnedTranslatedViewerGetsThePoleInTheirCurrentViewAndHomeRestoresTheExactOriginalFrame() {
        val moved = Pose(Vector3(2f, 1.3f, -3f), Quaternion(pitch = 20f, yaw = 90f, roll = 6f))
        val exploration = EarthExploration()
        exploration.apply(EarthControl.NORTH_POLE, moved)
        point(moved.t, exploration.worldFrame * exploration.observer())
        val direction = exploration.earthPose().t - moved.t
        assertTrue(direction.x > 100f)
        assertTrue(kotlin.math.abs(direction.z) < 30f)
        exploration.spinBy(42f)
        exploration.apply(EarthControl.HOME, moved)
        assertEquals(Pose(), exploration.worldFrame)
        assertEquals(Pose(), exploration.skyFrame)
        assertEquals(0f, exploration.spin, 0f)
        point(EarthRenderer.CENTER, exploration.earthPose().t)
        assertTrue(EarthRenderer.orientation().isWithinAngleDegrees(exploration.earthPose().q, .01f))
    }

    @Test fun orbitStepsWrapAndClampAtBothPolesWithoutSingularTransforms() {
        val exploration = EarthExploration()
        repeat(30) { exploration.apply(EarthControl.ORBIT_UP, viewer) }
        assertEquals(90f, exploration.latitude, 0f)
        repeat(40) {
            exploration.apply(EarthControl.ORBIT_RIGHT, viewer)
            assertTrue(exploration.longitude in -180f..180f)
            assertTrue(ScenePointer.valid(exploration.earthPose()))
            point(viewer.t, exploration.worldFrame * exploration.observer())
        }
        repeat(30) { exploration.apply(EarthControl.ORBIT_DOWN, viewer) }
        assertEquals(-90f, exploration.latitude, 0f)
        assertTrue(ScenePointer.valid(exploration.worldFrame))
    }

    @Test fun spinningChangesGeographyWhileTheObserverFrameNorthAxisAndSunRemainFixed() {
        val exploration = EarthExploration()
        exploration.apply(EarthControl.NORTH_POLE, viewer)
        val frame = exploration.worldFrame
        val before = exploration.earthPose()
        val sun = exploration.sunPosition(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC)
        exploration.apply(EarthControl.SPIN_RIGHT, viewer)
        assertEquals(frame, exploration.worldFrame)
        point(before.q * Vector3(0f, 1f, 0f), exploration.earthPose().q * Vector3(0f, 1f, 0f))
        assertFalse(before.q.isWithinAngleDegrees(exploration.earthPose().q, 10f))
        point(sun, exploration.sunPosition(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC), .5f)
    }

    @Test fun sharedFramePreservesSunIncidenceMoonPhaseAndFlybyGeometry() {
        val exploration = EarthExploration()
        exploration.apply(EarthControl.SOUTH_POLE, viewer)
        val frame = exploration.skyFrame
        val sun = SolarSystem.sunPosition(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC)
        for (body in SolarSystem.planets + SolarSystem.moonAt(Instant.parse("2026-10-09T18:00:00Z"), sun)) {
            val pointOnBody = body.center + body.rotation * Vector3(0f, 0f, body.radius)
            val normal = body.rotation * Vector3(0f, 0f, 1f)
            val incidence = normal.dot((sun - pointOnBody).normalize())
            val transformed = (frame.q * normal).dot((frame * sun - frame * pointOnBody).normalize())
            assertEquals(incidence, transformed, .0001f)
        }
        for (visitor in SpaceVisitors.all) {
            val canonical = SpaceVisitors.pose(visitor, .5)
            val rendered = frame * canonical
            assertEquals(visitor.distance, (rendered.t - frame * EarthRenderer.SEAT_EYE).length(), .001f)
        }
        val shader = SolarSystem.shaderSunPosition(frame * sun)
        assertEquals(-(frame * sun).z, shader.z, 0f)
    }
}
