package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

/** Exercise the same mode/ownership routing used by ScreenGrip, with recorded frame sequences. */
class ScreenHandIntegrationTest {
    private val initial = ScreenSize(2.8f, 1.575f)
    private fun pair(left: Boolean, right: Boolean, span: Float, aimed: Boolean = true) =
        listOf(HandInput(1, left, -span / 2, 1f, .3f, aimed),
               HandInput(2, right, span / 2, 1f, .3f, aimed))

    @Test fun libraryAndMovieResizeBothWaysWithoutClicksOrSeeks() {
        for (movie in listOf(false, true)) {
            val router = ScreenHandGestures()
            var size = initial
            router.update(pair(false, false, .4f), size, 0, movie)
            val begin = router.update(pair(true, true, .4f), size, 30, movie)
            assertTrue(begin.resizing)
            for ((time, span) in listOf(100L to .8f, 200L to .2f, 300L to .4f)) {
                val actions = router.update(pair(true, true, span, aimed = false), size, time, movie)
                assertTrue(actions.resizing) // rays may leave the screen while hands spread
                assertTrue(actions.taps.isEmpty())
                assertTrue(actions.seekDirections.isEmpty())
                size = actions.size!!
                assertEquals(initial.width * span / .4f, size.width, .0001f)
                assertEquals(initial.width / initial.height, size.width / size.height, .0001f)
            }
            val release = router.update(pair(false, false, .4f), size, 350, movie)
            assertFalse(release.resizing)
            assertTrue(release.taps.isEmpty())
            assertTrue(release.seekDirections.isEmpty())
        }
    }

    @Test fun firstPinchCanWaitForSecondHandWithoutAnInvisibleDeadline() {
        for (movie in listOf(false, true)) {
            val router = ScreenHandGestures()
            router.update(pair(false, false, .4f), initial, 0, movie)
            router.update(pair(true, false, .4f), initial, 10, movie)
            assertTrue(router.update(pair(true, true, .4f), initial, 1500, movie).resizing)
            assertEquals(4.2f, router.update(pair(true, true, .6f), initial, 1600, movie).size!!.width, .0001f)
            assertTrue(router.update(pair(false, false, .6f), initial, 1700, movie).taps.isEmpty())
        }
    }

    @Test fun libraryDefersSelectionAndNeverSeeksDuringDrag() {
        val router = ScreenHandGestures()
        val one = listOf(HandInput(1, false, 0f, 1f, .3f, true))
        router.update(one, initial, 0, false)
        assertTrue(router.update(one.map { it.copy(pinching = true) }, initial, 10, false).taps.isEmpty())
        assertEquals(listOf(1L), router.update(one, initial, 100, false).taps)
        router.update(one.map { it.copy(pinching = true) }, initial, 200, false)
        assertTrue(router.update(one.map { it.copy(pinching = true, x = .3f) }, initial, 300, false).seekDirections.isEmpty())
        assertTrue(router.update(one.map { it.copy(x = .3f) }, initial, 400, false).taps.isEmpty())
    }

    @Test fun hiddenNavigationAndModeSwitchCancelStretchAndRequireRelease() {
        for (block in listOf("hidden", "navigation", "mode")) {
            val router = ScreenHandGestures()
            router.update(pair(false, false, .4f), initial, 0, true)
            router.update(pair(true, true, .4f), initial, 10, true)
            val blocked = router.update(pair(true, true, .8f), initial, 100,
                watching = block != "mode", hidden = block == "hidden", navigating = block == "navigation")
            assertNull(blocked.size)
            val resumedMovie = block != "mode"
            assertFalse(router.update(pair(true, true, .8f), initial, 110, resumedMovie).resizing)
            assertTrue(router.update(pair(false, false, .4f), initial, 120, resumedMovie).taps.isEmpty())
            assertTrue(router.update(pair(true, true, .4f), initial, 130, resumedMovie).resizing)
        }
    }

    @Test fun railOwnedPinchCannotJoinStretch() {
        val router = ScreenHandGestures()
        router.update(pair(false, false, .4f), initial, 0, true)
        val mixed = pair(true, true, .4f).map { if (it.id == 2L) it.copy(onVideo = false) else it }
        assertFalse(router.update(mixed, initial, 10, true).resizing)
        assertFalse(router.update(pair(true, true, .4f), initial, 100, true).resizing)
    }
}
