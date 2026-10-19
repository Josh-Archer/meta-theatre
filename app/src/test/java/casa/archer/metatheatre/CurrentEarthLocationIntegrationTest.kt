package casa.archer.metatheatre

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
        val session=EarthLocationSession(backgroundScope,EarthLocationSource {delay(60_000);error("private coordinates")},messages::add,{fail("Must not move")})
        session.setEnabled(true);session.setActive(true);advanceTimeBy(45_001);runCurrent()
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
        lateinit var listener:LocationListener
        doAnswer { call ->listener=call.getArgument(3);null }.`when`(manager)
            .requestLocationUpdates(eq(LocationManager.NETWORK_PROVIDER),eq(0L),eq(0f),any(LocationListener::class.java),any(Looper::class.java))
        val waiting=async {source.locate()};runCurrent()
        val canceledListener=listener
        waiting.cancel();runCurrent();verify(manager).removeUpdates(canceledListener)
        canceledListener.onLocationChanged(fix()) // A late native callback cannot resume a canceled caller.
        val successful=async {source.locate()};runCurrent()
        listener.onLocationChanged(fix())
        assertTrue(successful.await().place.distance(place)<.01)
        verify(manager).removeUpdates(listener)
    }

    @Test fun freshUpdatesAreRequiredAndUnusableUpdatesDoNotAbortTheRequest() = runTest {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager=mock(LocationManager::class.java)
        `when`(manager.isLocationEnabled).thenReturn(true)
        `when`(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(true)
        val stale=fix(android.os.SystemClock.elapsedRealtimeNanos()-1_200_000_000_000L)
        lateinit var listener:LocationListener
        doAnswer { listener=it.getArgument(3);null }.`when`(manager)
            .requestLocationUpdates(eq(LocationManager.NETWORK_PROVIDER),eq(0L),eq(0f),any(LocationListener::class.java),any(Looper::class.java))
        val waiting=async {AndroidEarthLocation(context,manager).locate()};runCurrent()
        listener.onLocationChanged(stale);assertFalse(waiting.isCompleted)
        listener.onLocationChanged(fix().apply{accuracy=Float.NaN});assertFalse(waiting.isCompleted)
        listener.onLocationChanged(fix());assertEquals(place,waiting.await().place)
        verify(manager).removeUpdates(listener)
        verify(manager,never()).getLastKnownLocation(anyString())
    }

    @Test fun explicitRefreshWhileInactiveShowsFeedbackAndRunsWhenFocusReturns() = runTest {
        val messages=mutableListOf<String>();val centers=mutableListOf<WeatherPlace>()
        val session=EarthLocationSession(backgroundScope,EarthLocationSource {EarthLocationFix(place,3000f)},messages::add,centers::add)
        session.setEnabled(true);session.refresh();runCurrent()
        assertTrue(messages.last().contains("active"));assertTrue(centers.isEmpty())
        session.setActive(true);runCurrent()
        assertEquals(listOf(place),centers);assertTrue(messages.last().startsWith("Above your"))
    }

    @Test fun staleFutureInvalidAndUnboundedLocationsAreRejected() {
        val now=2_000_000_000_000L
        assertNotNull(AndroidEarthLocation.validFix(fix(now),now))
        assertNotNull(AndroidEarthLocation.validFix(fix(now-30_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now-31_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now-600_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now+11_000_000_000L),now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{accuracy=Float.NaN},now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{accuracy=200_000f},now))
        assertNull(AndroidEarthLocation.validFix(fix(now).apply{latitude=100.0},now))
    }

    @Test fun oldProviderUpdatesTimeOutWithoutMovingAndLateFreshCallbacksAreIgnored() = runTest {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager=mock(LocationManager::class.java)
        `when`(manager.isLocationEnabled).thenReturn(true)
        `when`(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(true)
        lateinit var listener:LocationListener
        doAnswer {listener=it.getArgument(3);null}.`when`(manager)
            .requestLocationUpdates(eq(LocationManager.NETWORK_PROVIDER),eq(0L),eq(0f),any(LocationListener::class.java),any(Looper::class.java))
        val messages=mutableListOf<String>();val centers=mutableListOf<WeatherPlace>()
        val session=EarthLocationSession(backgroundScope,AndroidEarthLocation(context,manager),messages::add,centers::add)
        session.setEnabled(true);session.setActive(true);runCurrent()
        listener.onLocationChanged(fix(android.os.SystemClock.elapsedRealtimeNanos()-600_000_000_000L))
        assertTrue(centers.isEmpty())
        advanceTimeBy(45_001);runCurrent()
        assertTrue(messages.last().contains("timed out"));assertTrue(centers.isEmpty())
        verify(manager).removeUpdates(listener)
        listener.onLocationChanged(fix());assertTrue(centers.isEmpty())
    }

    private fun fix(nanos:Long=android.os.SystemClock.elapsedRealtimeNanos())=Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude=place.latitude;longitude=place.longitude;accuracy=3000f;elapsedRealtimeNanos=nanos
    }
}
