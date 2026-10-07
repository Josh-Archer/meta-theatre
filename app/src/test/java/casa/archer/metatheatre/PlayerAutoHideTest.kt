package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class PlayerAutoHideTest {
    @Test fun hidesAtFiveSecondsAndTimeAloneNeverRevealsIt() {
        val controls = PlayerAutoHide()
        controls.setWatching(true, 10_000)
        controls.tick(14_999)
        assertTrue(controls.visible)
        controls.tick(15_000)
        assertFalse(controls.visible)
        controls.tick(60_000)
        assertFalse(controls.visible)
        controls.interact(60_100)
        assertTrue(controls.visible)
    }

    @Test fun interactionRestartsTheDeadlineAndRepeatedWatchingDoesNot() {
        val controls = PlayerAutoHide()
        controls.setWatching(true, 0)
        controls.interact(4_000)
        controls.setWatching(true, 8_000)
        controls.tick(8_999)
        assertTrue(controls.visible)
        controls.tick(9_000)
        assertFalse(controls.visible)
    }

    @Test fun aHeldPinchKeepsControlsVisibleUntilFiveSecondsAfterRelease() {
        val controls = PlayerAutoHide()
        controls.setWatching(true, 0)
        controls.select(1, true, 1_000)
        controls.tick(20_000)
        assertTrue(controls.visible)
        controls.select(1, false, 20_000)
        controls.tick(24_999)
        assertTrue(controls.visible)
        controls.tick(25_000)
        assertFalse(controls.visible)
    }

    @Test fun unrelatedPointerReleasesCannotRevealHiddenControls() {
        val controls = PlayerAutoHide()
        controls.setWatching(true, 0)
        controls.tick(5_000)
        controls.select(99, false, 5_100)
        assertFalse(controls.visible)
    }

    @Test fun twoHandsCanInteractAndOpeningTheLibraryClearsTheirGestures() {
        val controls = PlayerAutoHide()
        controls.setWatching(true, 0)
        controls.select(1, true, 1_000)
        controls.select(2, true, 1_100)
        controls.select(1, false, 2_000)
        controls.tick(10_000)
        assertTrue(controls.visible)
        controls.setWatching(false, 11_000)
        controls.interact(12_000)
        assertFalse(controls.visible)
        controls.setWatching(true, 15_000)
        controls.tick(20_000)
        assertFalse(controls.visible)
    }
}
