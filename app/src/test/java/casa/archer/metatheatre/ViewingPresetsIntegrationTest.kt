package casa.archer.metatheatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
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
@Config(sdk = [34], qualifiers = "w1600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewingPresetsIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("theatre_state", 0).edit().clear().commit() }

    @Test fun renderedButtonsSaveRestoreAndClearTheMatchingPersistentSlotThroughQuestRayCoordinates() {
        val player = mock(ScreenPlayer::class.java)
        `when`(player.transport).thenReturn(MutableStateFlow(Transport()))
        val model = TheatreModel(context, player)
        val preset = preset()
        var restored: ViewingPreset? = null
        var resets = 0
        model.onCapturePreset = { preset }
        model.onRestorePreset = { restored = it }
        model.onResetView = { resets++ }
        model.openEarthControls()
        compose.setContent { Box(Modifier.requiredSize(1600.dp, 900.dp)) { LibraryPanel(model, player) } }
        fun press(label: String) {
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val x = (bounds.center.x - root.left) / root.width
                val y = (bounds.center.y - root.top) / root.height
                assertFalse(model.weatherControlPress(x, y))
                assertTrue(model.earthControlPress(x, y))
            }
        }
        try {
            press("Viewing presets")
            press("Save 2")
            compose.runOnIdle { captureUiFixture(compose.activity, "viewing-presets.png") }
            compose.runOnIdle {
                assertNull(TheatreState(context).viewingPresets()[0])
                assertEquals(preset.size, TheatreState(context).viewingPresets()[1]!!.size)
            }
            press("Restore 2")
            assertEquals(preset.latitude, restored!!.latitude)
            press("Reset view")
            assertEquals(1, resets)
            press("Clear 2")
            assertNull(TheatreState(context).viewingPresets()[1])
            press("Done")
            compose.onNodeWithText("North Pole").assertIsDisplayed()
        } finally { model.release() }
    }

    @Test fun reloadRebasesScreenAndPolarEarthAtTheCurrentSeatWithoutChangingScreenSizeOrLosingSpin() {
        val preset = preset()
        TheatreState(context).saveViewingPreset(0, preset)
        val loaded = TheatreState(context).viewingPresets()[0]!!
        val newSeat = Pose(Vector3(5f, 1.7f, -8f), Quaternion(pitch = 15f, yaw = -70f, roll = 7f))
        val screen = loaded.screenAt(newSeat)
        val actual = ViewingPreset.frame(newSeat).inverse() * screen
        assertEquals(0f, (actual.t - loaded.screen.t).length(), .0001f)
        assertTrue(actual.q.isWithinAngleDegrees(loaded.screen.q, .01f))
        assertEquals(ScreenSize(4.8f, 2.7f), loaded.size)
        assertEquals(90f, loaded.chairYaw, 0f)
        val earth = EarthExploration()
        earth.restore(loaded, newSeat)
        assertEquals(90f, earth.latitude, 0f)
        assertEquals(43f, earth.spin, 0f)
        assertEquals(earth.distance, (newSeat.t - earth.earthPose().t).length(), .001f)
        assertTrue(ScenePointer.valid(earth.earthPose()))
        val place = WeatherPlace.viewed(earth.earthPose(), newSeat.t)
        assertEquals(90.0, place.latitude, .05) // Float pose math loses precision in asin very close to a pole.
    }

    @Test fun corruptOrUnsafeSavedSlotsAreIgnoredIndependentlyAndValidSlotsSurvive() {
        val state = TheatreState(context)
        state.saveViewingPreset(2, preset())
        context.getSharedPreferences("theatre_state", 0).edit()
            .putString("viewing_preset_0", "invalid-json")
            .putString("viewing_preset_1", preset().json().put("width", 10000).toString()).commit()
        val slots = TheatreState(context).viewingPresets()
        assertNull(slots[0]); assertNull(slots[1]); assertNotNull(slots[2])
        assertFalse(preset().copy(screen = Pose(Vector3(0f, 0f, 500f))).valid())
        assertFalse(preset().copy(latitude = Float.NaN).valid())
        assertFalse(preset().copy(screen = Pose(Vector3(0f, 0f, 3f), Quaternion(0f, 0f, 0f, 0f))).valid())
    }

    private fun preset(): ViewingPreset {
        val viewer = Pose(Vector3(1f, 1.6f, 2f), Quaternion(pitch = 0f, yaw = 90f, roll = 0f))
        val earth = EarthExploration().apply { apply(EarthControl.NORTH_POLE, viewer); spinBy(43f) }
        val screen = ViewingPreset.frame(viewer) * Pose(Vector3(.3f, -.2f, 3f), Quaternion(pitch = 0f, yaw = 180f, roll = 0f))
        return ViewingPreset.capture(viewer, screen, ScreenSize(4.8f, 2.7f), 90f, earth)!!
    }
}
