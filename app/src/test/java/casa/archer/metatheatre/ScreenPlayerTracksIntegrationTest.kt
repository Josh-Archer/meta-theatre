package casa.archer.metatheatre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.SubtitleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenPlayerTracksIntegrationTest {
    @Test
    fun nativeTrackCyclingAppliesMedia3OverridesAndSubtitleOffDisablesText() {
        val fixture = fixture()
        val english = TrackGroup(format("English", "en", MimeTypes.AUDIO_AAC))
        val french = TrackGroup(format("French", "fr", MimeTypes.AUDIO_AAC))
        val captions = TrackGroup(format("English captions", "en", MimeTypes.TEXT_VTT))
        fixture.listener.onTracksChanged(
            Tracks(
                listOf(
                    Tracks.Group(english, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(french, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                    Tracks.Group(captions, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                ),
            ),
        )

        fixture.screenPlayer.cycleAudio()
        val audioOverride = fixture.selector.parameters.overrides.values.single()
        assertEquals(french, audioOverride.mediaTrackGroup)
        assertEquals(listOf(0), audioOverride.trackIndices)

        fixture.screenPlayer.cycleSubtitles()
        assertTrue(fixture.selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertEquals(PlaybackTracks.SUBTITLES_OFF_ID, fixture.screenPlayer.tracks.value.selectedSubtitleId)
    }

    @Test
    fun cueListenerFeedsTextAndBitmapCuesToTheRealSubtitleView() {
        val fixture = fixture()
        fixture.screenPlayer.play(playable())
        val captions = TrackGroup(format("English captions", "en", MimeTypes.TEXT_VTT))
        fixture.listener.onTracksChanged(
            Tracks(listOf(Tracks.Group(captions, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))),
        )
        fixture.screenPlayer.selectSubtitleTrack("media-subtitle:0:0")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff0000.toInt()) }
        val textCue = Cue.Builder().setText("Hello from Plex").build()
        val bitmapCue = Cue.Builder().setBitmap(bitmap).setPosition(0.5f).setSize(0.2f)
            .setBitmapHeight(0.1f).setLine(0.8f, Cue.LINE_TYPE_FRACTION).build()
        fixture.listener.onCues(CueGroup(listOf(textCue, bitmapCue), 0L))

        val view = createSubtitleView(RuntimeEnvironment.getApplication())
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 640, 360)
        updateSubtitleView(view, fixture.screenPlayer.subtitleCues.value)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 640, 360)
        val rendered = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(rendered))

        assertEquals(listOf(textCue, bitmapCue), fixture.screenPlayer.subtitleCues.value)
        assertTrue("SubtitleView should paint the cue overlay", nonTransparentPixels(rendered) > 0)
        fixture.screenPlayer.close()
        assertTrue(fixture.screenPlayer.subtitleCues.value.isEmpty())
    }

    @Test
    fun plexMetadataStreamsDriveHlsSelectionAtCurrentPositionAndPlayIntent() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"MediaContainer":{"Metadata":[{"Media":[{"width":3840,"height":2160,"bitrate":40000,"Part":[{"key":"/library/parts/77/file.mkv","Stream":[{"id":"23","streamType":2,"language":"English","languageCode":"en","codec":"aac","selected":true},{"id":"47","streamType":3,"language":"French","languageCode":"fr","codec":"srt"}]}]}]}]}}""",
                ),
            )
            val client = PlexClient(RuntimeEnvironment.getApplication())
            val serverUri = server.url("/").toString().trimEnd('/')
            val item = client.playable(
                PlexServer("test", serverUri, "server-token"),
                PlexItem("42", "Track test", "", "movie"),
            )
            assertEquals(listOf("23"), item.audioStreams.map { it.id })
            assertEquals(listOf("47"), item.subtitleStreams.map { it.id })

            val fixture = fixture()
            fixture.screenPlayer.play(item, positionMs = 12_345L)
            fixture.screenPlayer.setQuality(PlaybackQuality.HD)
            fixture.screenPlayer.selectAudioTrack("plex-audio:23")
            assertTrue(fixture.screenPlayer.transport.value.playWhenReady)
            fixture.screenPlayer.toggle()
            assertFalse(fixture.screenPlayer.transport.value.playWhenReady)
            fixture.screenPlayer.selectSubtitleTrack("plex-subtitle:47")

            val mediaItems = org.mockito.ArgumentCaptor.forClass(MediaItem::class.java)
            val positions = org.mockito.ArgumentCaptor.forClass(Long::class.javaObjectType)
            verify(fixture.player, org.mockito.Mockito.atLeast(4)).setMediaItem(mediaItems.capture(), positions.capture())
            val selectedUrl = Uri.parse(mediaItems.allValues.last().localConfiguration!!.uri.toString())
            assertEquals("23", selectedUrl.getQueryParameter("audioStreamID"))
            assertEquals("47", selectedUrl.getQueryParameter("subtitleStreamID"))
            assertEquals("burn", selectedUrl.getQueryParameter("subtitles"))
            assertEquals("1920x1080", selectedUrl.getQueryParameter("videoResolution"))
            assertNotNull(selectedUrl.getQueryParameter("session"))
            assertNotEquals(
                Uri.parse(item.hdHlsUrl).getQueryParameter("session"),
                selectedUrl.getQueryParameter("session"),
            )
            assertEquals(12_345L, positions.allValues.last())
            val playIntents = org.mockito.ArgumentCaptor.forClass(Boolean::class.javaObjectType)
            verify(fixture.player, org.mockito.Mockito.atLeast(4)).setPlayWhenReady(playIntents.capture())
            assertTrue(playIntents.allValues.contains(true))
            assertFalse(playIntents.allValues.last())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pendingMediaRemembersMenuIntentAndCloseClearsTrackState() {
        val screen = ScreenPlayer(RuntimeEnvironment.getApplication()) { _, _, _ -> error("Player should remain pending") }
        screen.play(playable().copy(subtitleStreams = listOf(PlexPlaybackStream("9", title = "English"))))
        screen.onMenuOpened()
        assertFalse(screen.transport.value.playWhenReady)
        screen.onMenuClosed()
        assertTrue(screen.transport.value.playWhenReady)
        screen.close()
        assertTrue(screen.tracks.value.audio.isEmpty())
        assertEquals(listOf(PlaybackTracks.SUBTITLES_OFF_ID), screen.tracks.value.subtitles.map { it.id })
        assertTrue(screen.subtitleCues.value.isEmpty())
    }

    private fun fixture(): Fixture {
        val context = RuntimeEnvironment.getApplication()
        var capturedListener: Player.Listener? = null
        var capturedSelector: DefaultTrackSelector? = null
        val player = mock(ExoPlayer::class.java)
        doAnswer { invocation -> capturedListener = invocation.getArgument(0); null }
            .`when`(player).addListener(any(Player.Listener::class.java))
        var playWhenReady = false
        doAnswer { playWhenReady }.`when`(player).playWhenReady
        doAnswer { playWhenReady = it.getArgument(0); null }.`when`(player).setPlayWhenReady(anyBoolean())
        doAnswer { playWhenReady = true; null }.`when`(player).play()
        doAnswer { playWhenReady = false; null }.`when`(player).pause()
        doAnswer { 12_345L }.`when`(player).currentPosition
        val screenPlayer = ScreenPlayer(context) { _, selector, _ -> capturedSelector = selector; player }
        screenPlayer.attach(mock(Surface::class.java))
        assertNotNull(capturedListener)
        assertNotNull(capturedSelector)
        return Fixture(screenPlayer, player, capturedSelector!!, capturedListener!!)
    }

    private fun format(label: String, language: String, mimeType: String) =
        Format.Builder().setLabel(label).setLanguage(language).setSampleMimeType(mimeType).build()

    private fun playable() = Playable(
        title = "Track test",
        directUrl = "https://example.invalid/movie.mp4",
        hlsUrl = "https://plex.invalid/video/:/transcode/universal/start.m3u8?videoResolution=3840x2160&subtitles=none",
        hdHlsUrl = "https://plex.invalid/video/:/transcode/universal/start.m3u8?videoResolution=1920x1080&subtitles=none",
        headers = emptyMap(),
    )

    private fun nonTransparentPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            if (bitmap.getPixel(x, y) ushr 24 != 0) count++
        }
        return count
    }

    private data class Fixture(
        val screenPlayer: ScreenPlayer,
        val player: ExoPlayer,
        val selector: DefaultTrackSelector,
        val listener: Player.Listener,
    )
}
