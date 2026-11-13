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
import org.junit.Before
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
class EarthSettingsIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clear(){context.getSharedPreferences("theatre_state",0).edit().clear().commit()}

    @Test fun visibleControlsMatchQuestRayTargetsAndPersistOnlyPreferenceBooleans() {
        val player=mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val model=TheatreModel(context,player)
        val locationRequests=mutableListOf<Boolean>();var refreshes=0;var rotation:Boolean?=null
        val zooms=mutableListOf<EarthSetting>()
        var settings=0;var precise=0
        model.onLocationSettings={settings++};model.onPreciseLocation={precise++}
        model.onLocationEnabled=locationRequests::add
        model.onLocationRequest={refreshes++}
        model.onRealRotation={rotation=it}
        model.onEarthZoom=zooms::add
        model.openEarthControls()
        compose.setContent{Box(Modifier.requiredSize(1600.dp,900.dp)){LibraryPanel(model,player)}}
        fun press(label:String){
            val rect=compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle{
                val x=(rect.center.x-root.left)/root.width;val y=(rect.center.y-root.top)/root.height
                assertFalse(model.weatherControlPress(x,y));assertTrue(model.earthControlPress(x,y))
            }
        }
        try {
            press("Location, zoom & rotation")
            press("Use my location: Off")
            assertEquals(listOf(true),locationRequests)
            assertTrue(TheatreState(context).useCurrentLocation)
            press("Above me");assertEquals(1,refreshes)
            // The SDK can deliver a native Compose click instead of the custom hand ray.
            compose.onNodeWithText("Above me").performClick()
            assertEquals(2,refreshes)
            compose.onNodeWithText("Precise Wi-Fi fix").performClick()
            compose.onNodeWithText("Location settings").performClick()
            assertEquals(1,precise);assertEquals(1,settings)
            press("Real-time rotation: On")
            assertEquals(false,rotation);assertFalse(TheatreState(context).realEarthRotationEnabled)
            press("Closer to Earth");press("Farther from Earth");press("Reset zoom")
            assertEquals(listOf(EarthSetting.ZOOM_IN,EarthSetting.ZOOM_OUT,EarthSetting.ZOOM_RESET),zooms)
            compose.runOnIdle{
                model.earthZoom.value=9.2f
                model.earthZoomMessage.value="Closest safe view · screen and head clearance protected"
                model.locationMessage.value="Still waiting for a fresh fix. Approximate location may be throttled; try Precise Wi-Fi fix or Location settings."
            }
            compose.onNodeWithText(model.locationMessage.value).assertIsDisplayed()
            compose.onNodeWithText(model.earthZoomMessage.value).assertIsDisplayed()
            compose.runOnIdle{captureUiFixture(compose.activity,"earth-location-zoom-rotation.png")}
            press("Use my location: On")
            assertFalse(TheatreState(context).useCurrentLocation)
            val prefs=context.getSharedPreferences("theatre_state",0).all
            assertTrue(prefs.keys.all{it in setOf("use_current_location","real_earth_rotation")})
            press("Back to Earth")
            compose.onNodeWithText("North Pole").assertIsDisplayed()
        } finally {model.release()}
    }
}
