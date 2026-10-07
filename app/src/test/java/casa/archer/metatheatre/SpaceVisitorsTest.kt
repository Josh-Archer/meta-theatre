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
                assertTrue(p.y > 25)
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
            }
        }
    }
}
