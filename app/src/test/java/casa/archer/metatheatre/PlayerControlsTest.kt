package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class PlayerControlsTest {
    @Test fun screenCanGrowAndShrinkDuringPlaybackWithoutChangingAspectRatio() {
        val initial = ScreenSize(2.8f, 1.575f)
        val larger = initial.resized(ScreenSize.GROW)
        assertTrue(larger.width > initial.width)
        assertEquals(initial.width / initial.height, larger.width / larger.height, 0.00001f)
        val restored = larger.resized(ScreenSize.SHRINK)
        assertEquals(initial.width, restored.width, 0.00001f)
        assertEquals(initial.height, restored.height, 0.00001f)
    }

    @Test fun repeatedResizingStopsAtBoundsAndPreservesAspectRatio() {
        val initial = ScreenSize(2.8f, 1.575f)
        val smallest = (1..40).fold(initial) { size, _ -> size.resized(ScreenSize.SHRINK) }
        val largest = (1..40).fold(initial) { size, _ -> size.resized(ScreenSize.GROW) }
        assertEquals(ScreenSize.MIN_WIDTH, smallest.width, 0f)
        assertEquals(ScreenSize.MAX_WIDTH, largest.width, 0f)
        assertEquals(initial.width / initial.height, smallest.width / smallest.height, 0.00001f)
        assertEquals(initial.width / initial.height, largest.width / largest.height, 0.00001f)
        assertEquals(initial, initial.resized(Float.NaN))
        assertEquals(initial, initial.resized(-1f))
    }

    @Test fun railStaysOutsideTheVideoAndAtItsLowerEdgeAtEveryScreenSize() {
        for (width in listOf(ScreenSize.MIN_WIDTH, 2.8f, ScreenSize.MAX_WIDTH)) {
            val screen = ScreenSize(width, width * 9f / 16f)
            val rail = SideRailPlacement.beside(screen)
            assertEquals(width / 2f + Stage.PLAYER_GAP, rail.right - Stage.PLAYER_WIDTH / 2f, 0.00001f)
            assertEquals(-screen.height / 2f, rail.up - Stage.PLAYER_HEIGHT / 2f, 0.00001f)
        }
    }

    @Test fun sdkBaseDimensionRefreshDoesNotUndoPhysicalScreenScale() {
        val sdkShape = ScreenSize(2.8f, 1.575f)
        val desired = sdkShape.resized(2f)
        val scaleX = desired.width / sdkShape.width
        val scaleY = desired.height / sdkShape.height
        // ISDK publishes the unchanged shape dimensions again on subsequent frames.
        for (refreshedBase in List(3) { sdkShape.copy() }) {
            val physical = refreshedBase.scaled(scaleX, scaleY)
            assertEquals(desired, physical)
            val rail = SideRailPlacement.beside(physical)
            assertEquals(desired.width / 2f + Stage.PLAYER_GAP,
                rail.right - Stage.PLAYER_WIDTH / 2f, 0.00001f)
        }
    }
}
