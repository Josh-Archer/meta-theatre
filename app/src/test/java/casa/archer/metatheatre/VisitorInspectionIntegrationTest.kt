package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

/** Flyby geometry, pointer capture and screen routing run together with replayed hand/controller poses. */
class VisitorInspectionIntegrationTest {
    private val viewer = Pose(EarthRenderer.SEAT_EYE.copy())
    private fun target(visitor: SpaceVisitors.Visitor) = VisitorTarget(visitor.asset,
        SpaceVisitors.pose(visitor, .5), visitor.displayScale, if (visitor == SpaceVisitors.iss) 70f else 2.65f)
    private fun pointer(target: VisitorTarget, down: Boolean = false, id: Long = 1) = VisitorPointer(id,
        Pose(viewer.t + Vector3(0f, -.2f, .3f), Quaternion.fromDirection(
            target.pose.t - (viewer.t + Vector3(0f, -.2f, .3f)), Vector3(0f, 1f, 0f))), down)

    @Test fun eachFlybyCanBePulledRotatedReleasedAndReturnedWithoutPressingTheScreen() {
        for (visitor in SpaceVisitors.all) {
            val target = target(visitor)
            val inspection = VisitorInspection()
            val screen = ScreenHandGestures()
            var hand = pointer(target)
            fun frame(now: Long) = inspection.update(listOf(hand), listOf(target), viewer, now).also { result ->
                val inputs = if (hand.id in result.owned) emptyList() else
                    listOf(HandInput(hand.id, hand.down, 0f, 0f, 0f, false))
                val actions = screen.update(inputs, ScreenSize(2.8f, 1.575f), now, false)
                assertTrue(actions.taps.isEmpty()); assertTrue(actions.seekDirections.isEmpty()); assertNull(actions.size)
            }
            assertEquals(visitor.asset, frame(0).hover!!.asset)
            hand = hand.copy(down = true)
            assertTrue(hand.id in frame(10).owned)
            assertEquals(target.pose.t.z, inspection.overrides[visitor.asset]!!.pose.t.z, .001f)
            hand = hand.copy(pose = Pose(hand.pose.t - hand.pose.forward() * .1f, hand.pose.q))
            frame(100); frame(360)
            val pulled = inspection.overrides[visitor.asset]!!
            assertEquals(1.5f, (pulled.pose.t - viewer.t).length(), .001f)
            assertEquals(VisitorInspection.INSPECT_RADIUS, pulled.radius * pulled.scale, .0001f)
            val turn = Quaternion(pitch = 0f, yaw = 40f, roll = 0f)
            hand = hand.copy(pose = Pose(hand.pose.t + Vector3(.2f, 0f, 0f), turn * hand.pose.q))
            frame(400)
            val turned = inspection.overrides[visitor.asset]!!
            val expected = turn * pulled.pose.forward()
            assertEquals(expected.x, turned.pose.forward().x, .0001f)
            assertEquals(expected.z, turned.pose.forward().z, .0001f)
            assertEquals(pulled.pose.t.x + .3f, turned.pose.t.x, .001f)
            frame(620) // finish inspecting before a normal, stationary release
            hand = hand.copy(down = false)
            assertTrue(hand.id in frame(650).owned) // release belongs to the model too
            assertNull(SpaceVisitors.progress(visitor, visitor.firstPassSeconds + visitor.durationSeconds))
            inspection.update(emptyList(), emptyList(), viewer, 700)
            assertEquals(turned, inspection.overrides[visitor.asset]) // survives the end of the flyby
            inspection.reset()
            assertTrue(inspection.overrides.isEmpty())
        }
    }

    @Test fun nearerScreenBlocksDistantModelsAndNewlyTrackedPinchesRequireRelease() {
        val target = target(SpaceVisitors.roadster)
        val inspection = VisitorInspection()
        var hand = pointer(target).copy(maxDistance = 2f)
        assertNull(inspection.update(listOf(hand), listOf(target), viewer, 0).hover)
        hand = hand.copy(down = true)
        assertTrue(inspection.update(listOf(hand), listOf(target), viewer, 10).owned.isEmpty())
        hand = hand.copy(maxDistance = Float.POSITIVE_INFINITY)
        assertTrue(inspection.update(listOf(hand), listOf(target), viewer, 20).owned.isEmpty())
        inspection.update(listOf(hand.copy(down = false)), listOf(target), viewer, 30)
        assertTrue(1L in inspection.update(listOf(hand), listOf(target), viewer, 40).owned)
        inspection.reset()
        assertTrue(inspection.update(listOf(hand), listOf(target), viewer, 50).owned.isEmpty())
        val newlyTracked = VisitorInspection()
        assertTrue(newlyTracked.update(listOf(hand), listOf(target), viewer, 0).owned.isEmpty())
    }

    @Test fun trackingLossAndNavigationCancelCaptureAndQuietModePreventsCapture() {
        for (blocked in listOf(false, true)) {
            val target = target(SpaceVisitors.roadster)
            val inspection = VisitorInspection()
            val hand = pointer(target)
            inspection.update(listOf(hand), listOf(target), viewer, 0)
            inspection.update(listOf(hand.copy(down = true)), listOf(target), viewer, 10)
            inspection.update(if (blocked) listOf(hand.copy(down = true)) else emptyList(), listOf(target), viewer, 20, blocked)
            assertTrue(inspection.overrides.isEmpty())
        }
        val target = target(SpaceVisitors.iss)
        val inspection = VisitorInspection()
        val hand = pointer(target)
        inspection.update(listOf(hand), listOf(target), viewer, 0, blocked = true)
        val result = inspection.update(listOf(hand.copy(down = true)), listOf(target), viewer, 10, blocked = true)
        assertTrue(result.owned.isEmpty()); assertNull(result.hover); assertTrue(inspection.overrides.isEmpty())
    }

