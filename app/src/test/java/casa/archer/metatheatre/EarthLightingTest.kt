package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class EarthLightingTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 10, 4, hour, minute, 0, 0, zone).toInstant()

    @Test fun localNoonMidnightAndHorizons() {
        val noon = EarthLighting.sunAt(at(12), zone)
        assertEquals(1f, noon.z, 1e-6f)
        assertEquals(0f, noon.x, 1e-6f)
        assertEquals(-1f, EarthLighting.sunAt(at(0), zone).z, 1e-6f)
        assertEquals(-1f, EarthLighting.sunAt(at(6), zone).x, 1e-6f)
        assertEquals(1f, EarthLighting.sunAt(at(18), zone).x, 1e-6f)
        assertEquals(0.5, EarthLighting.dayWeight(EarthLighting.sunAt(at(6), zone).z.toDouble()), 1e-6)
    }

    @Test fun sunriseAndSunsetAreContinuous() {
        assertTrue(EarthLighting.sunAt(at(5, 45), zone).z < 0f)
        assertTrue(EarthLighting.sunAt(at(6, 15), zone).z > 0f)
        assertTrue(EarthLighting.sunAt(at(17, 45), zone).z > 0f)
        assertTrue(EarthLighting.sunAt(at(18, 15), zone).z < 0f)
        for (hour in listOf(6, 18)) {
            val event = at(hour)
            val before = EarthLighting.sunAt(event.minusMillis(10), zone)
            val after = EarthLighting.sunAt(event.plusMillis(10), zone)
            assertTrue(kotlin.math.abs(after.z - before.z) < 2e-6f)
            assertTrue(kotlin.math.abs(EarthLighting.dayWeight(after.z.toDouble()) - EarthLighting.dayWeight(before.z.toDouble())) < 2e-5)
            assertTrue(kotlin.math.abs(EarthLighting.cityWeight(after.z.toDouble()) - EarthLighting.cityWeight(before.z.toDouble())) < 2e-5)
        }
    }

    @Test fun unitDirectionAcrossDayAndSmoothMidnightWrap() {
        for (minute in 0 until 1440) {
            val sun = EarthLighting.sunAt(at(0).plusSeconds(minute * 60L), zone)
            assertEquals(1.0, sqrt((sun.x * sun.x + sun.z * sun.z).toDouble()), 1e-6)
        }
        val midnight = at(0)
        val before = EarthLighting.sunAt(midnight.minusNanos(1), zone)
        val after = EarthLighting.sunAt(midnight.plusNanos(1), zone)
        assertEquals(before.x, after.x, 1e-6f)
        assertEquals(before.z, after.z, 1e-6f)
    }

    @Test fun resumeAndTimeZoneChangesUseCurrentWallClock() {
        assertTrue(EarthLighting.sunAt(at(12), zone).z > 0.99f)
        assertTrue(EarthLighting.sunAt(at(12).plusSeconds(12 * 3600L), zone).z < -0.99f)
        assertEquals(0.5f, EarthLighting.sunAt(at(12), ZoneId.of("UTC")).z, 1e-6f)
        // DST is intentionally civil time: local noon remains noon in either offset.
        for (month in listOf(1, 7)) {
            val noon = ZonedDateTime.of(2026, month, 4, 12, 0, 0, 0, zone).toInstant()
            assertEquals(1f, EarthLighting.sunAt(noon, zone).z, 1e-6f)
        }
    }

    @Test fun cityLightsAreMaskedDuringDayAndRiseThroughTwilight() {
        assertEquals(0.0, EarthLighting.cityWeight(1.0), 0.0)
        assertEquals(1.0, EarthLighting.cityWeight(-1.0), 0.0)
        val weights = listOf(0.02, 0.0, -0.04, -0.08, -0.12).map(EarthLighting::cityWeight)
        assertTrue(weights.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun sdkQuaternionCentersEquatorOnSeatWithNorthUp() {
        val orientation = EarthRenderer.orientation()
        val front = orientation * Vector3(0f, 0f, 1f)
        val target = (EarthRenderer.SEAT_EYE - EarthRenderer.CENTER).normalize()
        assertEquals(target.x, front.x, 1e-5f)
        assertEquals(target.y, front.y, 1e-5f)
        assertEquals(target.z, front.z, 1e-5f)
        val north = orientation * Vector3(0f, 1f, 0f)
        assertTrue(north.y > 0.95f)
        assertEquals(0f, front.x * north.x + front.y * north.y + front.z * north.z, 1e-5f)
    }
}
