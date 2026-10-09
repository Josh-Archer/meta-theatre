package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class OpenHandScreenIntegrationTest {
    private val viewer = Pose(Vector3(0f, 1.6f, 0f))
    private val screen = Pose(Vector3(0f, 1.4f, 3f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
    private fun palms(span: Float) = listOf(
        OpenPalm(0, HandPoint(-span / 2, 1.3f, .5f), HandPoint(1f, 0f, 0f)),
        OpenPalm(1, HandPoint(span / 2, 1.3f, .5f), HandPoint(-1f, 0f, 0f)))

    @Test fun nativeFacingPalmsCloseAndOpenWithoutSdkRayTargetsAfterRecenterOrTurn() {
        for (transform in listOf(Pose(), Pose(Vector3(2f, 0f, -3f), Quaternion(pitch = 0f, yaw = 90f, roll = 0f)))) {
            val gestures = OpenHandGestures()
            val motion = ScreenVisibilityMotion()
            fun input(span: Float) = OpenHandFrame.project(palms(span).map { HandTrackingSpace.toScene(it, transform) },
                transform * screen, transform * viewer, emptySet())
            assertTrue(input(.5f).all { it.inFrontOfScreen && !it.aimingAtScreen })
            gestures.update(input(.5f), true, false, 0)
            assertNull(gestures.update(input(.32f), true, false, 100).move)
            assertTrue(gestures.update(input(.20f), true, false, 300).close)
            motion.setHidden(true, 300)
            assertEquals(.5f, motion.fraction(420), .0001f)
            assertEquals(0f, motion.fraction(540), .0001f)
            gestures.update(input(.20f), true, false, 540, screenHidden = true)
            assertTrue(gestures.update(input(.50f), true, false, 840, screenHidden = true).restore)
            motion.setHidden(false, 840)
            assertEquals(.5f, motion.fraction(960), .0001f)
            assertEquals(1f, motion.fraction(1080), .0001f)
            assertFalse(motion.animating(1080))
        }
    }

    @Test fun offScreenPairCannotHideAndOwnedModelPinchCannotBecomeAClap() {
        val gestures = OpenHandGestures()
        fun input(span: Float) = OpenHandFrame.project(palms(span).map {
            it.copy(position = it.position.copy(z = -.5f)) }, screen, viewer, emptySet())
        assertTrue(input(.5f).none { it.inFrontOfScreen })
        gestures.update(input(.5f), true, false, 0)
        assertFalse(gestures.update(input(.2f), true, false, 300).close)
        val aimed = OpenHandFrame.project(palms(.5f), screen, viewer, emptySet())
        gestures.update(aimed, true, false, 400)
        assertEquals(OpenHandActions(), gestures.update(OpenHandFrame.project(palms(.2f), screen, viewer, emptySet()),
            true, true, 700))
    }

    @Test fun restoreFollowsTurnedViewAndVerticalGazeStillProducesAFiniteUprightScreen() {
        val turned = Pose(Vector3(2f, 1.6f, -3f), Quaternion(pitch = 0f, yaw = 90f, roll = 0f))
        val restored = ScreenPlacement.restore(turned, screen)
        assertTrue(restored.t.x > turned.t.x)
        assertEquals(turned.t.z, restored.t.z, .0001f)
        assertEquals(1f, restored.up().y, .0001f)
        assertTrue(restored.forward().dot(turned.forward()) < -.99f)
        val vertical = Pose(viewer.t, Quaternion(pitch = 90f, yaw = 0f, roll = 0f))
        val fallback = ScreenPlacement.restore(vertical, Pose(viewer.t))
        assertTrue(fallback.t.x.isFinite() && fallback.t.z.isFinite())
        assertEquals(1.5f, (fallback.t - viewer.t).length(), .0001f)
    }

    @Test fun reversingTheFoldKeepsItsCurrentWidth() {
        val motion = ScreenVisibilityMotion()
        motion.setHidden(true, 100)
        val halfway = motion.fraction(220)
        motion.setHidden(false, 220)
        assertEquals(halfway, motion.fraction(220), .0001f)
        assertTrue(motion.fraction(340) > halfway)
        assertEquals(1f, motion.fraction(460), .0001f)
    }
}
