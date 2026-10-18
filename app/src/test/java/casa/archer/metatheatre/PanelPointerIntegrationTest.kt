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
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk=[34],qualifiers="w1600dp-h1800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelPointerIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var model:TheatreModel?=null
    @Before fun setup(){Dispatchers.setMain(UnconfinedTestDispatcher())}
    @After fun release(){model?.release();Dispatchers.resetMain()}
    private fun player():ScreenPlayer=mock(ScreenPlayer::class.java).also {
        `when`(it.transport).thenReturn(MutableStateFlow(Transport(title="Movie",playWhenReady=true)))
        `when`(it.tracks).thenReturn(MutableStateFlow(PlaybackTracks()))
    }
    @Test fun renderedSideMenuPointerSharesButtonCoordinatesAndClearsWhenHidden(){
        val player=player()
        val current=TheatreModel(RuntimeEnvironment.getApplication(),player,mock(PlexClient::class.java),Dispatchers.Unconfined)
        model=current;current.playerControlsOpen.value=true
        compose.setContent{Box(Modifier.requiredSize(560.dp,1640.dp)){PlayerBar(current,player)}}
        val button=compose.onNodeWithText("Pause").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        val x=button.center.x/root.width;val y=button.center.y/root.height
        compose.runOnIdle{
            assertEquals(PlayerTarget.Button(PlayerControl.TOGGLE),current.playerHitTargets.at(x,y))
            current.panelCursor.value=PanelCursor(PointerPanel.PLAYER,x,y)
        }
        compose.onNodeWithContentDescription("Side menu pointer").assertIsDisplayed()
        compose.runOnIdle{captureUiFixture(compose.activity,"side-menu-pointer.png")}
        compose.runOnIdle{current.playerControlsOpen.value=false}
        compose.onNodeWithContentDescription("Side menu pointer").assertDoesNotExist()
        compose.runOnIdle{current.playerControlsOpen.value=true;current.panelCursor.value=null}
        compose.onNodeWithContentDescription("Side menu pointer").assertDoesNotExist()
        compose.runOnIdle{current.panelCursor.value=PanelCursor(PointerPanel.LIBRARY,.5f,.5f)}
        compose.onNodeWithContentDescription("Side menu pointer").assertDoesNotExist()
    }
    @Test fun selectorPointerAppearsOverTheLibraryButNotTheEmptyMovieGrabFrame(){
        val player=player()
        val current=TheatreModel(RuntimeEnvironment.getApplication(),player,mock(PlexClient::class.java),Dispatchers.Unconfined)
        model=current
        compose.setContent{Box(Modifier.requiredSize(1600.dp,900.dp)){LibraryPanel(current,player)}}
        compose.runOnIdle{current.panelCursor.value=PanelCursor(PointerPanel.LIBRARY,.5f,.5f)}
        compose.onNodeWithContentDescription("Video selector pointer").assertIsDisplayed()
        compose.runOnIdle{captureUiFixture(compose.activity,"video-selector-pointer.png");current.libraryOpen.value=false}
        compose.onNodeWithContentDescription("Video selector pointer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Screen frame: drag edges to move, corners to resize").assertIsDisplayed()
        compose.runOnIdle{current.libraryOpen.value=true;current.screenHidden.value=true}
        compose.onNodeWithContentDescription("Video selector pointer").assertDoesNotExist()
    }
}
