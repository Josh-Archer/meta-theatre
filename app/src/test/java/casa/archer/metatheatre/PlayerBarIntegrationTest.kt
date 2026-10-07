package casa.archer.metatheatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w560dp-h1640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerBarIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var model: TheatreModel? = null

    @After fun release() { model?.release() }

    @Test fun visibleTrackAndCinemaButtonsDispatchThroughTheActualModel() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state", 0).edit().clear().commit()
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport(
            title = "Movie", playWhenReady = true, playing = false,
            durationMs = 120_000, sourceWidth = 3840, sourceHeight = 2160,
            videoWidth = 3840, videoHeight = 2160,
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
            Box(Modifier.requiredSize(560.dp, 1640.dp)) { PlayerBar(currentModel, player) }
        }

        pressRail("Pause", PlayerControl.TOGGLE, currentModel)
        verify(player).toggle()
        pressRail("Audio: English", PlayerControl.AUDIO, currentModel)
        verify(player).cycleAudio()
        pressRail("Subtitles: Off", PlayerControl.SUBTITLES, currentModel)
        verify(player).cycleSubtitles()
        pressRail("Quiet cinema: Off", PlayerControl.QUIET, currentModel)
        compose.onNodeWithText("Quiet cinema: On").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(true, quietPreference)
            assertTrue(TheatreState(context).quietCinemaEnabled)
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
