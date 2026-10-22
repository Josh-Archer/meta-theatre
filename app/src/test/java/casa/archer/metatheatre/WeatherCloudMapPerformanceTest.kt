package casa.archer.metatheatre

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
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
class WeatherCloudMapPerformanceTest {
    @Test fun weatherPixelsRemainIdenticalAtEquatorDateLineAndPole() = runTest {
        val map = WeatherCloudMap.load(RuntimeEnvironment.getApplication().assets)
        // Captured from the original full-resolution path before adding the row shortcut.
        val cases = listOf(WeatherPlace(0.0, -100.0) to 3068430721L,
            WeatherPlace(0.0, 179.9) to 1611490886L, WeatherPlace(90.0, 0.0) to 538384725L)
        for ((place, expected) in cases) {
            val snapshot = WeatherSnapshot(place, WeatherPlace.grid(place).map {
                WeatherReading(it, Instant.parse("2026-10-17T12:00:00Z"), 61, 75, 10.0, 140.0)
            })
            val start = System.nanoTime()
            val bitmap = map.build(snapshot)
            val millis = (System.nanoTime() - start) / 1_000_000
            try {
                val crc = CRC32()
                val row = IntArray(bitmap.width)
                val bytes = ByteBuffer.allocate(bitmap.width * 4)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    bytes.clear()
                    row.forEach(bytes::putInt)
                    crc.update(bytes.array())
                }
                println("Cloud map $place: ${millis}ms, CRC32=${crc.value}")
                assertEquals("Full-resolution cloud pixels changed at $place", expected, crc.value)
            } finally { bitmap.recycle() }
        }
    }
}
