package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class MovieGesturesTest {
    private val screen = ScreenSize(2.8f, 1.575f)
    private fun hand(id: Long = 1, down: Boolean = false, x: Float = 0f, y: Float = 0f,
                     z: Float = 0f, onVideo: Boolean = true) = HandInput(id, down, x, y, z, onVideo)

    @Test fun quickPinchSelectsOnceOnRelease() {
        val gestures = MovieHandGestures()
        gestures.update(listOf(hand()), screen, 0)
        assertTrue(gestures.update(listOf(hand(down = true)), screen, 10).taps.isEmpty())
        assertEquals(listOf(1L), gestures.update(listOf(hand()), screen, 110).taps)
        assertTrue(gestures.update(listOf(hand()), screen, 120).taps.isEmpty())
    }

    @Test fun sidewaysSwipeSeeksOnceAndReleaseDoesNotPause() {
        for (direction in listOf(-1, 1)) {
            val gestures = MovieHandGestures()
            gestures.update(listOf(hand()), screen, 0)
            gestures.update(listOf(hand(down = true)), screen, 10)
            assertTrue(gestures.update(listOf(hand(down = true, x = direction * 0.08f)), screen, 100).seekDirections.isEmpty())
            assertEquals(listOf(direction), gestures.update(listOf(hand(down = true, x = direction * 0.14f)), screen, 150).seekDirections)
            assertTrue(gestures.update(listOf(hand(down = true, x = direction * 0.25f)), screen, 200).seekDirections.isEmpty())
            assertTrue(gestures.update(listOf(hand(x = direction * 0.25f)), screen, 250).taps.isEmpty())
        }
    }

    @Test fun verticalMovementAndTrackingReacquisitionDoNotSeekOrSelect() {
        val gestures = MovieHandGestures()
        gestures.update(listOf(hand()), screen, 0)
        gestures.update(listOf(hand(down = true)), screen, 10)
        assertTrue(gestures.update(listOf(hand(down = true, x = 0.14f, y = 0.2f)), screen, 200).seekDirections.isEmpty())
        assertTrue(gestures.update(listOf(hand(x = 0.14f, y = 0.2f)), screen, 250).taps.isEmpty())
        gestures.update(emptyList(), screen, 300)
        gestures.update(listOf(hand(down = true)), screen, 400)
        assertTrue(gestures.update(listOf(hand(down = true, x = 0.2f)), screen, 450).seekDirections.isEmpty())
        assertTrue(gestures.update(listOf(hand()), screen, 460).taps.isEmpty())
    }

    @Test fun twoHandStretchUsesInitialSizeRatherThanCompoundingEveryFrame() {
        val gestures = MovieHandGestures()
        fun pair(down: Boolean, span: Float) = listOf(hand(1, down, -span / 2), hand(2, down, span / 2))
        gestures.update(pair(false, 0.4f), screen, 0)
        assertTrue(gestures.update(pair(true, 0.4f), screen, 10).resizing)
        val grown = gestures.update(pair(true, 0.8f), screen, 110)
        assertEquals(5.6f, grown.size!!.width, 0.0001f)
        val stable = gestures.update(pair(true, 0.8f), grown.size, 150)
        assertEquals(grown.size, stable.size)
        val shrunk = gestures.update(pair(true, 0.2f), grown.size, 200)
        assertEquals(1.4f, shrunk.size!!.width, 0.0001f)
        assertEquals(screen.width / screen.height, shrunk.size.width / shrunk.size.height, 0.0001f)
        val release = gestures.update(pair(false, 0.2f), shrunk.size, 250)
        assertFalse(release.resizing)
        assertTrue(release.taps.isEmpty())
        assertTrue(release.seekDirections.isEmpty())
    }

    @Test fun losingOneHandStopsStretchAndConsumesRemainingPinch() {
        val gestures = MovieHandGestures()
        gestures.update(listOf(hand(1, false, -0.2f), hand(2, false, 0.2f)), screen, 0)
        gestures.update(listOf(hand(1, true, -0.2f), hand(2, true, 0.2f)), screen, 10)
        val lost = gestures.update(listOf(hand(1, true, -0.4f)), screen, 100)
        assertFalse(lost.resizing)
        assertNull(lost.size)
        assertTrue(lost.seekDirections.isEmpty())
        val reappeared = gestures.update(listOf(hand(1, true, -0.4f), hand(2, true, 0.4f)), screen, 200)
        assertFalse(reappeared.resizing)
        assertTrue(gestures.update(listOf(hand(1), hand(2)), screen, 250).taps.isEmpty())
    }

    @Test fun pinchStartingOverControlsCannotBecomeMovieSwipeOrStretch() {
        val gestures = MovieHandGestures()
        gestures.update(listOf(hand(onVideo = false)), screen, 0)
        gestures.update(listOf(hand(down = true, onVideo = false)), screen, 10)
        assertTrue(gestures.update(listOf(hand(down = true, x = 0.2f)), screen, 100).seekDirections.isEmpty())
        assertTrue(gestures.update(listOf(hand(x = 0.2f)), screen, 150).taps.isEmpty())
    }

    @Test fun stretchClampsAtBothScreenBounds() {
        val gestures = MovieHandGestures()
        fun pair(down: Boolean, span: Float) = listOf(hand(1, down, -span / 2), hand(2, down, span / 2))
        gestures.update(pair(false, 0.4f), screen, 0)
        gestures.update(pair(true, 0.4f), screen, 10)
        assertEquals(ScreenSize.MAX_WIDTH, gestures.update(pair(true, 2f), screen, 20).size!!.width, 0f)
        assertEquals(ScreenSize.MIN_WIDTH, gestures.update(pair(true, 0.02f), screen, 30).size!!.width, 0f)
    }

    @Test fun stickSeeksOnDeflectionAndRepeatsWithoutBurstAfterDelayedFrame() {
        val stick = MovieStickSeek()
        assertEquals(-1, stick.update(-1, 0))
        assertEquals(0, stick.update(-1, 749))
        assertEquals(-1, stick.update(-1, 750))
        assertEquals(0, stick.update(-1, 751))
        assertEquals(-1, stick.update(-1, 5_000))
        assertEquals(0, stick.update(-1, 5_001))
        assertEquals(0, stick.update(0, 5_010))
        assertEquals(-1, stick.update(-1, 5_020))
        assertEquals(1, stick.update(1, 5_030))
    }

    @Test fun railCanHideUnderStillPointerThenRevealOnMovementOrReentry() {
        val motion = RailRevealMotion()
        val hide = PlayerAutoHide()
        hide.setWatching(true, 0)
        if (motion.update(1, 0.3f, 0.4f)) hide.interact(100)
        if (motion.update(1, 0.3f, 0.4f)) hide.interact(5_000)
        hide.tick(5_100)
        assertFalse(hide.visible)
        if (motion.update(1, 0.34f, 0.4f)) hide.interact(5_110)
        assertTrue(hide.visible)
        hide.tick(10_110)
        assertFalse(hide.visible)
        motion.update(1, null, null)
        if (motion.update(1, 0.34f, 0.4f)) hide.interact(10_120)
        assertTrue(hide.visible)
    }

    @Test fun rayTargetsMatchRenderedRectanglesAndSeekClampsOutsideTrack() {
        val targets = PlayerHitTargets()
        val larger = PlayerTarget.Button(PlayerControl.LARGER)
        targets.put(larger, PlayerHitRect(0.1f, 0.4f, 0.9f, 0.5f))
        targets.put(PlayerTarget.Seek, PlayerHitRect(0.1f, 0.2f, 0.9f, 0.25f))
        assertEquals(larger, targets.at(0.5f, 0.45f))
        assertNull(targets.at(0.5f, 0.39f))
        assertEquals(0.5f, targets.seekFraction(0.5f)!!, 0.0001f)
        assertEquals(0f, targets.seekFraction(-1f)!!, 0f)
        assertEquals(1f, targets.seekFraction(2f)!!, 0f)
        targets.clear()
        assertNull(targets.at(0.5f, 0.45f))
    }
}
