package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class SpaceVisitorsTest {
    @Test fun passesRepeatAndAreHiddenBetweenVisits() {
        for (v in SpaceVisitors.all) {
            assertNull(SpaceVisitors.progress(v, v.firstPassSeconds - .001))
            assertEquals(0.0, SpaceVisitors.progress(v, v.firstPassSeconds)!!, 0.0)
            assertNull(SpaceVisitors.progress(v, v.firstPassSeconds + v.durationSeconds))
            assertEquals(.5, SpaceVisitors.progress(v, v.firstPassSeconds + v.durationSeconds / 2 + v.periodSeconds)!!, 1e-9)
            assertNull(SpaceVisitors.progress(v, Double.NaN))
        }
    }
    @Test fun visitorPathsKeepTheSeatClearAndStayAboveTheMovie() {
        for (v in SpaceVisitors.all) {
            for (i in 0..100) {
                val p = SpaceVisitors.pose(v, i / 100.0).t
                assertEquals(v.distance, (p - EarthRenderer.SEAT_EYE).length(), .001f)
                val relative = p - EarthRenderer.SEAT_EYE
                assertTrue("Flyby must remain in front of the seat", relative.z > 0)
                val yaw = Math.toDegrees(kotlin.math.atan2(relative.x.toDouble(), relative.z.toDouble()))
                assertTrue("The entire pass should stay in the forward view", kotlin.math.abs(yaw) <= 42.01)
                val elevation = Math.toDegrees(kotlin.math.asin((relative.y / relative.length()).toDouble()))
                assertTrue("Flyby should be visible with a small upward glance", elevation in 15.0..26.0)
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
            }
        }
    }
    @Test fun bothVisitorsAppearEarlyAndRepeatWithinTwoAndAHalfMinutes() {
        assertTrue(SpaceVisitors.iss.firstPassSeconds <= 3.0)
        assertTrue(SpaceVisitors.roadster.firstPassSeconds <= 12.0)
        for (v in SpaceVisitors.all) {
            assertTrue(v.periodSeconds <= 150.0)
            assertTrue(v.durationSeconds >= 80.0)
        }
    }
}
