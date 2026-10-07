package casa.archer.metatheatre

import org.junit.Assert.*
import org.junit.Test

class EarthUpdateScheduleTest {
    @Test fun frameTicksUploadOncePerSecondAtAllQuestRefreshRates() {
        for (fps in listOf(72, 80, 90, 120)) {
            val schedule = EarthUpdateSchedule()
            val updates = (0 until fps * 10).count { frame ->
                schedule.shouldUpdate(frame * 1_000L / fps)
            }
            assertEquals("cadence at $fps Hz", 10, updates)
        }
    }

    @Test fun resumeForcesImmediateResampleAndRestartsCadence() {
        val schedule = EarthUpdateSchedule()
        assertTrue(schedule.shouldUpdate(0))
        assertFalse(schedule.shouldUpdate(200))
        assertTrue(schedule.shouldUpdate(200, force = true))
        assertFalse(schedule.shouldUpdate(1_199))
        assertTrue(schedule.shouldUpdate(1_200))
        assertTrue(schedule.shouldUpdate(60_000))
    }
}
