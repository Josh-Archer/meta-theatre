package casa.archer.metatheatre

import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackRecoveryIntegrationTest {
    @Test fun disconnectedPlaybackRetriesTheSameQualityThreeTimesAtTheSavedPositionThenOffersManualRecovery() {
        val f = fixture()
        try {
            repeat(3) { attempt ->
                f.listener.onPlayerError(networkError())
                assertTrue(f.screen.transport.value.reconnecting)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1L shl attempt))
            }
            f.listener.onPlayerError(networkError())
            assertFalse(f.screen.transport.value.reconnecting)
            assertTrue(f.screen.transport.value.canRetry)
            val items = org.mockito.ArgumentCaptor.forClass(MediaItem::class.java)
            verify(f.native, times(4)).setMediaItem(items.capture(), eq(42_000L))
            assertTrue(items.allValues.all { it.localConfiguration!!.uri.toString().endsWith("movie.mp4") })
            assertEquals("Original", f.screen.transport.value.playbackMode)
            assertEquals(42_000L, f.screen.transport.value.positionMs)
        } finally { f.screen.release() }
    }

    @Test fun aStopPalmOrMenuDuringTheRetryDelayCannotAccidentallyResumePlayback() {
        val f = fixture()
        try {
            f.listener.onPlayerError(networkError())
            f.screen.pause()
            f.screen.onMenuOpened()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            verify(f.native, times(2)).setMediaItem(any(MediaItem::class.java), eq(42_000L))
            assertFalse(f.screen.transport.value.playWhenReady)
            f.screen.onMenuClosed()
            verify(f.native, never()).play()
        } finally { f.screen.release() }
    }

    @Test fun closingOrReplacingTheMovieCancelsItsQueuedRetry() {
        val f = fixture()
        try {
            f.listener.onPlayerError(networkError())
            f.screen.close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            verify(f.native, times(1)).setMediaItem(any(MediaItem::class.java), anyLong())
            assertEquals("", f.screen.transport.value.title)
            f.screen.play(item("new"), 5_000)
            f.listener.onPlayerError(networkError())
            f.screen.play(item("replacement"), 10_000)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            verify(f.native, times(3)).setMediaItem(any(MediaItem::class.java), anyLong())
        } finally { f.screen.release() }
    }

    @Test fun decoderFailureUsesCompatibilityFallbackAtTheBookmarkInsteadOfRetryingAnUnusableDecoder() {
        val f = fixture()
        try {
            f.listener.onPlayerError(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            val items = org.mockito.ArgumentCaptor.forClass(MediaItem::class.java)
            verify(f.native, times(2)).setMediaItem(items.capture(), eq(42_000L))
            assertTrue(items.allValues.last().localConfiguration!!.uri.toString().contains("videoResolution=3840x2160"))
            assertFalse(f.screen.transport.value.canRetry)
        } finally { f.screen.release() }
    }

    @Test fun manualReconnectDelegatesUrlRefreshAndKeepsTheBookmarkAndExplicitPauseIntent() {
        val f = fixture()
        try {
            var refreshed = 0
            f.screen.onReconnect = { refreshed++ }
            f.listener.onPlayerError(networkError())
            f.screen.pause()
            f.screen.retry()
            assertEquals(1, refreshed)
            f.screen.reconnect(item("refreshed"))
            val items = org.mockito.ArgumentCaptor.forClass(MediaItem::class.java)
            verify(f.native, times(2)).setMediaItem(items.capture(), eq(42_000L))
            assertTrue(items.allValues.last().localConfiguration!!.uri.toString().contains("refreshed"))
            assertFalse(f.screen.transport.value.playWhenReady)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            verify(f.native, times(2)).prepare()
        } finally { f.screen.release() }
    }

    @Test fun idleCallbacksFromAnErroredDecoderCannotOverwriteTheResumeBookmarkWithZero() {
        val f = fixture()
        try {
            f.listener.onPlaybackStateChanged(Player.STATE_IDLE)
            assertEquals(42_000L, f.screen.transport.value.positionMs)
            f.listener.onPlayerError(networkError())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            verify(f.native, times(2)).setMediaItem(any(MediaItem::class.java), eq(42_000L))
        } finally { f.screen.release() }
    }

    private fun networkError() = PlaybackException("private network details", java.io.IOException(),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    private fun item(name: String = "movie") = Playable("Test", "https://example.invalid/$name.mp4",
        "https://example.invalid/stream.m3u8?videoResolution=3840x2160", emptyMap())
    private fun fixture(): Fixture {
        lateinit var listener: Player.Listener
        val native = mock(ExoPlayer::class.java)
        var intent = true
        doAnswer { listener = it.getArgument(0); null }.`when`(native).addListener(any(Player.Listener::class.java))
        doAnswer { intent }.`when`(native).playWhenReady
        doAnswer { intent = it.getArgument(0); null }.`when`(native).setPlayWhenReady(anyBoolean())
        doAnswer { intent = false; null }.`when`(native).pause()
        doAnswer { intent = true; null }.`when`(native).play()
        `when`(native.currentPosition).thenReturn(0L) // Simulate the failed decoder losing its position.
        val screen = ScreenPlayer(RuntimeEnvironment.getApplication()) { _, _, _ -> native }
        screen.attach(mock(Surface::class.java))
        screen.play(item(), 42_000)
        return Fixture(screen, native, listener)
    }
    private data class Fixture(val screen: ScreenPlayer, val native: ExoPlayer, val listener: Player.Listener)
}
