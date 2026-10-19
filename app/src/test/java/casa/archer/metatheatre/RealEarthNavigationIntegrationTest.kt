package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RealEarthNavigationIntegrationTest {
    private val epoch = Instant.parse("2026-06-21T12:00:00Z")
    private val viewer = Pose(Vector3(2f,1.7f,-3f), Quaternion(pitch=15f,yaw=60f,roll=4f))

    @Test fun aboveLocationUsesTheTextureMeridianAndCompensatesForManualAndRealTimeSpinIncludingPolesAndDateline() {
        var now = epoch
        val earth = EarthExploration { now }
        earth.spinBy(47f)
        earth.setRealRotation(true)
        now = now.plusSeconds(21_600)
        for (place in listOf(WeatherPlace(35.0,140.0), WeatherPlace(-30.0,179.9),
            WeatherPlace(90.0,0.0), WeatherPlace(-90.0,0.0), WeatherPlace(0.0,-100.0))) {
            earth.viewLocation(place,viewer)
            val viewed = WeatherPlace.viewed(earth.earthPose(),viewer.t)
            assertTrue("Requested region must be below the observer",place.distance(viewed)<.05)
            assertEquals(earth.distance,(earth.earthPose().t-viewer.t).length(),.001f)
            assertTrue(ScenePointer.valid(earth.earthPose()))
            earth.zoomBy(1.12f,viewer)
            val after = WeatherPlace.viewed(earth.earthPose(),viewer.t)
            assertTrue("Zoom must preserve $place; got $after",place.distance(after)<.05)
        }
    }

    @Test fun zoomStaysOutsideTheAtmosphereAndLeavesDistantPlanetsClearAtEveryZoomWithoutRescalingThem() {
        val earth = EarthExploration { epoch }
        earth.apply(EarthControl.NORTH_POLE,viewer)
        val sky = earth.skyFrame
        val turnedHead = Pose(viewer.t.copy(), Quaternion(pitch=0f,yaw=-20f,roll=0f))
        val startDirection = (earth.earthPose().t-viewer.t).normalize()
        repeat(25) {
            earth.zoomBy(1.12f,turnedHead)
            assertEquals(sky,earth.skyFrame)
            assertTrue((earth.earthPose().t-viewer.t).length()>=EarthRenderer.RADIUS*1.012f+11.99f)
            assertTrue(startDirection.dot((earth.earthPose().t-viewer.t).normalize())>.9999f)
            val inverse = sky.inverse()
            val center = inverse*earth.earthPose().t
            val eye = inverse*viewer.t
            for (body in SolarSystem.planets.filter { it.name!="earth" }) {
                val placed = EarthClearance.body(body,center,eye)
                assertEquals(body.radius,placed.radius,0f)
                assertEquals((body.center-eye).length(),(placed.center-eye).length(),.01f)
                val a = (center-eye).normalize(); val b = (placed.center-eye).normalize()
                val bound = body.radius*if(body.name=="saturn")SolarSystem.RING_OUTER else 1f
                val clearance = asin((EarthRenderer.RADIUS*1.012f/(center-eye).length()).toDouble())+
                    asin((bound/(placed.center-eye).length()).toDouble())
                assertTrue("${body.name} must stay outside Earth's silhouette",acos(a.dot(b).toDouble().coerceIn(-1.0,1.0))>=clearance-.00001)
            }
        }
        assertEquals(EarthExploration.MAX_ZOOM,earth.zoom,.0001f)
        repeat(40){earth.zoomBy(1f/1.12f,viewer)}
        assertEquals(EarthExploration.MIN_ZOOM,earth.zoom,0f)
        earth.resetZoom(viewer)
        assertEquals(1f,earth.zoom,0f)
        earth.zoomBy(Float.NaN,viewer)
        assertEquals(1f,earth.zoom,0f)
    }

    @Test fun rotationIsSiderealEastwardIndependentOfFramesAndPauseOrWakeDoesNotLoseElapsedTime() {
        var now = epoch
        val earth = EarthExploration { now }
        earth.setRealRotation(true)
        earth.viewLocation(WeatherPlace(0.0,0.0),viewer)
        now = epoch.plusSeconds(3600)
        assertEquals((3600/RealEarthTime.SIDEREAL_SECONDS*360).toFloat(),earth.spinAt(),.0001f)
        val place = WeatherPlace.viewed(earth.earthPose(),viewer.t)
        assertEquals(-15.041,place.longitude,.003)
        earth.setRealRotation(false)
        val frozen = earth.spinAt()
        now = now.plusSeconds(20_000)
        assertEquals(frozen,earth.spinAt(),0f)
        earth.setRealRotation(true)
        now = now.plusSeconds(3600)
        assertEquals(frozen+15.041f,earth.spinAt(),.001f)
        assertEquals(0f,RealEarthTime.rotationSince(epoch,epoch.plusNanos((RealEarthTime.SIDEREAL_SECONDS*1e9).toLong())),.0001f)
    }

    @Test fun maximumZoomKeepsTheRoadsterOutsideEarthAndTheIssVisibleThroughoutTheirPasses() {
        val earth = EarthExploration { epoch }
        earth.apply(EarthControl.EQUATOR,viewer)
        earth.zoomBy(3f,viewer)
        val inverse = earth.skyFrame.inverse()
        val globe = inverse*earth.earthPose().t
        val eye = inverse*viewer.t
        for(step in 0..100){
            val p=step/100.0
            val car=SpaceVisitors.pose(SpaceVisitors.roadster,p)
            val carRadius=SpaceVisitors.radius(SpaceVisitors.roadster)*SpaceVisitors.roadster.displayScale
            assertTrue((car.t-globe).length()>EarthRenderer.RADIUS*1.012f+carRadius)
            val iss=SpaceVisitors.pose(SpaceVisitors.iss,p)
            val radius=SpaceVisitors.radius(SpaceVisitors.iss)*SpaceVisitors.iss.displayScale
            val center=EarthClearance.position(iss.t,radius,globe,eye)
            val separation=acos((center-eye).normalize().dot((globe-eye).normalize()).toDouble().coerceIn(-1.0,1.0))
            val bound=asin((EarthRenderer.RADIUS*1.012f/(globe-eye).length()).toDouble())+
                asin((radius/(center-eye).length()).toDouble())
            assertTrue(separation>bound-.00001)
        }
    }

    @Test fun seasonalUtcSunLightsTheActualGeographyAndIsIndependentOfHeadsetTimezoneAndManualSpin() {
        assertEquals(23.44,RealEarthTime.subsolar(epoch).latitude,.6)
        assertEquals(-23.44,RealEarthTime.subsolar(Instant.parse("2026-12-21T12:00:00Z")).latitude,.6)
        val overhead = RealEarthTime.subsolar(epoch)
        val earth = EarthExploration { epoch }
        earth.setRealRotation(true)
        earth.spinBy(77f)
        for ((place,expected) in listOf(overhead to 1f,
            WeatherPlace(-overhead.latitude,WeatherPlace.wrap(overhead.longitude+180)) to -1f)) {
            earth.viewLocation(place,viewer)
            val pose = earth.earthPose()
            val surface = pose.t+(viewer.t-pose.t).normalize()*EarthRenderer.RADIUS
            val sun = earth.sunPosition(epoch,ZoneId.of("Pacific/Auckland"))
            assertEquals(sun,earth.sunPosition(epoch,ZoneId.of("America/New_York")))
            assertEquals(expected,(viewer.t-pose.t).normalize().dot((sun-surface).normalize()),.001f)
        }
    }

    @Test fun presetsKeepZoomAndOlderPresetsDefaultToTheOriginalDistance() {
        val earth = EarthExploration { epoch }
        earth.viewLocation(WeatherPlace(40.0,10.0),viewer)
        earth.zoomBy(1.4f,viewer)
        val preset = ViewingPreset.capture(viewer,ViewingPreset.frame(viewer)*Pose(Vector3(0f,0f,3f)),
            ScreenSize(2.8f,1.575f),30f,earth)!!
        val loaded = ViewingPreset.parse(preset.json())!!
        val restored = EarthExploration { epoch }
        restored.restore(loaded,viewer)
        assertEquals(earth.distance,restored.distance,.001f)
        assertTrue(WeatherPlace.viewed(earth.earthPose(),viewer.t).distance(WeatherPlace.viewed(restored.earthPose(),viewer.t))<.05)
        val legacy = preset.json().apply { remove("zoom") }
        assertEquals(1f,ViewingPreset.parse(legacy)!!.zoom,0f)
        assertNull(ViewingPreset.parse(preset.json().put("zoom",100)))
    }
}
