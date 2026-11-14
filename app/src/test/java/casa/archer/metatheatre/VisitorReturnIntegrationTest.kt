package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class VisitorReturnIntegrationTest {
    @Test fun replayImmediatelyShowsTheCarAtTheCenterOfAPassEvenDuringTheHiddenInterval() {
        val car = SpaceVisitors.roadster
        for (elapsed in listOf(0.0, 12.0, 110.0, 1000.0)) {
            val offset = SpaceVisitors.replayOffset(car, elapsed)
            assertEquals(.5, SpaceVisitors.progress(car, elapsed - offset)!!, .0001)
            val position = SpaceVisitors.pose(car, SpaceVisitors.progress(car, elapsed - offset)!!).t
            assertEquals(0f, position.x - EarthRenderer.SEAT_EYE.x, .001f)
            assertNotNull(SpaceVisitors.progress(car, elapsed + 40.0 - offset))
        }
    }
    private fun target(visitor: SpaceVisitors.Visitor, progress: Double) = VisitorTarget(visitor.asset,
        SpaceVisitors.pose(visitor, progress), visitor.displayScale, if (visitor == SpaceVisitors.iss) 70f else 2.65f)

    @Test fun returningDuringAnEndedOrEndingPassResumesAVisibleOrbit() {
        for (visitor in SpaceVisitors.all) {
            for (elapsed in listOf(visitor.firstPassSeconds + visitor.durationSeconds + 2,
                visitor.firstPassSeconds + visitor.durationSeconds * .99)) {
                val offset = SpaceVisitors.returnOffset(visitor, elapsed, 0.0)
                assertEquals(.5, SpaceVisitors.progress(visitor, elapsed - offset)!!, .0001)
                val orbit = target(visitor, SpaceVisitors.progress(visitor, elapsed - offset)!!)
                val miniature = orbit.copy(pose = Pose(EarthRenderer.SEAT_EYE + Vector3(0f, .2f, 1.5f)),
                    scale = VisitorInspection.INSPECT_RADIUS / orbit.radius)
                val returnFlight = VisitorReturnFlight(miniature, Vector3(0f, 0f, 1f), 0)
                assertEquals(miniature.pose.t, returnFlight.frame(orbit, 0).pose.t)
                val halfway = returnFlight.frame(orbit, 600)
                assertTrue((halfway.pose.t - EarthRenderer.SEAT_EYE).length() > 1.5f)
                assertTrue(halfway.scale > miniature.scale && halfway.scale < orbit.scale)
                val advanced = target(visitor, SpaceVisitors.progress(visitor, elapsed + 1.2 - offset)!!)
                assertEquals(advanced, returnFlight.frame(advanced, 1200))
                assertTrue(returnFlight.finished(1200))
                assertNotNull(SpaceVisitors.progress(visitor, elapsed + 20 - offset))
            }
        }
    }

    @Test fun returnButtonUsesTheSameFlightAndAnActivePassKeepsItsPhase() {
        for (visitor in SpaceVisitors.all) {
            val elapsed = visitor.firstPassSeconds + visitor.durationSeconds * .3 + visitor.periodSeconds
            val offset = SpaceVisitors.returnOffset(visitor, elapsed, 0.0)
            assertEquals(0.0, offset, .0001)
            val orbit = target(visitor, .3)
            val start = orbit.copy(pose = Pose(EarthRenderer.SEAT_EYE + Vector3(.3f, 0f, 1.5f)),
                scale = VisitorInspection.INSPECT_RADIUS / orbit.radius)
            val flight = VisitorReturnFlight(start, Vector3(0f, 0f, 0f), 100)
            assertFalse(flight.finished(1299))
            assertEquals(orbit, flight.frame(orbit, 1300))
        }
    }

    @Test fun quietCinemaFreezesTheReturnAnimationAndResumesItWithTheFlyby() {
        var realNow = 0L
        val clock = PausableElapsedClock { realNow }
        clock.reset()
        val orbit = target(SpaceVisitors.roadster, .5)
        val miniature = orbit.copy(pose = Pose(EarthRenderer.SEAT_EYE + Vector3(0f, 0f, 1.5f)), scale = .2f)
        val flight = VisitorReturnFlight(miniature, Vector3(0f, 0f, 1f), 0)
        fun frame() = flight.frame(orbit, (clock.elapsedSeconds() * 1000).toLong())
        realNow = 500
        val before = frame()
        clock.setPaused(true)
        realNow += 60_000
        assertEquals(before, frame())
        assertFalse(flight.finished((clock.elapsedSeconds() * 1000).toLong()))
        clock.setPaused(false)
        realNow += 700
        assertEquals(orbit, frame())
    }
}
