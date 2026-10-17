package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class ScreenDepthIntegrationTest {
    private val viewer = Pose(Vector3(0f, 1.6f, 0f))
    private val screen = Pose(Vector3(0f, 1.4f, 3f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
    private fun palms(z: Float, pull: Boolean = false) = listOf(-.24f, .24f).mapIndexed { id, x ->
        OpenPalm(id, HandPoint(x, 1.4f, z), HandPoint(0f, 0f, if (pull) -1f else 1f))
    }
    private fun input(z: Float, pull: Boolean = false, transform: Pose = Pose()) = OpenHandFrame.project(
        palms(z, pull).map { HandTrackingSpace.toScene(it, transform) }, transform * screen, transform * viewer, emptySet())

    @Test fun nativePalmsMoveScreenAwayAndCloserAfterWorldTurnWithoutResizingOrHiding() {
        for (transform in listOf(Pose(), Pose(Vector3(2f, 0f, -3f), Quaternion(pitch = 0f, yaw = 90f, roll = 0f)))) {
            val g = OpenHandGestures()
            var current = transform * screen
            val eye = transform * viewer
            val originalDistance = (current.t - eye.t).length()
            g.update(input(.5f, transform = transform), true, false, 0)
            val push = g.update(input(.6f, transform = transform), true, false, 100)
            assertEquals(.4f, push.depthMeters!!, .0001f)
            assertNull(push.move); assertFalse(push.close); assertFalse(push.pause)
            current = ScreenPlacement.shiftDepth(eye, current, push.depthMeters)
            assertEquals(originalDistance + .4f, (current.t - eye.t).length(), .0001f)
            g.update(input(.65f, pull = true, transform = transform), true, false, 200)
            val pull = g.update(input(.55f, pull = true, transform = transform), true, false, 300)
            assertEquals(-.4f, pull.depthMeters!!, .0001f)
            current = ScreenPlacement.shiftDepth(eye, current, pull.depthMeters)
            assertEquals(originalDistance, (current.t - eye.t).length(), .0001f)
            assertTrue(current.forward().dot((transform * screen).forward()) > .999f)
        }
    }

    @Test fun oneMovingHandLateralMotionPinchingAndTrackingLossCannotMoveDepth() {
        val g = OpenHandGestures()
        g.update(input(.5f), true, false, 0)
        val oneMoving = listOf(input(.5f)[0], input(.6f)[1])
        assertEquals(0f, g.update(oneMoving, true, false, 100).depthMeters!!, 0f)
        g.update(emptyList(), true, false, 200)
        assertEquals(0f, g.update(input(.7f), true, false, 300).depthMeters!!, 0f)
        assertEquals(OpenHandActions(), g.update(input(.8f), true, true, 400))
        assertEquals(0f, g.update(input(.9f), true, false, 500).depthMeters!!, 0f)
        val lateral = input(.9f).map { it.copy(viewPosition = it.viewPosition.copy(x = it.viewPosition.x + .2f)) }
        assertEquals(0f, g.update(lateral, true, false, 600).depthMeters!!, 0f)
    }

    @Test fun pullCanRestoreHiddenScreenEvenWhenItsPreviousLocationIsBehindTheViewer() {
        val turned = Pose(viewer.t, Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
        val transform = Pose(Vector3(0f, 0f, 0f), turned.q)
        fun frame(z: Float) = OpenHandFrame.project(palms(z, true).map { HandTrackingSpace.toScene(it, transform) },
            screen, turned, emptySet())
        assertTrue(frame(.65f).all { it.raisedInView && !it.inFrontOfScreen })
        val g = OpenHandGestures()
        g.update(frame(.65f), true, false, 0, screenHidden = true)
        assertTrue(g.update(frame(.55f), true, false, 150, screenHidden = true).restore)
        val restored = ScreenPlacement.restore(turned, screen)
        assertTrue((restored.t - turned.t).dot(turned.forward()) > 1f)
    }

    @Test fun depthClampsAtReachableDistancesAndRejectsTrackingJumps() {
        assertEquals(6f, (ScreenPlacement.shiftDepth(viewer, screen, 100f).t - viewer.t).length(), .0001f)
        assertEquals(1.5f, (ScreenPlacement.shiftDepth(viewer, screen, -100f).t - viewer.t).length(), .0001f)
        assertEquals(screen, ScreenPlacement.shiftDepth(viewer, screen, Float.NaN))
        val g = OpenHandGestures()
        g.update(input(.5f), true, false, 0)
        assertEquals(0f, g.update(input(1f), true, false, 100).depthMeters!!, 0f)
        assertEquals(0f, g.update(input(1f), true, false, 200).depthMeters!!, 0f)
    }

    @Test fun stopPalmNeedsOneRaisedOutwardPalmHeldStillAndFiresOnlyOnce() {
        val g = OpenHandGestures()
        val stop = input(.5f).take(1)
        assertTrue(stop.single().raisedInView)
        assertEquals(OpenHandActions(), g.update(stop, true, false, 0))
        assertFalse(g.update(stop, true, false, 490).pause)
        assertTrue(g.update(stop, true, false, 510).pause)
        assertFalse(g.update(stop, true, false, 1500).pause)
        g.update(emptyList(), true, false, 1600)
        g.update(stop, true, false, 1700)
        assertTrue(g.update(stop, true, false, 2250).pause)
        g.reset(); g.update(input(.5f, true).take(1), true, false, 0)
        assertFalse(g.update(input(.5f, true).take(1), true, false, 600).pause)
        g.reset(); g.update(stop, true, true, 0)
        assertFalse(g.update(stop, true, false, 500).pause)
        g.reset(); g.update(stop, true, false, 0, allowPause = false)
        assertFalse(g.update(stop, true, false, 600, allowPause = false).pause)
        val restingOtherHand = input(.5f)[1].copy(raisedInView = false)
        g.reset(); g.update(stop + restingOtherHand, true, false, 0)
        assertTrue(g.update(stop + restingOtherHand, true, false, 600).pause)
    }
}
