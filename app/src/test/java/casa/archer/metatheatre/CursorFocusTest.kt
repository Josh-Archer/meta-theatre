package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class CursorFocusTest {
    @Test fun alternatingHandDistancesDoNotSwapTheCursorButAnExplicitPressDoes() {
        val focus = CursorFocus()
        assertEquals(1L, focus.update(listOf(CursorCandidate(1, 2f, false), CursorCandidate(2, 2.1f, false)), 0))
        for (frame in 1..120) {
            val distance = if (frame % 2 == 0) 1.9f else 2.1f
            assertEquals(1L, focus.update(listOf(CursorCandidate(2, distance, false), CursorCandidate(1, 2f, false)), frame * 16L))
        }
        assertEquals(2L, focus.update(listOf(CursorCandidate(1, 2f, false), CursorCandidate(2, 2.1f, true)), 2000))
    }

    @Test fun briefTrackingLossDoesNotJumpToTheOtherHandAndSustainedLossAllowsHandoff() {
        val focus = CursorFocus()
        val left = CursorCandidate(1, 2f, false)
        val right = CursorCandidate(2, 2.1f, false)
        assertEquals(1L, focus.update(listOf(left, right), 0))
        assertEquals(1L, focus.update(listOf(right), 70))
        assertEquals(1L, focus.update(listOf(left, right), 100))
        assertEquals(1L, focus.update(listOf(right), 249))
        assertEquals(2L, focus.update(listOf(right), 250))
        focus.clear()
        assertNull(focus.update(emptyList(), 260))
        assertNull(focus.update(listOf(left.copy(distance = Float.NaN)), 300))
    }
}
