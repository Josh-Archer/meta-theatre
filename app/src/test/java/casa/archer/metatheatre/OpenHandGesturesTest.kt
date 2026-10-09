package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class OpenHandGesturesTest {
    private fun hand(id: Int = 0, x: Float = 0f, y: Float = 0f, z: Float = 0f, aim: Boolean = true,
                     normal: HandPoint = HandPoint(0f, 0f, 1f)) = OpenHandInput(id, HandPoint(x, y, z), normal, aim)
    private fun pair(span: Float, facing: Boolean = true) = listOf(
        hand(x = -span / 2, normal = HandPoint(1f, 0f, 0f)),
        hand(id = 1, x = span / 2, normal = if (facing) HandPoint(-1f, 0f, 0f) else HandPoint(1f, 0f, 0f)))

    @Test fun allFourSwipesMoveOnceAndDoNotClose() {
        for ((direction, end) in listOf(ScreenDirection.LEFT to hand(x = -0.2f), ScreenDirection.RIGHT to hand(x = 0.2f),
            ScreenDirection.UP to hand(y = 0.2f), ScreenDirection.DOWN to hand(y = -0.2f))) {
            val gestures = OpenHandGestures()
            gestures.update(listOf(hand()), true, false, 0)
            val action = gestures.update(listOf(end), true, false, 200)
            assertEquals(direction, action.move); assertFalse(action.close)
            assertNull(gestures.update(listOf(end), true, false, 210).move)
            assertNull(gestures.update(listOf(end), true, false, 600).move)
        }
    }
    @Test fun tinySlowDiagonalAndDepthStrokesAreIgnored() {
        for ((end, time) in listOf(hand(x = 0.08f) to 200L, hand(x = 0.2f) to 900L,
            hand(x = 0.2f, y = 0.2f) to 200L, hand(x = 0.2f, z = 0.3f) to 200L)) {
            val g = OpenHandGestures(); g.update(listOf(hand()), true, false, 0)
            assertNull(g.update(listOf(end), true, false, time).move)
        }
    }
    @Test fun swipeMustStartPointingAtScreenAndTrackingLossCancelsStroke() {
        val g = OpenHandGestures()
        g.update(listOf(hand(aim = false)), true, false, 0)
        assertNull(g.update(listOf(hand(x = 0.2f, aim = false)), true, false, 200).move)
        g.update(listOf(hand()), true, false, 300)
        g.update(emptyList(), true, false, 400)
        assertNull(g.update(listOf(hand(x = 0.3f)), true, false, 500).move)
    }
    @Test fun pinchAndControllerGripBlockOpenGesturesWithReleaseGracePeriod() {
        val g = OpenHandGestures()
        g.update(listOf(hand()), true, false, 0)
        assertEquals(OpenHandActions(), g.update(pair(0.1f), true, true, 200))
        assertEquals(OpenHandActions(), g.update(listOf(hand(x = 0.3f)), true, false, 300))
        assertEquals(OpenHandActions(), g.update(listOf(hand(x = 0.4f)), true, false, 600))
        assertEquals(ScreenDirection.RIGHT, g.update(listOf(hand(x = 0.6f)), true, false, 800).move)
    }
    @Test fun bothOpenPalmsClosingTogetherCloseOnceBeforeIndividualSwipes() {
        val g = OpenHandGestures()
        g.update(pair(0.5f), true, false, 0)
        assertEquals(OpenHandActions(), g.update(pair(0.3f), true, false, 100))
        val close = g.update(pair(0.12f), true, false, 200)
        assertTrue(close.close); assertNull(close.move)
        assertFalse(g.update(pair(0.10f), true, false, 1500).close)
    }
    @Test fun RestingNearbyHandsOneMovingHandAndPalmsFacingSameWayCannotClose() {
        val g = OpenHandGestures()
        assertFalse(g.update(pair(0.1f), true, false, 0).close)
        assertFalse(g.update(pair(0.08f), true, false, 200).close)
        g.reset(); g.update(pair(0.5f), true, false, 0)
        assertFalse(g.update(listOf(pair(0.5f)[0], hand(1, x = -0.14f, normal = HandPoint(-1f, 0f, 0f))), true, false, 200).close)
        g.reset(); g.update(pair(0.5f, false), true, false, 0)
        assertFalse(g.update(pair(0.1f, false), true, false, 200).close)
        g.reset(); g.update(pair(0.5f), false, false, 0)
        assertFalse(g.update(pair(0.1f), false, false, 200).close)
    }
    @Test fun lossOfEitherHandAndSlowClosureCancelClap() {
        val g = OpenHandGestures(); g.update(pair(0.5f), true, false, 0)
        g.update(pair(0.3f).take(1), true, false, 100)
        assertFalse(g.update(pair(0.1f), true, false, 200).close)
        g.reset(); g.update(pair(0.5f), true, false, 0)
        assertFalse(g.update(pair(0.1f), true, false, 1100).close)
    }

    @Test fun closingThenSpreadingRestoresHiddenScreenWithoutAnInvisibleRayTarget() {
        val g = OpenHandGestures()
        g.update(pair(0.5f), true, false, 0)
        assertTrue(g.update(pair(0.12f), true, false, 200).close)
        g.update(pair(0.12f).map { it.copy(aimingAtScreen = false) }, true, false, 220, screenHidden = true)
        val restore = g.update(pair(0.5f).map { it.copy(aimingAtScreen = false) }, true, false, 420, screenHidden = true)
        assertTrue(restore.restore)
        assertFalse(restore.close)
        assertNull(restore.move)
        assertFalse(g.update(pair(0.5f), true, false, 440, screenHidden = true).restore)
    }

    @Test fun hiddenScreenRejectsTrackingLossSlowAndOneHandedRestores() {
        val g = OpenHandGestures()
        g.update(pair(0.12f), true, false, 0, true)
        g.update(emptyList(), true, false, 100, true)
        assertFalse(g.update(pair(0.5f), true, false, 200, true).restore)
        g.reset(); g.update(pair(0.12f), true, false, 0, true)
        assertFalse(g.update(pair(0.5f), true, false, 1100, true).restore)
        g.reset(); g.update(pair(0.12f), true, false, 0, true)
        val oneHand = listOf(pair(0.12f)[0], hand(1, x = 0.5f, normal = HandPoint(-1f, 0f, 0f)))
        assertFalse(g.update(oneHand, true, false, 200, true).restore)
    }

    @Test fun hidingMustStartAtTheScreenAndPinchingBlocksRestoring() {
        val g = OpenHandGestures()
        g.update(pair(0.5f).map { it.copy(aimingAtScreen = false) }, true, false, 0)
        assertFalse(g.update(pair(0.12f), true, false, 200).close)
        g.update(pair(0.12f), true, true, 500, true)
        g.update(pair(0.12f), true, false, 600, true)
        assertFalse(g.update(pair(0.5f), true, false, 800, true).restore)
    }

    private fun joints(): MutableList<HandJoint> {
        val j = MutableList(26) { HandJoint(HandPoint.ZERO, 10) }
        j[0] = HandJoint(HandPoint(0f, 0.05f, 0f), 10)
        for ((finger, x) in listOf(6 to -0.035f, 11 to -0.01f, 16 to 0.015f, 21 to 0.035f)) {
            repeat(5) { n -> j[finger + n] = HandJoint(HandPoint(x, 0.05f + n * 0.02f, 0f), 10) }
        }
        j[3] = HandJoint(HandPoint(-0.04f, 0.02f, 0f), 10)
        j[4] = HandJoint(HandPoint(-0.06f, 0.035f, 0f), 10)
        j[5] = HandJoint(HandPoint(-0.08f, 0.05f, 0f), 10)
        return j
    }
    @Test fun straightFingersProduceAnOpenPalmButFoldedFingerAndPinchDoNot() {
        val j = joints(); assertNotNull(OpenHandPose.read(0, j))
        val folded = j.toMutableList(); folded[10] = folded[7]
        assertNull(OpenHandPose.read(0, folded))
        val pinched = j.toMutableList(); pinched[5] = pinched[10]
        assertNull(OpenHandPose.read(0, pinched))
    }
    @Test fun invalidUntrackedAndNonfiniteJointDataCannotTriggerOpenPose() {
        val j = joints()
        j[0] = j[0].copy(flags = 2); assertNull(OpenHandPose.read(0, j))
        j[0] = j[0].copy(flags = 10); j[8] = j[8].copy(flags = 0); assertNull(OpenHandPose.read(0, j))
        j[8] = j[8].copy(flags = 10, point = HandPoint(Float.NaN, 0f, 0f)); assertNull(OpenHandPose.read(0, j))
        assertNull(OpenHandPose.read(0, emptyList()))
    }
}
