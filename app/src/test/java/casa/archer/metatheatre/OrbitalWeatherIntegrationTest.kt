package casa.archer.metatheatre

import com.meta.spatial.core.Pose
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class OrbitalWeatherIntegrationTest {
    private val home = WeatherPlace(0.0,-100.0)
    private val epoch = Instant.parse("2026-10-17T12:00:00Z")
    private fun snapshot(place: WeatherPlace, time: Instant = epoch, code: Int = 3, cover: Int = 100) =
        WeatherSnapshot(place,WeatherPlace.grid(place).map { WeatherReading(it,time,code,cover,12.0,180.0) })
    private fun response(place: WeatherPlace) = JSONArray().apply {
        WeatherPlace.grid(place).forEach { p -> put(JSONObject().put("latitude",p.latitude).put("longitude",p.longitude)
            .put("current",JSONObject().put("time",epoch.epochSecond).put("weather_code",3).put("cloud_cover",100)
                .put("temperature_2m",12).put("wind_direction_10m",180))) }
    }.toString()

    @Test fun nativeMeridianManualSpinAndPolarExplorationAgreeWithTheSampledLocation() {
        val earth = EarthExploration()
        val viewer=Pose(EarthRenderer.SEAT_EYE)
        var place=WeatherPlace.viewed(earth.earthPose(),viewer.t)
        assertEquals(0.0,place.latitude,.001);assertEquals(-100.0,place.longitude,.001)
        earth.spinBy(30f)
        place=WeatherPlace.viewed(earth.earthPose(),viewer.t)
        assertEquals(-130.0,place.longitude,.001)
        earth.apply(EarthControl.HOME,viewer)
        for (control in listOf(EarthControl.NORTH_POLE,EarthControl.SOUTH_POLE,EarthControl.FAR_SIDE)) {
            earth.apply(control,viewer)
            place=WeatherPlace.viewed(earth.earthPose(),viewer.t)
            assertEquals(when(control){EarthControl.NORTH_POLE->90.0;EarthControl.SOUTH_POLE->-90.0;else->0.0},place.latitude,.04)
            val grid=WeatherPlace.grid(place)
            assertEquals(49,grid.size)
            assertTrue(grid.all { it.latitude.isFinite() && it.longitude in -180.0..180.0 && it.distance(place) < 61 })
            assertTrue(place.distance(grid[24]) < .03)
        }
        val seam=WeatherPlace.grid(WeatherPlace(10.0,179.9))
        assertTrue(seam.any{it.longitude<0});assertTrue(seam.any{it.longitude>0})
    }

    @Test fun actualBatchedHttpClientParsesAllCellsAndSendsOnlyWeatherParameters() = runTest {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(response(home)))
            val client=OpenMeteoWeather(server.url("/v1/forecast").toString())
            val data=client.fetch(home)
            assertEquals(49,data.readings.size);assertEquals(CloudPattern.OVERCAST,data.central.pattern)
            assertEquals(epoch,data.time)
            val request=server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals(49,request.requestUrl!!.queryParameter("latitude")!!.split(',').size)
            assertEquals(setOf("latitude","longitude","current","timeformat","timezone"),request.requestUrl!!.queryParameterNames)
            server.enqueue(MockResponse().setResponseCode(429).setBody("private diagnostics"))
            try {client.fetch(home);fail("HTTP error must fail")} catch(error: IllegalStateException) {
                assertFalse(error.message.orEmpty().contains("private"))
            }
        } finally {server.shutdown()}
    }

    @Test fun missingOrMismatchedNullAndOutOfRangeReadingsCannotBePresentedAsCurrent() {
        for (body in listOf("[]",response(home).replace("\"cloud_cover\":100","\"cloud_cover\":null"),
            response(home).replace("\"cloud_cover\":100","\"cloud_cover\":101"),response(home).replace("-100","-10"))) {
            assertTrue(runCatching{OpenMeteoWeather.parse(home,WeatherPlace.grid(home),body)}.isFailure)
        }
        assertFalse(snapshot(home,epoch.minusSeconds(31*60)).fresh(epoch))
        assertFalse(snapshot(home,epoch.plusSeconds(301)).fresh(epoch))
        assertEquals(CloudPattern.FOG,CloudPattern.forCode(45,0))
        assertEquals(CloudPattern.SNOW_BANDS,CloudPattern.forCode(75,100))
        assertEquals(CloudPattern.THUNDERHEAD,CloudPattern.forCode(99,100))
        assertEquals(CloudPattern.RAIN_FRONT,CloudPattern.forCode(63,90))
    }

    @Test fun offAndBackgroundNeverPollViewChangesDebounceAndCacheAvoidsDuplicateRequests() = runTest {
        val requests=mutableListOf<WeatherPlace>();val rendered=mutableListOf<WeatherSnapshot?>();val states=mutableListOf<WeatherUi>()
        val session=WeatherSession(backgroundScope,WeatherSource{p->requests.add(p);snapshot(p,epoch.plusMillis(testScheduler.currentTime))},
            {rendered.add(it)},{states.add(it)},{epoch.plusMillis(testScheduler.currentTime)},{testScheduler.currentTime})
        session.view(home);session.setActive(true)
        advanceTimeBy(10_000);runCurrent();assertTrue(requests.isEmpty())
        session.setEnabled(true);advanceTimeBy(999);runCurrent();assertTrue(requests.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf(home),requests)
        session.view(WeatherPlace(0.0,-99.0));runCurrent();assertEquals(1,requests.size)
        session.setActive(false);advanceTimeBy(WeatherSession.REFRESH_MS*2);runCurrent();assertEquals(1,requests.size)
        session.setActive(true);advanceTimeBy(1_000);runCurrent();assertEquals(2,requests.size)
        session.setEnabled(false);runCurrent();assertNull(rendered.last());assertFalse(states.last().enabled)
        session.setEnabled(true);runCurrent();assertEquals(2,requests.size);assertNotNull(rendered.last())
        session.view(WeatherPlace(35.0,20.0));advanceTimeBy(500)
        session.view(WeatherPlace(40.0,40.0));advanceTimeBy(500)
        session.setEnabled(false);advanceTimeBy(10_000);runCurrent();assertEquals(2,requests.size)
    }

    @Test fun staleFailuresAndCancelledResponsesNeverReplaceTheCurrentRegionWithOldWeather() = runTest {
        var fail=false;var slow=false
        val rendered=mutableListOf<WeatherSnapshot?>();val states=mutableListOf<WeatherUi>()
        val session=WeatherSession(backgroundScope,WeatherSource{p->if(slow)delay(20_000);if(fail)error("secret URL");snapshot(p,epoch.plusMillis(testScheduler.currentTime))},
            {rendered.add(it)},{states.add(it)},{epoch.plusMillis(testScheduler.currentTime)},{testScheduler.currentTime})
        session.view(home);session.setEnabled(true);session.setActive(true)
        advanceTimeBy(1_000);runCurrent();assertNotNull(rendered.last())
        fail=true;session.refresh();advanceTimeBy(5_000);runCurrent()
        assertTrue(states.last().stale);assertNotNull(states.last().reading);assertFalse(states.last().message.contains("secret"))
        session.view(WeatherPlace(40.0,40.0));advanceTimeBy(5_000);runCurrent()
        assertNull(rendered.last());assertNull(states.last().reading)
        fail=false;slow=true;session.refresh();advanceTimeBy(5_000);runCurrent()
        session.setEnabled(false);runCurrent();advanceTimeBy(25_000);runCurrent()
        assertNull(rendered.last());assertFalse(states.last().enabled)
    }

    @Test fun explicitDisableReleasesSourcesWhileRegionalFallbackAndBackgroundKeepThem() = runTest {
        val retention = mutableListOf<Boolean>()
        val rendered = mutableListOf<WeatherSnapshot?>()
        val session = WeatherSession(backgroundScope,
            WeatherSource { snapshot(it, epoch.plusMillis(testScheduler.currentTime)) },
            { rendered.add(it) }, { },
            { epoch.plusMillis(testScheduler.currentTime) }, { testScheduler.currentTime },
            { retention.add(it) })
        session.view(home)
        session.setEnabled(true)
        session.setActive(true)
        assertEquals(listOf(true), retention)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0.0, rendered.last()!!.center.latitude, 0.0)
        assertEquals(listOf(true), retention)

        session.setActive(false); runCurrent()
        session.setActive(true); runCurrent()
        assertEquals("Leaving the session active must not evict source arrays", listOf(true), retention)
        assertNotNull(rendered.last())

        session.view(WeatherPlace(40.0, 40.0)); runCurrent()
        assertNull("A new region restores the authored map while the forecast loads", rendered.last())
        assertEquals("Regional fallback must not evict source arrays", listOf(true), retention)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(40.0, rendered.last()!!.center.latitude, 0.0)
        assertEquals(listOf(true), retention)

        session.setEnabled(false); runCurrent()
        assertEquals(listOf(true, false), retention)
        assertNull(rendered.last())

        session.setEnabled(true); runCurrent()
        assertEquals(listOf(true, false, true), retention)
        assertEquals(40.0, rendered.last()!!.center.latitude, 0.0)
    }
}
