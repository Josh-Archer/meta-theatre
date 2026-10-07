package casa.archer.metatheatre

import com.meta.spatial.core.Vector3
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import org.junit.Assert.*
import org.junit.Test

class SolarSystemTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(hour: Int) = ZonedDateTime.of(2026, 10, 4, hour, 0, 0, 0, zone).toInstant()
    private fun assertVector(expected: Vector3, actual: Vector3, tolerance: Float) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test fun allEightPlanetsAndMoonShareOnePhysicalRadiusScale() {
        assertEquals(listOf("mercury", "venus", "earth", "mars", "jupiter", "saturn", "uranus", "neptune"), SolarSystem.planets.map { it.name })
        for (body in SolarSystem.planets + SolarSystem.moon) {
            assertEquals(body.radiusKm / SolarSystem.EARTH_MEAN_RADIUS_KM, body.radius / 90.0, 1e-6)
        }
        assertEquals(695700.0 / SolarSystem.EARTH_MEAN_RADIUS_KM, SolarSystem.sunRadius / 90.0, 1e-5)
        assertEquals(1737.4 / SolarSystem.EARTH_MEAN_RADIUS_KM, SolarSystem.moon.radius / 90.0, 1e-7)
    }

    @Test fun sunIsExactlyTheEarthCivilTimeLightTransformedToWorldSpace() {
        for (hour in 0..23) {
            val earthSun = EarthLighting.sunAt(at(hour), zone)
            val position = SolarSystem.sunPosition(at(hour), zone)
            val local = SolarSystem.localSun(SolarSystem.earth, position).normalize()
            assertVector(Vector3(earthSun.x, earthSun.y, earthSun.z), local, 2e-6f)
            assertEquals(SolarSystem.SUN_DISTANCE, (position - SolarSystem.earth.center).length(), 0.5f)
        }
    }

    @Test fun everyTiltedBodyReconstructsTheSamePointSun() {
        for (hour in listOf(0, 6, 12, 18)) {
            val position = SolarSystem.sunPosition(at(hour), zone)
            for (body in SolarSystem.planets + SolarSystem.moon) {
                val local = SolarSystem.localSun(body, position)
                val reconstructed = body.center + body.rotation * local * body.radius
                assertVector(position, reconstructed, 1f)
            }
        }
    }

    @Test fun saturnViewingTiltOpensItsRingsTowardTheSeatedViewer() {
        val saturn = SolarSystem.planets.first { it.name == "saturn" }
        val ringNormal = saturn.rotation * Vector3(0f, 1f, 0f)
        val view = (EarthRenderer.SEAT_EYE - saturn.center).normalize()
        assertTrue(kotlin.math.abs(ringNormal.dot(view)) > 0.3f)
    }

    @Test fun globeMeridiansFaceSeatBeforeExhibitTilt() {
        for (body in SolarSystem.planets.filter { it.name != "saturn" }) {
            val front = body.rotation * Vector3(0f, 0f, 1f)
            val view = (EarthRenderer.SEAT_EYE - body.center).normalize()
            assertVector(view, front, 2e-5f)
        }
    }

    @Test fun sphereAndRingBoundsDoNotIntersectBodiesOrTheatre() {
        val bodies = SolarSystem.planets + SolarSystem.moon
        fun radius(body: SolarSystem.Body) = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
        for ((index, body) in bodies.withIndex()) {
            assertTrue("${body.name} leaves the theatre clear", (body.center - EarthRenderer.SEAT_EYE).length() - radius(body) > 40f)
            for (other in bodies.drop(index + 1)) {
                assertTrue("${body.name}/${other.name}", (body.center - other.center).length() > radius(body) + radius(other))
            }
        }
    }

    @Test fun exhibitSilhouettesAreSeparatedInTheSeatedSky() {
        val bodies = SolarSystem.planets + SolarSystem.moon
        fun angularRadius(body: SolarSystem.Body): Double {
            val radius = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
            return asin((radius / (body.center - EarthRenderer.SEAT_EYE).length()).toDouble())
        }
        for ((index, body) in bodies.withIndex()) {
            val direction = (body.center - EarthRenderer.SEAT_EYE).normalize()
            for (other in bodies.drop(index + 1)) {
                val otherDirection = (other.center - EarthRenderer.SEAT_EYE).normalize()
                val separation = acos(direction.dot(otherDirection).toDouble().coerceIn(-1.0, 1.0))
                assertTrue("${body.name}/${other.name} silhouettes", separation > angularRadius(body) + angularRadius(other))
            }
        }
    }

    @Test fun sunAndBodiesFitInsideCatalogSkyAndConfiguredFarClip() {
        assertTrue(SolarSystem.STAR_RADIUS < SolarSystem.FAR_CLIP)
        for (minute in 0 until 1440) {
            val position = SolarSystem.sunPosition(at(0).plusSeconds(minute * 60L), zone)
            val displayPosition = SolarSystem.sunDisplayPosition(position)
            val displayRadius = SolarSystem.sunDisplayRadius(position)
            assertTrue(displayPosition.length() + displayRadius * 1.08f < SolarSystem.STAR_RADIUS)
            for (body in SolarSystem.planets + SolarSystem.moon) {
                val bound = body.radius * if (body.name == "saturn") SolarSystem.RING_OUTER else 1f
                assertTrue((position - body.center).length() > SolarSystem.sunRadius + bound)
                assertTrue(body.center.length() + bound < SolarSystem.FAR_CLIP)
            }
        }
        assertEquals(74500.0 / 58232.0, SolarSystem.RING_INNER.toDouble(), 1e-6)
        assertEquals(136780.0 / 58232.0, SolarSystem.RING_OUTER.toDouble(), 1e-6)
    }

    @Test fun visibleSunPreservesAstronomicalAngularSizeAndLightingDirection() {
        for (hour in 0..23) {
            val position = SolarSystem.sunPosition(at(hour), zone)
            val displayPosition = SolarSystem.sunDisplayPosition(position)
            val distance = (displayPosition - EarthRenderer.SEAT_EYE).length()
            val angularDiameter = Math.toDegrees(2 * asin((SolarSystem.sunDisplayRadius(position) / distance).toDouble()))
            assertEquals(0.533, angularDiameter, 0.001)
            assertVector((position - EarthRenderer.SEAT_EYE).normalize(),
                (displayPosition - EarthRenderer.SEAT_EYE).normalize(), 1e-6f)
        }
    }

    @Test fun customSunUniformMatchesGraphicsHsrWorldHandedness() {
        // Scene.setLightingEnvironment reflects Z in GraphicsHSR. Custom Vector4 uniforms do not.
        val point = SolarSystem.shaderSunPosition(Vector3(12f, 34f, 56f))
        assertEquals(12f, point.x, 0f)
        assertEquals(34f, point.y, 0f)
        assertEquals(-56f, point.z, 0f)
        assertEquals(1f, point.w, 0f)
    }
}
