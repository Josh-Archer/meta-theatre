package casa.archer.metatheatre

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class EarthLocationFix(val place: WeatherPlace, val accuracyMeters: Float)
internal class EarthLocationProblem(val explanation: String) : Exception()
internal fun interface EarthLocationSource { suspend fun locate(): EarthLocationFix }

/** Foreground, approximate Wi-Fi location. No GPS, IP lookup, background subscription or disk cache. */
internal class AndroidEarthLocation(private val context: Context,
    private val manager: LocationManager = context.getSystemService(LocationManager::class.java)) : EarthLocationSource {
    override suspend fun locate(): EarthLocationFix {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw EarthLocationProblem("Allow approximate Location in the headset permission prompt, then choose Above me.")
        if (!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            throw EarthLocationProblem("Turn on Location in headset settings, then choose Above me.")
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancellation, context.mainExecutor) { location ->
                    if (!continuation.isActive) return@getCurrentLocation
                    val fix = location?.let { validFix(it, SystemClock.elapsedRealtimeNanos()) }
                    if (fix == null) continuation.resumeWithException(EarthLocationProblem(
                        "No recent location is available. Check Wi-Fi and Location, then choose Above me."))
                    else continuation.resume(fix)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resumeWithException(EarthLocationProblem("Location permission was removed. Enable it to use Above me."))
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resumeWithException(EarthLocationProblem("The headset location provider is unavailable."))
            }
        }
    }

    companion object {
        fun validFix(location: Location, nowNanos: Long): EarthLocationFix? {
            val age = (nowNanos - location.elapsedRealtimeNanos) / 1e9
            if (location.elapsedRealtimeNanos <= 0 || age !in -10.0..300.0 || !location.hasAccuracy() ||
                !location.accuracy.isFinite() || location.accuracy !in 0f..100_000f) return null
            return runCatching { EarthLocationFix(WeatherPlace(location.latitude, location.longitude), location.accuracy) }.getOrNull()
        }
    }
}

/** One fix per app session or explicit request; cancellation prevents late location-driven jumps. */
internal class EarthLocationSession(private val scope: CoroutineScope, private val source: EarthLocationSource,
    private val publish: (String) -> Unit, private val center: (WeatherPlace) -> Unit) {
    private var enabled = false
    private var active = false
    private var centered = false
    private var job: Job? = null
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        centered = false
        if (!value) { job?.cancel(); job = null; publish("Current location is off.") }
        else { publish("Waiting for headset location."); request() }
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) { job?.cancel(); job = null } else if (!centered) request()
    }
    fun manualView() { job?.cancel(); job = null; centered = true }
    fun refresh() { centered = false; job?.cancel(); job = null; request() }
    private fun request() {
        if (!enabled || !active || job?.isActive == true) return
        job = scope.launch {
            publish("Finding your approximate location…")
            try {
                val fix = withTimeout(20_000) { source.locate() }
                if (!enabled || !active) return@launch
                centered = true
                center(fix.place)
                publish("Above your current location · approximate Wi-Fi location.")
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                publish("Location timed out. Check Wi-Fi and choose Above me to retry.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                publish((problem as? EarthLocationProblem)?.explanation ?: "Location is unavailable. Choose Above me to retry.")
            }
        }
    }
}
