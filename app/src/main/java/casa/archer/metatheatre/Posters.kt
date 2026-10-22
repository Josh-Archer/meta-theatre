package casa.archer.metatheatre

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Small Plex posters for the shelf. The URL carries the token, so it is never logged. */
internal object Posters {
    private const val MAX_BYTES = 2_000_000
    private data class Poster(val image: ImageBitmap, val bytes: Int)
    private val downloads = Semaphore(3)
    private class Pending(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    private val cache = object : LruCache<String, Poster>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Poster): Int = value.bytes
    }

    fun cached(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        return cache.get(url)?.image
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cached(url)?.let { return it }
        val request = synchronized(pending) { pending.getOrPut(url) { Pending() }.also { it.users++ } }
        try {
            // Wait for this URL before taking a download slot; other posters can still load.
            return request.mutex.withLock {
                cached(url)?.let { return@withLock it }
                downloads.withPermit {
                    val poster = withContext(Dispatchers.IO) { fetch(url) } ?: return@withPermit null
                    cache.put(url, poster)
                    poster.image
                }
            }
        } finally {
            synchronized(pending) {
                if (--request.users == 0) pending.remove(url)
            }
        }
    }

    private fun fetch(url: String): Poster? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            val token = URLDecoder.decode(url.substringAfter("X-Plex-Token=", ""), "UTF-8")
                .substringBefore("&")
            if (token.isNotBlank()) setRequestProperty("X-Plex-Token", token)
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = readLimited(connection) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 480 && sample < 16) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            Poster(decoded.asImageBitmap(), decoded.allocationByteCount)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(connection: HttpURLConnection): ByteArray? {
        val stream = connection.inputStream ?: return null
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        stream.use { input ->
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) return null
                buffer.write(chunk, 0, count)
            }
        }
        return buffer.toByteArray()
    }
}
