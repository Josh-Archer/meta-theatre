package casa.archer.metatheatre

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk=[34],qualifiers="w1600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherSettingsIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var model:TheatreModel
    @Before fun setup(){
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TheatreState(RuntimeEnvironment.getApplication()).saveLiveWeatherEnabled(false);TheatreState(RuntimeEnvironment.getApplication()).saveClearEarth(false)
    }
    @After fun release(){model.release();TheatreState(RuntimeEnvironment.getApplication()).saveLiveWeatherEnabled(false);TheatreState(RuntimeEnvironment.getApplication()).saveClearEarth(false);Dispatchers.resetMain()}
    private fun ray(label:String){
        val rect=compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle{assertTrue(model.weatherControlPress(rect.center.x/root.width,rect.center.y/root.height))}
    }
    @Test fun renderedControlsUseQuestRaysPersistTheOptInAndReturnToTheMovieWithoutPlexLogin(){
        val player=mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport(title="Movie")))
        model=TheatreModel(RuntimeEnvironment.getApplication(),player,mock(PlexClient::class.java),Dispatchers.Unconfined)
        model.libraryOpen.value=false
        val toggles=mutableListOf<Boolean>();val clearToggles=mutableListOf<Boolean>();var refreshes=0;var earthMoves=0
        model.onClearEarth=clearToggles::add
        model.onWeatherEnabled={toggles.add(it)};model.onWeatherRefresh={refreshes++};model.onEarthControl={earthMoves++}
        model.openEarthControls()
        compose.setContent{Box(Modifier.requiredSize(1600.dp,900.dp)){LibraryPanel(model,player)}}
        ray("Weather settings")
        compose.onNodeWithText("Earth weather").assertIsDisplayed()
        compose.onNodeWithText("Live weather: Off").assertIsDisplayed()
        ray("Live weather: Off")
        compose.runOnIdle{assertTrue(TheatreState(RuntimeEnvironment.getApplication()).liveWeatherEnabled);assertEquals(listOf(true),toggles)}
        ray("Clear Earth: Off")
        compose.runOnIdle {
            assertTrue(TheatreState(RuntimeEnvironment.getApplication()).clearEarth)
            assertEquals(listOf(true), clearToggles)
            assertTrue(TheatreState(RuntimeEnvironment.getApplication()).liveWeatherEnabled)
        }
        compose.onNodeWithText("Clear Earth · clouds and cloud shadows hidden").assertIsDisplayed()
        ray("Clear Earth: On")
        compose.runOnIdle { assertFalse(TheatreState(RuntimeEnvironment.getApplication()).clearEarth) }
        ray("Refresh")
        compose.runOnIdle{
            assertEquals(1,refreshes)
            assertTrue(model.weatherControlPress(.5f,.9f)) // text cannot select a hidden Earth button
            assertTrue(model.earthOrbitScroll(1));assertEquals(0,earthMoves)
            model.weatherUi.value=WeatherUi(true,WeatherPlace(0.0,-100.0),"Current model weather",
                WeatherReading(WeatherPlace(0.0,-100.0),java.time.Instant.parse("2026-10-17T12:00:00Z"),63,82,19.0,120.0))
        }
        compose.runOnIdle{captureUiFixture(compose.activity,"weather-settings.png")}
        ray("Live weather: On")
        compose.runOnIdle{assertFalse(TheatreState(RuntimeEnvironment.getApplication()).liveWeatherEnabled)}
        ray("Back to Earth")
        compose.onNodeWithText("North Pole").assertIsDisplayed()
        compose.runOnIdle{model.earthControl(EarthControl.DONE);assertFalse(model.libraryOpen.value);assertEquals(Browse.SignedOut,model.browse.value)}
        compose.onNodeWithContentDescription("Screen frame: drag edges to move, corners to resize").assertIsDisplayed()
    }
}
