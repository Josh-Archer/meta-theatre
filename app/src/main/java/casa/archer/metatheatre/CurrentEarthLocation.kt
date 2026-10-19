package casa.archer.metatheatre

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
    private val manager: LocationManager = context.getSystemService(LocationManager::class.java),
    private val diagnostic: (String) -> Unit = {}) : EarthLocationSource {
    override suspend fun locate(): EarthLocationFix {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw EarthLocationProblem("Allow approximate Location in the headset permission prompt, then choose Above me.")
        if (!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            throw EarthLocationProblem("Turn on Location in headset settings, then choose Above me.")
        return suspendCancellableCoroutine { continuation ->
            // Horizon OS can return an old cache entry from getCurrentLocation(). Keep a
            // foreground subscription until a recent fix arrives, then remove it immediately.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!continuation.isActive) return
                    diagnostic("Network update: ${fixStatus(location, SystemClock.elapsedRealtimeNanos())}")
                    val fix = validFix(location, SystemClock.elapsedRealtimeNanos()) ?: return
                    manager.removeUpdates(this)
                    continuation.resume(fix)
                }
                override fun onProviderDisabled(provider: String) {
                    if (!continuation.isActive) return
                    manager.removeUpdates(this)
                    continuation.resumeWithException(EarthLocationProblem("Turn on Location in headset settings, then choose Above me."))
                }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            try {
                diagnostic("Waiting for a fresh network fix")
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                // Covers cancellation racing registration on another thread.
                if (!continuation.isActive) manager.removeUpdates(listener)
            } catch (_: SecurityException) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resumeWithException(EarthLocationProblem("Location permission was removed. Enable it to use Above me."))
            } catch (_: IllegalArgumentException) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resumeWithException(EarthLocationProblem("The headset location provider is unavailable."))
            }
        }
    }

    companion object {
        // Explicit user preference: fresh location only; no last-known fallback.
        private const val MAX_AGE_SECONDS = 30.0
        /** Fixed diagnostic categories deliberately omit coordinates and raw provider messages. */
        private fun fixStatus(location: Location, nowNanos: Long): String = when {
            location.elapsedRealtimeNanos <= 0 -> "missing elapsed timestamp"
            (nowNanos-location.elapsedRealtimeNanos)/1e9 !in -10.0..MAX_AGE_SECONDS -> "expired timestamp"
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..100_000f -> "unusable accuracy"
            validFix(location,nowNanos) == null -> "invalid coordinates"
            else -> "usable fix"
        }
        fun validFix(location: Location, nowNanos: Long): EarthLocationFix? {
            val age = (nowNanos - location.elapsedRealtimeNanos) / 1e9
            if (location.elapsedRealtimeNanos <= 0 || age !in -10.0..MAX_AGE_SECONDS || !location.hasAccuracy() ||
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
        if (!value) {
            if (job?.isActive == true) publish("Waiting for the theatre to be active…")
            job?.cancel(); job = null
        } else if (!centered) request()
    }
    fun manualView() {
        if (job?.isActive == true) publish("Location centering canceled by orbit controls. Choose Above me to return.")
        job?.cancel(); job = null; centered = true
    }
    fun refresh() {
        centered = false; job?.cancel(); job = null
        if (enabled && !active) publish("Waiting for the theatre to be active…")
        request()
    }
    private fun request() {
        if (!enabled || !active || job?.isActive == true) return
        job = scope.launch {
            publish("Waiting for a fresh headset location…")
            try {
                val fix = withTimeout(45_000) { source.locate() }
                if (!enabled || !active) return@launch
                centered = true
                center(fix.place)
                publish("Above your current location · fresh approximate Wi-Fi location.")
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                publish("Fresh location timed out. Check headset Wi-Fi and Location settings, then choose Above me. No location change was applied.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                publish((problem as? EarthLocationProblem)?.explanation ?: "Location is unavailable. Choose Above me to retry.")
            }
        }
    }
}
