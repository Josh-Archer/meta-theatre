package casa.archer.metatheatre

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LunarPhaseTest {
    @Test fun septemberAndOctober2026PhasesAgreeWithUsno() {
        // USNO primary phase times, UTC, aa.usno.navy.mil/calculated/moon/phases.
        for ((date, phase) in listOf(
            "2026-09-11T03:27:00Z" to 0.0, "2026-09-18T20:44:00Z" to .25,
            "2026-09-26T16:49:00Z" to .5, "2026-10-03T13:25:00Z" to .75)) {
            val cycle = LunarPhase.at(Instant.parse(date)).cycle
            val error = kotlin.math.abs(cycle - phase).let { kotlin.math.min(it, 1 - it) }
            assertEquals(date, 0.0, error, 0.001)
        }
    }
    @Test fun currentDateIsWaningCrescentAndDoesNotRepeatEveryDay() {
        val now = Instant.parse("2026-10-05T23:00:00Z")
        val phase = LunarPhase.at(now)
        assertFalse(phase.waxing)
        assertTrue(phase.fraction in .15.. .4)
        assertTrue(LunarPhase.at(now.plusSeconds(86400)).fraction < phase.fraction - .03)
        assertTrue(LunarPhase.at(now.plusSeconds(15 * 86400)).waxing)
    }
    @Test fun moonGeometryProducesCalculatedPhaseFromTheSharedSunInEveryTimezone() {
        for (zone in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
            for (day in 1..31) {
                val instant = Instant.parse("2026-10-01T00:00:00Z").plusSeconds((day - 1) * 86400L)
                val sun = SolarSystem.sunPosition(instant, ZoneId.of(zone))
                val moon = SolarSystem.moonAt(instant, sun)
                val light = (sun - moon.center).normalize()
                val view = (EarthRenderer.SEAT_EYE - moon.center).normalize()
                val renderedFraction = (1 + light.dot(view)) / 2.0
                assertEquals(LunarPhase.at(instant).fraction, renderedFraction, 2e-6)
                assertEquals(300f, (moon.center - EarthRenderer.SEAT_EYE).length(), .001f)
            }
        }
    }
}
