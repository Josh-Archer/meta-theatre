package casa.archer.metatheatre

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarshipControlsIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun visibleButtonsMatchRayTargetsAndLaunchNeverChangesTheViewEnvironment() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("theatre_state",0).edit().clear().commit()
        val player=mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val model=TheatreModel(context,player)
        val requests=mutableListOf<Boolean>();var locationChanges=0
        model.onStarshipFlight=requests::add;model.onLocationChanged={locationChanges++}
        compose.setContent { Box(Modifier.requiredSize(1600.dp,900.dp)) { LibraryPanel(model,player) } }
        try {
            compose.onNodeWithText("Scenes").assertIsDisplayed().performClick()
            val cabin = TheatreLocation.entries.first { it != TheatreLocation.EARTH_ORBIT }
            compose.onNodeWithText(cabin.title).performClick()
            compose.runOnIdle { assertEquals(cabin, model.currentLocation.value) }
            compose.onNodeWithText(TheatreLocation.EARTH_ORBIT.title).performClick()
            compose.runOnIdle { assertEquals(TheatreLocation.EARTH_ORBIT, model.currentLocation.value) }
            val selections = locationChanges
            val rect=compose.onNodeWithText("Launch / replay Starship").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertTrue(model.scenePickerPress((rect.center.x-root.left)/root.width,(rect.center.y-root.top)/root.height))
            }
            assertEquals(listOf(true),requests)
            compose.onNodeWithText("End flight").performClick()
            assertEquals(listOf(true,false),requests)
            compose.runOnIdle {
                model.currentLocation.value=TheatreLocation.entries.first { it != TheatreLocation.EARTH_ORBIT }
                model.starshipFlight(true)
            }
            assertEquals(listOf(true,false),requests)
            assertEquals(selections,locationChanges)
            assertEquals("Select Earth orbit, then launch",model.starshipStatus.value)
            compose.onNodeWithText("Back to library").performClick()
            compose.onNodeWithText("Scenes").assertIsDisplayed()
        } finally { compose.runOnIdle { model.release() } }
    }
}
