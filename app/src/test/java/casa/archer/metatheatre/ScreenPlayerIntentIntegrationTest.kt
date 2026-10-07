package casa.archer.metatheatre

import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenPlayerIntentIntegrationTest {
    @Test
    fun togglePausesBufferingPlaybackAndPublishesPauseIntent() {
        val fixture = fixture(initialPlayWhenReady = true)

        fixture.screenPlayer.toggle()

        verify(fixture.player).pause()
        verify(fixture.player, never()).play()
        assertFalse(fixture.screenPlayer.transport.value.playing)
    }

    @Test
    fun menuOpenRemembersPlayIntentWhileBuffering() {
        val fixture = fixture(initialPlayWhenReady = true)

        fixture.screenPlayer.onMenuOpened()
        fixture.screenPlayer.onMenuClosed()

        verify(fixture.player).pause()
        verify(fixture.player).play()
    }

    @Test
    fun duplicateMenuOpenDoesNotEraseResumeIntent() {
        val fixture = fixture(initialPlayWhenReady = true)

        fixture.screenPlayer.onMenuOpened()
        fixture.screenPlayer.onMenuOpened()
        fixture.screenPlayer.onMenuClosed()

        verify(fixture.player).play()
    }

    @Test
    fun mediaStartedWhileMenuIsOpenWaitsForMenuClose() {
        val fixture = fixture(initialPlayWhenReady = true)
        fixture.screenPlayer.onMenuOpened()

        fixture.screenPlayer.play(playable("replacement"))

        verify(fixture.player).setPlayWhenReady(false)
        fixture.screenPlayer.onMenuClosed()
        verify(fixture.player).play()
    }

    @Test
    fun menuOpenedForPausedBufferingMediaDoesNotResumeIt() {
        val fixture = fixture(initialPlayWhenReady = false)

        fixture.screenPlayer.onMenuOpened()
        fixture.screenPlayer.onMenuClosed()

        verify(fixture.player, never()).play()
        assertFalse(fixture.screenPlayer.transport.value.playing)
    }

    private fun fixture(initialPlayWhenReady: Boolean): Fixture {
        val context = RuntimeEnvironment.getApplication()
        var playWhenReady = initialPlayWhenReady
        val player = mock(ExoPlayer::class.java)
        doAnswer { playWhenReady }.`when`(player).playWhenReady
        doAnswer { playWhenReady = true; null }.`when`(player).play()
        doAnswer { playWhenReady = false; null }.`when`(player).pause()
        val screenPlayer = ScreenPlayer(context) { _, _, _ -> player }
        screenPlayer.attach(mock(Surface::class.java))
        return Fixture(screenPlayer, player)
    }

    private fun playable(title: String) = Playable(
        title = title,
        directUrl = "https://example.invalid/$title.mp4",
        hlsUrl = "https://example.invalid/$title.m3u8",
        headers = emptyMap(),
    )

    private data class Fixture(val screenPlayer: ScreenPlayer, val player: ExoPlayer)
}