    @Test fun releaseDuringApproachSettlesNearAndASecondGrabManipulatesImmediately() {
        val target = target(SpaceVisitors.roadster)
        val inspection = VisitorInspection()
        val hand = pointer(target)
        inspection.update(listOf(hand), listOf(target), viewer, 0)
        inspection.update(listOf(hand.copy(down = true)), listOf(target), viewer, 10)
        val pull = hand.copy(down = true, pose = Pose(hand.pose.t - hand.pose.forward() * .1f, hand.pose.q))
        inspection.update(listOf(pull), listOf(target), viewer, 100)
        inspection.update(listOf(pull.copy(down = false)), listOf(target), viewer, 110)
        val near = inspection.overrides[target.asset]!!
        assertEquals(1.5f, (near.pose.t - viewer.t).length(), .001f)
        val next = pointer(near)
        inspection.update(listOf(next), listOf(near), viewer, 200)
        assertTrue(1L in inspection.update(listOf(next.copy(down = true)), listOf(near), viewer, 210).owned)
        inspection.update(listOf(next.copy(down = true, pose = Pose(next.pose.t + Vector3(.1f, 0f, 0f), next.pose.q))),
            listOf(near), viewer, 220)
        assertEquals(near.pose.t.x + .15f, inspection.overrides[target.asset]!!.pose.t.x, .001f)
        inspection.cancelGrab()
        assertTrue(target.asset in inspection.overrides)
    }

    @Test fun outwardTossReturnsEitherModelAndOwnsTheReleaseWithoutAClick() {
        for (visitor in SpaceVisitors.all) {
            val target = target(visitor)
            val inspection = VisitorInspection()
            val screen = ScreenHandGestures()
            val initial = pointer(target)
            fun frame(hand: VisitorPointer, now: Long) = inspection.update(listOf(hand), listOf(target), viewer, now).also { result ->
                val hands = if (hand.id in result.owned) emptyList() else
                    listOf(HandInput(hand.id, hand.down, 0f, 0f, 0f, true))
                val actions = screen.update(hands, ScreenSize(2.8f, 1.575f), now, true)
                assertTrue(actions.taps.isEmpty()); assertTrue(actions.seekDirections.isEmpty())
            }
            frame(initial, 0)
            frame(initial.copy(down = true), 10)
            val nearHand = initial.copy(down = true,
                pose = Pose(initial.pose.t - initial.pose.forward() * .1f, initial.pose.q))
            frame(nearHand, 100)
            frame(nearHand, 360)
            frame(nearHand, 400)
            val pushed = nearHand.copy(pose = Pose(nearHand.pose.t + Vector3(0f, 0f, .22f), nearHand.pose.q))
            frame(pushed, 480)
            val result = frame(pushed.copy(down = false), 490)
            assertTrue(initial.id in result.owned)
            assertEquals(visitor.asset, result.returned.single().target.asset)
            assertTrue(result.returned.single().velocity.z > .6f)
            assertNull(result.hover)
            assertTrue(inspection.overrides.isEmpty())
            assertTrue(inspection.update(listOf(pushed.copy(down = false)), emptyList(), viewer, 500).returned.isEmpty())
        }
    }

    @Test fun slowInwardStaleOrLostTrackingReleaseNeverThrows() {
        for (kind in listOf("slow", "inward", "stale", "lost", "blocked", "jump", "headMove", "approach")) {
            val target = target(SpaceVisitors.roadster)
            val inspection = VisitorInspection()
            val initial = pointer(target)
            inspection.update(listOf(initial), listOf(target), viewer, 0)
            inspection.update(listOf(initial.copy(down = true)), listOf(target), viewer, 10)
            var hand = initial.copy(down = true, pose = Pose(initial.pose.t - initial.pose.forward() * .1f, initial.pose.q))
            inspection.update(listOf(hand), listOf(target), viewer, 100)
            if (kind == "approach") {
                hand = hand.copy(pose = Pose(hand.pose.t + Vector3(0f, 0f, .22f), hand.pose.q))
                inspection.update(listOf(hand), listOf(target), viewer, 180)
                assertTrue(inspection.update(listOf(hand.copy(down = false)), listOf(target), viewer, 190).returned.isEmpty())
                continue
            }
            inspection.update(listOf(hand), listOf(target), viewer, 360)
            inspection.update(listOf(hand), listOf(target), viewer, 400)
            val delta = Vector3(0f, 0f, when (kind) { "slow" -> .02f; "inward" -> -.22f; "jump" -> 2f; else -> .22f })
            hand = hand.copy(pose = Pose(hand.pose.t + delta, hand.pose.q))
            val eye = if (kind == "headMove") Pose(viewer.t + delta) else viewer
            inspection.update(listOf(hand), listOf(target), eye, 480)
            val inputs = if (kind == "lost") emptyList() else listOf(hand.copy(down = false))
            val release = inspection.update(inputs, listOf(target), eye, if (kind == "stale") 800 else 490,
                blocked = kind == "blocked")
            assertTrue("$kind must leave the model parked", release.returned.isEmpty())
            assertTrue(target.asset in inspection.overrides)
        }
    }
}
