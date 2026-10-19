package casa.archer.metatheatre

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentEarthLocationIntegrationTest {
    private val place = WeatherPlace(35.0,140.0) // Synthetic fixture; no device location is read.

    @Test fun onlyOptedInForegroundSessionsReadLocationAndSuccessfulFixesDoNotContinuouslyTrack() = runTest {
        val centers=mutableListOf<WeatherPlace>();val messages=mutableListOf<String>();var reads=0
        val session=EarthLocationSession(backgroundScope,EarthLocationSource { reads++;EarthLocationFix(place,3000f) },messages::add,centers::add)
        session.setActive(true);runCurrent();assertEquals(0,reads)
        session.setEnabled(true);runCurrent();assertEquals(listOf(place),centers)
        session.setActive(false);session.setActive(true);runCurrent();assertEquals(1,reads)
        session.refresh();runCurrent();assertEquals(2,reads)
        session.setEnabled(false);session.refresh();runCurrent();assertEquals(2,reads)
        assertTrue(messages.last().contains("off"))
    }

    @Test fun disablingBackgroundingOrManualNavigationCancelsLateFixesBeforeTheyCanMoveTheView() = runTest {
        for(action in 0..2){
            var canceled=false;var moved=false
            val session=EarthLocationSession(backgroundScope,EarthLocationSource {
                try {delay(15_000);EarthLocationFix(place,3000f)} finally {canceled=true}
            },{}, {moved=true})
            session.setEnabled(true);session.setActive(true);runCurrent()
            when(action){0->session.setEnabled(false);1->session.setActive(false);else->session.manualView()}
            advanceTimeBy(30_000);runCurrent()
            assertTrue(canceled);assertFalse(moved)
        }
    }

    @Test fun timeoutsAndProviderErrorsAreVisibleWithoutLeakingRawLocationOrNetworkDiagnostics() = runTest {
        val messages=mutableListOf<String>()
        val session=EarthLocationSession(backgroundScope,EarthLocationSource {delay(25_000);error("private coordinates")},messages::add,{fail("Must not move")})
        session.setEnabled(true);session.setActive(true);advanceTimeBy(20_001);runCurrent()
        assertTrue(messages.last().contains("timed out"))
        val failed=EarthLocationSession(backgroundScope,EarthLocationSource {error("private@example.invalid latitude=35")},messages::add,{fail("Must not move")})
        failed.setEnabled(true);failed.setActive(true);runCurrent()
        assertFalse(messages.last().contains("private"));assertFalse(messages.last().contains("latitude"))
    }

    @Test fun androidSourceRequiresPermissionBeforeTouchingTheProviderAndAcceptsOnlyRecentApproximateFixes() = runTest {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager=mock(LocationManager::class.java)
        val source=AndroidEarthLocation(context,manager)
        try {source.locate();fail("Expected permission error")}catch(problem:EarthLocationProblem){assertTrue(problem.explanation.contains("Location"))}
        verifyNoInteractions(manager)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        `when`(manager.isLocationEnabled).thenReturn(true)
        `when`(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(true)
        lateinit var callback:Consumer<Location?>
        lateinit var signal:CancellationSignal
        doAnswer { call ->signal=call.getArgument(1);callback=call.getArgument(3);null }.`when`(manager)
            .getCurrentLocation(eq(LocationManager.NETWORK_PROVIDER),any(CancellationSignal::class.java),any(Executor::class.java),any())
        val waiting=async {source.locate()};runCurrent()
        waiting.cancel();runCurrent();assertTrue(signal.isCanceled)
        callback.accept(fix()) // A late native callback cannot resume a canceled caller.
        val successful=async {source.locate()};runCurrent()
        callback.accept(fix())
        assertTrue(successful.await().place.distance(place)<.01)
    }

    @Test fun staleFutureInvalidAndUnboundedLocationsAreRejected() {
        val now=1_000_000_000_000L
        assertNotNull(AndroidEarthLocation.validFix(fix(now),now))
        assertNull(AndroidEarthLocation.validFix(fix(now-301_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now+11_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{accuracy=Float.NaN},now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{accuracy=200_000f},now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{latitude=100.0},now))
    }

    private fun fix(nanos:Long=android.os.SystemClock.elapsedRealtimeNanos())=Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude=place.latitude;longitude=place.longitude;accuracy=3000f;elapsedRealtimeNanos=nanos
    }
}
