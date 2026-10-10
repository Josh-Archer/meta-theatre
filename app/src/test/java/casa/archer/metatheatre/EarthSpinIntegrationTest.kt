package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class EarthSpinIntegrationTest {
    private val viewer = Pose(EarthRenderer.SEAT_EYE.copy())
    private val globe = VisitorTarget("earth_dynamic_surface.glb", Pose(EarthRenderer.CENTER), 1f, EarthRenderer.RADIUS * 1.012f)
    private fun hand(down: Boolean = false) = VisitorPointer(1, Pose(viewer.t + Vector3(0f, -.2f, .3f),
        Quaternion.fromDirection(globe.pose.t - viewer.t, Vector3(0f, 1f, 0f))), down)

    @Test fun globeDragSpinsBothWaysAndOwnsReleaseAfterItsRayLeavesTheSurface() {
        val gesture = EarthSpinGesture()
        val exploration = EarthExploration()
        val screen = ScreenHandGestures()
        var pointer = hand()
        fun frame(now: Long) = gesture.update(listOf(pointer), globe, viewer, false, emptySet()).also { input ->
            exploration.spinBy(input.degrees)
            val hands = if (pointer.id in input.owned) emptyList() else listOf(HandInput(pointer.id, pointer.down, 0f, 0f, 0f, true))
            val actions = screen.update(hands, ScreenSize(2.8f, 1.575f), now, false)
            assertTrue(actions.taps.isEmpty()); assertTrue(actions.seekDirections.isEmpty()); assertNull(actions.size)
        }
        assertNotNull(frame(0).hover)
        pointer = pointer.copy(down = true)
        assertTrue(pointer.id in frame(10).owned)
        pointer = pointer.copy(pose = Pose(pointer.pose.t + Vector3(.025f, 0f, 0f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f)))
        assertEquals(-4f, frame(20).degrees, .0001f)
        pointer = pointer.copy(pose = Pose(pointer.pose.t - Vector3(.05f, 0f, 0f), pointer.pose.q))
        assertEquals(5f, frame(30).degrees, .0001f) // bounded angular change per frame
        assertEquals(1f, exploration.spin, .0001f)
        pointer = pointer.copy(down = false)
        assertTrue(pointer.id in frame(40).owned)
        assertTrue(frame(50).owned.isEmpty())
    }

    @Test fun visibleScreenVisitorOwnershipAndNavigationPreventEarthCapture() {
        for (block in listOf("screen", "visitor", "navigation")) {
            val gesture = EarthSpinGesture()
            val owner = if (block == "visitor") setOf(1L) else emptySet()
            var pointer = hand().copy(maxDistance = if (block == "screen") 2f else Float.POSITIVE_INFINITY)
            gesture.update(listOf(pointer), globe, viewer, block == "navigation", owner)
            pointer = pointer.copy(down = true)
            assertTrue(gesture.update(listOf(pointer), globe, viewer, block == "navigation", owner).owned.isEmpty())
        }
        val pointer = hand()
        val earthHit = ScenePointer.hit(pointer, globe)!!
        val distant = VisitorTarget("iss.glb", Pose(viewer.t + pointer.pose.forward() * 420f), 1f, 20f)
        val visitors = VisitorInspection()
        val clipped = pointer.copy(maxDistance = earthHit.distance)
        assertNull(visitors.update(listOf(clipped), listOf(distant), viewer, 0).hover)
        assertTrue(visitors.update(listOf(clipped.copy(down = true)), listOf(distant), viewer, 10).owned.isEmpty())
    }

    @Test fun newlyTrackedPinchesTrackingLossAndViewChangesRequireAReleaseBeforeGrabbingAgain() {
        val gesture = EarthSpinGesture()
        val held = hand(true)
        assertTrue(gesture.update(listOf(held), globe, viewer, false, emptySet()).owned.isEmpty())
        gesture.update(listOf(held.copy(down = false)), globe, viewer, false, emptySet())
        assertTrue(1L in gesture.update(listOf(held), globe, viewer, false, emptySet()).owned)
        gesture.cancel()
        assertTrue(gesture.update(listOf(held), globe, viewer, false, emptySet()).owned.isEmpty())
        gesture.update(emptyList(), globe, viewer, false, emptySet())
        assertTrue(gesture.update(listOf(held), globe, viewer, false, emptySet()).owned.isEmpty())
        val invalid = held.copy(pose = Pose(Vector3(Float.NaN, 0f, 0f)))
        assertEquals(0f, gesture.update(listOf(invalid), globe, viewer, false, emptySet()).degrees, 0f)
    }

    @Test fun theGrabbedSurfaceFollowsARightwardDragFromEitherPoleAndEitherEquatorialSide() {
        for (control in listOf(EarthControl.NORTH_POLE, EarthControl.SOUTH_POLE, EarthControl.EQUATOR, EarthControl.FAR_SIDE)) {
            val exploration = EarthExploration()
            exploration.apply(control, viewer)
            val before = exploration.earthPose()
            val normal = ((viewer.t - before.t).normalize() + Vector3(0f, .5f, 0f)).normalize()
            val point = before.t + normal * globe.radius
            val pointer = VisitorPointer(1, Pose(viewer.t, Quaternion.fromDirection(point - viewer.t, Vector3(0f, 1f, 0f))), false)
            val target = globe.copy(pose = before)
            val hit = ScenePointer.hit(pointer, target)!!
            val localPoint = before.q.inverse() * (hit.point - before.t)
            val gesture = EarthSpinGesture()
            gesture.update(listOf(pointer), target, viewer, false, emptySet())
            gesture.update(listOf(pointer.copy(down = true)), target, viewer, false, emptySet())
            val drag = gesture.update(listOf(pointer.copy(down = true,
                pose = Pose(pointer.pose.t + Vector3(.02f, 0f, 0f), pointer.pose.q))), target, viewer, false, emptySet())
            exploration.spinBy(drag.degrees)
            val afterPoint = exploration.earthPose().t + exploration.earthPose().q * localPoint
            assertTrue("$control should follow the hand to the right", afterPoint.x > hit.point.x)
        }
    }
}
