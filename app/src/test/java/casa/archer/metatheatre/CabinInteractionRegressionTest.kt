package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class CabinInteractionRegressionTest {
    @Test fun distantWallResizeAllowsNearMissWithoutCreatingClicksOrSeeks() {
        val screen = Pose(Vector3(0f, 2.15f, -6.95f))
        val size = ScreenSize(3.2f, 1.8f)
        val hand = Pose(Vector3(0f, 1.2f, 0f), Quaternion(0f, 198f, 0f))
        assertTrue(ScreenResizeAim.accepts(hand, screen, size))
        assertFalse(ScreenResizeAim.accepts(Pose(hand.t, Quaternion()), screen, size))
        assertFalse(ScreenResizeAim.accepts(Pose(hand.t, Quaternion(0f, 270f, 0f)), screen, size))
        for (movie in listOf(false, true)) {
            val gestures = ScreenHandGestures()
            fun hands(down: Boolean, span: Float) = listOf(
                HandInput(1, down, -span/2, 1f, .3f, false, resizeEligible = true),
                HandInput(2, down, span/2, 1f, .3f, false, resizeEligible = true))
            gestures.update(hands(false, .4f), size, 0, movie)
            assertTrue(gestures.update(hands(true, .4f), size, 20, movie).resizing)
            val grown = gestures.update(hands(true, .6f), size, 100, movie)
            assertEquals(4.8f, grown.size!!.width, .001f)
            assertTrue(grown.seekDirections.isEmpty())
            assertTrue(gestures.update(hands(false, .6f), grown.size, 150, movie).taps.isEmpty())
            // A single near-miss pinch must never click or seek.
            val single = hands(false, .4f).take(1)
            gestures.update(single, size, 200, movie)
            gestures.update(single.map { it.copy(pinching = true) }, size, 220, movie)
            assertTrue(gestures.update(single.map { it.copy(pinching = true, x = .5f) }, size, 260, movie).seekDirections.isEmpty())
            assertTrue(gestures.update(single, size, 300, movie).taps.isEmpty())
        }
    }

    @Test fun navigationTargetsBridgeBriefTrackingGapsButDoNotLatch() {
        val visibility = NavigationTargetVisibility()
        assertFalse(visibility.update(false, 0))
        assertTrue(visibility.update(true, 10))
        assertTrue(visibility.update(false, 30))
        assertTrue(visibility.update(true, 60))
        assertTrue(visibility.update(false, 200))
        assertFalse(visibility.update(false, 261))
        assertFalse(visibility.update(false, 500))
    }
}
