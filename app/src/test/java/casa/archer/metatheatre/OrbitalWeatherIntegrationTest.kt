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
                .put("cloud_cover_low",100).put("cloud_cover_mid",80).put("cloud_cover_high",30)
                .put("temperature_2m",12).put("wind_direction_10m",180))) }
    }.toString()

    @Test fun refreshedTimestampsReuseCloudAppearanceButWeatherAndGeographyInvalidateIt() {
        val first = snapshot(home)
        val later = first.copy(readings = first.readings.map { it.copy(time = epoch.plusSeconds(900)) })
        assertEquals(first.appearance(), later.appearance())
        val central = first.central
        for (changed in listOf(
            central.copy(place = WeatherPlace(10.0, 20.0)),
            central.copy(code = 95), central.copy(cloudCover = 70),
            central.copy(windDirection = 90.0), central.copy(cloudCoverHigh = 90),
            central.copy(cloudCoverMid = 25), central.copy(cloudCoverLow = 10),
        )) {
            val readings = first.readings.toMutableList().apply { set(size / 2, changed) }
            assertNotEquals(first.appearance(), first.copy(readings = readings).appearance())
        }
    }

    @Test fun nativeMeridianManualSpinAndPolarExplorationAgreeWithTheSampledLocation() {
        val earth = EarthExploration()
        val viewer=Pose(EarthRenderer.SEAT_EYE)
        var place=WeatherPlace.viewed(earth.earthPose(),viewer.t)
        assertEquals(0.0,place.latitude,.001);assertEquals(-100.0,place.longitude,.001)
        earth.spinBy(30f)
        place=WeatherPlace.viewed(earth.earthPose(),viewer.t)
        assertEquals(-70.0,place.longitude,.001)
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
            assertEquals(100,data.central.cloudCoverLow);assertEquals(80,data.central.cloudCoverMid);assertEquals(30,data.central.cloudCoverHigh)
            assertEquals(0f,data.central.highCloudWeight,0f)
            assertEquals(epoch,data.time)
            val request=server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals(49,request.requestUrl!!.queryParameter("latitude")!!.split(',').size)
            assertEquals(setOf("latitude","longitude","current","timeformat","timezone"),request.requestUrl!!.queryParameterNames)
            assertEquals("weather_code,cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_2m,wind_direction_10m",
                request.requestUrl!!.queryParameter("current"))
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

    @Test fun partlyCloudyCodesStayBillowingUntilVerifiedHighCloudDominates() {
        assertEquals(CloudPattern.CUMULUS, CloudPattern.forCode(1, 40))
        assertEquals(CloudPattern.CUMULUS, CloudPattern.forCode(2, 55))
        assertEquals(CloudPattern.OVERCAST, CloudPattern.forCode(3, 90))
        for (code in listOf(0, 1, 2, 3, 45, 48, 61, 71, 95, 99)) {
            assertNotEquals(CloudPattern.CIRRUS, CloudPattern.forCode(code, 20))
            assertNotEquals(CloudPattern.CIRRUS, CloudPattern.forCode(code, 100))
        }
        val partly = WeatherReading(home, epoch, 2, 45, 12.0, 180.0)
        assertEquals(CloudPattern.CUMULUS, partly.pattern)
        assertEquals(0f, partly.highCloudWeight, 0f)
        val lower = partly.copy(cloudCoverLow = 40, cloudCoverMid = 25, cloudCoverHigh = 15)
        assertEquals(CloudPattern.CUMULUS, lower.pattern)
        assertEquals(0f, lower.highCloudWeight, 0f)
        val high = partly.copy(cloudCoverLow = 0, cloudCoverMid = 4, cloudCoverHigh = 70)
        assertEquals(1f, high.highCloudWeight, 0.001f)
        assertEquals(CloudPattern.CIRRUS, high.pattern)
        val stacked = partly.copy(cloudCoverLow = 60, cloudCoverMid = 20, cloudCoverHigh = 75)
        assertEquals(0f, stacked.highCloudWeight, 0f)
        assertEquals(CloudPattern.CUMULUS, stacked.pattern)
        val storm = WeatherReading(home, epoch, 95, 90, 12.0, 180.0, 20, 30, 95)
        assertEquals(CloudPattern.THUNDERHEAD, storm.pattern)
        assertEquals(0f, storm.highCloudWeight, 0f)
        val clearSky = WeatherReading(home, epoch, 0, 5, 12.0, 180.0, 0, 0, 80)
        assertEquals(CloudPattern.CLEAR, clearSky.pattern)
        assertEquals(CloudPattern.CUMULUS, partly.copy(cloudCoverHigh = 90).pattern)

        val grid = WeatherPlace.grid(home)
        fun body(low: Int?, mid: Int?, high: Int?) = JSONArray().apply {
            grid.forEach { place ->
                val current = JSONObject().put("time", epoch.epochSecond).put("weather_code", 3).put("cloud_cover", 85)
                    .put("temperature_2m", 16).put("wind_direction_10m", 180)
                if (low != null) current.put("cloud_cover_low", low)
                if (mid != null) current.put("cloud_cover_mid", mid)
                if (high != null) current.put("cloud_cover_high", high)
                put(JSONObject().put("latitude", place.latitude).put("longitude", place.longitude).put("current", current))
            }
        }.toString()
        val cirrus = OpenMeteoWeather.parse(home, grid, body(0, 0, 85))
        assertEquals(CloudPattern.CIRRUS, cirrus.central.pattern)
        assertEquals(85, cirrus.central.cloudCoverHigh)
        assertEquals(0, cirrus.central.cloudCoverLow)
        val omitted = OpenMeteoWeather.parse(home, grid, body(null, null, null))
        assertNull(omitted.central.cloudCoverLow)
        assertNull(omitted.central.cloudCoverMid)
        assertNull(omitted.central.cloudCoverHigh)
        assertEquals(CloudPattern.OVERCAST, omitted.central.pattern)
        assertEquals(0f, omitted.central.highCloudWeight, 0f)
        val partial = OpenMeteoWeather.parse(home, grid, body(null, 0, 90))
        assertNull(partial.central.cloudCoverLow)
        assertEquals(CloudPattern.OVERCAST, partial.central.pattern)
        val nulled = body(0, 0, 85).replace("\"cloud_cover_high\":85", "\"cloud_cover_high\":null")
        val fallback = OpenMeteoWeather.parse(home, grid, nulled)
        assertNull(fallback.central.cloudCoverHigh)
        assertEquals(CloudPattern.OVERCAST, fallback.central.pattern)
        assertTrue(runCatching { OpenMeteoWeather.parse(home, grid, body(0, 0, 101)) }.isFailure)
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
