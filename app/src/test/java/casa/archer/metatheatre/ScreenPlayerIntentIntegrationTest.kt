package casa.archer.metatheatre

import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenPlayerIntentIntegrationTest {
    @Test fun aHiddenPendingMovieWaitsForRestorationAfterItsSurfaceArrives() {
        val native = mock(ExoPlayer::class.java)
        val screen = ScreenPlayer(RuntimeEnvironment.getApplication()) { _, _, _ -> native }
        screen.play(playable("pending"), 42_000L)
        screen.setScreenHidden(true)
        screen.attach(mock(Surface::class.java))
        verify(native).setPlayWhenReady(false)
        verify(native, never()).play()
        screen.setScreenHidden(false)
        verify(native).play()
    }

    @Test fun hidingKeepsTheLoadedMovieAndItsPositionWithoutStoppingOrClearingIt() {
        val f = fixture(initialPlayWhenReady = true)
        `when`(f.player.currentPosition).thenReturn(42_000L)
        f.screenPlayer.play(playable("movie"), 42_000L)
        f.screenPlayer.setScreenHidden(true)
        assertFalse(f.screenPlayer.transport.value.playWhenReady)
        f.screenPlayer.setScreenHidden(false)
        org.junit.Assert.assertEquals("movie", f.screenPlayer.transport.value.title)
        org.junit.Assert.assertEquals(42_000L, f.screenPlayer.transport.value.positionMs)
        verify(f.player, never()).stop()
        verify(f.player, never()).clearMediaItems()
    }

    @Test fun hidingPausesBufferingMovieAndRestoringResumesItsPreviousIntent() {
        val f = fixture(initialPlayWhenReady = true)
        f.screenPlayer.setScreenHidden(true)
        verify(f.player).pause()
        f.screenPlayer.setScreenHidden(false)
        verify(f.player).play()
    }

    @Test fun screenRestoreCannotResumeThroughAnOpenSystemMenu() {
        val f = fixture(initialPlayWhenReady = true)
        f.screenPlayer.setScreenHidden(true)
        f.screenPlayer.onMenuOpened()
        f.screenPlayer.setScreenHidden(false)
        verify(f.player, never()).play()
        f.screenPlayer.onMenuClosed()
        verify(f.player).play()
    }

    @Test fun closingSystemMenuCannotResumeWhileScreenRemainsHidden() {
        val f = fixture(initialPlayWhenReady = true)
        f.screenPlayer.onMenuOpened()
        f.screenPlayer.setScreenHidden(true)
        f.screenPlayer.onMenuClosed()
        verify(f.player, never()).play()
        f.screenPlayer.setScreenHidden(false)
        verify(f.player).play()
    }

    @Test fun hidingAnAlreadyPausedMovieDoesNotResumeIt() {
        val f = fixture(initialPlayWhenReady = false)
        f.screenPlayer.setScreenHidden(true)
        f.screenPlayer.setScreenHidden(false)
        verify(f.player, never()).play()
    }

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
