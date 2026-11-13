package casa.archer.metatheatre

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reloadable 8K cloud morphology. Explicit disable and memory pressure drop the
 * source arrays. A regional fallback must not: the next forecast rebuilds from
 * the same bytes instead of decoding both atlases again.
 *
 * [build] may run off the main thread. Retention changes are synchronized with it
 * so an in-flight decode cannot republish arrays that were just released.
 */
internal class WeatherCloudCache(private val loader: () -> WeatherCloudMap) {
    private val lock = Any()
    private val builds = Mutex()
    private var map: WeatherCloudMap? = null
    private var epoch = 0
    private var reloadAllowed = true
    private var loads = 0

    val retainsSources: Boolean get() = synchronized(lock) { map != null }
    val retainedBytes: Int get() = if (retainsSources) WeatherCloudMap.SOURCE_BYTES else 0
    val acceptsReload: Boolean get() = synchronized(lock) { reloadAllowed }
    val loadCount: Int get() = synchronized(lock) { loads }

    suspend fun build(snapshot: WeatherSnapshot): Bitmap? = builds.withLock {
        var existing: WeatherCloudMap? = null
        var ticket = 0
        synchronized(lock) {
            existing = map
            if (!reloadAllowed) return null
            ticket = epoch
        }
        val resolved = existing ?: loader().also { loaded ->
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                loads += 1
                if (epoch == ticket && reloadAllowed) map = loaded
            }
        }
        currentCoroutineContext().ensureActive()
        if (!acceptsReload) return null
        resolved.build(snapshot)
    }

    /** User turned live weather off. The next atlas decode waits for [allowReload]. */
    fun releaseForDisable() = synchronized(lock) {
        epoch += 1
        reloadAllowed = false
        map = null
    }

    /** Memory pressure. The next requested rebuild may decode the atlases again. */
    fun evictForMemoryPressure() = synchronized(lock) {
        epoch += 1
        map = null
    }

    fun allowReload() = synchronized(lock) {
        reloadAllowed = true
    }

    companion object {
        /**
         * Running-memory pressure and background LRU trims release the cache.
         * UI-hidden is not pressure: treating focus loss as eviction would decode
         * the atlases again on every return. API 34 may omit background LRU levels;
         * the running levels still arrive and are honored.
         */
        fun releasesOnTrim(level: Int): Boolean = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
            else -> level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        }
    }
}
