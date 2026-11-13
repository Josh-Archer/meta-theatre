package casa.archer.metatheatre

import android.content.ComponentCallbacks2
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherCloudCacheTest {
    private val place = WeatherPlace(0.0, -100.0)
    private val snapshot = WeatherSnapshot(place, WeatherPlace.grid(place).map {
        WeatherReading(it, Instant.parse("2026-10-17T12:00:00Z"), 61, 75, 10.0, 140.0)
    })

    @Test fun morphologyStaysFullResolutionAndTrimIgnoresFocusLoss() {
        assertEquals(8192, WeatherCloudMap.WIDTH)
        assertEquals(4096, WeatherCloudMap.HEIGHT)
        assertEquals(4096, WeatherCloudMap.STRUCTURE_WIDTH)
        assertEquals(2048, WeatherCloudMap.STRUCTURE_HEIGHT)
        assertEquals(56 * 1024 * 1024, WeatherCloudMap.SOURCE_BYTES)
        assertFalse(WeatherCloudCache.releasesOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(WeatherCloudCache.releasesOnTrim(0))
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) assertTrue("level $level must release weather sources", WeatherCloudCache.releasesOnTrim(level))
    }

    @Test fun disabledCacheDoesNotDecodeUntilWeatherIsEnabledAgain() = runTest {
        val cache = WeatherCloudCache { error("disabled weather must not decode the atlases") }
        cache.releaseForDisable()
        assertFalse(cache.retainsSources)
        assertEquals(0, cache.retainedBytes)
        assertFalse(cache.acceptsReload)
        assertNull(cache.build(snapshot))
        assertEquals(0, cache.loadCount)
        cache.allowReload()
        assertTrue(cache.acceptsReload)
    }

    @Test fun disableAndMemoryPressureReleaseSourcesAndReenableMatchesThe8KMap() = runTest {
        val assets = RuntimeEnvironment.getApplication().assets
        val cache = WeatherCloudCache { WeatherCloudMap.load(assets) }
        val first = checkNotNull(cache.build(snapshot))
        try {
            assertEquals(8192, first.width)
            assertEquals(4096, first.height)
            assertEquals(1, cache.loadCount)
            assertTrue(cache.retainsSources)
            assertEquals(WeatherCloudMap.SOURCE_BYTES, cache.retainedBytes)
            assertEquals(3068430721L, checksum(first))
        } finally { first.recycle() }

        val cached = checkNotNull(cache.build(snapshot))
        try {
            assertEquals("Regional fallback must reuse the decoded morphology", 1, cache.loadCount)
            assertEquals(3068430721L, checksum(cached))
        } finally { cached.recycle() }

        cache.evictForMemoryPressure()
        assertFalse(cache.retainsSources)
        assertEquals(0, cache.retainedBytes)
        assertTrue(cache.acceptsReload)
        val afterPressure = checkNotNull(cache.build(snapshot))
        try {
            assertEquals(2, cache.loadCount)
            assertEquals(8192, afterPressure.width)
            assertEquals(4096, afterPressure.height)
            assertEquals(3068430721L, checksum(afterPressure))
        } finally { afterPressure.recycle() }

        cache.releaseForDisable()
        assertFalse(cache.retainsSources)
        assertEquals(0, cache.retainedBytes)
        assertNull(cache.build(snapshot))
        assertEquals(2, cache.loadCount)

        cache.allowReload()
        val reenabled = checkNotNull(cache.build(snapshot))
        try {
            assertEquals(3, cache.loadCount)
            assertEquals(8192, reenabled.width)
            assertEquals(4096, reenabled.height)
            assertEquals(3068430721L, checksum(reenabled))
        } finally { reenabled.recycle() }
    }

    @Test fun releaseDuringDecodeDoesNotRepublishTheSourceArrays() = runTest {
        val shared = WeatherCloudMap.load(RuntimeEnvironment.getApplication().assets)
        lateinit var disabled: WeatherCloudCache
        disabled = WeatherCloudCache {
            disabled.releaseForDisable()
            shared
        }
        val built = checkNotNull(disabled.build(snapshot))
        built.recycle()
        assertFalse(disabled.retainsSources)
        assertEquals(0, disabled.retainedBytes)
        assertFalse(disabled.acceptsReload)
        assertEquals(1, disabled.loadCount)
        assertNull(disabled.build(snapshot))
        assertEquals(1, disabled.loadCount)

        lateinit var pressed: WeatherCloudCache
        pressed = WeatherCloudCache {
            pressed.evictForMemoryPressure()
            shared
        }
        val duringPressure = checkNotNull(pressed.build(snapshot))
        duringPressure.recycle()
        assertFalse(pressed.retainsSources)
        assertEquals(0, pressed.retainedBytes)
        assertTrue("Memory pressure must still allow the next live rebuild", pressed.acceptsReload)
        assertEquals(1, pressed.loadCount)
    }

    private fun checksum(bitmap: android.graphics.Bitmap): Long {
        val crc = CRC32()
        val row = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            bytes.clear()
            row.forEach(bytes::putInt)
            crc.update(bytes.array())
        }
        return crc.value
    }
}
