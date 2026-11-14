package casa.archer.metatheatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.mockito.Mockito.never
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerBarIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var model: TheatreModel? = null

    @After fun release() { model?.release() }

    @Test fun theMovieRemainsVisibleThroughTheControlSurface() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport(title = "Movie")))
        `when`(player.tracks).thenReturn(MutableStateFlow(PlaybackTracks()))
        val current = TheatreModel(context, player).also { model = it }
        current.playerControlsOpen.value = true
        val movie = mutableStateOf(Color.Black)
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 640.dp).background(movie.value)) { PlayerBar(current, player) }
        }
        fun surfacePixel(): Int {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            return android.graphics.Color.red(image.getPixel(900, 410)).also { image.recycle() }
        }
        var dark = 0
        compose.runOnIdle { dark = surfacePixel(); movie.value = Color.White }
        compose.runOnIdle {
            // Sample inside the surface, away from its border, text and buttons.
            val transmitted = surfacePixel() - dark
            assertTrue("Movie pixels must show through the panel", transmitted > 60)
            assertTrue("The panel must retain a readable dark tint", transmitted < 140)
        }
    }

    @Test fun visibleTrackAndCinemaButtonsDispatchThroughTheActualModel() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport(
            title = "Movie", playWhenReady = true, playing = false,
            durationMs = 120_000, sourceWidth = 3840, sourceHeight = 2160,
            videoWidth = 3840, videoHeight = 2160, videoColor = "Dolby Vision",
        )))
        `when`(player.tracks).thenReturn(MutableStateFlow(PlaybackTracks(
            audio = listOf(PlaybackTrack("audio", "English")), selectedAudioId = "audio",
        )))
        val currentModel = TheatreModel(context, player)
        model = currentModel
        currentModel.playerControlsOpen.value = true
        var quietPreference: Boolean? = null
        currentModel.onQuietCinemaPreference = { quietPreference = it }
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 640.dp)) { PlayerBar(currentModel, player) }
        }

        compose.onNodeWithText("Smaller").assertDoesNotExist()
        compose.runOnIdle { captureUiFixture(compose.activity, "player-attached-compact.png") }
        // A native per-hand touch cannot also activate our shared-ray selection.
        compose.onNodeWithText("Pause").performTouchInput { click() }
        verify(player, never()).toggle()
        pressRail("Settings", PlayerControl.SETTINGS, currentModel)
        val labels = listOf("Pause", "−10s", "+10s", "Smaller", "Larger", "Audio", "Subtitles",
            "Explore Earth", "Library", "Quiet cinema: Off", "Reconnect", "Skip +30s", "Done")
        val buttonBounds = labels.map { label ->
            val node = compose.onNodeWithText(label).assertIsDisplayed()
            val layout = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            val text = layout.single()
            assertEquals("Single line label $label", 1, text.lineCount)
            assertTrue("Readable font for $label", text.layoutInput.style.fontSize.value >= 24f)
            assertTrue("Unclipped height $label", text.multiParagraph.height <= text.size.height + 1f)
            assertTrue("Complete label $label", text.getLineEnd(text.lineCount - 1, visibleEnd = true) == label.length)
            node.fetchSemanticsNode().boundsInRoot
        }
        compose.onNodeWithText("Input: Dolby Vision · Display: SDR / Rec.709").assertIsDisplayed()
        for (i in buttonBounds.indices) for (j in i+1 until buttonBounds.size) {
            val a = buttonBounds[i]; val b = buttonBounds[j]
            assertTrue("Controls must not overlap: ${labels[i]}, ${labels[j]}",
                a.bottom <= b.top || b.bottom <= a.top || a.right <= b.left || b.right <= a.left)
        }
        compose.runOnIdle { captureUiFixture(compose.activity, "player-attached-expanded.png") }

        pressRail("Pause", PlayerControl.TOGGLE, currentModel)
        verify(player).toggle()
        pressRail("Audio", PlayerControl.AUDIO, currentModel)
        verify(player).cycleAudio()
        pressRail("Subtitles", PlayerControl.SUBTITLES, currentModel)
        verify(player).cycleSubtitles()
        pressRail("Quiet cinema: Off", PlayerControl.QUIET, currentModel)
        compose.onNodeWithText("Quiet cinema: On").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(true, quietPreference)
            assertTrue(TheatreState(context).quietCinemaEnabled)
        }
        pressRail("Done", PlayerControl.SETTINGS, currentModel)
        compose.onNodeWithText("Smaller").assertDoesNotExist()
        compose.runOnIdle {
            for (y in 0..60) for (x in 0..100) {
                assertTrue(currentModel.playerHitTargets.at(x / 100f, y / 100f) == null)
            }
        }
        pressRail("Settings", PlayerControl.SETTINGS, currentModel)
        pressRail("Explore Earth", PlayerControl.EARTH, currentModel)
        compose.runOnIdle {
            assertTrue(currentModel.libraryOpen.value)
            assertTrue(currentModel.earthControlsOpen.value)
        }
    }

    private fun pressRail(label: String, expected: PlayerControl, model: TheatreModel) {
        val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            // Quest rays dispatch through measured bounds rather than Android touch events.
            val target = model.playerHitTargets.at(bounds.center.x / root.width, bounds.center.y / root.height)
            assertEquals(PlayerTarget.Button(expected), target)
            model.playerControl((target as PlayerTarget.Button).control)
        }
    }
}
