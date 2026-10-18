package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import org.junit.Assert.*
import org.junit.Test

class ExplorationLightingIntegrationTest {
    private val viewers = listOf(Pose(EarthRenderer.SEAT_EYE.copy()),
        Pose(Vector3(2f, 1.3f, -3f), Quaternion(pitch = 10f, yaw = 90f, roll = 0f)))
    private val presets = listOf(EarthControl.NORTH_POLE, EarthControl.SOUTH_POLE, EarthControl.EQUATOR, EarthControl.FAR_SIDE)

    @Test fun midnightNoonDawnAndDuskFollowLocalTimeAtEveryViewAndManualSpin() {
        for (zone in listOf(ZoneId.of("America/New_York"), ZoneId.of("Pacific/Auckland"))) {
            for (viewer in viewers) for (preset in presets) {
                val exploration = EarthExploration()
                exploration.apply(preset, viewer)
                for (spin in listOf(0f, 75f, 180f, -120f)) {
                    exploration.spinBy(spin)
                    val earth = exploration.earthPose()
                    val normal = (viewer.t - earth.t).normalize()
                    val surface = earth.t + normal * EarthRenderer.RADIUS
                    for (hour in listOf(0, 6, 12, 18)) {
                        val instant = ZonedDateTime.of(2026, 10, 17, hour, 0, 0, 0, zone).toInstant()
                        val sun = exploration.sunPosition(instant, zone)
                        val incidence = normal.dot((sun - surface).normalize()).toDouble()
                        when (hour) {
                            0 -> { assertTrue("$preset midnight must be dark", incidence < -.99); assertEquals(1.0, EarthLighting.cityWeight(incidence), 0.0) }
                            12 -> assertTrue("$preset noon must be lit", incidence > .99)
                            else -> assertEquals("$preset terminator", 0.0, incidence, .001)
                        }
                        val shader = SolarSystem.shaderSunPosition(sun)
                        assertEquals(-sun.z, shader.z, 0f)
                    }
                }
            }
        }
    }

    @Test fun everyOrbitStepKeepsPlanetSilhouettesClearOfEarthWithoutChangingBodyScale() {
        for (viewer in viewers) {
            val exploration = EarthExploration()
            exploration.apply(EarthControl.SOUTH_POLE, viewer)
            repeat(13) {
                repeat(18) {
                    exploration.apply(EarthControl.ORBIT_RIGHT, viewer)
                    val earth = exploration.earthPose().t - viewer.t
                    val earthAngle = asin((EarthRenderer.RADIUS * 1.012f / earth.length()).toDouble())
                    for (body in SolarSystem.planets.filter { it.name != "earth" }) {
                        val relative = exploration.skyFrame * body.center - viewer.t
                        val bound = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
                        val bodyAngle = asin((bound / relative.length()).toDouble())
                        val angle = acos(earth.normalize().dot(relative.normalize()).toDouble().coerceIn(-1.0, 1.0))
                        assertTrue("${body.name} overlaps Earth at ${exploration.label()}", angle > earthAngle + bodyAngle)
                        assertTrue((exploration.skyFrame * body.center - exploration.earthPose().t).length() > bound + EarthRenderer.RADIUS)
                    }
                }
                exploration.apply(EarthControl.ORBIT_UP, viewer)
            }
        }
    }

    @Test fun switchingHemisphereKeepsTheSameSceneSunAndHomeRestoresAllFrames() {
        val viewer = viewers.last()
        val exploration = EarthExploration()
        val instant = ZonedDateTime.of(2026, 10, 17, 23, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        exploration.apply(EarthControl.EQUATOR, viewer)
        val sun = exploration.sunPosition(instant, ZoneId.of("UTC"))
        for (preset in presets) {
            exploration.apply(preset, viewer)
            assertTrue((exploration.sunPosition(instant, ZoneId.of("UTC")) - sun).length() < 1f)
        }
        exploration.apply(EarthControl.HOME, viewer)
        assertEquals(Pose(), exploration.worldFrame)
        assertEquals(Pose(), exploration.skyFrame)
    }
}
